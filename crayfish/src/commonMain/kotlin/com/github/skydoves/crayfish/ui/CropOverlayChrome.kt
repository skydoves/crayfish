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

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density

/**
 * Draws the scrim, the cut-out, the composition guides and the handles.
 *
 * **Right-to-left.** Nothing in here mirrors, and that is a decision rather than an omission. Every
 * coordinate comes from `state.cropRect`, which is measured in viewport pixels from the left edge,
 * and the pixels the crop finally takes are those same pixels in either direction, so mirroring
 * the frame would draw the chrome over a region the crop does not select. The chrome that remains
 * is symmetric anyway (four corner brackets, four edge bars, a centred grid), so there is nothing
 * that *could* meaningfully mirror; `DrawScope.layoutDirection` is therefore never consulted, and a
 * `CropShape.Custom` path is drawn exactly as the caller built it. The direction-sensitive part of
 * the overlay is its wording, which the platform lays out for the reading direction itself.
 */
internal fun Modifier.cropOverlayChrome(
  state: CropState,
  style: CropStyle,
  shape: CropShape,
): Modifier = this
  // The scrim is punched through with BlendMode.Clear, and a blend mode applies to whatever the
  // drawing lands on. Without an offscreen layer to contain it, that is the window: Google's own
  // `CompositingStrategy` documentation notes the blend "would clear all of the pixels to show the
  // app or wallpaper underneath" on a translucent window, which is exactly what an edge-to-edge
  // crop screen is. The failure is invisible on an opaque background, so the wrapper has to be
  // deliberate: it is load-bearing on precisely the screens this library is used on.
  .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
  .drawWithCache {
    // Allocated once per size/style change. A drag changes the rectangle on every frame but none
    // of these: the paths are rewound and refilled below rather than rebuilt.
    val framePath = Path()
    val guidePath = Path()
    val handlePath = Path()
    val frameStroke = Stroke(width = style.frameWidth.toPx())
    val guideStroke = Stroke(width = style.gridWidth.toPx())
    // Round caps so two brackets meeting at a corner do not leave a notch.
    val handleStroke = Stroke(width = style.handleThickness.toPx(), cap = StrokeCap.Round)
    // The drawn size, never `handleTouchRadius`: how large a handle looks and how close a finger
    // has to land are different numbers, and the hit size belongs to the gesture layer.
    val handleLength = style.handleLength.toPx()

    onDrawWithContent {
      drawContent()
      // Read in the draw phase, not in composition: during a drag this changes every frame, and a
      // composition-phase read would re-run the whole overlay subtree each time instead of
      // re-running one draw lambda.
      val rect = state.cropRect
      if (rect.isEmpty || !rect.isFinite) return@onDrawWithContent
      val crop = Rect(rect.left, rect.top, rect.right, rect.bottom)

      framePath.rewind()
      framePath.addCropShape(shape, crop, this)

      drawRect(color = style.scrimColor)
      // The colour is ignored under Clear; what matters is the shape being removed.
      drawPath(framePath, color = Color.Black, blendMode = BlendMode.Clear)

      if (style.gridMode.showsGuides(state.isInteracting)) {
        guidePath.rewind()
        guidePath.addRuleOfThirds(crop)
        // Clipped to the shape so a circular mask does not sprout lines across its corners.
        clipPath(framePath) {
          drawPath(guidePath, color = style.gridColor, style = guideStroke)
        }
      }

      drawPath(framePath, color = style.frameColor, style = frameStroke)

      handlePath.rewind()
      handlePath.addHandles(crop, handleLength)
      drawPath(handlePath, color = style.handleColor, style = handleStroke)
    }
  }

/** [CropGridMode.OnTouch] is the only one that has to ask the state anything. */
private fun CropGridMode.showsGuides(isInteracting: Boolean): Boolean = when (this) {
  CropGridMode.Never -> false
  CropGridMode.Always -> true
  CropGridMode.OnTouch -> isInteracting
}

/**
 * The outline of [shape] fitted to [crop].
 *
 * One path serves as both the cut-out and the frame, so the two cannot drift apart: a frame drawn
 * from different arithmetic than the mask is how a rounded cropper ends up with a hairline of
 * scrim inside its own border.
 */
private fun Path.addCropShape(shape: CropShape, crop: Rect, density: Density) {
  when (shape) {
    CropShape.Rectangle -> addRect(crop)

    CropShape.Circle -> addOval(crop)

    is CropShape.RoundedRectangle -> {
      // A radius past half the shorter side is not a rounder rectangle, it is a malformed one.
      val radius = with(density) { shape.cornerRadius.toPx() }
        .coerceIn(0f, crop.minDimension / 2f)
      addRoundRect(RoundRect(crop, CornerRadius(radius)))
    }

    is CropShape.Custom -> addPath(
      path = shape.build(crop.width, crop.height),
      offset = Offset(crop.left, crop.top),
    )
  }
}

/** Two lines each way at the thirds: the rule of thirds, under its own name. */
private fun Path.addRuleOfThirds(crop: Rect) {
  val stepX = crop.width / 3f
  val stepY = crop.height / 3f
  for (index in 1..2) {
    val x = crop.left + stepX * index
    moveTo(x, crop.top)
    lineTo(x, crop.bottom)
    val y = crop.top + stepY * index
    moveTo(crop.left, y)
    lineTo(crop.right, y)
  }
}

/**
 * Corner brackets and edge bars, as one path so the whole set is a single stroke call.
 *
 * [length] is shortened on a small rectangle, because two brackets that meet in the middle of an
 * edge read as a solid border and lose the affordance they exist to advertise.
 */
private fun Path.addHandles(crop: Rect, length: Float) {
  val size = minOf(length, crop.minDimension / 3f)
  if (size <= 0f) return

  moveTo(crop.left, crop.top + size)
  lineTo(crop.left, crop.top)
  lineTo(crop.left + size, crop.top)

  moveTo(crop.right - size, crop.top)
  lineTo(crop.right, crop.top)
  lineTo(crop.right, crop.top + size)

  moveTo(crop.right, crop.bottom - size)
  lineTo(crop.right, crop.bottom)
  lineTo(crop.right - size, crop.bottom)

  moveTo(crop.left + size, crop.bottom)
  lineTo(crop.left, crop.bottom)
  lineTo(crop.left, crop.bottom - size)

  val half = size / 2f
  moveTo(crop.center.x - half, crop.top)
  lineTo(crop.center.x + half, crop.top)
  moveTo(crop.center.x - half, crop.bottom)
  lineTo(crop.center.x + half, crop.bottom)
  moveTo(crop.left, crop.center.y - half)
  lineTo(crop.left, crop.center.y + half)
  moveTo(crop.right, crop.center.y - half)
  lineTo(crop.right, crop.center.y + half)
}
