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

/**
 * The shape the crop rectangle is constrained to.
 *
 * Deliberately separate from [CropShape]: a circular mask on a square output and a square mask on a
 * 16:9 output are both things people ask for, and the one existing Compose cropper that conflated
 * the two has an open issue about it.
 */
@Immutable
public sealed interface AspectRatio {

  /** The rectangle may be dragged to any proportions. */
  public data object Free : AspectRatio

  /** Width divided by height, held constant while the rectangle is resized. */
  @Immutable
  public data class Fixed(public val ratio: Float) : AspectRatio {
    init {
      require(ratio.isFinite() && ratio > 0f) { "ratio must be finite and positive, was $ratio" }
    }
  }

  public companion object {
    public val Square: AspectRatio = Fixed(1f)
    public val Portrait3x4: AspectRatio = Fixed(3f / 4f)
    public val Landscape4x3: AspectRatio = Fixed(4f / 3f)
    public val Widescreen16x9: AspectRatio = Fixed(16f / 9f)
    public val Portrait9x16: AspectRatio = Fixed(9f / 16f)
  }
}
