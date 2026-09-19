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
import com.github.skydoves.crayfish.geometry.assertClose
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a finger drag does to the crop rectangle, for every grab point, free and locked.
 *
 * The fixture is asymmetric on every axis a mistake could hide behind: a non-square viewport, a
 * crop rectangle that is neither square nor centred within it, and aspect ratios in both
 * orientations. A centred square fixture turns a mirrored axis into a no-op and makes the whole
 * suite agree with an implementation that has left and right the wrong way round.
 */
class CropDragGeometryTest {

  /** A 480 x 320 viewport: landscape, so a transposed width and height cannot pass. */
  private val bounds = FloatRect(0f, 0f, 480f, 320f)

  /** 240 x 180 at (60, 40): closer to the left and top edges than to the right and bottom. */
  private val origin = FloatRect(60f, 40f, 300f, 220f)

  private val minimum = 56f

  private val resizeHandles = CropHandle.entries.filter { it != CropHandle.Inside }

  private fun resize(
    handle: CropHandle,
    delta: FloatPoint,
    ratio: AspectRatio = AspectRatio.Free,
    origin: FloatRect = this.origin,
    bounds: FloatRect = this.bounds,
    minimumSize: Float = minimum,
  ): FloatRect = resolveCropRect(origin, handle, delta, bounds, minimumSize, ratio)

  // -----------------------------------------------------------------------------------------------
  // 1. The aspect-ratio lock, on every handle, including at the viewport edge.
  // -----------------------------------------------------------------------------------------------

  /**
   * Every grab point, every direction, two ratios: the result holds the ratio **and** stays inside
   * the viewport.
   *
   * Both halves matter and the second is the one that breaks. Holding a ratio is easy until the
   * drag reaches an edge; at that point the tempting implementation clamps the rectangle against
   * the bounds, which shortens one dimension without the other and silently abandons the lock the
   * user set. The deltas below include 1000px pushes in every direction precisely so that every
   * handle is tested against its own bound rather than in open space.
   */
  @Test
  fun everyHandleHoldsALockedRatioRightUpToTheViewportEdge() {
    val ratios = listOf(16f / 9f, 9f / 16f, 4f / 3f)
    val deltas = listOf(
      FloatPoint(-1000f, -1000f),
      FloatPoint(1000f, 1000f),
      FloatPoint(-1000f, 1000f),
      FloatPoint(1000f, -1000f),
      FloatPoint(-70f, 45f),
      FloatPoint(90f, -35f),
    )
    val unchanged = mutableListOf<String>()

    ratios.forEach { ratio ->
      resizeHandles.forEach { handle ->
        deltas.forEach { delta ->
          val result = resize(handle, delta, AspectRatio.Fixed(ratio))
          val where = "$handle dragged by $delta at ratio $ratio gave $result"

          assertTrue(result.left < result.right, "$where: inverted horizontally")
          assertTrue(result.top < result.bottom, "$where: inverted vertically")
          assertClose(ratio, result.width / result.height, ratio * 1e-4f, where)
          // A thousandth of a pixel of slack: `placeLocked` derives an edge by subtraction, so a
          // rectangle that exactly fills the viewport can land an ulp outside it.
          assertTrue(bounds.inflate(1e-3f).contains(result), "$where: left the viewport")
          if (result == origin) unchanged += "$handle by $delta at ratio $ratio"
        }
      }
    }

    // Without this the assertions above would all hold for an implementation that returned the
    // origin unchanged for every drag: a rectangle that never moves holds any ratio it started on
    // and never leaves the viewport.
    assertEquals(emptyList(), unchanged, "these drags did nothing at all")
  }

  /**
   * The four cases above, with the arithmetic written out.
   *
   * An invariant check cannot tell a correct answer from a differently correct one; these say
   * which rectangle, to the pixel. Each is a drag driven hard into a bound, so each is the case
   * where a ratio lock gives up.
   */
  @Test
  fun aLockedDragAgainstABoundLandsExactlyWhereTheAnchorAllows() {
    val ratio = AspectRatio.Fixed(16f / 9f)

    // Top-left corner hauled up and left: the anchor is the bottom-right corner, and the limit is
    // the 300px of room the anchored right edge leaves against the viewport's left edge.
    assertRect(
      FloatRect(0f, 51.25f, 300f, 220f),
      resize(CropHandle.TopLeft, FloatPoint(-1000f, -1000f), ratio),
    )
    // Top edge hauled up: the width grows about the rectangle's own centre line, so the limit is
    // twice the smaller gap from that centre line to a side, 2 * 180 = 360.
    assertRect(
      FloatRect(0f, 17.5f, 360f, 220f),
      resize(CropHandle.Top, FloatPoint(0f, -1000f), ratio),
    )
    // Right edge hauled out: the anchor is the left edge at x = 60, so the limit is 420.
    assertRect(
      FloatRect(60f, 11.875f, 480f, 248.125f),
      resize(CropHandle.Right, FloatPoint(1000f, 0f), ratio),
    )
    // Bottom-right corner hauled in past its opposite: the short side stops at `minimum` and the
    // long side is derived from it, never clamped independently.
    assertRect(
      FloatRect(60f, 40f, 60f + 56f * 16f / 9f, 96f),
      resize(CropHandle.BottomRight, FloatPoint(-1000f, -1000f), ratio),
    )
  }

  // -----------------------------------------------------------------------------------------------
  // 2. The minimum size, and never inverting.
  // -----------------------------------------------------------------------------------------------

  /**
   * A handle dragged 2000px past its opposite side stops at [minimum] and stays on its own side of
   * the rectangle.
   *
   * Inversion is the failure this guards: without the clamp, `right` ends up left of `left`, the
   * rectangle reads as empty everywhere downstream, and the only way back is to reset the state.
   */
  @Test
  fun aHandleDraggedPastItsOppositeClampsAtTheMinimumAndNeverInverts() {
    val expected = mapOf(
      CropHandle.BottomRight to FloatRect(60f, 40f, 116f, 96f),
      CropHandle.TopLeft to FloatRect(244f, 164f, 300f, 220f),
      CropHandle.TopRight to FloatRect(60f, 164f, 116f, 220f),
      CropHandle.BottomLeft to FloatRect(244f, 40f, 300f, 96f),
      CropHandle.Left to FloatRect(244f, 40f, 300f, 220f),
      CropHandle.Right to FloatRect(60f, 40f, 116f, 220f),
      CropHandle.Top to FloatRect(60f, 164f, 300f, 220f),
      CropHandle.Bottom to FloatRect(60f, 40f, 300f, 96f),
    )

    expected.forEach { (handle, want) ->
      // Towards the opposite side, whichever way that is for this handle.
      val delta = FloatPoint(
        x = if (handle.movesLeftEdge) 2000f else -2000f,
        y = if (handle.movesTopEdge) 2000f else -2000f,
      )
      val result = resize(handle, delta)

      assertTrue(result.left < result.right, "$handle inverted horizontally: $result")
      assertTrue(result.top < result.bottom, "$handle inverted vertically: $result")
      assertRect(want, result, "$handle dragged by $delta")
    }
  }

  /**
   * The same drag taken past the clamp and then back out reopens to where the finger is.
   *
   * This is the recompute-from-a-frozen-origin rule as an equation: the answer depends on the
   * total travel and nothing else, so the 2000px of overshoot spent against the clamp is not
   * subtracted from the way back.
   */
  @Test
  fun aDragPastTheClampAndBackIsAPureFunctionOfWhereItEnded() {
    val overshoot = resize(CropHandle.TopLeft, FloatPoint(2000f, 2000f))
    assertRect(FloatRect(244f, 164f, 300f, 220f), overshoot, "hard against the minimum")

    assertRect(
      FloatRect(120f, 90f, 300f, 220f),
      resize(CropHandle.TopLeft, FloatPoint(60f, 50f)),
      "the same drag, ended 60px right and 50px down of where it began",
    )
  }

  // -----------------------------------------------------------------------------------------------
  // 3. Degenerate input.
  // -----------------------------------------------------------------------------------------------

  /** A rectangle or viewport that does not exist yet leaves the drag with nothing to do. */
  @Test
  fun aDragAgainstGeometryThatDoesNotExistReturnsTheOriginUnchanged() {
    val delta = FloatPoint(40f, -30f)

    val broken = mapOf(
      "a NaN origin" to Triple(FloatRect(60f, 40f, Float.NaN, 220f), bounds, minimum),
      "an infinite origin" to
        Triple(FloatRect(60f, 40f, Float.POSITIVE_INFINITY, 220f), bounds, minimum),
      "an empty origin" to Triple(FloatRect(60f, 40f, 60f, 220f), bounds, minimum),
      "an inverted origin" to Triple(FloatRect(300f, 40f, 60f, 220f), bounds, minimum),
      "a NaN viewport" to Triple(origin, FloatRect(0f, 0f, Float.NaN, 320f), minimum),
      "an empty viewport" to Triple(origin, FloatRect.Zero, minimum),
    )

    broken.forEach { (name, fixture) ->
      val (badOrigin, badBounds, min) = fixture
      assertEquals(
        badOrigin,
        resolveCropRect(badOrigin, CropHandle.BottomRight, delta, badBounds, min, AspectRatio.Free),
        "$name should have been handed straight back",
      )
    }
  }

  /** An unusable minimum is a minimum of zero, not a NaN that poisons every later comparison. */
  @Test
  fun anUnusableMinimumSizeBehavesAsNoMinimumAtAll() {
    val delta = FloatPoint(-80f, -60f)
    val atZero = resize(CropHandle.BottomRight, delta, minimumSize = 0f)

    listOf(Float.NaN, -12f, Float.NEGATIVE_INFINITY).forEach { unusable ->
      assertEquals(
        atZero,
        resize(CropHandle.BottomRight, delta, minimumSize = unusable),
        "a minimum of $unusable should behave exactly as zero does",
      )
    }
    assertRect(FloatRect(60f, 40f, 220f, 160f), atZero, "with no minimum the drag is unclamped")
  }

  /** A NaN in the pointer delta is dropped on that axis only; the other axis still tracks. */
  @Test
  fun aNonFinitePointerDeltaMovesNothingOnThatAxis() {
    assertRect(
      FloatRect(60f, 40f, 240f, 220f),
      resize(CropHandle.BottomRight, FloatPoint(-60f, Float.NaN)),
      "a NaN y must not move the bottom edge",
    )
    assertRect(
      FloatRect(60f, 40f, 300f, 160f),
      resize(CropHandle.BottomRight, FloatPoint(Float.POSITIVE_INFINITY, -60f)),
      "an infinite x must not move the right edge",
    )
  }

  // -----------------------------------------------------------------------------------------------
  // 4. Moving the whole rectangle.
  // -----------------------------------------------------------------------------------------------

  @Test
  fun aMoveOfTheWholeRectangleKeepsItsSizeAndStopsAtTheViewportEdge() {
    val slid = resize(CropHandle.Inside, FloatPoint(40f, -25f))
    assertRect(FloatRect(100f, 15f, 340f, 195f), slid, "a move well inside the viewport")

    val pinned = resize(CropHandle.Inside, FloatPoint(1000f, 1000f))
    assertRect(FloatRect(240f, 140f, 480f, 320f), pinned, "hard into the bottom-right corner")
    assertEquals(origin.width, pinned.width, "a move must never resize")
    assertEquals(origin.height, pinned.height, "a move must never resize")
  }

  private fun assertRect(expected: FloatRect, actual: FloatRect, message: String = "") {
    assertTrue(
      abs(expected.left - actual.left) <= TOLERANCE &&
        abs(expected.top - actual.top) <= TOLERANCE &&
        abs(expected.right - actual.right) <= TOLERANCE &&
        abs(expected.bottom - actual.bottom) <= TOLERANCE,
      "$message: expected $expected but was $actual",
    )
  }

  private companion object {
    /** Every number here is derived by one subtraction or one division from a whole pixel. */
    const val TOLERANCE = 1e-3f
  }
}
