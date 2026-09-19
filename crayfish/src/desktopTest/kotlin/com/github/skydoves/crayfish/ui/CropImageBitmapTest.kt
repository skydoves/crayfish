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

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.skydoves.crayfish.decode.ImageRegion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Cutting a rectangle out of an image that is already decoded.
 *
 * The shared primitive under the image-loader bridges, which is why it is tested here rather than
 * in one of them. An earlier bridge got this wrong in a way no type could catch: Skia's
 * `extractSubset` **aliases** the source rather than copying it, so two crops of one photo were the
 * same pixels. [aCropDoesNotAliasTheSource] is the assertion that would have caught it.
 */
class CropImageBitmapTest {

  /** Left half red, right half blue. */
  private fun fixture(width: Int = 40, height: Int = 20): ImageBitmap {
    val bitmap = ImageBitmap(width, height)
    CanvasDrawScope().draw(
      Density(1f),
      LayoutDirection.Ltr,
      Canvas(bitmap),
      Size(width.toFloat(), height.toFloat()),
    ) {
      drawRect(Color.Red, size = Size(width / 2f, height.toFloat()))
      drawRect(
        Color.Blue,
        topLeft = Offset(width / 2f, 0f),
        size = Size(width / 2f, height.toFloat()),
      )
    }
    return bitmap
  }

  @Test
  fun aCropIsThePixelsThatWereThere() {
    val cropped = assertNotNull(fixture().cropTo(ImageRegion(20, 0, 40, 20)))

    assertEquals(20, cropped.width)
    assertEquals(20, cropped.height)
    val map = cropped.toPixelMap()
    assertTrue(map[10, 10].blue > map[10, 10].red, "the right half of the fixture is not blue")
  }

  /**
   * Two crops of one image are two images.
   *
   * The regression that motivated this living in one place: an aliasing implementation passes every
   * size and bounds assertion and fails only this one.
   */
  @Test
  fun aCropDoesNotAliasTheSource() {
    val source = fixture()
    val left = assertNotNull(source.cropTo(ImageRegion(0, 0, 20, 20)))
    val right = assertNotNull(source.cropTo(ImageRegion(20, 0, 40, 20)))

    val leftPixel = left.toPixelMap()[10, 10]
    val rightPixel = right.toPixelMap()[10, 10]
    assertTrue(
      leftPixel.red > leftPixel.blue && rightPixel.blue > rightPixel.red,
      "both crops came back as the same pixels: left=$leftPixel right=$rightPixel",
    )
  }

  @Test
  fun aRegionRunningOffTheEdgeIsClipped() {
    val cropped = assertNotNull(fixture().cropTo(ImageRegion(30, 10, 200, 200)))

    assertEquals(10, cropped.width)
    assertEquals(10, cropped.height)
  }

  @Test
  fun aRegionEntirelyOutsideTheImageIsNull() {
    assertNull(fixture().cropTo(ImageRegion(100, 100, 120, 120)))
    assertNull(fixture().cropTo(ImageRegion(5, 5, 5, 5)), "an empty region")
  }

  /** The whole image is not worth a copy. */
  @Test
  fun croppingToTheWholeImageReturnsTheSameInstance() {
    val source = fixture()
    assertSame(source, source.cropTo(ImageRegion(0, 0, 40, 20)))
  }
}
