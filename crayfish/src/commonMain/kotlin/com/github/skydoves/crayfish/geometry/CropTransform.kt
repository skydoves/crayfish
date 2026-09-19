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
 * The viewer's zoom, pan, rotation and mirroring, as one immutable value.
 *
 * ## Transform order
 *
 * A point is mapped as **Translate · Rotate(pivot) · Scale(pivot)**, scale innermost. This is not a
 * free choice: it is what Android's own renderer does, in `RenderProperties::updateMatrix()`:
 *
 * ```cpp
 * transform->setTranslate(getTranslationX(), getTranslationY());
 * transform->preRotate(getRotation(), getPivotX(), getPivotY());
 * transform->preScale(getScaleX(), getScaleY(), getPivotX(), getPivotY());
 * ```
 *
 * `preRotate`/`preScale` post-multiply, so that builds `T · R · S` and applies scale first.
 * Matching it means the geometry here predicts exactly where a `graphicsLayer` carrying the same
 * values puts a pixel, which is what makes the maths testable headlessly and trustworthy on screen.
 *
 * The consequence: [offset] is applied **outside** the scale, so it is a post-scale screen-pixel
 * translation, not a pan in content pixels. Zooming towards a point therefore does not keep that
 * point still on its own; a compensating translation is needed every frame. See [scaledAround].
 *
 * ## Why the pivot is the centre
 *
 * Every operation here pivots about the **centre** of the content, never its top-left corner. A
 * top-left pivot is not merely inconvenient for rotation, it makes the bounds algebra unsolvable
 * without a rewrite, which is why rotation has stayed unimplemented for years across the Compose
 * zoom libraries. telephoto's author names exactly this as the blocker:
 *
 * > The only blocker is the `coerceWithinContentBounds()` function... It currently uses the
 * > content's top-left coordinates as its pivot point, but to support rotations, it will need to be
 * > changed to the centre.
 *
 * So the pivot is the centre from the first line, and [CropBounds] and [CropCoverage] are written
 * against that convention rather than retrofitted to it. Two things fall out for free: a flip about
 * the content centre maps the content rectangle onto itself, so mirroring cannot affect whether the
 * crop frame is covered; and for content fitted by [fitInside] the content centre *is* the viewport
 * centre, so the pivot costs the caller nothing.
 *
 * @property scale Uniform zoom. Mirroring is [flipHorizontal]/[flipVertical], not a negative scale.
 * @property offset Post-scale translation in viewport units. See the note on transform order.
 * @property rotationDegrees Clockwise, free-angle, not restricted to 90-degree steps.
 * @property flipHorizontal Mirror across the content's vertical centre line, with the scale.
 * @property flipVertical Mirror across the content's horizontal centre line.
 */
public data class CropTransform(
  public val scale: Float = 1f,
  public val offset: FloatPoint = FloatPoint.Zero,
  public val rotationDegrees: Float = 0f,
  public val flipHorizontal: Boolean = false,
  public val flipVertical: Boolean = false,
) {

  /** The signed x scale actually applied, with [flipHorizontal] folded in as Skia folds it in. */
  public val signedScaleX: Float get() = if (flipHorizontal) -scale else scale

  /** The signed y scale actually applied, with [flipVertical] folded in. */
  public val signedScaleY: Float get() = if (flipVertical) -scale else scale

  /** Whether every numeric field is finite and [scale] is usable, i.e. strictly positive. */
  public val isValid: Boolean
    get() = scale.isFinite() && scale > 0f && rotationDegrees.isFinite() && offset.isFinite

  /**
   * This transform with every unusable field replaced by its identity value.
   *
   * Every entry point in this package sanitises its inputs, so that a NaN from a gesture detector
   * or a zero scale from a degenerate pinch cannot propagate into a rectangle that something later
   * refuses to draw. A non-positive [scale] becomes `1f` rather than a small epsilon: a zero scale
   * has no inverse, and treating it as un-zoomed keeps [unmapPoint] total without a magic number.
   */
  public fun sanitized(): CropTransform = if (isValid) {
    this
  } else {
    copy(
      scale = if (scale.isFinite() && scale > 0f) scale else 1f,
      offset = offset.sanitized(),
      rotationDegrees = if (rotationDegrees.isFinite()) rotationDegrees else 0f,
    )
  }

  /**
   * Maps [point] from the content's untransformed coordinate space into the viewport, pivoting
   * about [pivot], which callers derive from the content's centre.
   */
  public fun mapPoint(point: FloatPoint, pivot: FloatPoint): FloatPoint {
    val transform = sanitized()
    val safePivot = pivot.sanitized()
    val safePoint = point.sanitized()

    // Mirroring rides along with the scale as a negative factor, as `graphicsLayer(scaleX = -1f)`
    // does it, which is why there is no separate flip step.
    val scaled = FloatPoint(
      x = safePivot.x + (safePoint.x - safePivot.x) * transform.signedScaleX,
      y = safePivot.y + (safePoint.y - safePivot.y) * transform.signedScaleY,
    )
    val rotated = rotateAbout(
      point = scaled,
      pivot = safePivot,
      cos = cosDegrees(transform.rotationDegrees),
      sin = sinDegrees(transform.rotationDegrees),
    )
    return rotated + transform.offset
  }

  /**
   * Maps [point] back from the viewport into the content's untransformed coordinate space: the
   * exact inverse of [mapPoint].
   */
  public fun unmapPoint(point: FloatPoint, pivot: FloatPoint): FloatPoint {
    val transform = sanitized()
    val safePivot = pivot.sanitized()
    val safePoint = point.sanitized()

    val untranslated = safePoint - transform.offset
    val unrotated = unrotateAbout(
      point = untranslated,
      pivot = safePivot,
      cos = cosDegrees(transform.rotationDegrees),
      sin = sinDegrees(transform.rotationDegrees),
    )
    return FloatPoint(
      x = safePivot.x + (unrotated.x - safePivot.x) / transform.signedScaleX,
      y = safePivot.y + (unrotated.y - safePivot.y) / transform.signedScaleY,
    )
  }

  /** The four corners of [rect] after [mapPoint], clockwise from [rect]'s top-left. */
  public fun mapCorners(rect: FloatRect, pivot: FloatPoint): List<FloatPoint> =
    rect.corners().map { mapPoint(it, pivot) }

  /**
   * The axis-aligned bounding box of [rect] after this transform.
   *
   * At a rotation that is not a multiple of 90 degrees this box is strictly larger than the rotated
   * content, so it answers "how much room does this need" and not "does this cover the crop frame".
   * [CropCoverage] answers the second, in a frame where the difference disappears.
   */
  public fun mapRect(rect: FloatRect, pivot: FloatPoint): FloatRect =
    FloatRect.bounding(mapCorners(rect, pivot))

  /**
   * This transform re-scaled to [newScale] while keeping whatever content sits under [centroid]
   * exactly where it is: the pinch-to-zoom anchor.
   *
   * The derivation is short because of the centre pivot, and would not be otherwise. Write `c` for
   * the centroid taken **relative to the pivot**, `R` for the rotation and `S` for the signed
   * scale. A content point `p` currently lands at `R·S·p + offset` (pivot-relative), so the point
   * under the finger is `p = S⁻¹·R⁻¹·(c - offset)`. Requiring it to land on `c` again after scaling
   * to `S'` gives:
   *
   * ```
   * offset' = c - R·S'·S⁻¹·R⁻¹·(c - offset)
   * ```
   *
   * `S'·S⁻¹` is `(newScale / scale)` times the identity, the mirroring cancelling because it is
   * unchanged, and a scalar commutes with the rotation, collapsing the whole thing to
   * `offset' = c·(1 - ratio) + offset·ratio`. Rotation and mirroring drop out entirely.
   *
   * That cancellation is what makes the anchor exact rather than approximate, and it only happens
   * because `c` is measured from the pivot. Pass a centroid in raw viewport coordinates against a
   * centre pivot and the result is wrong by `(1 - ratio)` times the pivot: small at first, and
   * accumulating over a gesture into the "swimming" that users read as the image sliding out from
   * under their fingers.
   *
   * @param centroid the anchor in viewport coordinates, the same space as [offset].
   * @param pivot the content's centre, in the same space. [FloatSize.center] of the viewport is the
   *   right value whenever the content was laid out by [fitInside].
   */
  public fun scaledAround(newScale: Float, centroid: FloatPoint, pivot: FloatPoint): CropTransform {
    val transform = sanitized()
    if (!newScale.isFinite() || newScale <= 0f) return transform

    val safePivot = pivot.sanitized()
    val relativeCentroid = centroid.sanitized() - safePivot
    val ratio = newScale / transform.scale

    return transform.copy(
      scale = newScale,
      offset = relativeCentroid * (1f - ratio) + transform.offset * ratio,
    )
  }

  public companion object {
    public val Identity: CropTransform = CropTransform()
  }
}
