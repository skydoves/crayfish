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
package com.github.skydoves.crayfish.exif

import com.github.skydoves.crayfish.decode.ImageSize

/**
 * The eight orientations an image's metadata can declare, per the TIFF/Exif `Orientation` tag.
 *
 * Four of them ([FLIP_HORIZONTAL], [FLIP_VERTICAL], [TRANSPOSE], [TRANSVERSE]) are *mirrored*, and
 * handling only the rotations is the single most common EXIF bug: the image comes out upright and
 * silently back-to-front, which nobody notices until a face or a word appears reversed.
 *
 * **Convention.** Applying an orientation means: rotate clockwise by [rotationDegrees], then mirror
 * horizontally if [isMirrored]. That order is not interchangeable; swapping it turns [TRANSPOSE]
 * into [TRANSVERSE]. [applyTo] is the reference implementation and the tests pin the semantics to
 * a labelled grid.
 */
public enum class ImageOrientation(
  /** The value stored in the Exif tag. */
  public val exifValue: Int,
  /** Clockwise rotation to apply, before any mirroring. */
  public val rotationDegrees: Int,
  /** Whether a horizontal mirror follows the rotation. */
  public val isMirrored: Boolean,
) {
  NORMAL(exifValue = 1, rotationDegrees = 0, isMirrored = false),
  FLIP_HORIZONTAL(exifValue = 2, rotationDegrees = 0, isMirrored = true),
  ROTATE_180(exifValue = 3, rotationDegrees = 180, isMirrored = false),
  FLIP_VERTICAL(exifValue = 4, rotationDegrees = 180, isMirrored = true),
  TRANSPOSE(exifValue = 5, rotationDegrees = 90, isMirrored = true),
  ROTATE_90(exifValue = 6, rotationDegrees = 90, isMirrored = false),
  TRANSVERSE(exifValue = 7, rotationDegrees = 270, isMirrored = true),
  ROTATE_270(exifValue = 8, rotationDegrees = 270, isMirrored = false),
  ;

  /** Whether applying this orientation swaps width and height. */
  public val transposesDimensions: Boolean
    get() = rotationDegrees == 90 || rotationDegrees == 270

  /** The size [size] becomes once this orientation is applied. */
  public fun transformSize(size: ImageSize): ImageSize =
    if (transposesDimensions) ImageSize(size.height, size.width) else size

  /**
   * Applies this orientation to a packed pixel buffer, row-major, [size].width pixels per row.
   *
   * This exists because on every target except Android there is no platform API that will do it:
   * Skia's `Image.makeFromEncoded` ignores the Exif tag entirely, which is why images that are
   * upright on Android arrive rotated on iOS, desktop and the web.
   *
   * @return the reoriented buffer; the input is not modified.
   */
  public fun applyTo(pixels: IntArray, size: ImageSize): IntArray {
    require(pixels.size >= size.width * size.height) {
      "pixel buffer holds ${pixels.size} pixels, which is fewer than the " +
        "${size.width}x${size.height} the size claims"
    }
    if (this == NORMAL) return pixels.copyOf(size.width * size.height)

    val sourceWidth = size.width
    val sourceHeight = size.height
    val target = transformSize(size)
    val out = IntArray(target.width * target.height)

    for (y in 0 until sourceHeight) {
      for (x in 0 until sourceWidth) {
        val pixel = pixels[y * sourceWidth + x]

        // Rotate clockwise into the target's coordinate space.
        var tx: Int
        var ty: Int
        when (rotationDegrees) {
          90 -> {
            tx = sourceHeight - 1 - y
            ty = x
          }

          180 -> {
            tx = sourceWidth - 1 - x
            ty = sourceHeight - 1 - y
          }

          270 -> {
            tx = y
            ty = sourceWidth - 1 - x
          }

          else -> {
            tx = x
            ty = y
          }
        }
        // Then mirror horizontally, in the rotated frame.
        if (isMirrored) tx = target.width - 1 - tx

        out[ty * target.width + tx] = pixel
      }
    }
    return out
  }

  public companion object {
    /**
     * Maps an Exif tag value to an orientation.
     *
     * @return the orientation, or [NORMAL] for 0, for the undefined values above 8, and for
     *   anything else a malformed file might carry. Treating an unreadable tag as "already upright"
     *   is the only choice that cannot rotate a correct image into a wrong one.
     */
    public fun fromExifValue(value: Int): ImageOrientation =
      entries.firstOrNull { it.exifValue == value } ?: NORMAL
  }
}
