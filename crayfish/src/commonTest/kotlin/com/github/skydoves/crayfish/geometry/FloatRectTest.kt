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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rectangle every other file in this package is written in terms of.
 *
 * [FloatRect.isEmpty] and [FloatRect.isFinite] are the two guards that every entry point in the
 * geometry and gesture layers opens with, so a hole in either is a hole in all of them at once.
 * They are tested exhaustively, one case per edge per kind of non-finite value, rather than
 * sampled, because the failure they exist to stop is exactly the one that only shows up on the
 * field nobody thought of.
 */
class FloatRectTest {

  /** Off-centre and wider than it is tall: no axis can be swapped for the other and still pass. */
  private val rect = FloatRect(60f, 40f, 300f, 220f)

  private val nonFinite = mapOf(
    "NaN" to Float.NaN,
    "+Inf" to Float.POSITIVE_INFINITY,
    "-Inf" to Float.NEGATIVE_INFINITY,
  )

  @Test
  fun theBasicMeasurementsAreTakenFromTheRightPairOfEdges() {
    assertEquals(240f, rect.width)
    assertEquals(180f, rect.height)
    assertEquals(FloatSize(240f, 180f), rect.size)
    assertEquals(FloatPoint(180f, 130f), rect.center)
  }

  // -----------------------------------------------------------------------------------------------
  // isEmpty
  // -----------------------------------------------------------------------------------------------

  /**
   * "Empty" has to mean "bounds nothing", on either axis independently.
   *
   * Written as a negated `>` rather than `<=` so that a NaN edge, which fails every comparison,
   * reports empty instead of sliding through as a usable rectangle. Both axes are listed because
   * an implementation that only checks the width passes every square fixture ever written.
   */
  @Test
  fun aRectangleThatBoundsNothingOnEitherAxisIsEmpty() {
    assertFalse(rect.isEmpty, "a rectangle with area is not empty")

    assertTrue(FloatRect(60f, 40f, 60f, 220f).isEmpty, "zero width")
    assertTrue(FloatRect(60f, 40f, 300f, 40f).isEmpty, "zero height")
    assertTrue(FloatRect(300f, 40f, 60f, 220f).isEmpty, "inverted horizontally")
    assertTrue(FloatRect(60f, 220f, 300f, 40f).isEmpty, "inverted vertically")
    assertTrue(FloatRect.Zero.isEmpty, "the zero rectangle")

    assertTrue(FloatRect(60f, 40f, Float.NaN, 220f).isEmpty, "a NaN right edge bounds nothing")
    assertTrue(FloatRect(60f, 40f, 300f, Float.NaN).isEmpty, "a NaN bottom edge bounds nothing")
    assertTrue(FloatRect(Float.NaN, 40f, 300f, 220f).isEmpty, "a NaN left edge bounds nothing")
    assertTrue(FloatRect(60f, Float.NaN, 300f, 220f).isEmpty, "a NaN top edge bounds nothing")
  }

  // -----------------------------------------------------------------------------------------------
  // isFinite
  // -----------------------------------------------------------------------------------------------

  /** One case per edge per kind of non-finite value: twelve rectangles, none of them finite. */
  @Test
  fun aRectangleWithAnyNonFiniteEdgeIsNotFinite() {
    assertTrue(rect.isFinite, "an ordinary rectangle is finite")

    nonFinite.forEach { (name, value) ->
      assertFalse(FloatRect(value, 40f, 300f, 220f).isFinite, "a $name left edge")
      assertFalse(FloatRect(60f, value, 300f, 220f).isFinite, "a $name top edge")
      assertFalse(FloatRect(60f, 40f, value, 220f).isFinite, "a $name right edge")
      assertFalse(FloatRect(60f, 40f, 300f, value).isFinite, "a $name bottom edge")
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Containment
  // -----------------------------------------------------------------------------------------------

  /** Each side gets its own case, so a dropped or duplicated comparison cannot hide. */
  @Test
  fun aPointOutsideAnySideIsNotContained() {
    assertTrue(rect.contains(FloatPoint(180f, 130f)), "the middle")
    assertTrue(rect.contains(FloatPoint(60f, 40f)), "the top-left corner counts as inside")
    assertTrue(rect.contains(FloatPoint(300f, 220f)), "the bottom-right corner counts as inside")

    assertFalse(rect.contains(FloatPoint(59f, 130f)), "one pixel left of the left edge")
    assertFalse(rect.contains(FloatPoint(301f, 130f)), "one pixel right of the right edge")
    assertFalse(rect.contains(FloatPoint(180f, 39f)), "one pixel above the top edge")
    assertFalse(rect.contains(FloatPoint(180f, 221f)), "one pixel below the bottom edge")
  }

  @Test
  fun aRectangleContainsAnotherOnlyWhenEverySideIsInside() {
    assertTrue(rect.contains(FloatRect(70f, 50f, 290f, 210f)), "wholly inside")
    assertTrue(rect.contains(rect), "edges touching is allowed")

    assertFalse(rect.contains(FloatRect(59f, 50f, 290f, 210f)), "over the left edge")
    assertFalse(rect.contains(FloatRect(70f, 39f, 290f, 210f)), "over the top edge")
    assertFalse(rect.contains(FloatRect(70f, 50f, 301f, 210f)), "over the right edge")
    assertFalse(rect.contains(FloatRect(70f, 50f, 290f, 221f)), "over the bottom edge")
  }

  // -----------------------------------------------------------------------------------------------
  // Construction
  // -----------------------------------------------------------------------------------------------

  @Test
  fun inflateAndTranslateMoveTheEdgesTheyClaimTo() {
    assertEquals(FloatRect(50f, 30f, 310f, 230f), rect.inflate(10f))
    assertEquals(FloatRect(70f, 50f, 290f, 210f), rect.inflate(-10f))
    assertEquals(FloatRect(75f, 15f, 315f, 195f), rect.translate(FloatPoint(15f, -25f)))
  }

  @Test
  fun theCornersAreListedClockwiseFromTheTopLeft() {
    assertEquals(
      listOf(
        FloatPoint(60f, 40f),
        FloatPoint(300f, 40f),
        FloatPoint(300f, 220f),
        FloatPoint(60f, 220f),
      ),
      rect.corners(),
    )
  }

  /** The bounding box of no points at all is a defined value, not a crash on `points[0]`. */
  @Test
  fun theBoundingBoxOfNoPointsIsTheZeroRectangle() {
    assertEquals(FloatRect.Zero, FloatRect.bounding(emptyList()))
  }

  @Test
  fun theBoundingBoxTakesTheExtremeOnEachSideIndependently() {
    val scattered = listOf(
      FloatPoint(120f, -30f),
      FloatPoint(-40f, 90f),
      FloatPoint(35f, 250f),
      FloatPoint(410f, 12f),
    )

    assertEquals(FloatRect(-40f, -30f, 410f, 250f), FloatRect.bounding(scattered))
    assertEquals(
      FloatRect(120f, -30f, 120f, -30f),
      FloatRect.bounding(listOf(FloatPoint(120f, -30f))),
      "one point bounds itself",
    )
  }

  // -----------------------------------------------------------------------------------------------
  // fitInside
  // -----------------------------------------------------------------------------------------------

  /**
   * The letterboxing step. A 2:1 image in a 3:2 viewport is limited by the width, and the result
   * is centred, which is what makes the content's centre coincide with the viewport's, and that is
   * the assumption [CropTransform]'s pivot convention rests on.
   */
  @Test
  fun theFittedContentIsTheLargestThatFitsAndIsCentredInIt() {
    val into = FloatRect(0f, 0f, 480f, 320f)

    val wide = fitInside(FloatSize(2000f, 1000f), into)
    assertClose(FloatRect(0f, 40f, 480f, 280f), wide, TOLERANCE, "limited by the width")
    assertClose(into.center, wide.center, TOLERANCE, "and centred")

    val tall = fitInside(FloatSize(1000f, 2000f), into)
    assertClose(FloatRect(160f, 0f, 320f, 320f), tall, TOLERANCE, "limited by the height")
    assertClose(into.center, tall.center, TOLERANCE, "and centred")
  }

  /**
   * Degenerate input produces an empty rectangle rather than a NaN.
   *
   * This runs on the first frame of every cropper ever mounted: the viewport is measured as zero
   * and the image size is not known yet, and dividing one by the other is how a NaN reaches a
   * transform and stays there.
   */
  @Test
  fun fitInsideRefusesDegenerateInputWithoutProducingANaN() {
    val into = FloatRect(0f, 0f, 480f, 320f)
    // Infinite rather than NaN where the finiteness check is the one under test: a NaN dimension
    // already reports itself empty and never reaches it.
    val degenerate = listOf(
      FloatSize.Zero to into,
      FloatSize(2000f, 0f) to into,
      FloatSize(Float.NaN, 1000f) to into,
      FloatSize(Float.POSITIVE_INFINITY, 1000f) to into,
      FloatSize(2000f, 1000f) to FloatRect.Zero,
      FloatSize(2000f, 1000f) to FloatRect(0f, 0f, Float.POSITIVE_INFINITY, 320f),
      FloatSize(2000f, 1000f) to FloatRect(0f, 0f, Float.NaN, 320f),
    )

    degenerate.forEach { (content, container) ->
      val result = fitInside(content, container)

      assertTrue(result.isFinite, "fitInside($content, $container) = $result is not finite")
      assertTrue(result.isEmpty, "fitInside($content, $container) = $result should have no area")
    }

    assertEquals(
      FloatRect(240f, 160f, 240f, 160f),
      fitInside(FloatSize.Zero, into),
      "an unmeasured image still collapses to the centre of the viewport it would have filled",
    )
  }

  private companion object {
    /** A thousandth of a viewport unit: the fit is one division and one multiplication deep. */
    const val TOLERANCE = 1e-3f
  }
}
