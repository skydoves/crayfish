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

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import com.github.skydoves.crayfish.geometry.FloatPoint
import kotlin.math.abs

/** The fastest fling the image is allowed to leave a gesture with, in viewport units per second. */
internal const val MAXIMUM_FLING_VELOCITY: Float = 8_000f

/** Below this the lift was a stop, not a throw, and animating it would only add a wobble. */
internal const val MINIMUM_FLING_VELOCITY: Float = 60f

/**
 * [velocity] with every non-finite component replaced by zero.
 *
 * `VelocityTracker.calculateVelocity()` clamps a velocity that is merely *large*, which is what
 * its `maximumVelocity` parameter is for, but it does not guard NaN. Its least-squares fit divides
 * by a time span that is zero whenever two samples arrive carrying the same timestamp, and the NaN
 * that comes out rides straight into [com.github.skydoves.crayfish.geometry.CropTransform.offset],
 * where everything derived from it is NaN from then on: not a crash, just an image that has
 * silently stopped existing and cannot be recovered without resetting the state. Three separate
 * fling regressions across two Compose libraries have exactly this shape, so the guard sits on the
 * boundary where the value enters rather than at the first place a NaN happened to be noticed.
 */
internal fun finiteVelocity(velocity: Velocity): Velocity = Velocity(
  x = if (velocity.x.isFinite()) velocity.x else 0f,
  y = if (velocity.y.isFinite()) velocity.y else 0f,
)

/** Whether [velocity] is a throw worth animating rather than a lift-off from a standstill. */
internal fun isFlingWorthwhile(velocity: Velocity): Boolean =
  abs(velocity.x) >= MINIMUM_FLING_VELOCITY || abs(velocity.y) >= MINIMUM_FLING_VELOCITY

/**
 * Coasts the image to a stop from [velocity], handing each frame's translation to [onDelta].
 *
 * Deltas rather than absolute positions, because the transform is not this animation's to own: a
 * coverage correction may move the image between two frames, and a fling that wrote absolute values
 * would undo it.
 *
 * @param onDelta applies one frame's translation and returns whether the image actually moved.
 *   `false` stops the animation: the image is against its bounds and the remaining frames would be
 *   a no-op that keeps the frame clock busy for nothing.
 */
internal suspend fun animateCropFling(velocity: Velocity, onDelta: (FloatPoint) -> Boolean) {
  val safe = finiteVelocity(velocity)
  if (!isFlingWorthwhile(safe)) return

  var previous = Offset.Zero
  val animation = AnimationState(
    typeConverter = Offset.VectorConverter,
    initialValue = Offset.Zero,
    initialVelocityVector = AnimationVector2D(safe.x, safe.y),
  )
  animation.animateDecay(exponentialDecay()) {
    val current = value
    // A second NaN guard, on the way out rather than the way in: the decay itself is well behaved,
    // but nothing downstream of here re-checks, and one bad frame poisons the transform for good.
    if (!current.x.isFinite() || !current.y.isFinite()) {
      cancelAnimation()
      return@animateDecay
    }
    val delta = current - previous
    previous = current
    // The animation opens on its initial value, so the first frame's delta is zero. Asking for
    // nothing and getting nothing is not the image being blocked, and treating it as such would
    // end every fling on the frame it started.
    if (delta == Offset.Zero) return@animateDecay
    if (!onDelta(FloatPoint(delta.x, delta.y))) cancelAnimation()
  }
}
