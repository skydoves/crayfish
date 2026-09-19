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

import com.github.skydoves.crayfish.decode.ImageSize
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CropCoverageTest {

  private val viewport = FloatSize(1000f, 1000f)

  /** A 2:1 image letterboxed into a square viewport: 1000x500, centred on the pivot (500, 500). */
  private val content = fitInside(FloatSize(2000f, 1000f), FloatRect.of(viewport))

  /** A square crop frame in the middle, the shape a cropper with a 1:1 output ratio draws. */
  private val cropFrame = FloatRect.fromCenter(viewport.center, FloatSize(400f, 400f))

  /**
   * Translation first: a 5-degree tilt with the image panned 200 units down leaves the crop frame
   * uncovered at the top, and the image is still tall enough to cover it: 500 units against the
   * 433 that a 400-unit square needs once tilted 5 degrees. So the fix is a slide, and the scale
   * must come back untouched, bit for bit.
   *
   * This is the assertion that stops the image pulsing in size on every nudge of a rotation wheel.
   * uCrop gets this right and it is the reason its rotation feels calm; reaching for the scale
   * first is the obvious implementation and it is wrong.
   */
  @Test
  fun coverageIsRestoredByTranslationAloneWhenTranslationCanDoIt() {
    val tilted = CropTransform(rotationDegrees = 5f, offset = FloatPoint(0f, 200f))
    assertFalse(
      CropCoverage.covers(tilted, content, cropFrame),
      "fixture must start uncovered, otherwise this test proves nothing",
    )

    val correction = CropCoverage.correct(tilted, content, cropFrame)

    assertFalse(correction.scaled, "translation was enough, so the scale must not move")
    assertEquals(tilted.scale, correction.transform.scale, "scale must be returned bit-identical")
    assertEquals(tilted.rotationDegrees, correction.transform.rotationDegrees)
    assertTrue(correction.translated, "the offset had to move")
    assertTrue(
      CropCoverage.covers(correction.transform, content, cropFrame),
      "corrected transform still does not cover: ${correction.transform}",
    )
  }

  /**
   * A whole sweep of small rotations from a panned start, all of which translation can absorb. A
   * 400-unit square needs `400 * (cos a + sin a)` of the image's 500-unit height, which stays under
   * 500 until roughly 17 degrees, so none of these may touch the scale.
   */
  @Test
  fun smallRotationsNeverReachForTheScale() {
    var uncovered = 0

    for (degrees in 0..15) {
      listOf(FloatPoint(0f, 0f), FloatPoint(150f, 120f), FloatPoint(-300f, -90f)).forEach { pan ->
        val transform = CropTransform(rotationDegrees = degrees.toFloat(), offset = pan)
        if (!CropCoverage.covers(transform, content, cropFrame)) uncovered++

        val correction = CropCoverage.correct(transform, content, cropFrame)

        assertFalse(correction.scaled, "$degrees degrees panned by $pan should not rescale")
        assertEquals(1f, correction.transform.scale, "$degrees degrees panned by $pan")
        assertTrue(
          CropCoverage.covers(correction.transform, content, cropFrame),
          "$degrees degrees panned by $pan is still uncovered",
        )
      }
    }
    assertTrue(uncovered > 0, "every case was already covered, so nothing was being corrected")
  }

  /**
   * Past the angle translation can absorb, the scale grows, and only by the deficit.
   *
   * Hand-computed: a 400-unit square tilted 45 degrees needs `400 * sqrt(2) = 565.7` units of
   * image on both axes, and the image is only 500 tall, so
   * `deltaScale = 565.7 / 500 = 1.1314`. The crop frame is inflated by
   * [CropCoverage.CORRECTION_SLACK] on each side before the deficit is measured, so the expected
   * factor is `(400 + 2 * slack) * sqrt(2) / 500`, which is 1.13165. The tolerance is a
   * ten-thousandth, tight enough that a wrong formula (dividing by the image's width rather than
   * its height, say, which would give 0.566) cannot slip through.
   */
  @Test
  fun aLargeRotationGrowsTheScaleByExactlyTheDeficit() {
    val turned = CropTransform(rotationDegrees = 45f)
    val expected = (400f + 2f * CropCoverage.CORRECTION_SLACK) * sqrt(2f) / 500f

    val correction = CropCoverage.correct(turned, content, cropFrame)

    assertTrue(correction.scaled, "45 degrees cannot be covered by translation alone")
    assertClose(expected, correction.transform.scale, 1e-4f, "deltaScale")
    assertTrue(
      CropCoverage.covers(correction.transform, content, cropFrame),
      "corrected transform still does not cover: ${correction.transform}",
    )
  }

  /**
   * The other half of "only by the deficit": shave one per cent off the corrected scale and,
   * however it is then placed, the crop frame is no longer covered. Without this, a correction that
   * grew the scale by a comfortable factor of two would pass the containment check above, and
   * nobody would notice the image jumping.
   */
  @Test
  fun theGrownScaleIsMinimal() {
    val correction = CropCoverage.correct(CropTransform(rotationDegrees = 45f), content, cropFrame)
    val shaved = correction.transform.copy(scale = correction.transform.scale * 0.99f)

    val bestPlacement = shaved.copy(
      offset = CropBounds.coerceOffset(shaved, content, cropFrame),
    )

    assertFalse(
      CropCoverage.covers(bestPlacement, content, cropFrame),
      "one per cent less scale should not be enough, so the correction was not minimal",
    )
  }

  @Test
  fun anAlreadyCoveringTransformIsReturnedUntouched() {
    val fine = CropTransform(scale = 1.4f, offset = FloatPoint(20f, -15f), rotationDegrees = 3f)
    assertTrue(CropCoverage.covers(fine, content, cropFrame))

    val correction = CropCoverage.correct(fine, content, cropFrame)

    assertEquals(fine, correction.transform)
    assertFalse(correction.translated)
    assertFalse(correction.scaled)
  }

  /** Mirroring about the content's own centre maps it onto itself, so it cannot uncover. */
  @Test
  fun mirroringNeverChangesWhetherTheFrameIsCovered() {
    val base = CropTransform(scale = 1.2f, rotationDegrees = 31f, offset = FloatPoint(40f, -25f))

    listOf(false, true).forEach { horizontal ->
      listOf(false, true).forEach { vertical ->
        assertEquals(
          CropCoverage.covers(base, content, cropFrame),
          CropCoverage.covers(
            base.copy(flipHorizontal = horizontal, flipVertical = vertical),
            content,
            cropFrame,
          ),
          "flipH=$horizontal flipV=$vertical changed the verdict",
        )
      }
    }
  }

  /**
   * The property test: sweep random angle by aspect ratio by zoom by pan by mirroring, and assert
   * the crop frame ends up inside the rotated image every single time.
   *
   * The containment check here is deliberately **not** [CropCoverage.covers]: that would only
   * prove the algorithm agrees with itself. Instead each corner of the crop frame is pushed back
   * through [CropTransform.unmapPoint] into the content's own untransformed space, where the image
   * is simply [content] and containment is four comparisons. A viewport point lies inside the
   * rotated image exactly when its pre-image lies inside the content rectangle, so this is the real
   * geometric property rather than a restatement of the implementation.
   *
   * The seed is fixed so a failure can be reproduced by re-running rather than by luck.
   *
   * Tolerance is a twentieth of a content unit. The correction crosses a rotation twice, once to
   * measure in the un-rotated frame and once to convert the answer back, which at these magnitudes
   * costs a few thousandths of a unit, and the check itself divides by the scale, which at the
   * smallest scale in the sweep multiplies that by five.
   */
  @Test
  fun theCropFrameIsAlwaysCoveredAfterCorrection() {
    val random = Random(seed = 20260917)
    val tolerance = 0.05f
    var needed = 0
    var grew = 0

    repeat(TRIALS) { trial ->
      val trialViewport = FloatSize(
        width = random.nextFloat(320f, 2400f),
        height = random.nextFloat(320f, 2400f),
      )
      val image = ImageSize(random.nextInt(120, 6000), random.nextInt(120, 6000))
      val bounds = fitInside(image.toFloatSize(), FloatRect.of(trialViewport))
      val frame = FloatRect.fromCenter(
        center = trialViewport.center,
        size = FloatSize(
          width = trialViewport.width * random.nextFloat(0.2f, 0.95f),
          height = trialViewport.height * random.nextFloat(0.2f, 0.95f),
        ),
      )
      val transform = CropTransform(
        scale = random.nextFloat(0.2f, 5f),
        offset = FloatPoint(
          x = random.nextFloat(-trialViewport.width, trialViewport.width),
          y = random.nextFloat(-trialViewport.height, trialViewport.height),
        ),
        rotationDegrees = random.nextFloat(-180f, 180f),
        flipHorizontal = random.nextBoolean(),
        flipVertical = random.nextBoolean(),
      )
      val fixture = "trial $trial: viewport=$trialViewport image=$image bounds=$bounds " +
        "frame=$frame transform=$transform"

      if (!CropCoverage.covers(transform, bounds, frame)) needed++
      val correction = CropCoverage.correct(transform, bounds, frame)
      if (correction.scaled) grew++

      assertFinite(correction.transform, fixture)
      assertTrue(
        CropCoverage.covers(correction.transform, bounds, frame),
        "$fixture -> ${correction.transform} still reports uncovered",
      )
      frame.corners().forEach { corner ->
        val local = correction.transform.unmapPoint(corner, bounds.center)

        assertTrue(
          bounds.inflate(tolerance).contains(local),
          "$fixture -> corner $corner maps to $local, outside $bounds",
        )
      }
    }

    // Positive controls. Without these the sweep could be passing because every random transform
    // happened to cover the frame already, or because every correction reached for the scale.
    assertTrue(needed > TRIALS / 10, "only $needed of $TRIALS trials needed correcting")
    assertTrue(grew > 0, "no trial ever had to grow the scale")
    assertTrue(grew < needed, "every correction grew the scale; translation is never being tried")
  }

  // -----------------------------------------------------------------------------------------------
  // Degenerate input.
  // -----------------------------------------------------------------------------------------------

  @Test
  fun degenerateRectanglesAreHandledWithoutThrowingOrRescaling() {
    val transform = CropTransform(scale = 2f, rotationDegrees = 12f)
    val broken = listOf(
      FloatRect.Zero,
      FloatRect(0f, 0f, Float.NaN, 100f),
      FloatRect(100f, 100f, 50f, 50f),
    )

    broken.forEach { rect ->
      val byContent = CropCoverage.correct(transform, rect, cropFrame)
      val byFrame = CropCoverage.correct(transform, content, rect)

      assertEquals(transform, byContent.transform, "degenerate content $rect")
      assertEquals(transform, byFrame.transform, "degenerate frame $rect")
      assertFalse(CropCoverage.covers(transform, rect, cropFrame), "nothing covers from $rect")
      assertTrue(CropCoverage.covers(transform, content, rect), "$rect asks to cover nothing")
    }
  }

  @Test
  fun aNonFiniteTransformIsCorrectedFromItsSanitisedForm() {
    val poisoned = CropTransform(
      scale = Float.NaN,
      offset = FloatPoint(Float.NaN, Float.NEGATIVE_INFINITY),
      rotationDegrees = Float.NaN,
    )

    val correction = CropCoverage.correct(poisoned, content, cropFrame)

    assertFinite(correction.transform, "corrected from a poisoned transform")
    assertTrue(CropCoverage.covers(correction.transform, content, cropFrame))
  }

  // -----------------------------------------------------------------------------------------------
  // The tolerance parameter.
  // -----------------------------------------------------------------------------------------------

  /**
   * A caller polling [CropCoverage.covers] every frame against numbers it did not compute itself
   * wants a fraction of a pixel of give; the default of zero is the honest question. Both sides are
   * pinned here, and the middle case, a tolerance smaller than the miss, is what stops the
   * parameter being read as "forgive everything".
   */
  @Test
  fun aToleranceForgivesExactlyAsMuchAsItSays() {
    // Panned 60 down: the image runs 310..810 and the frame 300..700, so the frame's top edge is
    // uncovered by exactly 10.
    val panned = CropTransform(offset = FloatPoint(0f, 60f))

    assertFalse(
      CropCoverage.covers(panned, content, cropFrame),
      "the fixture must start uncovered, or every assertion below is vacuous",
    )
    assertFalse(
      CropCoverage.covers(panned, content, cropFrame, tolerance = 5f),
      "5 units of give cannot forgive a 10 unit miss",
    )
    assertTrue(
      CropCoverage.covers(panned, content, cropFrame, tolerance = 20f),
      "20 units of give covers a 10 unit miss",
    )
  }

  /**
   * An unusable tolerance is no tolerance, not an infinite one.
   *
   * Inflating by an infinity produces a rectangle that contains everything, so a NaN arriving from
   * a caller's own arithmetic would turn the check into a constant `true`: a coverage test that
   * can never report a problem.
   */
  @Test
  fun anUnusableToleranceIsNoToleranceAtAll() {
    val panned = CropTransform(offset = FloatPoint(0f, 60f))

    listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -20f, 0f).forEach { give ->
      assertFalse(
        CropCoverage.covers(panned, content, cropFrame, tolerance = give),
        "a tolerance of $give must behave as zero",
      )
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Corrections the arithmetic cannot express.
  // -----------------------------------------------------------------------------------------------

  /**
   * An image zoomed so far out that its transformed rectangle rounds to a single point.
   *
   * There is no scale that brings it back, because the deficit is unbounded, so the transform is
   * handed straight back rather than multiplied by a factor derived from a division by zero.
   * Without the guard the scale becomes an infinity and the image stops existing for good: every
   * later frame derives from it, and nothing downstream re-checks.
   */
  @Test
  fun anImageScaledIntoASinglePointIsLeftAloneRatherThanGrown() {
    // 1e-30 against a pivot at 500: every corner lands within half an ulp of the pivot itself.
    val vanished = CropTransform(scale = 1e-30f)

    val correction = CropCoverage.correct(vanished, content, cropFrame)

    assertEquals(vanished, correction.transform, "a vanished image must be handed straight back")
    assertFalse(correction.translated)
    assertFalse(correction.scaled)
  }

  /**
   * The deficit itself overflows: the image is real but so small that the factor needed to cover
   * the frame is past [Float.MAX_VALUE].
   *
   * The contract is to keep the translation and leave the scale alone, which is strictly better
   * than the alternative, since an infinite scale is indistinguishable from a NaN two frames on.
   */
  @Test
  fun anImageTooSmallToGrowIntoTheFrameKeepsItsScaleInsteadOfOverflowing() {
    // A one-unit image scaled to 1e-37: a hair over 1e-37 wide against a 400 unit frame.
    val speck = FloatRect(-0.5f, -0.25f, 0.5f, 0.25f)
    val shrunk = CropTransform(scale = 1e-37f)

    val correction = CropCoverage.correct(shrunk, speck, cropFrame)

    assertFalse(correction.scaled, "there is no finite factor, so the scale must not move")
    assertEquals(shrunk.scale, correction.transform.scale, "the scale must come back untouched")
    assertFinite(correction.transform, "an unrepresentable deficit must not leak an infinity")
  }

  /**
   * A content rectangle wide enough that transforming it stops producing numbers.
   *
   * Scaling 2e38 units by 100 overflows to an infinity, and the rotation step then multiplies that
   * infinity by a zero sine, so every corner comes back NaN. The correction must hand the
   * transform straight back rather than rescale by a factor derived from a NaN. A scale of NaN is
   * not recoverable by any later gesture.
   */
  @Test
  fun anImageWhoseTransformStopsProducingNumbersIsLeftAlone() {
    // A degenerate panorama: 2e38 units across and one unit tall, zoomed 100x.
    val panorama = FloatRect(-1e38f, 0f, 1e38f, 1f)
    val zoomed = CropTransform(scale = 100f)

    val correction = CropCoverage.correct(zoomed, panorama, cropFrame)

    assertEquals(zoomed, correction.transform, "a NaN frame must not produce a rescale")
    assertFalse(correction.scaled)
    assertFalse(correction.translated)
  }

  /**
   * Past the angle translation can absorb **and** panned off centre: the correction has to do both.
   *
   * The 45-degree test above starts from a centred transform, where the re-placement happens to
   * land on the offset it started with, so it cannot tell "the offset was recomputed" from "the
   * offset was never touched".
   */
  @Test
  fun aLargeRotationFromAPannedStartBothGrowsTheScaleAndMovesTheImage() {
    val turnedAndPanned = CropTransform(
      rotationDegrees = 45f,
      offset = FloatPoint(150f, -120f),
    )

    val correction = CropCoverage.correct(turnedAndPanned, content, cropFrame)

    assertTrue(correction.scaled, "45 degrees cannot be covered by translation alone")
    assertTrue(correction.translated, "a pan of (150, -120) has to be undone as well")
    assertTrue(
      CropCoverage.covers(correction.transform, content, cropFrame),
      "corrected transform still does not cover: ${correction.transform}",
    )
  }

  private companion object {
    /**
     * Enough trials that a bug confined to one quadrant of the angle sweep still shows up, and few
     * enough that the whole class stays well under a second.
     */
    const val TRIALS = 2_000
  }
}
