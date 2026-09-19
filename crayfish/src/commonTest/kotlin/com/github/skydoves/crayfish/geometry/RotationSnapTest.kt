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

class RotationSnapTest {

  /**
   * The snapped values are exact multiples of 90 produced by `round(x / 90) * 90`, and the
   * unsnapped ones are returned by identity, so both sides are bit-exact and the tolerance only
   * has to absorb the one division and multiplication. A thousandth of a degree is far more than
   * that and far less than a wheel can express.
   */
  private val tolerance = 1e-3f

  @Test
  fun anglesInsideTheThresholdSnapToTheNearestRightAngle() {
    val cases = mapOf(
      0f to 0f,
      2f to 0f,
      -3f to 0f,
      88f to 90f,
      93f to 90f,
      177.5f to 180f,
      271f to 270f,
      -87f to -90f,
      -184f to -180f,
      359f to 360f,
    )

    cases.forEach { (input, expected) ->
      assertClose(expected, RotationSnap.snap(input), tolerance, "snap($input)")
      assertEquals(input != expected, RotationSnap.isSnapping(input), "isSnapping($input)")
    }
  }

  @Test
  fun anglesOutsideTheThresholdAreLeftAlone() {
    listOf(7f, 45f, -30f, 83f, 100f, 200.5f, -145f).forEach { input ->
      assertEquals(input, RotationSnap.snap(input), "snap($input) should not move")
      assertFalse(RotationSnap.isSnapping(input), "isSnapping($input)")
    }
  }

  /**
   * The boundary, both sides of it. The comparison is documented as inclusive, so exactly the
   * threshold snaps and a hair past it does not; pinning both is what stops the condition quietly
   * turning into `<` or into `<=` on the wrong quantity.
   */
  @Test
  fun theThresholdIsInclusiveAndTight() {
    val threshold = RotationSnap.DEFAULT_THRESHOLD_DEGREES

    assertClose(0f, RotationSnap.snap(threshold), tolerance, "exactly at the threshold")
    assertClose(90f, RotationSnap.snap(90f - threshold), tolerance, "exactly at the threshold")
    assertEquals(threshold + 0.01f, RotationSnap.snap(threshold + 0.01f), "just outside")
    assertEquals(-threshold - 0.01f, RotationSnap.snap(-threshold - 0.01f), "just outside")
  }

  @Test
  fun theThresholdIsConfigurable() {
    assertClose(0f, RotationSnap.snap(10f, thresholdDegrees = 15f), tolerance, "wide threshold")
    assertEquals(10f, RotationSnap.snap(10f, thresholdDegrees = 5f), "narrow threshold")
    // 46 rounds towards 90, which is 44 away, so a threshold of 43 is one degree short.
    assertEquals(46f, RotationSnap.snap(46f, thresholdDegrees = 43f), "just short of reaching 90")
    assertClose(90f, RotationSnap.snap(46f, thresholdDegrees = 44f), tolerance, "just reaching 90")
  }

  /**
   * The winding survives the snap. A wheel that has been spun twice round sits at 725 degrees, and
   * snapping it to 0 would send the image spinning backwards through two whole turns at the exact
   * moment the magnet engages, the kind of thing that looks like a rendering bug.
   */
  @Test
  fun snappingPreservesTheWindingRatherThanNormalising() {
    assertClose(720f, RotationSnap.snap(725f), tolerance, "two turns plus five")
    assertClose(-720f, RotationSnap.snap(-723f), tolerance, "two turns back")
    assertClose(1080f, RotationSnap.snap(1077f), tolerance, "three turns")
  }

  @Test
  fun normalizeFoldsAnyAngleIntoOneTurn() {
    assertClose(0f, RotationSnap.normalize(0f), tolerance)
    assertClose(0f, RotationSnap.normalize(360f), tolerance)
    assertClose(5f, RotationSnap.normalize(725f), tolerance)
    assertClose(270f, RotationSnap.normalize(-90f), tolerance)
    assertClose(359f, RotationSnap.normalize(-1f), tolerance)
    assertClose(180f, RotationSnap.normalize(-180f), tolerance)
  }

  /**
   * A snapped angle is evaluated exactly, not merely closely: `cosDegrees` reproduces Skia's
   * `SkScalarCosSnapToZero`, so a quarter turn produces an exactly axis-aligned result rather than
   * one off by 4e-8 that leaves a hairline of resampling along every edge.
   */
  @Test
  fun aSnappedAngleTurnsTheContentExactlyOntoItsAxes() {
    val content = FloatRect(0f, 0f, 200f, 100f)
    val pivot = content.center

    listOf(0f, 90f, 180f, 270f, -90f, 360f).forEach { degrees ->
      val turned = CropTransform(rotationDegrees = RotationSnap.snap(degrees))
        .mapRect(content, pivot)
      val expected = if (RotationSnap.normalize(degrees) % 180f == 0f) {
        content
      } else {
        FloatRect.fromCenter(pivot, FloatSize(100f, 200f))
      }

      // Zero tolerance: the claim is exactness, not closeness. `assertClose` rather than
      // `assertEquals` because a data class compares floats with `Float.compare`, which reports
      // -0.0 and 0.0 as different, a distinction no geometry here means to make.
      assertClose(expected, turned, tolerance = 0f, message = "$degrees should be exact")
    }
  }

  @Test
  fun degenerateAnglesAndThresholdsDoNotProduceNaN() {
    listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { bad ->
      assertEquals(0f, RotationSnap.snap(bad), "snap($bad)")
      assertEquals(0f, RotationSnap.normalize(bad), "normalize($bad)")
      assertFalse(RotationSnap.isSnapping(bad), "isSnapping($bad)")
    }

    listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { badThreshold ->
      assertEquals(
        7f,
        RotationSnap.snap(7f, thresholdDegrees = badThreshold),
        "threshold $badThreshold should disable snapping, not enable it everywhere",
      )
    }
  }
}
