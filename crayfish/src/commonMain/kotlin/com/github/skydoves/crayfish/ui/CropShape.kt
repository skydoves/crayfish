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

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp

/**
 * The mask drawn over the crop rectangle, and cut out of the output when the format allows alpha.
 *
 * Orthogonal to [AspectRatio]. A circular avatar is [Circle] with [AspectRatio.Square]; asking for
 * one without the other is legitimate and neither implies the other.
 */
@Immutable
public sealed interface CropShape {

  /** The rectangle itself. */
  public data object Rectangle : CropShape

  /**
   * Rounded corners.
   *
   * @param cornerRadius in [Dp], like every other dimension in this API. A raw pixel value would
   *   make the caller reach for `LocalDensity` for one number and would not survive a move to a
   *   display of a different density.
   */
  @Immutable
  public data class RoundedRectangle(public val cornerRadius: Dp) : CropShape

  /** An ellipse inscribed in the rectangle, a circle when the ratio is square. */
  public data object Circle : CropShape

  /**
   * An arbitrary outline, built to fit the rectangle it is given.
   *
   * The lambda is called with the crop rectangle's size and must return a path in that rectangle's
   * local coordinates. It runs on every frame the rectangle changes, so it should not allocate
   * beyond the path itself.
   */
  @Immutable
  public data class Custom(public val build: (width: Float, height: Float) -> Path) : CropShape
}
