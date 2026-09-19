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

import com.github.skydoves.crayfish.decode.CropSource
import com.github.skydoves.crayfish.geometry.FloatPoint
import com.github.skydoves.crayfish.geometry.FloatRect
import com.github.skydoves.crayfish.geometry.FloatSize
import com.github.skydoves.crayfish.geometry.assertClose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The single resize entry point every non-drag input goes through: an arrow key, a screen
 * reader's custom action, a talkback swipe.
 *
 * `resizeCropRect` is a separate implementation from the drag path's `resolveCropRect`, and the
 * two clamp in a different order on purpose, so it needs its own table rather than an argument
 * that the drag tests cover it. The fixture is again asymmetric on every axis: a landscape
 * viewport, an off-centre rectangle inside it, and ratios in both orientations.
 */
class CropNudgeGeometryTest {

  private val bounds = FloatRect(0f, 0f, 480f, 320f)
  private val current = FloatRect(60f, 40f, 300f, 220f)
  private val minimum = 56f

  private val resizeHandles = CropHandle.entries.filter { it != CropHandle.Inside }

  private fun nudge(
    handle: CropHandle,
    delta: FloatPoint,
    ratio: AspectRatio = AspectRatio.Free,
    current: FloatRect = this.current,
    bounds: FloatRect = this.bounds,
    minimumSize: Float = minimum,
  ): FloatRect = resizeCropRect(current, handle, delta, bounds, minimumSize, ratio)

  // -----------------------------------------------------------------------------------------------
  // 1. The locked ratio.
  // -----------------------------------------------------------------------------------------------

  /**
   * Every handle, both orientations of ratio, nudges large enough to run into every bound.
   *
   * The lock is held by deriving one dimension from the other and then *sliding* the result back
   * inside the viewport rather than shrinking it. Shrinking would have to shorten one axis alone,
   * which is the ratio gone. Both halves are asserted here because an implementation that clamps
   * instead of sliding still passes a containment check.
   */
  @Test
  fun everyHandleHoldsALockedRatioUnderANudge() {
    val ratios = listOf(16f / 9f, 9f / 16f, 4f / 3f)
    val deltas = listOf(
      FloatPoint(-600f, -600f),
      FloatPoint(600f, 600f),
      FloatPoint(-600f, 600f),
      FloatPoint(600f, -600f),
      FloatPoint(-45f, 30f),
      FloatPoint(70f, -25f),
    )
    val unchanged = mutableListOf<String>()

    ratios.forEach { ratio ->
      resizeHandles.forEach { handle ->
        deltas.forEach { delta ->
          val result = nudge(handle, delta, AspectRatio.Fixed(ratio))
          val where = "$handle nudged by $delta at ratio $ratio gave $result"

          assertTrue(result.left < result.right, "$where: inverted horizontally")
          assertTrue(result.top < result.bottom, "$where: inverted vertically")
          assertClose(ratio, result.width / result.height, ratio * 1e-4f, where)
          assertTrue(bounds.inflate(1e-3f).contains(result), "$where: left the viewport")
          if (result == current) unchanged += "$handle by $delta at ratio $ratio"
        }
      }
    }

    assertEquals(emptyList(), unchanged, "these nudges did nothing at all")
  }

  /**
   * A rectangle pushed off the right-hand edge slides back, keeping its size.
   *
   * The right edge is anchored to the left one, so growing 200px puts the rectangle 20px past the
   * viewport. Shrinking those 20px away would take the height with it or break the lock; sliding
   * does neither.
   */
  @Test
  fun aNudgeThatOverhangsTheRightEdgeSlidesBackInsteadOfShrinking() {
    val result = nudge(CropHandle.Right, FloatPoint(200f, 0f), AspectRatio.Fixed(16f / 9f))

    assertRect(FloatRect(40f, 6.25f, 480f, 253.75f), result)
    assertClose(440f, result.width, 1e-2f, "the width the nudge asked for survived the slide")
  }

  /** The same, off the top edge, where the anchored bottom edge is what pushes it out. */
  @Test
  fun aNudgeThatOverhangsTheTopEdgeSlidesBackDown() {
    val result = nudge(CropHandle.Top, FloatPoint(0f, -60f), AspectRatio.Fixed(16f / 9f))

    assertRect(FloatRect(0f, 0f, 426.667f, 240f), result)
  }

  /**
   * A minimum size that cannot be honoured at the locked ratio inside this viewport.
   *
   * 200 units at 2:1 needs 400 across, and the viewport is 300 wide. The interval to clamp into is
   * inverted, and `coerceIn` throws on an inverted range, so the contract is stated explicitly:
   * take the largest rectangle that does fit, rather than throwing or returning a rectangle wider
   * than the screen.
   */
  @Test
  fun aMinimumThatCannotFitTheLockedRatioTakesTheLargestRectangleThatDoes() {
    val narrowViewport = FloatRect(0f, 0f, 300f, 400f)
    val small = FloatRect(20f, 30f, 140f, 90f)

    val result = nudge(
      handle = CropHandle.Right,
      delta = FloatPoint(50f, 0f),
      ratio = AspectRatio.Fixed(2f),
      current = small,
      bounds = narrowViewport,
      minimumSize = 200f,
    )

    assertRect(FloatRect(0f, 0f, 300f, 150f), result)
    assertClose(2f, result.width / result.height, 1e-4f, "the ratio survived the impossible floor")
  }

  // -----------------------------------------------------------------------------------------------
  // 2. The free path.
  // -----------------------------------------------------------------------------------------------

  @Test
  fun aFreeNudgeMovesOnlyTheEdgesItsHandleOwns() {
    assertRect(FloatRect(60f, 40f, 340f, 220f), nudge(CropHandle.Right, FloatPoint(40f, 25f)))
    assertRect(FloatRect(100f, 40f, 300f, 220f), nudge(CropHandle.Left, FloatPoint(40f, 25f)))
    assertRect(FloatRect(60f, 65f, 300f, 220f), nudge(CropHandle.Top, FloatPoint(40f, 25f)))
    assertRect(FloatRect(60f, 40f, 300f, 245f), nudge(CropHandle.Bottom, FloatPoint(40f, 25f)))
    assertRect(FloatRect(100f, 65f, 300f, 220f), nudge(CropHandle.TopLeft, FloatPoint(40f, 25f)))
  }

  @Test
  fun aFreeNudgePastTheOppositeEdgeStopsAtTheMinimum() {
    assertRect(FloatRect(244f, 40f, 300f, 220f), nudge(CropHandle.Left, FloatPoint(900f, 0f)))
    assertRect(FloatRect(60f, 40f, 116f, 220f), nudge(CropHandle.Right, FloatPoint(-900f, 0f)))
    assertRect(FloatRect(60f, 164f, 300f, 220f), nudge(CropHandle.Top, FloatPoint(0f, 900f)))
    assertRect(FloatRect(60f, 40f, 300f, 96f), nudge(CropHandle.Bottom, FloatPoint(0f, -900f)))
  }

  /**
   * A rectangle that is *already* narrower than the minimum, which a viewport resize or a restored
   * state can produce, must still be draggable, and must never end up inverted.
   *
   * With `right - minimum` to the left of the viewport's own left edge, the interval to clamp into
   * is inverted. Taking the low end keeps `left` inside the viewport and to the left of `right`;
   * `coerceIn` on that range would throw.
   */
  @Test
  fun aRectangleAlreadyNarrowerThanTheMinimumIsNeverInverted() {
    val tooNarrow = FloatRect(10f, 40f, 40f, 220f)

    val result = nudge(CropHandle.Left, FloatPoint(-100f, 0f), current = tooNarrow)

    assertRect(FloatRect(0f, 40f, 40f, 220f), result)
    assertTrue(result.left < result.right, "the rectangle inverted: $result")
  }

  @Test
  fun aNudgeOfTheWholeRectangleKeepsItsSizeAndStopsAtTheViewportEdge() {
    assertRect(FloatRect(100f, 15f, 340f, 195f), nudge(CropHandle.Inside, FloatPoint(40f, -25f)))
    assertRect(
      FloatRect(240f, 140f, 480f, 320f),
      nudge(CropHandle.Inside, FloatPoint(900f, 900f)),
      "hard into the bottom-right corner",
    )
  }

  // -----------------------------------------------------------------------------------------------
  // 3. Degenerate input.
  // -----------------------------------------------------------------------------------------------

  @Test
  fun aNudgeAgainstGeometryThatDoesNotExistReturnsTheRectangleUnchanged() {
    val delta = FloatPoint(30f, -20f)
    // An infinite edge rather than a NaN one: a NaN rectangle already reports itself empty, so it
    // never reaches the finiteness check.
    val infinite = FloatRect(60f, 40f, Float.POSITIVE_INFINITY, 220f)

    assertEquals(
      current,
      resizeCropRect(current, CropHandle.Right, delta, FloatRect.Zero, minimum, AspectRatio.Free),
      "an unmeasured viewport",
    )
    assertEquals(
      FloatRect(300f, 40f, 60f, 220f),
      resizeCropRect(
        FloatRect(300f, 40f, 60f, 220f),
        CropHandle.Right,
        delta,
        bounds,
        minimum,
        AspectRatio.Free,
      ),
      "an inverted rectangle",
    )
    assertEquals(
      infinite,
      resizeCropRect(infinite, CropHandle.Right, delta, bounds, minimum, AspectRatio.Free),
      "an infinite rectangle",
    )
  }

  // -----------------------------------------------------------------------------------------------
  // 4. Writing the result back into the state.
  // -----------------------------------------------------------------------------------------------

  @Test
  fun aNudgeIsStoredAsFractionsOfTheViewportRatherThanPixels() {
    val state = state(FloatSize(480f, 320f))

    state.nudgeCropRect(CropHandle.Right, FloatPoint(60f, 0f), minimumSizePx = minimum)

    assertRect(FloatRect(60f, 40f, 360f, 220f), state.cropRect)
    assertRect(FloatRect(0.125f, 0.125f, 0.75f, 0.6875f), state.normalizedCropRect)
  }

  /**
   * Nothing is written before the viewport has been measured.
   *
   * The rectangle is stored as a fraction of the viewport, so dividing by a viewport of zero would
   * store an infinity and the rectangle would never be recoverable.
   */
  @Test
  fun aNudgeBeforeTheViewportIsMeasuredWritesNothing() {
    val state = state(FloatSize.Zero)
    val before = state.normalizedCropRect

    state.nudgeCropRect(CropHandle.Right, FloatPoint(60f, 0f), minimumSizePx = minimum)

    assertEquals(before, state.normalizedCropRect, "an unmeasured viewport must write nothing")
  }

  /**
   * A nudge whose result has no area is refused rather than stored.
   *
   * With no minimum to stop it, which is what a caller passing a zero-size floor asks for, the
   * right edge lands exactly on the left one. Storing that would make `cropRect` empty, and every
   * later hit test, drag and crop would have nothing to work with.
   */
  @Test
  fun aNudgeThatWouldCollapseTheRectangleIsRefused() {
    val state = state(FloatSize(480f, 320f))
    val before = state.normalizedCropRect

    state.nudgeCropRect(CropHandle.Right, FloatPoint(-240f, 0f), minimumSizePx = 0f)

    assertEquals(before, state.normalizedCropRect, "a rectangle with no area must not be stored")
  }

  @Test
  fun aNudgeThatChangesNothingWritesNothing() {
    val state = state(FloatSize(480f, 320f))
    val before = state.normalizedCropRect

    state.nudgeCropRect(CropHandle.Right, FloatPoint(0f, 0f), minimumSizePx = minimum)

    assertEquals(before, state.normalizedCropRect)
  }

  private fun state(viewport: FloatSize): RealCropState {
    val state = RealCropState(
      source = CropSource.Bytes(ByteArray(0), "crop-nudge-test"),
      initialAspectRatio = AspectRatio.Free,
    )
    state.viewportSize = viewport
    // 60..300 x 40..220 in a 480 x 320 viewport, the same off-centre rectangle as above.
    state.normalizedCropRect = FloatRect(0.125f, 0.125f, 0.625f, 0.6875f)
    return state
  }

  private fun assertRect(expected: FloatRect, actual: FloatRect, message: String = "") {
    assertClose(expected.left, actual.left, TOLERANCE, "$message left of $actual")
    assertClose(expected.top, actual.top, TOLERANCE, "$message top of $actual")
    assertClose(expected.right, actual.right, TOLERANCE, "$message right of $actual")
    assertClose(expected.bottom, actual.bottom, TOLERANCE, "$message bottom of $actual")
  }

  private companion object {
    const val TOLERANCE = 1e-3f
  }
}
