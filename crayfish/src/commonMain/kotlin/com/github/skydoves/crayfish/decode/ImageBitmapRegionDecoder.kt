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

import androidx.compose.ui.graphics.ImageBitmap
import com.github.skydoves.crayfish.exif.ImageOrientation

/**
 * A [RegionDecoder] over pixels that are already decoded.
 *
 * What backs [CropSource.Image], and the reason that source needed no new expect/actual: an
 * `ImageBitmap` can be read in common code, so there is one implementation rather than four.
 *
 * Nothing here is a decode. A region is a copy out of a buffer the caller already owns, which makes
 * it far cheaper per call than the platform decoders and far more expensive in the one way that
 * matters to this library: the whole image is resident the entire time. That cost belongs to
 * whoever produced the bitmap, and is the trade [CropSource.Image] documents.
 *
 * The source bitmap is **not** closed by [close]. It came from the caller, it is very likely still
 * being drawn by the screen that handed it over, and releasing something this class did not
 * allocate is how a cropper takes down the UI behind it.
 */
internal class ImageBitmapRegionDecoder(private val bitmap: ImageBitmap) : RegionDecoder {

  override val imageSize: ImageSize = ImageSize(bitmap.width, bitmap.height)

  override val format: ImageFormat = ImageFormat.RAW

  /** Whatever decoded these pixels applied the Exif tag already. Applying it again turns twice. */
  override val appliedOrientation: ImageOrientation = ImageOrientation.NORMAL

  override suspend fun decodeRegion(region: ImageRegion, sampleSize: Int): DecodedRegion? {
    val clipped = region.intersect(ImageRegion.of(imageSize)) ?: return null
    if (clipped.width <= 0 || clipped.height <= 0) return null

    // Rounded down to a power of two, and never below one, because that is what the platform
    // decoders do with this argument and a source that behaved differently would make the sample
    // size mean two things.
    val step = sampleSize.toPowerOfTwoAtMost()

    // No budget check and no catch around this. `OutOfMemoryError` is not a type common Kotlin can
    // name, and it is not needed: a region is at most the whole bitmap, and the bitmap is already
    // resident because the caller handed it over. The worst case is a second copy of something
    // that already fits.
    val source = IntArray(clipped.width * clipped.height)
    bitmap.readPixels(
      buffer = source,
      startX = clipped.left,
      startY = clipped.top,
      width = clipped.width,
      height = clipped.height,
    )

    val pixels = if (step == 1) source else source.subsampled(clipped.width, clipped.height, step)
    val size = if (step == 1) {
      ImageSize(clipped.width, clipped.height)
    } else {
      ImageSize(sampledLength(clipped.width, step), sampledLength(clipped.height, step))
    }
    val image = platformImageOfArgbPixels(pixels, size) ?: return null
    return DecodedRegion(image = image, region = clipped, sampleSize = step)
  }

  /** Nothing to release: the bitmap belongs to the caller. */
  override fun close(): Unit = Unit
}

/** Nearest-neighbour, matching what a platform decoder's `inSampleSize` does. */
private fun IntArray.subsampled(width: Int, height: Int, step: Int): IntArray {
  val outWidth = sampledLength(width, step)
  val outHeight = sampledLength(height, step)
  val out = IntArray(outWidth * outHeight)
  for (y in 0 until outHeight) {
    val sourceRow = y * step * width
    val outRow = y * outWidth
    for (x in 0 until outWidth) {
      out[outRow + x] = this[sourceRow + x * step]
    }
  }
  return out
}

/**
 * Floored, never zero, which is what [SampleSize.sampledBy] documents the platform decoders doing.
 *
 * Ceiling here instead would make this source report one pixel more per axis than every other
 * source at the same sample size, and the geometry above it is shared.
 */
private fun sampledLength(length: Int, step: Int): Int = (length / step).coerceAtLeast(1)

/**
 * The largest power of two not greater than this, at least one.
 *
 * `RegionDecoder.decodeRegion` documents that a sample size which is not a power of two is rounded
 * **down**, because that is what the platform decoders do, and a source that rounded up would hand
 * back a smaller bitmap than the budget approved.
 */
private fun Int.toPowerOfTwoAtMost(): Int = if (this < 1) 1 else takeHighestOneBit()
