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

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Velocity
import com.github.skydoves.crayfish.geometry.FloatPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The decay animation behind a flick, driven by a real frame clock.
 *
 * [FlingVelocityTest] covers the two decisions made before the animation starts; this is the
 * animation. What matters about it is not the curve - that is Compose's `exponentialDecay` and not
 * ours to test - but the contract at its edges: that it refuses to start when it should not, that
 * every delta it reports is a *difference* rather than a position, and that it gives up the moment
 * the caller says the image cannot move any further.
 *
 * That last one is the load-bearing case. The image is clamped to cover the crop frame, so a flick
 * towards an edge is stopped by the bounds almost immediately. A fling that keeps animating against
 * a wall burns frames for a second and a half and makes the whole screen feel stuck.
 */
@OptIn(ExperimentalTestApi::class)
class CropFlingAnimationTest {

  @Test
  fun aFlickBelowTheThresholdNeverAnimatesAtAll() = runComposeUiTest {
    val deltas = fling(Velocity(10f, 10f))

    assertTrue(deltas.isEmpty(), "a slow drag produced ${deltas.size} fling frames")
  }

  @Test
  fun aNonFiniteVelocityNeverAnimatesAtAll() = runComposeUiTest {
    val deltas = fling(Velocity(Float.NaN, Float.NaN))

    assertTrue(deltas.isEmpty(), "a NaN velocity produced ${deltas.size} fling frames")
  }

  /**
   * A real flick moves the image, in the direction it was flicked, by a decaying amount.
   *
   * The positive control for every "nothing happened" assertion above: without it they would all
   * be satisfied by a fling that never runs.
   */
  @Test
  fun aRealFlickMovesTheImageInTheDirectionItWasFlicked() = runComposeUiTest {
    val deltas = fling(Velocity(2_000f, -1_500f))

    assertTrue(deltas.size > 1, "the fling produced ${deltas.size} frames")
    val travelled = deltas.fold(FloatPoint.Zero) { sum, d -> FloatPoint(sum.x + d.x, sum.y + d.y) }
    assertTrue(travelled.x > 1f, "a rightward flick travelled ${travelled.x} horizontally")
    assertTrue(travelled.y < -1f, "an upward flick travelled ${travelled.y} vertically")

    // Every value handed out is a delta, not a position, so they shrink as the decay settles.
    val first = abs(deltas.first().x)
    val last = abs(deltas.last().x)
    assertTrue(last <= first, "deltas grew from $first to $last; these are positions, not deltas")
  }

  /** No frame ever reports a zero delta: asking the caller for nothing is not worth a callback. */
  @Test
  fun neverAsksTheCallerToMoveByNothing() = runComposeUiTest {
    val deltas = fling(Velocity(2_000f, 0f))

    assertTrue(deltas.isNotEmpty())
    assertTrue(
      deltas.none { it.x == 0f && it.y == 0f },
      "the fling reported a zero delta, which the bounds clamp cannot tell from being blocked",
    )
  }

  /**
   * The caller refusing a delta ends the fling immediately.
   *
   * `onDelta` returns false when the image is already against the edge of the crop frame. Carrying
   * on would animate to a standstill against a wall.
   */
  @Test
  fun stopsTheMomentTheCallerSaysTheImageCannotMove() = runComposeUiTest {
    val all = fling(Velocity(4_000f, 0f))
    assertTrue(
      all.size > 2,
      "the unblocked fling only ran ${all.size} frames, so blocking is not visible",
    )

    val blocked = fling(Velocity(4_000f, 0f), acceptFirst = 2)

    assertEquals(2, blocked.size, "the fling kept animating after the image stopped moving")
  }

  /** Blocking on the very first frame ends it there, rather than running the whole curve. */
  @Test
  fun aFlingBlockedOnItsFirstFrameStopsThere() = runComposeUiTest {
    val blocked = fling(Velocity(4_000f, 0f), acceptFirst = 1)

    assertEquals(1, blocked.size)
  }

  // -----------------------------------------------------------------------------------------
  // Harness
  // -----------------------------------------------------------------------------------------

  /**
   * Runs one fling to completion and returns every delta it asked for.
   *
   * [acceptFirst] is how many deltas `onDelta` accepts before reporting the image as blocked;
   * [Int.MAX_VALUE] never blocks.
   */
  private fun ComposeUiTest.fling(
    velocity: Velocity,
    acceptFirst: Int = Int.MAX_VALUE,
  ): List<FloatPoint> {
    val deltas = mutableListOf<FloatPoint>()
    var done = false
    var scope: CoroutineScope? = null
    setContent { scope = rememberCoroutineScope() }

    assertNotNull(scope).launch {
      animateCropFling(velocity) { delta ->
        deltas += delta
        deltas.size < acceptFirst || acceptFirst == Int.MAX_VALUE
      }
      done = true
    }
    waitUntil(timeoutMillis = TIMEOUT) { done }
    return deltas.toList()
  }

  private companion object {
    const val TIMEOUT = 10_000L
  }
}
