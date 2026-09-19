/*
 * Designed and developed by 2026 skydoves (Jaewoong Eum)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.skydoves.crayfish.decode

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import com.github.skydoves.crayfish.exif.ImageOrientation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal actual suspend fun createPlatformRegionDecoder(source: CropSource): RegionDecoder? =
  withContext(Dispatchers.IO) {
    val header = source.readHeader() ?: return@withContext null
    val format = ImageProbe.probe(header)?.format ?: return@withContext null
    // BitmapRegionDecoder reads JPEG, PNG, WebP and HEIF and nothing else, so a GIF or an AVIF is
    // turned away from its header instead of by an exception thrown out of newInstance.
    if (!format.supportsRegionDecoding) return@withContext null

    val decoder = source.openRegionDecoder() ?: return@withContext null
    AndroidRegionDecoder(
      decoder = decoder,
      // The decoder's own dimensions, never the probe's. The header is a fast pre-check that says
      // nothing at all for HEIF, and the decoder is the thing the rectangles have to be legal for.
      imageSize = ImageSize(width = decoder.width, height = decoder.height),
      format = format,
    )
  }

/**
 * A [RegionDecoder] over [BitmapRegionDecoder].
 *
 * One native decoder is shared by every decode, and it is not safe to call concurrently, so the
 * calls are serialised here. Overlapping calls are the normal case rather than a corner one: a
 * tiled preview asks for several regions of the same source at once.
 */
private class AndroidRegionDecoder(
  private val decoder: BitmapRegionDecoder,
  override val imageSize: ImageSize,
  override val format: ImageFormat,
) : RegionDecoder {

  private val mutex = Mutex()

  /**
   * Whether [close] has run.
   *
   * Read outside the mutex so that a decode waiting its turn behind a long one still sees a close
   * that happened meanwhile, rather than reaching a recycled decoder.
   */
  private val closed = AtomicBoolean(false)

  // BitmapRegionDecoder reads pixels and nothing else; the Exif tag is left for the layer that
  // owns the crop rectangle, so these pixels stay in the same space as `imageSize`.
  override val appliedOrientation: ImageOrientation get() = ImageOrientation.NORMAL

  override suspend fun decodeRegion(region: ImageRegion, sampleSize: Int): DecodedRegion? {
    // Platform decoders disagree about a rectangle that runs off the edge: clamp, throw, or pad
    // with undefined pixels. It is made legal before one ever sees it.
    val clipped = region.intersect(ImageRegion.of(imageSize)) ?: return null
    return withContext(Dispatchers.IO) {
      mutex.withLock { decodeClipped(clipped, sampleSize) }
    }
  }

  private fun decodeClipped(region: ImageRegion, sampleSize: Int): DecodedRegion? {
    if (closed.get()) return null

    val rect = Rect(region.left, region.top, region.right, region.bottom)
    // BitmapFactory rounds inSampleSize down to a power of two itself and says nothing about it,
    // so rounding here is what keeps the reported sample size and the returned pixels in agreement.
    var attempt = sampleSize.coerceIn(1, SampleSize.MAX).takeHighestOneBit()
    while (true) {
      try {
        val bitmap = decoder.decodeRegion(rect, decodeOptions(attempt)) ?: return null
        // `attempt`, not the requested sampleSize: a retry has already coarsened these pixels, and
        // a caller placing tiles from the requested value would put them in the wrong place.
        return DecodedRegion(image = PlatformImage(bitmap), region = region, sampleSize = attempt)
      } catch (_: OutOfMemoryError) {
        // Not a defect to be reported upwards: the region simply asked for more than this heap had
        // left, which a 200MP source does routinely, and the platform throws from inside
        // nativeDecodeRegion where nothing can be freed. Doubling the sample size quarters the
        // allocation, so retrying is worth more than failing, up to the point where the sampled
        // image has collapsed to a pixel.
        if (attempt >= SampleSize.MAX) return null
        attempt *= 2
      } catch (_: IOException) {
        return null
      } catch (_: RuntimeException) {
        // IllegalArgumentException for a rectangle the decoder rejects, IllegalStateException for
        // a close() that landed mid-decode. A crop reports a failed region, it does not crash.
        return null
      }
    }
  }

  override fun close() {
    // Idempotent because every holder of an AutoCloseable can reach this: `use`, a disposed
    // composable, and a cancelled crop, in any order and more than once.
    if (closed.compareAndSet(false, true)) {
      decoder.recycle()
    }
  }
}

private fun decodeOptions(sampleSize: Int): BitmapFactory.Options = BitmapFactory.Options().apply {
  inSampleSize = sampleSize
  // ARGB_8888 rather than RGB_565, which is what the other Android croppers ask for. These pixels
  // are the user's cropped photo: 565 drops the alpha channel outright and quantises every colour
  // to 16 bits, so the saving is taken out of the output the caller came for. Naming a software
  // config also rules out Bitmap.Config.HARDWARE, whose pixels cannot be read back and therefore
  // cannot be encoded.
  inPreferredConfig = Bitmap.Config.ARGB_8888
}

/**
 * Opens a platform decoder over this source, or `null` when it cannot be read.
 *
 * Every failure is ordinary here: a picker hands out truncated files, paths that have since been
 * deleted, and containers the platform declines. All of them come back as `null`.
 */
private fun CropSource.openRegionDecoder(): BitmapRegionDecoder? = try {
  when (this) {
    is CropSource.Bytes ->
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
      } else {
        legacyRegionDecoder(bytes)
      }

    is CropSource.FilePath ->
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        BitmapRegionDecoder.newInstance(path)
      } else {
        legacyRegionDecoder(path)
      }

    // Fetched before any platform decoder sees it. See `CropSource.resolved`.
    is CropSource.Loader -> null

    // Never reaches a platform decoder: it is answered in common code.
    is CropSource.Image -> null
  }
} catch (_: IOException) {
  null
} catch (_: RuntimeException) {
  // A malformed container surfaces as IllegalArgumentException rather than IOException.
  null
} catch (_: OutOfMemoryError) {
  // Opening copies the encoded bytes, and on a large enough source that allocation can itself
  // fail. There is no smaller attempt to retry with: the input is the whole file.
  null
}

/**
 * The pre-31 spelling of `BitmapRegionDecoder.newInstance` for bytes.
 *
 * The `isShareable` overloads were deprecated at API 31 and the flag-free ones do not exist below
 * it, so both spellings have to be kept; the suppression covers only the call that needs it.
 * `false` asks the decoder to copy rather than keep a shallow reference into an array the caller
 * still owns and may go on to mutate.
 */
@Suppress("DEPRECATION")
private fun legacyRegionDecoder(bytes: ByteArray): BitmapRegionDecoder =
  BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)

/** The pre-31 spelling of `BitmapRegionDecoder.newInstance` for a path; see the one above. */
@Suppress("DEPRECATION")
private fun legacyRegionDecoder(path: String): BitmapRegionDecoder =
  BitmapRegionDecoder.newInstance(path, false)

/**
 * The leading bytes of this source, and no more than [ImageProbe] needs.
 *
 * Bounded deliberately: handing a whole in-memory JPEG to the probe walks its marker segments
 * looking for a size that is not wanted here, since the decoder reports that itself.
 */
private fun CropSource.readHeader(): ByteArray? = when (this) {
  is CropSource.Bytes -> bytes.copyOf(minOf(bytes.size, ImageProbe.HEADER_BYTE_COUNT))

  is CropSource.FilePath -> readFileHeader(path)

  // Fetched before any platform decoder sees it. See `CropSource.resolved`.
  is CropSource.Loader -> null

  // Never reaches a platform decoder: it is answered in common code. See `openRegionDecoder`.
  is CropSource.Image -> null
}

private fun readFileHeader(path: String): ByteArray? = try {
  FileInputStream(path).use { stream ->
    val header = ByteArray(ImageProbe.HEADER_BYTE_COUNT)
    var read = 0
    while (read < header.size) {
      val count = stream.read(header, read, header.size - read)
      if (count <= 0) break
      read += count
    }
    header.copyOf(read)
  }
} catch (_: IOException) {
  null
}
