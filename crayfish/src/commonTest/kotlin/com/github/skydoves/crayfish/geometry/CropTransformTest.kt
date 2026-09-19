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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CropTransformTest {

  /**
   * One thousandth of a viewport unit.
   *
   * The fixtures below are chosen so every step is exactly representable in binary floating point;
   * the only inexactness is `sin`/`cos` of a right angle in float radians, which is around 1e-5 at
   * these magnitudes. A thousandth of a pixel is above that and three orders of magnitude below
   * anything a display can show, so it cannot hide a real error.
   */
  private val tolerance = 1e-3f

  /** A 200x100 content rectangle at the viewport origin, so the pivot is exactly (100, 50). */
  private val content = FloatRect(0f, 0f, 200f, 100f)
  private val pivot = FloatPoint(100f, 50f)

  /**
   * The order fixture: `Translate · Rotate(centre) · Scale(centre)`, matching AOSP's
   * `RenderProperties::updateMatrix()`.
   *
   * Derived by hand for `scale = 2`, `rotation = 90` clockwise, `offset = (10, 20)`, pivot
   * `(100, 50)`, taking the content's top-left corner `(0, 0)`:
   *
   * 1. **Scale about the pivot.** `100 + (0 - 100) * 2 = -100`, `50 + (0 - 50) * 2 = -50`
   *    → `(-100, -50)`.
   * 2. **Rotate 90 clockwise about the same pivot.** Relative to the pivot that is `(-200, -100)`;
   *    with `cos = 0, sin = 1` the map `(x, y) -> (x*cos - y*sin, x*sin + y*cos)` gives
   *    `(100, -200)` → back to absolute, `(200, -150)`.
   * 3. **Translate.** `(200 + 10, -150 + 20)` → `(210, -130)`.
   *
   * The bottom-right corner `(200, 100)` runs the same way: scale to `(300, 150)`, rotate to
   * `(0, 250)`, translate to `(10, 270)`.
   *
   * The order is not interchangeable and that is the point of asserting an exact value. Applying
   * the translation *innermost* instead (a content-space pan, which is what a cropper that stores
   * "pan in image pixels" ends up doing) sends `(0, 0)` to `(160, -130)`, not `(210, -130)`.
   */
  @Test
  fun mapsAPointThroughTranslateRotateScaleInThatOrder() {
    val transform = CropTransform(
      scale = 2f,
      offset = FloatPoint(10f, 20f),
      rotationDegrees = 90f,
    )

    assertClose(
      expected = FloatPoint(210f, -130f),
      actual = transform.mapPoint(FloatPoint(0f, 0f), pivot),
      tolerance = tolerance,
      message = "content top-left",
    )
    assertClose(
      expected = FloatPoint(10f, 270f),
      actual = transform.mapPoint(FloatPoint(200f, 100f), pivot),
      tolerance = tolerance,
      message = "content bottom-right",
    )
  }

  /**
   * The centre-pivot canary, and the single most important assertion in this package.
   *
   * Whatever the scale and whatever the angle, the content's centre can only move by the
   * translation; every other step turns about it. Move the pivot to the content's top-left, which
   * is what telephoto's `coerceWithinContentBounds()` does and what its author named as the reason
   * rotation cannot be supported there, and this fixture maps the centre to `(-90, 220)` instead of
   * `(110, 70)`.
   */
  @Test
  fun theContentCentreMovesOnlyByTheTranslation() {
    val cases = listOf(
      CropTransform(scale = 2f, offset = FloatPoint(10f, 20f), rotationDegrees = 90f),
      CropTransform(scale = 0.25f, offset = FloatPoint(10f, 20f), rotationDegrees = 37.5f),
      CropTransform(
        scale = 3.75f,
        offset = FloatPoint(10f, 20f),
        rotationDegrees = -212f,
        flipHorizontal = true,
        flipVertical = true,
      ),
    )

    cases.forEach { transform ->
      assertClose(
        expected = pivot + transform.offset,
        actual = transform.mapPoint(pivot, pivot),
        tolerance = tolerance,
        message = "$transform should leave the pivot fixed but for the offset",
      )
    }
  }

  /**
   * A 180-degree turn about the centre swaps opposite corners exactly. This is the assertion that
   * notices a rotation applied about the origin, or about the content's top-left: either sends the
   * top-left corner to `(-200, -100)` rather than onto the bottom-right corner.
   */
  @Test
  fun aHalfTurnAboutTheCentreSwapsOppositeCorners() {
    val transform = CropTransform(rotationDegrees = 180f)

    assertClose(
      FloatPoint(200f, 100f),
      transform.mapPoint(FloatPoint(0f, 0f), pivot),
      tolerance,
      "top-left goes to bottom-right",
    )
    assertClose(
      FloatPoint(0f, 0f),
      transform.mapPoint(FloatPoint(200f, 100f), pivot),
      tolerance,
      "bottom-right goes to top-left",
    )
  }

  /**
   * Mirroring rides with the scale, inside the rotation, exactly as `graphicsLayer(scaleX = -1f)`
   * does it. Hand-computed: a horizontal flip at `scale = 2` sends `(0, 0)` to
   * `100 + (0 - 100) * -2 = 300` on x and `50 + (0 - 50) * 2 = -50` on y.
   */
  @Test
  fun mirroringIsAppliedWithTheScale() {
    val transform = CropTransform(scale = 2f, flipHorizontal = true)

    assertEquals(-2f, transform.signedScaleX)
    assertEquals(2f, transform.signedScaleY)
    assertClose(
      FloatPoint(300f, -50f),
      transform.mapPoint(FloatPoint(0f, 0f), pivot),
      tolerance,
      "flipped top-left",
    )
  }

  /** A flip about the content's own centre maps the content rectangle onto itself. */
  @Test
  fun mirroringDoesNotMoveTheContentRectangle() {
    listOf(
      CropTransform(flipHorizontal = true),
      CropTransform(flipVertical = true),
      CropTransform(flipHorizontal = true, flipVertical = true),
    ).forEach { transform ->
      assertClose(content, transform.mapRect(content, pivot), tolerance, "$transform")
    }
  }

  /**
   * The anchoring property, as the user experiences it: whatever content pixel is under the pinch
   * centroid before the zoom is still under it afterwards.
   *
   * Swept over centroids that include all four viewport corners, where a pivot mistake shows up
   * largest since the error is proportional to the distance from the pivot, and over scales that
   * both zoom in and zoom out, starting from a transform that is already rotated, panned and
   * mirrored so that nothing can pass by accident.
   *
   * Tolerance is a hundredth of a viewport unit: the check maps a point out through the inverse
   * transform and back in through the forward one, which at these magnitudes (a 1000x800 viewport
   * and scales up to 3.7) costs a few float ulps, around 1e-3.
   */
  @Test
  fun zoomingAboutACentroidLeavesTheContentUnderItStill() {
    val viewport = FloatSize(1000f, 800f)
    val contentBounds = fitInside(FloatSize(1600f, 900f), FloatRect.of(viewport))
    val zoomPivot = contentBounds.center
    val start = CropTransform(
      scale = 1.3f,
      offset = FloatPoint(-40f, 17f),
      rotationDegrees = 23f,
      flipHorizontal = true,
    )
    val centroids = listOf(
      FloatPoint(0f, 0f),
      FloatPoint(1000f, 0f),
      FloatPoint(0f, 800f),
      FloatPoint(1000f, 800f),
      FloatPoint(500f, 400f),
      FloatPoint(123f, 777f),
    )

    centroids.forEach { centroid ->
      val anchored = start.unmapPoint(centroid, zoomPivot)

      listOf(0.5f, 1f, 1.3f, 2f, 3.7f).forEach { newScale ->
        val zoomed = start.scaledAround(newScale, centroid, zoomPivot)

        assertEquals(newScale, zoomed.scale, "scale should be applied verbatim")
        assertClose(
          expected = centroid,
          actual = zoomed.mapPoint(anchored, zoomPivot),
          tolerance = 1e-2f,
          message = "centroid $centroid at scale $newScale drifted",
        )
      }
    }
  }

  /**
   * The positive control for the test above. If the offset never had to change, that test would
   * pass with [CropTransform.scaledAround] returning `copy(scale = newScale)` and would be proving
   * nothing, which is exactly the bug it exists to catch, since the transform order puts the
   * translation outside the scale and so the compensation does not fall out of the maths.
   */
  @Test
  fun zoomingAboutAnOffCentreCentroidGenuinelyHasToMoveTheOffset() {
    val zoomPivot = FloatPoint(500f, 400f)
    val start = CropTransform(scale = 1f, offset = FloatPoint(12f, -8f))

    val zoomed = start.scaledAround(newScale = 2f, centroid = FloatPoint(0f, 0f), pivot = zoomPivot)

    assertNotEquals(start.offset, zoomed.offset)
    // Hand-computed: ratio = 2, centroid relative to the pivot = (-500, -400), so
    // offset' = (-500, -400) * (1 - 2) + (12, -8) * 2 = (500 + 24, 400 - 16) = (524, 384).
    assertClose(FloatPoint(524f, 384f), zoomed.offset, tolerance, "compensating translation")
  }

  /** Zooming about the pivot itself only scales the offset: no lateral compensation. */
  @Test
  fun zoomingAboutThePivotOnlyScalesTheOffset() {
    val zoomPivot = FloatPoint(500f, 400f)
    val start = CropTransform(scale = 2f, offset = FloatPoint(30f, -40f))

    val zoomed = start.scaledAround(newScale = 1f, centroid = zoomPivot, pivot = zoomPivot)

    assertClose(FloatPoint(15f, -20f), zoomed.offset, tolerance, "offset halves with the scale")
  }

  @Test
  fun unmapPointInvertsMapPoint() {
    val transform = CropTransform(
      scale = 2.5f,
      offset = FloatPoint(-31f, 62f),
      rotationDegrees = 137.25f,
      flipHorizontal = true,
      flipVertical = true,
    )

    listOf(
      FloatPoint(0f, 0f),
      FloatPoint(200f, 100f),
      FloatPoint(-50f, 250f),
      pivot,
    ).forEach { point ->
      // A tenth of a unit: the round trip divides by 2.5 and multiplies back, and these fixtures
      // reach a few hundred units, so a handful of ulps is around 1e-4. A tenth is far below the
      // pixel that anything downstream cares about.
      assertClose(
        expected = point,
        actual = transform.unmapPoint(transform.mapPoint(point, pivot), pivot),
        tolerance = 0.1f,
        message = "round trip of $point",
      )
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Degenerate input. A NaN that reaches a transform is invisible until something refuses to draw;
  // Compose's own VelocityTracker shipped exactly that into two libraries.
  // -----------------------------------------------------------------------------------------------

  @Test
  fun aNonFiniteTransformIsTreatedAsTheIdentityRatherThanPropagating() {
    val poisoned = CropTransform(
      scale = Float.NaN,
      offset = FloatPoint(Float.POSITIVE_INFINITY, Float.NaN),
      rotationDegrees = Float.NaN,
    )

    assertEquals(CropTransform.Identity, poisoned.sanitized())
    assertFinite(poisoned.mapPoint(FloatPoint(10f, 10f), pivot), "mapPoint")
    assertFinite(poisoned.unmapPoint(FloatPoint(10f, 10f), pivot), "unmapPoint")
    assertFinite(poisoned.mapRect(content, pivot), "mapRect")
    assertFinite(poisoned.scaledAround(2f, FloatPoint(1f, 1f), pivot), "scaledAround")
  }

  /**
   * A zero scale has no inverse, so it is read as un-zoomed rather than collapsed. The alternative,
   * clamping to a small epsilon, keeps the arithmetic finite but sends [unmapPoint] to values in
   * the millions, which is a NaN bug wearing a disguise.
   */
  @Test
  fun aZeroOrNegativeScaleIsReadAsUnzoomed() {
    listOf(0f, -1f, -0.0f).forEach { scale ->
      val transform = CropTransform(scale = scale)

      assertEquals(1f, transform.sanitized().scale, "scale $scale")
      assertClose(
        FloatPoint(0f, 0f),
        transform.mapPoint(FloatPoint(0f, 0f), pivot),
        tolerance,
        "scale $scale",
      )
      assertFinite(transform.unmapPoint(FloatPoint(0f, 0f), pivot), "scale $scale")
    }
  }

  @Test
  fun aNonFiniteOrNonPositiveTargetScaleIsRefusedRatherThanApplied() {
    val start = CropTransform(scale = 2f, offset = FloatPoint(5f, 5f))

    listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -3f).forEach { newScale ->
      val zoomed = start.scaledAround(newScale, FloatPoint(10f, 10f), pivot)

      assertEquals(start, zoomed, "newScale $newScale should be refused")
    }
  }

  @Test
  fun aNonFinitePointOrPivotDoesNotPoisonTheResult() {
    val transform = CropTransform(scale = 2f, rotationDegrees = 30f)
    val nan = FloatPoint(Float.NaN, Float.NaN)

    assertFinite(transform.mapPoint(nan, pivot), "NaN point")
    assertFinite(transform.mapPoint(FloatPoint(1f, 1f), nan), "NaN pivot")
    assertFinite(transform.unmapPoint(nan, nan), "NaN both")
    assertFinite(transform.scaledAround(2f, nan, nan), "NaN centroid and pivot")
  }

  @Test
  fun fitInsideLetterboxesAndCentres() {
    // A 16:9 image in a 1080x1920 portrait viewport: width-limited, so 1080 wide and 607.5 tall,
    // centred vertically at 960.
    val fitted = fitInside(FloatSize(1600f, 900f), FloatRect(0f, 0f, 1080f, 1920f))

    assertClose(FloatRect(0f, 656.25f, 1080f, 1263.75f), fitted, tolerance, "letterboxed")
    assertClose(
      FloatPoint(540f, 960f),
      fitted.center,
      tolerance,
      "content centre is the viewport's",
    )
  }

  @Test
  fun fitInsideDegeneratesWithoutDividingByZero() {
    listOf(
      FloatSize.Zero to FloatRect(0f, 0f, 100f, 100f),
      FloatSize(100f, 100f) to FloatRect.Zero,
      FloatSize(Float.NaN, 10f) to FloatRect(0f, 0f, 100f, 100f),
      FloatSize(10f, 10f) to FloatRect(0f, 0f, Float.NaN, 100f),
    ).forEach { (source, into) ->
      val fitted = fitInside(source, into)

      assertFinite(fitted, "$source in $into")
      assertTrue(fitted.isEmpty, "$source in $into should be empty, was $fitted")
    }
  }
}
