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
package com.github.skydoves.crayfish.coil

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import coil3.size.Size
import com.github.skydoves.crayfish.decode.ImageRegion
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import androidx.compose.ui.geometry.Size as ComposeSize

/**
 * The Coil bridge: a stored rectangle applied inside the load.
 *
 * The cut itself is `ImageBitmap.cropTo` in the core module and is tested there. What is tested here
 * is the part only this module has: Coil's cache key, Coil's bitmap type, and the promise that a
 * rectangle it cannot apply leaves the picture alone rather than failing the load.
 */
class CropTransformationTest {

  /** Left half red, right half blue, as a Skia bitmap, which is what `coil3.Bitmap` is here. */
  private fun fixture(width: Int = 40, height: Int = 20) =
    ImageBitmap(width, height).also { bitmap ->
      CanvasDrawScope().draw(
        Density(1f),
        LayoutDirection.Ltr,
        Canvas(bitmap),
        ComposeSize(width.toFloat(), height.toFloat()),
      ) {
        drawRect(Color.Red, size = ComposeSize(width / 2f, height.toFloat()))
        drawRect(
          Color.Blue,
          topLeft = Offset(width / 2f, 0f),
          size = ComposeSize(width / 2f, height.toFloat()),
        )
      }
    }.asSkiaBitmap()

  @Test
  fun theTransformationCropsToTheRegion() = runTest {
    val cropped = CropTransformation(ImageRegion(20, 0, 40, 20))
      .transform(fixture(), Size.ORIGINAL)

    assertEquals(20, cropped.width)
    assertEquals(20, cropped.height)
    val pixel = cropped.asComposeImageBitmap().toPixelMap()[10, 10]
    assertTrue(pixel.blue > pixel.red, "the right half of the fixture is not blue")
  }

  /**
   * Two crops of one image are two cache entries.
   *
   * Without this they collide, and whichever screen asks second shows the first one's rectangle.
   */
  @Test
  fun twoRegionsAreTwoCacheKeys() {
    val left = CropTransformation(ImageRegion(0, 0, 20, 20)).cacheKey
    val right = CropTransformation(ImageRegion(20, 0, 40, 20)).cacheKey

    assertNotEquals(left, right)
    assertEquals(left, CropTransformation(ImageRegion(0, 0, 20, 20)).cacheKey)
    assertTrue(left.startsWith("crayfish-crop:"), "the key does not name its owner: $left")
  }

  /** A rectangle that cannot be applied leaves the picture alone rather than failing the load. */
  @Test
  fun anUnusableRegionReturnsTheInputUntouched() = runTest {
    val input = fixture()

    assertSame(input, CropTransformation(ImageRegion(0, 0, 0, 0)).transform(input, Size.ORIGINAL))
    assertSame(
      input,
      CropTransformation(ImageRegion(500, 500, 600, 600)).transform(input, Size.ORIGINAL),
      "a region entirely outside the image should leave it alone",
    )
  }

  @Test
  fun aClosedBitmapIsLeftAlone() = runTest {
    val input = fixture()
    input.close()

    assertSame(
      input,
      CropTransformation(ImageRegion(0, 0, 10, 10)).transform(input, Size.ORIGINAL),
      "a closed bitmap was read, which on Skia is a SIGSEGV rather than an exception",
    )
  }
}
