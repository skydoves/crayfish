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

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

/**
 * Magnetic snapping of a free rotation angle to the four right angles.
 *
 * Without a snap, returning a photo to upright after a straightening gesture is a matter of luck
 * and a 0.3-degree residual tilt survives into the exported file. Snapping is the cheap half of
 * the fix; the other half is that [CropTransform.mapPoint] evaluates a snapped angle exactly (see
 * [cosDegrees]), so a snapped rotation is pixel-exact rather than merely close.
 */
public object RotationSnap {

  /**
   * Five degrees, which is roughly the tilt a hand-held photo picks up and comfortably wider than
   * the precision a finger on a rotation wheel can express.
   */
  public const val DEFAULT_THRESHOLD_DEGREES: Float = 5f

  /**
   * [degrees] pulled to the nearest multiple of 90 when it is within [thresholdDegrees] of one.
   *
   * The winding is deliberately preserved rather than normalised: 725 degrees snaps to 720, not to
   * 0, so a continuing gesture or an animation driven by this value does not spin the image through
   * two full turns at the moment it snaps.
   *
   * The comparison is inclusive, so an angle exactly [thresholdDegrees] away does snap.
   *
   * @return [degrees] unchanged when it is outside the threshold, when [thresholdDegrees] is not a
   *   positive finite number, or when [degrees] is not finite.
   */
  public fun snap(degrees: Float, thresholdDegrees: Float = DEFAULT_THRESHOLD_DEGREES): Float {
    if (!degrees.isFinite()) return 0f
    if (!thresholdDegrees.isFinite() || thresholdDegrees <= 0f) return degrees
    val nearest = round(degrees / QUARTER_TURN) * QUARTER_TURN
    return if (abs(degrees - nearest) <= thresholdDegrees) nearest else degrees
  }

  /** Whether [snap] would move [degrees], i.e. whether the magnet is currently engaged. */
  public fun isSnapping(
    degrees: Float,
    thresholdDegrees: Float = DEFAULT_THRESHOLD_DEGREES,
  ): Boolean = degrees.isFinite() && snap(degrees, thresholdDegrees) != degrees

  /** [degrees] folded into `[0, 360)`, for display and for comparing two angles for sameness. */
  public fun normalize(degrees: Float): Float {
    if (!degrees.isFinite()) return 0f
    val wrapped = degrees % FULL_TURN
    return if (wrapped < 0f) wrapped + FULL_TURN else wrapped
  }

  private const val QUARTER_TURN = 90f
  private const val FULL_TURN = 360f
}

private const val DEGREES_TO_RADIANS = 0.017453292f

/**
 * Skia's `SK_ScalarNearlyZero`, 2^-12.
 *
 * `SkMatrix::setRotate` routes through `SkScalarSinSnapToZero`/`SkScalarCosSnapToZero`, which clamp
 * a nearly-zero sine or cosine to exactly zero. Matching that threshold here is what lets this
 * package claim to predict what a `graphicsLayer` will actually draw: at 90 degrees the renderer
 * produces an exactly axis-aligned matrix, and so does this.
 */
private const val NEARLY_ZERO = 1f / 4096f

/**
 * Cosine of [degrees], snapped to zero near a right angle exactly as Skia does.
 *
 * A consequence worth knowing: the resulting matrix is not exactly orthonormal within the snap
 * window, because only one of the two components is adjusted. That is why the inverse in
 * [CropTransform.unmapPoint] is the transpose of these same snapped values rather than a true
 * matrix inverse. The two then compose back to the identity to within a float ulp, which a true
 * inverse of a snapped matrix would not.
 */
internal fun cosDegrees(degrees: Float): Float = snapToZero(cos(degrees * DEGREES_TO_RADIANS))

/** Sine of [degrees], snapped to zero near a straight angle. See [cosDegrees]. */
internal fun sinDegrees(degrees: Float): Float = snapToZero(sin(degrees * DEGREES_TO_RADIANS))

private fun snapToZero(value: Float): Float = if (abs(value) < NEARLY_ZERO) 0f else value

/**
 * [point] rotated clockwise by the angle whose cosine is [cos] and sine is [sin], about [pivot].
 *
 * Clockwise, because the y axis points down: the matrix below is the textbook counter-clockwise
 * rotation, and a downward y turns it into the clockwise one a user sees. This is the same
 * convention as `graphicsLayer`'s `rotationZ` and `android.graphics.Matrix.setRotate`.
 */
internal fun rotateAbout(point: FloatPoint, pivot: FloatPoint, cos: Float, sin: Float): FloatPoint {
  val dx = point.x - pivot.x
  val dy = point.y - pivot.y
  return FloatPoint(
    x = pivot.x + dx * cos - dy * sin,
    y = pivot.y + dx * sin + dy * cos,
  )
}

/** The inverse of [rotateAbout], as the transpose of the same two snapped values. */
internal fun unrotateAbout(
  point: FloatPoint,
  pivot: FloatPoint,
  cos: Float,
  sin: Float,
): FloatPoint {
  val dx = point.x - pivot.x
  val dy = point.y - pivot.y
  return FloatPoint(
    x = pivot.x + dx * cos + dy * sin,
    y = pivot.y - dx * sin + dy * cos,
  )
}
