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

import androidx.compose.ui.unit.Velocity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two pure decisions a fling makes before it animates anything.
 *
 * Both exist because a velocity tracker is the least trustworthy input in a gesture stack: it
 * divides by a time delta that can be zero, so it hands back NaN and infinities on perfectly
 * ordinary gestures. Three fling regressions across two other Compose zoom libraries (telephoto
 * #97, #71, Zoomable #351) are the same bug class, which is why these are separated out and tested
 * rather than left inline.
 */
class FlingVelocityTest {

  @Test
  fun replacesANonFiniteVelocityComponentWithZero() {
    listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { bad ->
      assertEquals(Velocity(0f, 500f), finiteVelocity(Velocity(bad, 500f)), "x = $bad")
      assertEquals(Velocity(500f, 0f), finiteVelocity(Velocity(500f, bad)), "y = $bad")
    }
  }

  /** Each axis is sanitised on its own, so one bad reading does not discard a good one. */
  @Test
  fun leavesAFiniteVelocityExactlyAlone() {
    val velocity = Velocity(-1234.5f, 678.25f)

    assertEquals(velocity, finiteVelocity(velocity))
    assertEquals(Velocity(0f, 0f), finiteVelocity(Velocity(Float.NaN, Float.NaN)))
  }

  /**
   * Either axis on its own is enough to fling.
   *
   * The alternative reading - requiring both - would refuse every purely horizontal or purely
   * vertical flick, which is most of them. Asserted per axis and in both directions so an `&&`
   * written where an `||` was meant cannot survive.
   */
  @Test
  fun eitherAxisAloneIsEnoughToFling() {
    val fast = MINIMUM_FLING_VELOCITY
    val slow = MINIMUM_FLING_VELOCITY - 1f

    assertTrue(isFlingWorthwhile(Velocity(fast, 0f)), "a purely horizontal flick was refused")
    assertTrue(isFlingWorthwhile(Velocity(0f, fast)), "a purely vertical flick was refused")
    assertTrue(isFlingWorthwhile(Velocity(-fast, 0f)), "a leftward flick was refused")
    assertTrue(isFlingWorthwhile(Velocity(0f, -fast)), "an upward flick was refused")
    assertTrue(isFlingWorthwhile(Velocity(fast, fast)))
    assertFalse(isFlingWorthwhile(Velocity(slow, slow)), "a slow drag started a fling")
  }

  @Test
  fun theThresholdIsInclusiveAndNothingUnderItFlings() {
    assertTrue(isFlingWorthwhile(Velocity(MINIMUM_FLING_VELOCITY, 0f)))
    assertFalse(isFlingWorthwhile(Velocity(MINIMUM_FLING_VELOCITY - 0.01f, 0f)))
    assertFalse(isFlingWorthwhile(Velocity.Zero))
  }

  /**
   * A NaN velocity must not fling, and must not throw either.
   *
   * `abs(NaN) >= threshold` is `false`, so the raw value already declines - but the pipeline
   * sanitises first, and this pins that the two agree. A build where sanitising happened after the
   * threshold check would fling on a zero velocity.
   */
  @Test
  fun aNonFiniteVelocityNeverFlings() {
    listOf(Float.NaN, Float.POSITIVE_INFINITY).forEach { bad ->
      assertFalse(isFlingWorthwhile(finiteVelocity(Velocity(bad, bad))), "$bad started a fling")
    }
    // An infinity is above every threshold, so declining it has to come from the sanitiser.
    assertTrue(isFlingWorthwhile(Velocity(Float.POSITIVE_INFINITY, 0f)))
  }
}
