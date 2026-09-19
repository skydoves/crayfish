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

import com.github.skydoves.crayfish.geometry.FloatPoint
import com.github.skydoves.crayfish.geometry.FloatRect

/**
 * Moves the crop rectangle by [delta], as if [handle] had been dragged that far.
 *
 * **The only place the crop rectangle changes shape.** A finger on a handle, a screen reader's
 * custom action and an arrow key all arrive here as the same pair of arguments, so there is one
 * set of clamping rules rather than one per input. That matters more than it sounds: the moment a
 * keyboard path grows its own arithmetic, it is the path nobody re-tests when the drag rules
 * change, and the two silently disagree about the minimum size or the aspect ratio.
 *
 * [CropHandle.movesLeftEdge] and its siblings exist for exactly this: they let one function cover
 * all nine grab points without a nine-arm `when`.
 */
internal fun RealCropState.nudgeCropRect(
  handle: CropHandle,
  delta: FloatPoint,
  minimumSizePx: Float,
) {
  val viewport = viewportSize
  if (viewport.isEmpty) return
  val current = cropRect
  val next = resizeCropRect(
    current = current,
    handle = handle,
    delta = delta,
    bounds = frameBounds,
    minimumSize = minimumSizePx,
    aspectRatio = aspectRatio,
  )
  if (next == current || next.isEmpty) return
  // Stored as fractions, never pixels: `RealCropState.normalizedCropRect` is what survives a
  // rotation into a viewport that never existed when the rectangle was drawn.
  normalizedCropRect = FloatRect(
    left = next.left / viewport.width,
    top = next.top / viewport.height,
    right = next.right / viewport.width,
    bottom = next.bottom / viewport.height,
  )
  // Every mutation that can uncover a corner of the image has to end here. See `CropCoverageGlue`.
  constrainFrameToImage()
}

/**
 * [current] after [handle] is dragged by [delta], clamped to [bounds] and to [minimumSize].
 *
 * Pure, so the clamping rules can be reasoned about, and tested, without a composition.
 */
internal fun resizeCropRect(
  current: FloatRect,
  handle: CropHandle,
  delta: FloatPoint,
  bounds: FloatRect,
  minimumSize: Float,
  aspectRatio: AspectRatio,
): FloatRect {
  if (bounds.isEmpty || current.isEmpty || !current.isFinite) return current
  val move = delta.sanitized()
  // A minimum larger than the viewport would make every clamp range inverted.
  val minimum = minimumSize.coerceIn(0f, minOf(bounds.width, bounds.height))
  return when {
    // `Inside` reports that it moves all four edges, which is true of a slide but not of the edge
    // arithmetic below: with every edge "moved", the opposite-edge clamps would fight each other.
    handle == CropHandle.Inside -> current.translate(move).nudgedInside(bounds)

    aspectRatio is AspectRatio.Fixed ->
      current.resizedToRatio(handle, move, bounds, minimum, aspectRatio.ratio)

    else -> current.resizedFreely(handle, move, bounds, minimum)
  }
}

/** Each moved edge clamped independently: against the viewport, and against its opposite edge. */
private fun FloatRect.resizedFreely(
  handle: CropHandle,
  delta: FloatPoint,
  bounds: FloatRect,
  minimum: Float,
): FloatRect = FloatRect(
  left = if (handle.movesLeftEdge) {
    clamp(left + delta.x, bounds.left, right - minimum)
  } else {
    left
  },
  top = if (handle.movesTopEdge) clamp(top + delta.y, bounds.top, bottom - minimum) else top,
  right = if (handle.movesRightEdge) {
    clamp(right + delta.x, left + minimum, bounds.right)
  } else {
    right
  },
  bottom = if (handle.movesBottomEdge) {
    clamp(bottom + delta.y, top + minimum, bounds.bottom)
  } else {
    bottom
  },
)

/**
 * The same drag with the width and height locked to [ratio].
 *
 * One dimension is driven by the edge that moved and the other is derived from it, so the ratio is
 * exact after every step rather than drifting by a rounding error per nudge. The edge opposite the
 * one being dragged stays put; the axis that was not dragged keeps its centre, which is what makes
 * a left-edge nudge look like the rectangle grew sideways rather than sliding diagonally.
 *
 * A corner is driven from its horizontal delta. The accessibility layer nudges single edges and
 * never sends a corner, so this only becomes visible once a corner drag exists to use it.
 */
private fun FloatRect.resizedToRatio(
  handle: CropHandle,
  delta: FloatPoint,
  bounds: FloatRect,
  minimum: Float,
  ratio: Float,
): FloatRect {
  val drivenByWidth = handle.movesLeftEdge || handle.movesRightEdge
  val target = if (drivenByWidth) {
    if (handle.movesLeftEdge) width - delta.x else width + delta.x
  } else {
    (if (handle.movesTopEdge) height - delta.y else height + delta.y) * ratio
  }
  // Both dimensions have to clear the minimum, and both have to fit the viewport; expressing each
  // bound as a width is what keeps the ratio exact through the clamp.
  val lowest = maxOf(minimum, minimum * ratio)
  val highest = minOf(bounds.width, bounds.height * ratio)
  val newWidth = if (lowest > highest) highest else clamp(target, lowest, highest)
  val newHeight = newWidth / ratio
  val newLeft = when {
    handle.movesLeftEdge && !handle.movesRightEdge -> right - newWidth
    handle.movesRightEdge && !handle.movesLeftEdge -> left
    else -> center.x - newWidth / 2f
  }
  val newTop = when {
    handle.movesTopEdge && !handle.movesBottomEdge -> bottom - newHeight
    handle.movesBottomEdge && !handle.movesTopEdge -> top
    else -> center.y - newHeight / 2f
  }
  return FloatRect(newLeft, newTop, newLeft + newWidth, newTop + newHeight).nudgedInside(bounds)
}

/**
 * This rectangle slid back inside [bounds], without changing its size.
 *
 * Sliding rather than shrinking is what keeps a locked aspect ratio locked when a nudge runs into
 * the viewport edge: a rectangle that has to shrink to obey the bounds would have to shrink on one
 * axis only, and that is the ratio gone.
 */
internal fun FloatRect.nudgedInside(bounds: FloatRect): FloatRect {
  val dx = when {
    width >= bounds.width -> bounds.left - left
    left < bounds.left -> bounds.left - left
    right > bounds.right -> bounds.right - right
    else -> 0f
  }
  val dy = when {
    height >= bounds.height -> bounds.top - top
    top < bounds.top -> bounds.top - top
    bottom > bounds.bottom -> bounds.bottom - bottom
    else -> 0f
  }
  return if (dx == 0f && dy == 0f) this else translate(FloatPoint(dx, dy))
}

/** [Float.coerceIn] throws on an inverted range; a degenerate rectangle must not crash a drag. */
private fun clamp(value: Float, lowest: Float, highest: Float): Float =
  if (lowest > highest) lowest else value.coerceIn(lowest, highest)
