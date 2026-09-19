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
package com.github.skydoves.crayfish.geometry

/**
 * An axis-aligned rectangle in a continuous coordinate space, origin at the top-left.
 *
 * The integer counterpart is `ImageRegion`, which addresses an image's own pixels. This one
 * addresses things that are laid out rather than decoded: the viewport, the image's fitted bounds
 * inside it, and the crop frame.
 */
public data class FloatRect(
  public val left: Float,
  public val top: Float,
  public val right: Float,
  public val bottom: Float,
) {
  public val width: Float get() = right - left
  public val height: Float get() = bottom - top
  public val size: FloatSize get() = FloatSize(width, height)
  public val center: FloatPoint get() = FloatPoint((left + right) / 2f, (top + bottom) / 2f)

  /** Written so that a NaN edge reports empty rather than silently passing every comparison. */
  public val isEmpty: Boolean get() = !(right > left) || !(bottom > top)

  public val isFinite: Boolean
    get() = left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()

  /** The four corners, clockwise from the top-left. */
  public fun corners(): List<FloatPoint> = listOf(
    FloatPoint(left, top),
    FloatPoint(right, top),
    FloatPoint(right, bottom),
    FloatPoint(left, bottom),
  )

  /** This rectangle grown by [delta] on every side; shrunk when [delta] is negative. */
  public fun inflate(delta: Float): FloatRect =
    FloatRect(left - delta, top - delta, right + delta, bottom + delta)

  public fun translate(by: FloatPoint): FloatRect =
    FloatRect(left + by.x, top + by.y, right + by.x, bottom + by.y)

  public operator fun contains(point: FloatPoint): Boolean =
    point.x >= left && point.x <= right && point.y >= top && point.y <= bottom

  /** Whether [other] lies wholly inside this rectangle, edges touching allowed. */
  public fun contains(other: FloatRect): Boolean =
    other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

  public companion object {
    public val Zero: FloatRect = FloatRect(0f, 0f, 0f, 0f)

    /** A rectangle of [size] centred on [center]. */
    public fun fromCenter(center: FloatPoint, size: FloatSize): FloatRect = FloatRect(
      left = center.x - size.width / 2f,
      top = center.y - size.height / 2f,
      right = center.x + size.width / 2f,
      bottom = center.y + size.height / 2f,
    )

    /** A rectangle of [size] with its top-left corner at the origin. */
    public fun of(size: FloatSize): FloatRect = FloatRect(0f, 0f, size.width, size.height)

    /**
     * The axis-aligned bounding box of [points], or [Zero] when there are none.
     *
     * This is the `trapToRect` of uCrop's `RectUtils`, and the reason the coverage test in
     * [CropCoverage] can be a rectangle comparison rather than polygon containment.
     */
    public fun bounding(points: List<FloatPoint>): FloatRect {
      if (points.isEmpty()) return Zero
      var left = points[0].x
      var top = points[0].y
      var right = left
      var bottom = top
      for (index in 1 until points.size) {
        val point = points[index]
        if (point.x < left) left = point.x
        if (point.x > right) right = point.x
        if (point.y < top) top = point.y
        if (point.y > bottom) bottom = point.y
      }
      return FloatRect(left, top, right, bottom)
    }
  }
}

/**
 * The largest rectangle with [content]'s aspect ratio that fits inside [into], centred within it.
 *
 * This is the letterboxing step that makes the whole of [CropBounds] necessary: a cropper that
 * assumes the content fills its container is wrong the instant an image's aspect ratio differs
 * from the viewport's, which is nearly always. Centring here is also what makes the content's
 * centre coincide with the viewport's, so [CropTransform]'s pivot convention costs the caller
 * nothing.
 *
 * @return an empty rectangle at the centre of [into] when either input is degenerate, so that a
 *   zero-sized viewport or a not-yet-measured image produces no NaN downstream.
 */
public fun fitInside(content: FloatSize, into: FloatRect): FloatRect {
  if (content.isEmpty || !content.isFinite || into.isEmpty || !into.isFinite) {
    val center = if (into.isFinite) into.center else FloatPoint.Zero
    return FloatRect.fromCenter(center, FloatSize.Zero)
  }
  val scale = minOf(into.width / content.width, into.height / content.height)
  return FloatRect.fromCenter(
    center = into.center,
    size = FloatSize(content.width * scale, content.height * scale),
  )
}
