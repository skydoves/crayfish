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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ViewportToImage] answers exactly what [CoordinateSpace.viewportToImage] does, bit for bit.
 *
 * Exact rather than within a tolerance, because the crop's pixels are sampled at these
 * coordinates: a last-place difference moves a bilinear weight across a rounding boundary and
 * changes a pixel, and "turning a crop got faster" must not also mean "its pixels changed".
 */
class ViewportToImageTest {

  @Test
  fun matchesThePointBasedMappingBitForBit() {
    var compared = 0
    for (space in spaces()) {
      val toImage = ViewportToImage(space)
      for (point in probes()) {
        val expected = space.viewportToImage(point)
        val actualX = toImage.x(point.x, point.y)
        val actualY = toImage.y(point.x, point.y)
        assertEquals(
          expected.x.toRawBits(),
          actualX.toRawBits(),
          "x of $point under $space: expected ${expected.x}, was $actualX",
        )
        assertEquals(
          expected.y.toRawBits(),
          actualY.toRawBits(),
          "y of $point under $space: expected ${expected.y}, was $actualY",
        )
        compared++
      }
    }
    // Set by construction below; a loop that compared nothing would pass every assertion above.
    assertEquals(spaces().size * probes().size, compared)
    assertTrue(compared > 1_000, "only $compared points were compared")
  }

  private fun spaces(): List<CoordinateSpace> {
    val transforms = listOf(
      CropTransform.Identity,
      CropTransform(rotationDegrees = 90f),
      CropTransform(rotationDegrees = 180f),
      CropTransform(rotationDegrees = 270f),
      CropTransform(flipHorizontal = true),
      CropTransform(flipVertical = true),
      CropTransform(rotationDegrees = 90f, flipHorizontal = true, flipVertical = true),
      CropTransform(rotationDegrees = 37f, scale = 1.6f),
      CropTransform(rotationDegrees = -12.5f, scale = 2.3f, offset = FloatPoint(31.7f, -18.2f)),
      CropTransform(rotationDegrees = 3f, scale = 0.7f, flipVertical = true),
      // Invalid fields, which both paths have to sanitise the same way.
      CropTransform(rotationDegrees = Float.NaN, scale = 0f, offset = FloatPoint(Float.NaN, 4f)),
    )
    val layouts = listOf(
      ImageSize(3840, 2160) to FloatSize(900f, 700f),
      ImageSize(1, 32767) to FloatSize(1080f, 1920f),
      ImageSize(4000, 3000) to FloatSize(333.3f, 777.7f),
    )
    val spaces = layouts.flatMap { (image, viewport) ->
      transforms.map { CoordinateSpace.fitting(image, viewport, it) }
    }
    // Degenerate, where both answer zero.
    return spaces + CoordinateSpace.fitting(ImageSize(0, 0), FloatSize(900f, 700f))
  }

  private fun probes(): List<FloatPoint> {
    val grid = (0..8).flatMap { row ->
      (0..8).map { column -> FloatPoint(column * 137.31f - 40f, row * 251.9f - 60f) }
    }
    return grid + listOf(
      FloatPoint(0.5f, 0.5f),
      FloatPoint(Float.NaN, 12f),
      FloatPoint(12f, Float.POSITIVE_INFINITY),
    )
  }
}
