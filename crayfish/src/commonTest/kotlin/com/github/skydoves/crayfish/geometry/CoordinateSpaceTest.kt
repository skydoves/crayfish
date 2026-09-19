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

import com.github.skydoves.crayfish.decode.ImageRegion
import com.github.skydoves.crayfish.decode.ImageSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoordinateSpaceTest {

  private val image = ImageSize(1600, 900)
  private val viewport = FloatSize(1080f, 1920f)

  /**
   * A 16:9 image in a portrait viewport is width-limited: 1080 wide, 607.5 tall, centred at
   * y = 960, so the content occupies 656.25..1263.75.
   */
  private val fitted = FloatRect(0f, 656.25f, 1080f, 1263.75f)

  @Test
  fun fittingLetterboxesTheImageAndPutsItsCentreOnTheViewportCentre() {
    val space = CoordinateSpace.fitting(image, viewport)

    assertClose(fitted, space.contentBounds, 1e-3f, "fitted bounds")
    assertClose(FloatPoint(540f, 960f), space.pivot, 1e-3f, "pivot is the viewport centre")
  }

  /**
   * The round trip, across scales, free angles, and every combination of mirroring.
   *
   * Tolerance is a hundredth of a viewport unit. The trip out divides by the scale, as low as 0.3
   * here, so viewport coordinates near 1920 become image coordinates near 9500, and the trip back
   * multiplies it all out again; a handful of float ulps at that magnitude is a few thousandths.
   * A hundredth of a unit is comfortably above that and two orders of magnitude below a pixel, so
   * it cannot hide a sign error or a transposed axis.
   */
  @Test
  fun viewportToImageAndBackReturnsTheOriginalPoint() {
    val probes = listOf(
      FloatPoint(0f, 0f),
      FloatPoint(1080f, 0f),
      FloatPoint(0f, 1920f),
      FloatPoint(1080f, 1920f),
      FloatPoint(540f, 960f),
      FloatPoint(37f, 1301f),
      FloatPoint(-200f, 2500f),
    )

    forEachTransform { transform ->
      val space = CoordinateSpace.fitting(image, viewport, transform)

      probes.forEach { point ->
        assertClose(
          expected = point,
          actual = space.imageToViewport(space.viewportToImage(point)),
          tolerance = 1e-2f,
          message = "$transform round trip of $point",
        )
      }
    }
  }

  /**
   * And the same trip starting from image space, so that a bug which happens to cancel in one
   * direction cannot hide. Tolerance is a twentieth of an image pixel, the same float budget as
   * above, restated in the units this direction returns.
   */
  @Test
  fun imageToViewportAndBackReturnsTheOriginalPixel() {
    val probes = listOf(
      FloatPoint(0f, 0f),
      FloatPoint(1600f, 900f),
      FloatPoint(800f, 450f),
      FloatPoint(13f, 877f),
    )

    forEachTransform { transform ->
      val space = CoordinateSpace.fitting(image, viewport, transform)

      probes.forEach { pixel ->
        assertClose(
          expected = pixel,
          actual = space.viewportToImage(space.imageToViewport(pixel)),
          tolerance = 0.05f,
          message = "$transform round trip of pixel $pixel",
        )
      }
    }
  }

  @Test
  fun anUntransformedImageMapsOntoItsFittedRectangle() {
    val space = CoordinateSpace.fitting(image, viewport)

    assertClose(FloatPoint(0f, 656.25f), space.imageToViewport(FloatPoint(0f, 0f)), 1e-3f, "origin")
    assertClose(
      expected = FloatPoint(1080f, 1263.75f),
      actual = space.imageToViewport(FloatPoint(1600f, 900f)),
      tolerance = 1e-3f,
      message = "bottom-right pixel",
    )
  }

  /**
   * The call this type exists for. A crop frame laid exactly over the untransformed image is the
   * whole image; a frame half its size in the middle is the middle 800x450, because the fit scale
   * is 1080/1600 and dividing back out is the whole of the conversion.
   */
  @Test
  fun anOnScreenCropFrameBecomesAnImageRegion() {
    val space = CoordinateSpace.fitting(image, viewport)

    assertEquals(ImageRegion(0, 0, 1600, 900), space.toImageRegion(fitted))
    assertEquals(
      ImageRegion(400, 225, 1200, 675),
      space.toImageRegion(FloatRect.fromCenter(space.pivot, FloatSize(540f, 303.75f))),
      "the middle quarter of the image by area",
    )
  }

  /**
   * A quarter turn about the centre swaps the axes, so an upright frame over a turned image is a
   * region of the source that has swapped its own axes too. Asserted at whole pixels because the
   * angle is exact: `cosDegrees` snaps a right angle's cosine to zero, matching Skia.
   */
  @Test
  fun aQuarterTurnSwapsTheAxesOfTheRegion() {
    val space = CoordinateSpace.fitting(image, viewport, CropTransform(rotationDegrees = 90f))
    // The image, turned 90 degrees clockwise about (540, 960), occupies 607.5 x 1080 there.
    val turnedBounds = FloatRect.fromCenter(space.pivot, FloatSize(607.5f, 1080f))

    assertEquals(ImageRegion(0, 0, 1600, 900), space.toImageRegion(turnedBounds))
  }

  /** A frame dragged off the edge is clipped, because region decoders disagree about the rest. */
  @Test
  fun aRegionRunningOffTheImageIsClippedToIt() {
    val space = CoordinateSpace.fitting(image, viewport)
    val overhanging = FloatRect(-400f, 500f, 600f, 1000f)

    val region = space.toImageRegion(overhanging)

    assertEquals(ImageRegion(0, 0, 889, 509), region)
    assertTrue(ImageRegion.of(image).intersect(region!!) == region, "must lie inside the image")
  }

  @Test
  fun aFrameEntirelyOffTheImageHasNoRegion() {
    val space = CoordinateSpace.fitting(image, viewport)

    assertNull(space.toImageRegion(FloatRect(-5000f, -5000f, -4000f, -4000f)))
  }

  // -----------------------------------------------------------------------------------------------
  // Degenerate input.
  // -----------------------------------------------------------------------------------------------

  @Test
  fun aDegenerateSpaceMapsToZeroRatherThanNaN() {
    val spaces = listOf(
      CoordinateSpace.fitting(ImageSize.Zero, viewport),
      CoordinateSpace.fitting(image, FloatSize.Zero),
      CoordinateSpace.fitting(ImageSize(-5, 900), viewport),
      CoordinateSpace.fitting(image, FloatSize(Float.NaN, 100f)),
      CoordinateSpace(image, FloatRect(0f, 0f, Float.NaN, 10f), CropTransform.Identity),
    )

    spaces.forEach { space ->
      assertTrue(!space.isValid, "$space should report itself invalid")
      assertFinite(space.imageToViewport(FloatPoint(10f, 10f)), "$space")
      assertFinite(space.viewportToImage(FloatPoint(10f, 10f)), "$space")
      assertFinite(space.viewportToImage(FloatRect(0f, 0f, 10f, 10f)), "$space")
      assertNull(space.toImageRegion(FloatRect(0f, 0f, 10f, 10f)), "$space")
    }
  }

  @Test
  fun aNonFiniteOrEmptyFrameHasNoRegion() {
    val space = CoordinateSpace.fitting(image, viewport)

    assertNull(space.toImageRegion(FloatRect(0f, 0f, Float.NaN, 10f)))
    assertNull(space.toImageRegion(FloatRect.Zero))
    assertNull(space.toImageRegion(FloatRect(100f, 100f, 50f, 50f)))
  }

  @Test
  fun aNonFiniteTransformStillMapsFinitely() {
    val space = CoordinateSpace.fitting(
      imageSize = image,
      viewportSize = viewport,
      transform = CropTransform(scale = 0f, offset = FloatPoint(Float.NaN, 1f)),
    )

    assertFinite(space.imageToViewport(FloatPoint(800f, 450f)), "poisoned transform")
    assertFinite(space.viewportToImage(FloatPoint(540f, 960f)), "poisoned transform")
  }

  /** Every transform a user can reach: both zoom directions, free angles, all four mirrorings. */
  private fun forEachTransform(block: (CropTransform) -> Unit) {
    listOf(0.3f, 1f, 2.5f).forEach { scale ->
      listOf(0f, 17f, 90f, 180f, 213.7f, -45f).forEach { degrees ->
        listOf(false, true).forEach { horizontal ->
          listOf(false, true).forEach { vertical ->
            block(
              CropTransform(
                scale = scale,
                offset = FloatPoint(-73f, 214f),
                rotationDegrees = degrees,
                flipHorizontal = horizontal,
                flipVertical = vertical,
              ),
            )
          }
        }
      }
    }
  }
}
