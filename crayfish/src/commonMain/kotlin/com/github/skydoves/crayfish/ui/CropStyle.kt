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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How the crop overlay looks and how large its touch targets are.
 *
 * `@Immutable` because Compose cannot infer stability for a type it does not own, and an unstable
 * style parameter would recompose the whole overlay on every frame of a drag. The annotation is a
 * 1.0 commitment: it cannot be added to a public type after release.
 */
@Immutable
public data class CropStyle(
  /** Dimming drawn outside the crop rectangle. */
  public val scrimColor: Color = Color.Black.copy(alpha = 0.6f),
  public val frameColor: Color = Color.White,
  public val frameWidth: Dp = 1.dp,
  public val gridColor: Color = Color.White.copy(alpha = 0.5f),
  public val gridWidth: Dp = 1.dp,
  public val gridMode: CropGridMode = CropGridMode.OnTouch,
  public val handleColor: Color = Color.White,
  /** How far a corner handle is drawn along each edge. */
  public val handleLength: Dp = 20.dp,
  public val handleThickness: Dp = 3.dp,

  /**
   * How close a touch must land to a handle to grab it.
   *
   * Separate from [handleLength] on purpose. A handle drawn at 3dp and hit-tested at 3dp is
   * unusable, and one cropper shipped exactly that: a hard-coded 48px tolerance, about 18dp on a
   * 420dpi screen, under both Material's 48dp target and WCAG 2.2's 24dp floor. The default here
   * is the WCAG floor; raise it, never lower it.
   */
  public val handleTouchRadius: Dp = 24.dp,

  /**
   * The smallest the crop rectangle may be dragged.
   *
   * Below this the handles overlap each other and the rectangle can no longer be recovered by
   * dragging.
   */
  public val minimumCropSize: Dp = 56.dp,
) {
  public companion object {
    public val Default: CropStyle = CropStyle()
  }
}
