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
@file:OptIn(ExperimentalWasmJsInterop::class)

package com.github.skydoves.crayfish.decode

import com.github.skydoves.crayfish.exif.ExifReader
import com.github.skydoves.crayfish.exif.ImageOrientation
import kotlinx.coroutines.await
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.webext.installPixelsFromArrayBuffer
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.khronos.webgl.toInt8Array
import kotlin.coroutines.cancellation.CancellationException
import kotlin.js.ExperimentalWasmJsInterop

/** Bytes per pixel in the RGBA buffers a canvas hands back. */
private const val BYTES_PER_PIXEL: Int = 4

/**
 * Asks for the file's own pixel grid, ignoring any Exif orientation tag.
 *
 * This is what makes wasm agree with every other target: Skia's decoders ignore the tag outright,
 * so [com.github.skydoves.crayfish.exif.ImageOrientation] is applied a layer above, and a browser
 * that had already rotated the pixels would have it applied twice.
 *
 * A browser that still accepts the value but has quietly made it a synonym for `from-image` would
 * defeat that, and there is no way to detect it from here. Tiling stays self-consistent either
 * way, because the size was read from the same call, but the rotation would be applied twice.
 */
private const val ORIENTATION_FROM_FILE: String = "none"

/**
 * Asks for nothing, leaving the browser to its default, which today means applying the Exif tag.
 *
 * `"none"` was dropped from the spec once the default became `from-image`, and an enum value a
 * browser does not know is a `TypeError`, not a fallback. So the value is tried, and this is where
 * a browser that has retired it lands.
 */
private const val ORIENTATION_BROWSER_DEFAULT: String = ""

/**
 * Opens [source] through `createImageBitmap`, the browser's one honest region decoder.
 *
 * It takes a source rectangle and a target size in the same call, so the only bitmap that ever
 * exists is the tile that was asked for. A 200MP source is 768MiB decoded, which no browser tab
 * will hold.
 *
 * [CropSource.FilePath] returns `null`, and not as a shortcut: a browser has no filesystem and no
 * Web API accepts a path. On wasm a picked file arrives as [CropSource.Bytes] already.
 *
 * Opening decodes the image once, at full size, to read its dimensions off the result. The header
 * size from [ImageProbe] would be free and is deliberately not used: it describes the file's own
 * pixel grid, which is *not* the space the browser hands back once it has applied an Exif
 * rotation, and a decoder whose [RegionDecoder.imageSize] disagrees with the space its own crop
 * rectangles live in crops the wrong part of every rotated photo. It also settles what no table of
 * format support can, namely whether *this* browser can decode *this* container: HEIF passes
 * [ImageFormat.supportsRegionDecoding] but only Safari will read it, and this reports an
 * unreadable source at open time rather than tile by tile.
 *
 * The cost is one transient full-resolution decode, the known weak spot here. It is bounded by the
 * same decode every tile already asks the browser to perform, and nothing is *retained* full size.
 */
internal actual suspend fun createPlatformRegionDecoder(source: CropSource): RegionDecoder? {
  val bytes = when (source) {
    is CropSource.Bytes -> source.bytes

    is CropSource.FilePath -> return null

    // Fetched before any platform decoder sees it. See `CropSource.resolved`.
    is CropSource.Loader -> return null

    // Never reaches a platform decoder: it is answered in common code.
    is CropSource.Image -> return null
  }
  val format = ImageProbe.probe(bytes)?.format ?: return null
  if (!format.supportsRegionDecoding) return null

  // One byte per interop call, because a Kotlin/Wasm array lives in the GC heap rather than in
  // linear memory and so no typed array can be laid over it in bulk. That cost is paid once, here,
  // for the encoded bytes, which is why the decoded ones, paid per tile, take the other route.
  val blob = blobOf(bytes.toInt8Array())
  val opened = openSource(blob, ORIENTATION_FROM_FILE)
    ?: openSource(blob, ORIENTATION_BROWSER_DEFAULT)
    ?: return null
  return BrowserRegionDecoder(
    blob = blob,
    imageSize = opened.imageSize,
    format = format,
    imageOrientation = opened.imageOrientation,
    // When the browser refused to leave the Exif tag alone, it applied the tag the file carries,
    // so that is what these pixels already have baked in, and saying so is what stops the layer
    // above rotating them a second time.
    appliedOrientation = if (opened.imageOrientation == ORIENTATION_FROM_FILE) {
      ImageOrientation.NORMAL
    } else {
      ExifReader.readOrientation(bytes)
    },
  )
}

/** What one opening attempt learned: the decoded size, and the orientation that produced it. */
private class OpenedSource(val imageSize: ImageSize, val imageOrientation: String)

/**
 * Decodes [blob] whole under [imageOrientation] to learn its size, or `null` if it will not decode.
 *
 * The full-size bitmap is closed before this returns; it exists for exactly two integers.
 */
private suspend fun openSource(blob: Blob, imageOrientation: String): OpenedSource? {
  val bitmap = orNull {
    createImageBitmap(blob, orientationOptions(imageOrientation)).await()
  } ?: return null
  return try {
    OpenedSource(ImageSize(bitmap.width, bitmap.height), imageOrientation)
  } finally {
    bitmap.close()
  }
}

/**
 * Holds the encoded bytes, never the decoded ones.
 *
 * Keeping a full-size `ImageBitmap` around would be the one allocation this class exists to avoid,
 * so every tile goes back to the [Blob] and decodes again. Whether the browser truly reads only
 * the requested rectangle out of the stream, or decodes the lot and then crops, is its own
 * business and differs between engines. What is guaranteed from here is the part that matters:
 * what comes *back* is only ever the tile.
 */
private class BrowserRegionDecoder(
  private val blob: Blob,
  override val imageSize: ImageSize,
  override val format: ImageFormat,
  private val imageOrientation: String,
  override val appliedOrientation: ImageOrientation,
) : RegionDecoder {

  private var closed: Boolean = false

  override suspend fun decodeRegion(region: ImageRegion, sampleSize: Int): DecodedRegion? {
    if (closed) return null
    val clipped = region.intersect(ImageRegion.of(imageSize)) ?: return null

    // `createImageBitmap` will resize by any ratio at all, which is precisely why the ratio has to
    // be pinned here: a platform region decoder rounds a sample size down to a power of two, and a
    // tile that came back 1/3 of its size on wasm and 1/2 everywhere else would not line up.
    val step = sampleSize.coerceAtLeast(1).takeHighestOneBit()
    val target = with(SampleSize) { clipped.size.sampledBy(step) }

    val bitmap = orNull {
      createImageBitmapRegion(
        source = blob,
        sx = clipped.left,
        sy = clipped.top,
        sw = clipped.width,
        sh = clipped.height,
        options = resizeOptions(target.width, target.height, imageOrientation),
      ).await()
    } ?: return null

    return try {
      orNull { bitmap.toPlatformImage() }
        ?.let { DecodedRegion(image = it, region = clipped, sampleSize = step) }
    } finally {
      bitmap.close()
    }
  }

  /**
   * Refuses further decodes.
   *
   * There is nothing else to release: a `Blob` has no disposal call, and its bytes go when the last
   * reference to this decoder does. The flag is still worth keeping, because a decoder that kept
   * serving tiles after it was closed would hide the leak of whoever held onto it.
   */
  override fun close() {
    closed = true
  }
}

/**
 * Reads this bitmap's pixels back through an [OffscreenCanvas] into a Skia bitmap.
 *
 * **Channel order.** `getImageData` hands back straight RGBA, unpremultiplied, so the bitmap is
 * told it holds exactly that, [ColorType.RGBA_8888] and [ColorAlphaType.UNPREMUL].
 *
 * Caveat, from trying to break it: changing this [ColorType] to `BGRA_8888` does **not** change
 * what comes out, so this constant is not what keeps the round trip correct;
 * `installPixelsFromArrayBuffer` appears to ignore it. What *is* load-bearing is the byte order in
 * `readArgbPixels`: swapping red and blue there turns two tests red. Left as `RGBA_8888` because it
 * describes the buffer truthfully, but do not treat it as the thing keeping the channels straight.
 *
 * **Why an `ArrayBuffer` and not a `ByteArray`.** Skiko's byte-array interop on web moves one byte
 * per call across the wasm boundary (`Native.web.kt`'s `toWasm` is a `skia_memSetByte` loop), and
 * reading the canvas into a Kotlin array costs another such loop: together some eight million
 * boundary crossings for one 1MP tile, on the interactive path. Handing Skiko the canvas's own
 * `ArrayBuffer` keeps the pixels on the JS side, where the copy is one bulk `Uint8Array.set`.
 */
@OptIn(ExperimentalSkikoApi::class)
private suspend fun ImageBitmap.toPlatformImage(): PlatformImage? {
  val canvas = newOffscreenCanvas(width, height)
  val context = canvasContext2d(canvas) ?: return null
  context.drawImage(this, 0.0, 0.0)

  val pixels = context.getImageData(0, 0, width, height).data
  val rowBytes = width * BYTES_PER_PIXEL
  // The buffer is handed over as a pointer with no length attached, so Skia will read
  // `rowBytes * height` bytes from it whatever it actually holds. A canvas always gives back
  // exactly that, densely packed from the start of its buffer; checking costs nothing and the
  // alternative to checking is an out-of-bounds read rather than a failed decode.
  val buffer = pixels.buffer
  if (pixels.byteOffset != 0 || buffer.byteLength < rowBytes * height) return null

  val info = ImageInfo(
    width = width,
    height = height,
    colorType = ColorType.RGBA_8888,
    alphaType = ColorAlphaType.UNPREMUL,
    colorSpace = null,
  )
  val bitmap = Bitmap()
  if (!bitmap.installPixelsFromArrayBuffer(info, buffer, rowBytes)) {
    bitmap.close()
    return null
  }
  return PlatformImage(bitmap)
}

/**
 * Runs [block], turning any browser-side failure into `null`.
 *
 * `createImageBitmap` rejects for entirely ordinary reasons: a container this engine will not
 * decode, a truncated file, memory it would rather not commit. [RegionDecoder] calls all of those
 * an absent result rather than an exception. Cancellation is not one of them: it is rethrown,
 * so a coroutine that abandons a tile mid-decode still unwinds instead of being reported as a
 * decode that quietly failed.
 */
private suspend fun <T> orNull(block: suspend () -> T): T? = try {
  block()
} catch (cancellation: CancellationException) {
  throw cancellation
} catch (failure: Throwable) {
  null
}
