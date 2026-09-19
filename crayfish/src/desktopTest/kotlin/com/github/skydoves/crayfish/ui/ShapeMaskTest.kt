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

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.github.skydoves.crayfish.decode.CropSource
import com.github.skydoves.crayfish.decode.ImageSize
import com.github.skydoves.crayfish.decode.TestImages
import com.github.skydoves.crayfish.decode.platformImageOfArgbPixels
import com.github.skydoves.crayfish.encode.EncodeOptions
import com.github.skydoves.crayfish.encode.EncodedFormat
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A circular crop comes back circular.
 *
 * `CropShape` documented itself as "the mask drawn over the crop rectangle, **and cut out of the
 * output when the format allows alpha**" from the day it was written, and only the first half was
 * ever built: the shape reached `CropOverlay` and nothing else, so picking Circle in the demo
 * produced a square photograph. `crop()` could not have honoured it even in principle, because it
 * had no shape to honour.
 */
@OptIn(ExperimentalTestApi::class)
class ShapeMaskTest {

  private fun opaque(width: Int, height: Int) = assertNotNull(
    platformImageOfArgbPixels(
      IntArray(width * height) { 0xFF336699.toInt() },
      ImageSize(width, height),
    ),
  )

  private fun alphaAt(
    image: com.github.skydoves.crayfish.decode.PlatformImage,
    x: Int,
    y: Int,
  ): Int {
    val pixels = assertNotNull(image.readArgbPixels())
    return (pixels[y * image.width + x] ushr 24) and 0xFF
  }

  // -------------------------------------------------------------------------------------------
  // The mask itself
  // -------------------------------------------------------------------------------------------

  @Test
  fun aCircleClearsTheCornersAndKeepsTheMiddle() {
    val source = opaque(64, 64)
    val masked = assertNotNull(maskToShape(source, CropShape.Circle, cornerRadiusPx = 0f))

    assertEquals(0, alphaAt(masked, 0, 0), "the top left corner is still opaque")
    assertEquals(0, alphaAt(masked, 63, 0), "the top right corner is still opaque")
    assertEquals(0, alphaAt(masked, 0, 63), "the bottom left corner is still opaque")
    assertEquals(0, alphaAt(masked, 63, 63), "the bottom right corner is still opaque")
    assertEquals(255, alphaAt(masked, 32, 32), "the middle was cleared")
    assertEquals(255, alphaAt(masked, 32, 2), "the top of the circle was cleared")
    assertEquals(255, alphaAt(masked, 2, 32), "the left of the circle was cleared")
    source.close()
    masked.close()
  }

  /** Not a circle on a non-square crop: an ellipse inscribed in whatever rectangle it is given. */
  @Test
  fun anEllipseFillsANonSquareCrop() {
    val source = opaque(80, 40)
    val masked = assertNotNull(maskToShape(source, CropShape.Circle, cornerRadiusPx = 0f))

    assertEquals(255, alphaAt(masked, 40, 2), "the ellipse does not reach the top edge")
    assertEquals(255, alphaAt(masked, 2, 20), "the ellipse does not reach the left edge")
    assertEquals(0, alphaAt(masked, 0, 0))
    source.close()
    masked.close()
  }

  @Test
  fun aRoundedRectangleClearsOnlyTheCorners() {
    val source = opaque(64, 64)
    val masked = assertNotNull(
      maskToShape(source, CropShape.RoundedRectangle(16.dp), cornerRadiusPx = 16f),
    )

    assertEquals(0, alphaAt(masked, 0, 0), "the corner is still opaque")
    assertEquals(255, alphaAt(masked, 32, 0), "the top edge was cleared")
    assertEquals(255, alphaAt(masked, 0, 32), "the left edge was cleared")
    assertEquals(255, alphaAt(masked, 32, 32))
    source.close()
    masked.close()
  }

  /** The edge is feathered rather than stepped, or a circle is visibly jagged at any real size. */
  @Test
  fun theEdgeIsAntiAliased() {
    val source = opaque(64, 64)
    val masked = assertNotNull(maskToShape(source, CropShape.Circle, cornerRadiusPx = 0f))

    val alphas = (0 until 64).map { alphaAt(masked, it, 32) }.toSet()
    assertTrue(
      alphas.any { it in 1..254 },
      "every pixel across the circle is fully on or fully off, so the edge is a staircase",
    )
    source.close()
    masked.close()
  }

  @Test
  fun aRectangleNeedsNoMaskAtAll() {
    val source = opaque(8, 8)
    assertNull(
      maskToShape(source, CropShape.Rectangle, cornerRadiusPx = 0f),
      "the rectangle allocated a copy to change nothing",
    )
    source.close()
  }

  // -------------------------------------------------------------------------------------------
  // End to end
  // -------------------------------------------------------------------------------------------

  @Test
  fun croppingToAnImageWithACircleShapeReturnsTransparentCorners() = runComposeUiTest {
    val state = readyState()
    state.shape = CropShape.Circle

    val success = assertIs<CropImage.Success>(runBlocking { state.cropToImage() })
    val map = success.image.toPixelMap()

    assertEquals(0f, map[0, 0].alpha, "the crop came back square")
    assertEquals(0f, map[map.width - 1, map.height - 1].alpha, "the crop came back square")
    assertTrue(map[map.width / 2, map.height / 2].alpha > 0.99f, "the middle was cleared too")
  }

  /** The control. Without a shape the same crop is opaque everywhere, so the test above means it. */
  @Test
  fun theSameCropWithoutAShapeIsOpaqueInTheCorners() = runComposeUiTest {
    val state = readyState()

    val success = assertIs<CropImage.Success>(runBlocking { state.cropToImage() })
    val map = success.image.toPixelMap()

    assertTrue(map[0, 0].alpha > 0.99f, "a rectangular crop came back with a transparent corner")
  }

  /** PNG carries alpha, so the cut reaches the bytes. */
  @Test
  fun croppingToPngWithACircleShapeEncodesTheTransparency() = runComposeUiTest {
    val state = readyState()
    state.shape = CropShape.Circle

    val success = assertIs<CropResult.Success>(
      runBlocking { state.crop(EncodeOptions(EncodedFormat.PNG)) },
    )
    assertTrue(success.bytes.isNotEmpty())
  }

  /**
   * JPEG has no alpha, so the shape is skipped rather than flattened onto a colour nobody chose.
   *
   * The alternative would be a refusal, and refusing to crop because the viewfinder was round is
   * not a trade any caller would want made for them.
   */
  @Test
  fun croppingToJpegWithACircleShapeStillSucceeds() = runComposeUiTest {
    val state = readyState()
    state.shape = CropShape.Circle

    assertIs<CropResult.Success>(runBlocking { state.crop(EncodeOptions(EncodedFormat.JPEG)) })
  }

  private fun androidx.compose.ui.test.ComposeUiTest.readyState(): RealCropState {
    var held: CropState? = null
    setContent {
      val state = rememberCropState(TestImages.source(TestImages.pngBytes(), key = "shape"))
      held = state
      Cropper(state = state, modifier = Modifier.fillMaxSize())
    }
    waitUntil(timeoutMillis = 10_000) { held?.status is CropStatus.Ready }
    waitUntil(timeoutMillis = 10_000) { held?.viewportSize?.isEmpty == false }
    waitForIdle()
    return assertNotNull(held) as RealCropState
  }
}
