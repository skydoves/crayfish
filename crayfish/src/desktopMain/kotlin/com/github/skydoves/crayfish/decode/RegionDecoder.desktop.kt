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

import com.github.skydoves.crayfish.exif.ImageOrientation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.stream.ImageInputStream
import kotlin.coroutines.cancellation.CancellationException

private const val BYTES_PER_PIXEL = 4

/**
 * Opens [source] for region decoding with `javax.imageio`.
 *
 * **The saving is not guaranteed.** `ImageReadParam` is a request: whether an `ImageReader` skips
 * the pixels outside `sourceRegion` or decodes the whole image and discards most of it is up to
 * that codec, the JDK's JPEG reader honours both the region and the subsampling period, others may
 * not, and there is no way to ask one in advance. Treat this as "as lean as the installed codec
 * allows", never as a bounded allocation: the only hard guarantee is the size of the bitmap back.
 *
 * @return a decoder, or `null` when the bytes are not one of the containers [ImageProbe] knows,
 *   when [ImageFormat.supportsRegionDecoding] is false for that container, or when no installed
 *   `ImageReader` can read it. HEIF and AVIF end at the last of those: neither the stock JDK nor
 *   TwelveMonkeys ships a reader for them, and there is nothing to substitute, so `null` is the
 *   honest answer rather than a decoder that fails on first use.
 */
internal actual suspend fun createPlatformRegionDecoder(source: CropSource): RegionDecoder? =
  withContext(Dispatchers.IO) {
    // Probed before a reader is opened, because ImageFormat is part of the decoder's public
    // surface and ImageProbe is the library's authority on it. A container ImageIO can read but
    // ImageProbe does not model (BMP, TIFF, WBMP) has no ImageFormat to report, so it is declined
    // here rather than reported as something it is not.
    val format = source.probeFormat() ?: return@withContext null
    if (!format.supportsRegionDecoding) return@withContext null

    val stream = source.openImageInputStream() ?: return@withContext null
    val decoder = try {
      stream.openDecoder(format)
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      null
    }
    if (decoder == null) {
      runCatching { stream.close() }
    }
    decoder
  }

/** The container [ImageProbe] recognises in this source's leading bytes, or `null`. */
private fun CropSource.probeFormat(): ImageFormat? {
  val header = when (this) {
    is CropSource.Bytes -> bytes

    is CropSource.FilePath -> readHeader(path) ?: return null

    // Fetched before any platform decoder sees it. See `CropSource.resolved`.
    is CropSource.Loader -> return null

    // Never reaches a platform decoder: it is answered in common code. See
    // `openRegionDecoder`.
    is CropSource.Image -> return null
  }
  return ImageProbe.probe(header)?.format
}

/** The first [ImageProbe.HEADER_BYTE_COUNT] bytes of [path], or `null` if it cannot be read. */
private fun readHeader(path: String): ByteArray? = try {
  val file = File(path)
  if (!file.isFile) {
    null
  } else {
    file.inputStream().use { input ->
      val header = ByteArray(ImageProbe.HEADER_BYTE_COUNT)
      val read = input.readNBytes(header, 0, header.size)
      if (read <= 0) null else header.copyOf(read)
    }
  }
} catch (error: Exception) {
  null
}

// A FilePath is opened as a File rather than as its bytes: ImageIO then seeks inside the file
// instead of buffering the whole encoded image, which is the entire reason CropSource.FilePath
// exists. Bytes has no such option, the encoded image being in memory by then.
private fun CropSource.openImageInputStream(): ImageInputStream? = when (this) {
  is CropSource.Bytes -> ImageIO.createImageInputStream(ByteArrayInputStream(bytes))

  is CropSource.FilePath -> File(path).takeIf { it.isFile }?.let(ImageIO::createImageInputStream)

  // Fetched before any platform decoder sees it. See `CropSource.resolved`.
  is CropSource.Loader -> null

  // Never reaches a platform decoder: it is answered in common code.
  is CropSource.Image -> null
}

/** Binds an `ImageReader` to this stream, or `null` when nothing installed can read it. */
private fun ImageInputStream.openDecoder(format: ImageFormat): RegionDecoder? {
  val readers = ImageIO.getImageReaders(this)
  if (!readers.hasNext()) return null
  val reader = readers.next()

  val size = try {
    // seekForwardOnly = false: every decodeRegion seeks back to the start of the image data, and
    // a forward-only reader would be able to serve exactly one of them. ignoreMetadata = true
    // because orientation is read elsewhere; nothing here needs the EXIF block.
    reader.setInput(this, false, true)
    // Neither of these decodes a pixel, which is what makes it safe to learn the size of a 200MP
    // source before deciding how to sample it.
    ImageSize(reader.getWidth(0), reader.getHeight(0))
  } catch (error: Exception) {
    reader.dispose()
    return null
  }

  if (size.width <= 0 || size.height <= 0) {
    reader.dispose()
    return null
  }
  return ImageIoRegionDecoder(reader, this, size, format)
}

private class ImageIoRegionDecoder(
  private val reader: ImageReader,
  private val stream: ImageInputStream,
  override val imageSize: ImageSize,
  override val format: ImageFormat,
) : RegionDecoder {

  /**
   * `ImageReader` is not thread safe: one instance carries the input stream position and the
   * codec's own state, so reads are serialised. A coroutine [Mutex] rather than a monitor keeps a
   * waiting decode suspended instead of pinning a thread.
   */
  private val readerLock = Mutex()

  /**
   * [close] is the one entry point that is not `suspend`, so it can arrive on any thread while a
   * decode is in flight; atomic rather than merely volatile so that repeated calls dispose once.
   */
  private val closed = AtomicBoolean(false)

  /** Separate from [closed]: the reader is freed once, and possibly after the close returns. */
  private val disposed = AtomicBoolean(false)

  // ImageIO is asked for nothing but pixels: no read param here applies the Exif tag, so the
  // rectangles a caller hands in stay in the same space as `imageSize`.
  override val appliedOrientation: ImageOrientation get() = ImageOrientation.NORMAL

  override suspend fun decodeRegion(region: ImageRegion, sampleSize: Int): DecodedRegion? {
    // Made legal before the reader ever sees it: ImageIO throws on a source region that runs past
    // the edge of the image, and a crop rectangle dragged to the border routinely does.
    val clipped = region.intersect(ImageRegion.of(imageSize)) ?: return null

    return withContext(Dispatchers.IO) {
      try {
        // Rounded down to a power of two before anything else. ImageIO would subsample by an
        // arbitrary integer, but Android's and the browser's decoders will not, and a tile that came
        // back at 1/3 here and 1/2 everywhere else would not line up.
        var attempt = sampleSize.coerceIn(1, SampleSize.MAX).takeHighestOneBit()
        while (attempt <= SampleSize.MAX) {
          if (closed.get() || !isActive) return@withContext null
          try {
            // `attempt`, not `sampleSize`: after a retry these pixels are coarser than the caller
            // asked for, and the mapping back to source coordinates has to use what was really used.
            return@withContext readerLock.withLock { read(clipped, attempt) }
              ?.toPlatformImage()
              ?.let { DecodedRegion(image = it, region = clipped, sampleSize = attempt) }
          } catch (error: CancellationException) {
            throw error
          } catch (error: OutOfMemoryError) {
            // Documented by RegionDecoder as an ordinary outcome, not a crash. Doubling the sample
            // size quarters the allocation, so retrying is worth more than reporting failure; the
            // loop bound stops it before the arithmetic stops meaning anything.
            attempt *= 2
          } catch (error: Exception) {
            // A corrupt file, a truncated one, or a reader that gave up: null by contract.
            return@withContext null
          }
        }
        null
      } finally {
        // A close that arrived while this decode held the reader could not free it. Do it here.
        disposeIfIdle()
      }
    }
  }

  /**
   * Reads [region] at [sampleSize]. Call only with [readerLock] held.
   *
   * @return the pixels, or `null` if the reader returned nothing.
   */
  private fun read(region: ImageRegion, sampleSize: Int): BufferedImage? {
    val param = reader.defaultReadParam
    return try {
      param.sourceRegion = Rectangle(region.left, region.top, region.width, region.height)
      if (sampleSize > 1) {
        // Offsets of 0 take the first pixel of each block, matching every other platform's sample
        // size, and what readWholeAndCrop below has to reproduce.
        param.setSourceSubsampling(sampleSize, sampleSize, 0, 0)
      }
      reader.read(0, param)
    } catch (error: Exception) {
      readWholeAndCrop(region, sampleSize)
    }
  }

  /**
   * Decodes the whole image and crops afterwards.
   *
   * This exists because honouring an `ImageReadParam` is per-reader: one that will not apply a
   * source region or a subsampling period throws `IllegalArgumentException` or
   * `UnsupportedOperationException` rather than degrading, and without this the caller would see
   * `null` for an image the JDK can plainly read. It allocates the full image, the exact thing
   * this library exists to avoid, hence a fallback and never the first attempt, and hence a path
   * that can itself run out of memory and be retried at a coarser sample size.
   */
  private fun readWholeAndCrop(region: ImageRegion, sampleSize: Int): BufferedImage? {
    val whole = reader.read(0) ?: return null
    val width = subsampledLength(region.width, sampleSize)
    val height = subsampledLength(region.height, sampleSize)
    val cropped = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until height) {
      for (x in 0 until width) {
        // Every sampleSize-th pixel, so the two paths agree on the pixels and not merely on the
        // dimensions. Otherwise a tile decoded through the fallback would not line up with its
        // neighbours decoded through the fast path.
        val sourceX = region.left + x * sampleSize
        val sourceY = region.top + y * sampleSize
        cropped.setRGB(x, y, whole.getRGB(sourceX, sourceY))
      }
    }
    return cropped
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    disposeIfIdle()
  }

  /**
   * Frees the reader, but only when no decode is inside it.
   *
   * `dispose()` cannot be called while a read holds the reader: the JDK's JPEG reader refuses with
   * `IllegalStateException: Attempt to use instance ... locked on thread ...`. Disposing straight
   * from [close] meant a cropper left while a tile was decoding swallowed that refusal in a
   * `runCatching` and never freed the native decompression struct. `TileStore` decodes for as long
   * as the user pans, so that is the ordinary way out of the screen rather than a corner.
   *
   * Taking the lock and waiting is not the answer either, because [close] runs from a
   * `DisposableEffect` on the main thread. So whoever is last out frees it: [close] tries, and a
   * decode that was holding the reader calls this again on its way out.
   */
  private fun disposeIfIdle() {
    if (!closed.get() || disposed.get()) return
    if (!readerLock.tryLock()) return
    try {
      if (disposed.compareAndSet(false, true)) {
        // Both wrapped: a stream whose backing cache file is already gone must not turn an
        // idempotent close into an exception.
        runCatching { reader.dispose() }
        runCatching { stream.close() }
      }
    } finally {
      readerLock.unlock()
    }
  }
}

/**
 * The number of pixels `setSourceSubsampling(sampleSize, sampleSize, 0, 0)` produces from
 * [length].
 *
 * `ImageReadParam` rounds *up*, as `(length - offset + period - 1) / period`, where
 * `SampleSize.sampledBy` rounds down. They differ by one pixel on an odd dimension, so this is
 * spelled out rather than assumed to be `length / sampleSize`.
 */
private fun subsampledLength(length: Int, sampleSize: Int): Int =
  ((length + sampleSize - 1) / sampleSize).coerceAtLeast(1)

/**
 * Copies a decoded `BufferedImage` into a Skia bitmap.
 *
 * `getRGB` is used rather than reaching into the raster because a `BufferedImage`'s real layout
 * depends on where it came from (`TYPE_3BYTE_BGR` from the JPEG reader, an indexed palette from a
 * paletted PNG, `TYPE_BYTE_GRAY` from a scan) while `getRGB` always answers in the default RGB
 * colour model: non-premultiplied, packed `0xAARRGGBB`, sRGB.
 *
 * **The byte order is the part that is easy to get wrong.** Skia's [ColorType.BGRA_8888] names the
 * order of *bytes in memory*, blue at offset 0 and alpha at offset 3, while a packed ARGB `Int`
 * nests the other way round with alpha in the high byte. So the int's low byte is written first
 * and its high byte last. Writing the int's bytes in their natural order instead yields an image
 * that looks completely plausible with red and blue exchanged, which is why there is a test
 * asserting that pure red survives as `0xFFFF0000`.
 */
private fun BufferedImage.toPlatformImage(): PlatformImage? {
  val bytes = ByteArray(width * height * BYTES_PER_PIXEL)
  // A row at a time: a whole-image IntArray would add another four bytes per pixel on top of the
  // byte buffer, and avoiding exactly that kind of duplicate allocation is the point of this
  // library.
  val row = IntArray(width)
  var offset = 0
  for (y in 0 until height) {
    getRGB(0, y, width, 1, row, 0, width)
    for (x in 0 until width) {
      val argb = row[x]
      bytes[offset] = (argb and 0xFF).toByte()
      bytes[offset + 1] = ((argb shr 8) and 0xFF).toByte()
      bytes[offset + 2] = ((argb shr 16) and 0xFF).toByte()
      bytes[offset + 3] = ((argb shr 24) and 0xFF).toByte()
      offset += BYTES_PER_PIXEL
    }
  }

  // UNPREMUL to match PlatformImage.readArgbPixels, which reads with the same colour and alpha
  // type: the two are then exact inverses and a decode followed by a read returns the source's
  // colours untouched. PREMUL would quantise every translucent pixel on the way in and could not
  // be undone on the way out. An explicit BGRA_8888 rather than N32 for the same reason the read
  // side gives: Skia's native type is BGRA on some targets and RGBA on others.
  val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL, null)
  val bitmap = Bitmap()
  if (!bitmap.installPixels(info, bytes, width * BYTES_PER_PIXEL)) {
    bitmap.close()
    return null
  }
  // Decoded pixels are never written to again, and saying so lets Image.makeFromBitmap in the
  // encoder alias them instead of copying the whole crop a second time.
  bitmap.setImmutable()
  return PlatformImage(bitmap)
}
