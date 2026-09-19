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
import kotlin.math.abs

/**
 * Which part of [cropRect] a touch at [point] has hold of, or `null` for a touch that grabs
 * nothing.
 *
 * Hit tested against [touchRadius] rather than the drawn handle, which is deliberately smaller;
 * see [CropStyle.handleTouchRadius]. The radius also extends *outside* the rectangle, so a finger
 * that lands just beyond a corner still grabs it: a finger is a blob, and the part of it the
 * digitiser reports is not the part the user is aiming with.
 *
 * Corners win over edges whenever both are in range. At the default 24dp radius the two targets
 * overlap along most of a short edge, and resizing a corner is both the more common intent and the
 * one that cannot be reached any other way, since an edge drag is two corner drags.
 */
internal fun hitTestCropHandle(
  cropRect: FloatRect,
  point: FloatPoint,
  touchRadius: Float,
): CropHandle? {
  if (!cropRect.isFinite || cropRect.isEmpty || !point.isFinite) return null
  val radius = if (touchRadius.isFinite() && touchRadius > 0f) touchRadius else 0f

  val nearestCorner = CORNER_HANDLES
    .map { it to cornerOf(cropRect, it) }
    .minByOrNull { (_, corner) -> squaredDistance(corner, point) }
  if (nearestCorner != null && squaredDistance(nearestCorner.second, point) <= radius * radius) {
    return nearestCorner.first
  }

  val nearestEdge = EDGE_HANDLES
    .map { it to edgeDistance(cropRect, it, point, radius) }
    .filter { (_, distance) -> distance <= radius }
    .minByOrNull { (_, distance) -> distance }
  if (nearestEdge != null) return nearestEdge.first

  return if (point in cropRect) CropHandle.Inside else null
}

/**
 * The crop rectangle a drag has produced, as a pure function of where the drag *started*.
 *
 * [origin] is the rectangle as it was when the drag began and [delta] is the pointer's **total**
 * travel since then, never the change since the previous frame. Applying per-frame deltas to the
 * rectangle that is already on screen looks identical until a clamp fires: from then on every
 * frame's result is the previous frame's clamped output, the overshoot is silently eaten, and the
 * rectangle never returns to where the finger says it should be. A locked ratio compounds that
 * error a second time. Recomputing from a frozen origin makes a drag path-independent: the same
 * finger position always yields the same rectangle.
 *
 * @param bounds the viewport. The result never leaves it, except when [minimumSize] cannot fit
 *   inside it at all, a rectangle too small to grab being worse than one that overhangs.
 */
internal fun resolveCropRect(
  origin: FloatRect,
  handle: CropHandle,
  delta: FloatPoint,
  bounds: FloatRect,
  minimumSize: Float,
  aspectRatio: AspectRatio,
): FloatRect {
  if (!origin.isFinite || origin.isEmpty) return origin
  if (!bounds.isFinite || bounds.isEmpty) return origin

  val safeDelta = delta.sanitized()
  val minSize = if (minimumSize.isFinite() && minimumSize > 0f) minimumSize else 0f

  return when {
    handle == CropHandle.Inside -> moveWithin(origin, safeDelta, bounds)

    aspectRatio is AspectRatio.Fixed ->
      resizeLocked(origin, handle, safeDelta, bounds, minSize, aspectRatio.ratio)

    else -> resizeFree(origin, handle, safeDelta, bounds, minSize)
  }
}

/** A whole-rectangle move, clamped so the rectangle stays inside [bounds]. Size never changes. */
private fun moveWithin(origin: FloatRect, delta: FloatPoint, bounds: FloatRect): FloatRect {
  val xLow = bounds.left - origin.left
  val xHigh = bounds.right - origin.right
  val yLow = bounds.top - origin.top
  val yHigh = bounds.bottom - origin.bottom
  // min/max rather than the interval as written: a rectangle wider than the viewport inverts the
  // two ends, and `coerceIn` throws on an empty range.
  return origin.translate(
    FloatPoint(
      x = delta.x.coerceIn(minOf(xLow, xHigh), maxOf(xLow, xHigh)),
      y = delta.y.coerceIn(minOf(yLow, yHigh), maxOf(yLow, yHigh)),
    ),
  )
}

/** Each dragged edge moved independently, clamped to [bounds] and to [minSize]. */
private fun resizeFree(
  origin: FloatRect,
  handle: CropHandle,
  delta: FloatPoint,
  bounds: FloatRect,
  minSize: Float,
): FloatRect {
  // The minimum-size constraint is applied last on each axis so that it wins over the viewport
  // clamp. A rectangle that overhangs a viewport too narrow to hold `minimumCropSize` is still
  // draggable; one collapsed to zero width is not.
  val left = if (handle.movesLeftEdge) {
    (origin.left + delta.x).coerceAtLeast(bounds.left).coerceAtMost(origin.right - minSize)
  } else {
    origin.left
  }
  val right = if (handle.movesRightEdge) {
    (origin.right + delta.x).coerceAtMost(bounds.right).coerceAtLeast(origin.left + minSize)
  } else {
    origin.right
  }
  val top = if (handle.movesTopEdge) {
    (origin.top + delta.y).coerceAtLeast(bounds.top).coerceAtMost(origin.bottom - minSize)
  } else {
    origin.top
  }
  val bottom = if (handle.movesBottomEdge) {
    (origin.bottom + delta.y).coerceAtMost(bounds.bottom).coerceAtLeast(origin.top + minSize)
  } else {
    origin.bottom
  }
  return FloatRect(left, top, right, bottom)
}

/**
 * A resize that holds `width / height` at [ratio], in the order the contract fixes: clamp the size
 * the drag asked for, derive the locked dimension from it, and only then clamp against the room the
 * anchor leaves inside [bounds].
 *
 * Deriving before clamping is the tempting order and the wrong one: the bounds clamp would then
 * shorten one dimension without the other, and the ratio the user locked would come out of a drag
 * that reached an edge slightly wrong.
 */
private fun resizeLocked(
  origin: FloatRect,
  handle: CropHandle,
  delta: FloatPoint,
  bounds: FloatRect,
  minSize: Float,
  ratio: Float,
): FloatRect {
  // 1. The size the drag asks for, clamped on each axis on its own.
  val askedWidth = (origin.width + widthDelta(handle, delta)).coerceIn(minSize, bounds.width)
  val askedHeight = (origin.height + heightDelta(handle, delta)).coerceIn(minSize, bounds.height)

  // 2. One driver dimension, and the other derived from it. A corner takes whichever axis asks for
  //    more so that a diagonal drag grows whenever either direction does; an edge handle is driven
  //    by the axis it actually moves along.
  val drivenWidth = when {
    handle.isCorner -> maxOf(askedWidth, askedHeight * ratio)
    handle == CropHandle.Top || handle == CropHandle.Bottom -> askedHeight * ratio
    else -> askedWidth
  }

  // 3. Clamp against the bounds, through the one dimension that is free. `minWidth` is applied last
  //    for the same reason as in `resizeFree`: an unusably small rectangle is the worse failure.
  val minWidth = maxOf(minSize, minSize * ratio)
  val width = drivenWidth
    .coerceAtMost(minOf(bounds.width, bounds.height * ratio))
    .coerceAtMost(lockedWidthLimit(origin, handle, bounds, ratio))
    .coerceAtLeast(minWidth)

  return placeLocked(origin, handle, width, width / ratio)
}

/** The widest a locked rectangle can be before the anchored edge or corner pushes it out. */
private fun lockedWidthLimit(
  origin: FloatRect,
  handle: CropHandle,
  bounds: FloatRect,
  ratio: Float,
): Float {
  val widthRoom = when {
    handle.movesLeftEdge && !handle.movesRightEdge -> origin.right - bounds.left

    handle.movesRightEdge && !handle.movesLeftEdge -> bounds.right - origin.left

    // Top/Bottom grow symmetrically about the rectangle's own vertical centre line.
    else -> 2f * minOf(origin.center.x - bounds.left, bounds.right - origin.center.x)
  }
  val heightRoom = when {
    handle.movesTopEdge && !handle.movesBottomEdge -> origin.bottom - bounds.top
    handle.movesBottomEdge && !handle.movesTopEdge -> bounds.bottom - origin.top
    else -> 2f * minOf(origin.center.y - bounds.top, bounds.bottom - origin.center.y)
  }
  return minOf(widthRoom, heightRoom * ratio)
}

/** A rectangle of [width] by [height] hung off whichever corner or edge the drag is not moving. */
private fun placeLocked(
  origin: FloatRect,
  handle: CropHandle,
  width: Float,
  height: Float,
): FloatRect {
  val left = when {
    handle.movesLeftEdge && !handle.movesRightEdge -> origin.right - width
    handle.movesRightEdge && !handle.movesLeftEdge -> origin.left
    else -> origin.center.x - width / 2f
  }
  val top = when {
    handle.movesTopEdge && !handle.movesBottomEdge -> origin.bottom - height
    handle.movesBottomEdge && !handle.movesTopEdge -> origin.top
    else -> origin.center.y - height / 2f
  }
  return FloatRect(left, top, left + width, top + height)
}

private fun widthDelta(handle: CropHandle, delta: FloatPoint): Float {
  val fromRight = if (handle.movesRightEdge) delta.x else 0f
  val fromLeft = if (handle.movesLeftEdge) delta.x else 0f
  return fromRight - fromLeft
}

private fun heightDelta(handle: CropHandle, delta: FloatPoint): Float {
  val fromBottom = if (handle.movesBottomEdge) delta.y else 0f
  val fromTop = if (handle.movesTopEdge) delta.y else 0f
  return fromBottom - fromTop
}

private fun cornerOf(rect: FloatRect, handle: CropHandle): FloatPoint = when (handle) {
  CropHandle.TopLeft -> FloatPoint(rect.left, rect.top)
  CropHandle.TopRight -> FloatPoint(rect.right, rect.top)
  CropHandle.BottomLeft -> FloatPoint(rect.left, rect.bottom)
  else -> FloatPoint(rect.right, rect.bottom)
}

/**
 * How far [point] is from one edge of [rect], or [Float.MAX_VALUE] when it is off the end of that
 * edge: a touch level with the left edge but well below the rectangle is not on the left edge.
 */
private fun edgeDistance(
  rect: FloatRect,
  handle: CropHandle,
  point: FloatPoint,
  radius: Float,
): Float {
  val withinSpan = when (handle) {
    CropHandle.Left, CropHandle.Right ->
      point.y >= rect.top - radius && point.y <= rect.bottom + radius

    else -> point.x >= rect.left - radius && point.x <= rect.right + radius
  }
  if (!withinSpan) return Float.MAX_VALUE
  return when (handle) {
    CropHandle.Left -> abs(point.x - rect.left)
    CropHandle.Right -> abs(point.x - rect.right)
    CropHandle.Top -> abs(point.y - rect.top)
    else -> abs(point.y - rect.bottom)
  }
}

private fun squaredDistance(a: FloatPoint, b: FloatPoint): Float {
  val dx = a.x - b.x
  val dy = a.y - b.y
  return dx * dx + dy * dy
}

private val CORNER_HANDLES = listOf(
  CropHandle.TopLeft,
  CropHandle.TopRight,
  CropHandle.BottomLeft,
  CropHandle.BottomRight,
)

private val EDGE_HANDLES = listOf(
  CropHandle.Left,
  CropHandle.Right,
  CropHandle.Top,
  CropHandle.Bottom,
)
