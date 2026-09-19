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
import com.github.skydoves.crayfish.geometry.FloatPoint
import com.github.skydoves.crayfish.geometry.FloatRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parts of the gesture layer that are pure functions, tested without a composition.
 *
 * The rules they encode (corners beat edges, a locked ratio is derived rather than nudged, a
 * velocity is never allowed through non-finite) are the ones a gesture test can only observe
 * indirectly, through a whole drag. Pinning them here makes a failure say which rule broke.
 */
class CropGestureGeometryTest {

  private val rect = FloatRect(100f, 100f, 300f, 300f)

  @Test
  fun aCornerBeatsAnEdgeWhenBothAreInRange() {
    // Level with the left edge and 10px above the top-left corner: inside the tolerance of both.
    val handle = hitTestCropHandle(rect, FloatPoint(100f, 110f), touchRadius = 24f)
    assertEquals(CropHandle.TopLeft, handle)
  }

  @Test
  fun anEdgeIsGrabbedAwayFromTheCorners() {
    assertEquals(CropHandle.Left, hitTestCropHandle(rect, FloatPoint(96f, 200f), 24f))
    assertEquals(CropHandle.Bottom, hitTestCropHandle(rect, FloatPoint(200f, 305f), 24f))
  }

  @Test
  fun theTouchTargetReachesOutsideTheRectangle() {
    assertEquals(
      CropHandle.BottomRight,
      hitTestCropHandle(rect, FloatPoint(312f, 312f), 24f),
      "a finger that lands just beyond a corner is still aiming at it",
    )
  }

  @Test
  fun theMiddleIsInsideAndEverythingElseIsNothing() {
    assertEquals(CropHandle.Inside, hitTestCropHandle(rect, FloatPoint(200f, 200f), 24f))
    assertNull(hitTestCropHandle(rect, FloatPoint(20f, 20f), 24f))
  }

  @Test
  fun aLockedRatioIsDerivedRatherThanNudged() {
    val ratio = 16f / 9f
    val bounds = FloatRect(0f, 0f, 400f, 400f)
    // A diagonal drag whose two components disagree about the size: the ratio has to come out of
    // one driver dimension, not out of both being pulled independently.
    val resized = resolveCropRect(
      origin = rect,
      handle = CropHandle.BottomRight,
      delta = FloatPoint(40f, -70f),
      bounds = bounds,
      minimumSize = 56f,
      aspectRatio = AspectRatio.Fixed(ratio),
    )
    assertEquals(ratio, resized.width / resized.height, absoluteTolerance = 1e-4f)
    assertTrue(bounds.contains(resized), "the locked rectangle left the viewport: $resized")
  }

  @Test
  fun aDragIsAPureFunctionOfWhereItEnded() {
    val bounds = FloatRect(0f, 0f, 400f, 400f)
    fun resize(delta: FloatPoint) = resolveCropRect(
      origin = rect,
      handle = CropHandle.BottomRight,
      delta = delta,
      bounds = bounds,
      minimumSize = 56f,
      aspectRatio = AspectRatio.Free,
    )

    // The overshoot first, hard against the minimum, and then the delta that matters. Both answers
    // are read off the frozen origin, so the second is unaffected by the first, which is the whole
    // of the recompute-don't-accumulate rule, stated as an equation. The end-to-end proof that the
    // detector actually calls it this way is in `CropGestureTest`.
    assertEquals(
      FloatRect(100f, 100f, 156f, 156f),
      resize(FloatPoint(-400f, -400f)),
      "the minimum crop size is the floor, measured from the origin rather than from the last frame",
    )
    assertEquals(FloatRect(100f, 100f, 240f, 240f), resize(FloatPoint(-60f, -60f)))
  }

  @Test
  fun aNonFiniteVelocityIsNeverLetThrough() {
    assertEquals(Velocity(0f, 0f), finiteVelocity(Velocity(Float.NaN, Float.NaN)))
    assertEquals(Velocity(0f, 12f), finiteVelocity(Velocity(Float.NaN, 12f)))
    assertEquals(
      Velocity(0f, 0f),
      finiteVelocity(Velocity(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)),
    )
    assertFalse(
      isFlingWorthwhile(finiteVelocity(Velocity(Float.NaN, Float.NaN))),
      "a NaN velocity must not start a fling at all",
    )
  }
}
