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
package com.github.skydoves.crayfish.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import com.github.skydoves.crayfish.decode.ImageRegion
import com.github.skydoves.crayfish.decode.ImageSize

/**
 * The outcome of a crop that stays in the process.
 *
 * The sibling of [CropResult], for callers whose crop is going into their own UI rather than into a
 * file or an upload. It carries pixels Compose can draw instead of encoded bytes, which is both
 * shorter and lossless: the bytes route would encode the crop and decode it straight back, paying
 * for a second full copy and, in a lossy format, for quality that does not come back.
 *
 * Failure and cancellation mean exactly what they mean for [CropResult], and share its
 * [CropResult.Failure.Reason], so a caller that handles one already knows the vocabulary of the
 * other.
 */
public sealed interface CropImage {

  /**
   * @property image the cropped pixels, ready for `Image(bitmap = ...)`.
   * @property size the pixel size of [image]. A quarter turn has already swapped width and height.
   * @property region the rectangle of the source these pixels came from, in the source's own
   *   coordinates after Exif correction. Always axis aligned, so a crop rotated by a free angle
   *   reports the *bounding box* of the tilted frame: where the pixels came from, not their shape.
   */
  public class Success(
    public val image: ImageBitmap,
    public val size: ImageSize,
    public val region: ImageRegion,
  ) : CropImage {

    /**
     * The same pixels as a [Painter], for `Image(painter = ...)` and anything else that takes one.
     *
     * Built once and held, because a `Painter` is identity compared by everything that caches on
     * it: returning a new one per read would make every such cache miss on every recomposition.
     */
    public val painter: Painter = BitmapPainter(image)
  }

  /** The user dismissed the cropper, or the caller's scope was cancelled. */
  public data object Cancelled : CropImage

  /** The crop could not be produced. [reason] is the same vocabulary [CropResult] uses. */
  public class Failure(
    public val reason: CropResult.Failure.Reason,
    public val cause: Throwable? = null,
  ) : CropImage
}
