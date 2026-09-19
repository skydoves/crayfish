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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.IntSize
import com.github.skydoves.crayfish.decode.CropSource
import com.github.skydoves.crayfish.decode.ImageFormat
import com.github.skydoves.crayfish.decode.ImageRegion
import com.github.skydoves.crayfish.decode.ImageSize
import com.github.skydoves.crayfish.decode.createRegionDecoder
import com.github.skydoves.crayfish.geometry.FloatRect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Cropping an image that is already decoded, and getting one back.
 *
 * The two ends a Compose app actually has. Whatever put a picture on screen produced a `Painter` or
 * an `ImageBitmap`, and wherever the crop is going will take one too, so a cropper that speaks only
 * in file paths and byte arrays makes the caller encode and decode around it for no reason.
 */
@OptIn(ExperimentalTestApi::class)
class ImageSourceAndOutputTest {

  /** Left half red, right half blue, so a region is identifiable by what colour came back. */
  private fun drawnFixture(width: Int = 40, height: Int = 30): ImageBitmap {
    val bitmap = ImageBitmap(width, height)
    val canvas = androidx.compose.ui.graphics.Canvas(bitmap)
    val scope = androidx.compose.ui.graphics.drawscope.CanvasDrawScope()
    scope.draw(
      androidx.compose.ui.unit.Density(1f),
      androidx.compose.ui.unit.LayoutDirection.Ltr,
      canvas,
      Size(width.toFloat(), height.toFloat()),
    ) {
      drawRect(Color.Red, size = Size(width / 2f, height.toFloat()))
      drawRect(
        Color.Blue,
        topLeft = androidx.compose.ui.geometry.Offset(width / 2f, 0f),
        size = Size(width / 2f, height.toFloat()),
      )
    }
    return bitmap
  }

  // -------------------------------------------------------------------------------------------
  // Input
  // -------------------------------------------------------------------------------------------

  @Test
  fun anImageSourceOpensWithoutTouchingAPlatformDecoder() = runTest {
    val bitmap = drawnFixture()
    val decoder = assertNotNull(createRegionDecoder(CropSource.Image(bitmap, "in-memory")))

    decoder.use {
      assertEquals(ImageSize(40, 30), it.imageSize)
      assertEquals(ImageFormat.RAW, it.format)
    }
  }

  @Test
  fun aRegionOfAnImageSourceIsThePixelsThatWereThere() = runTest {
    val decoder = assertNotNull(createRegionDecoder(CropSource.Image(drawnFixture(), "k")))

    decoder.use {
      // The right half, which the fixture painted blue.
      val decoded = assertNotNull(it.decodeRegion(ImageRegion(20, 0, 40, 30), sampleSize = 1))
      decoded.use { region ->
        assertEquals(20, region.width)
        assertEquals(30, region.height)
        val map = assertNotNull(region.image.toImageBitmap()).toPixelMap()
        assertTrue(map[10, 15].blue > map[10, 15].red, "the right half of the fixture is not blue")
      }
    }
  }

  @Test
  fun aRegionOutsideTheImageIsNull() = runTest {
    val decoder = assertNotNull(createRegionDecoder(CropSource.Image(drawnFixture(), "k")))
    decoder.use { assertNull(it.decodeRegion(ImageRegion(100, 100, 120, 120), sampleSize = 1)) }
  }

  @Test
  fun subsamplingFollowsTheSameRoundingAsEveryOtherSource() = runTest {
    val decoder = assertNotNull(createRegionDecoder(CropSource.Image(drawnFixture(40, 30), "k")))

    decoder.use {
      val decoded =
        assertNotNull(it.decodeRegion(ImageRegion.of(ImageSize(40, 30)), sampleSize = 2))
      decoded.use { region ->
        assertEquals(20, region.width)
        assertEquals(15, region.height)
        assertEquals(2, region.sampleSize)
      }
      // Not a power of two: `RegionDecoder` documents rounding down, never up.
      val coarse = assertNotNull(it.decodeRegion(ImageRegion.of(ImageSize(40, 30)), sampleSize = 3))
      coarse.use { region -> assertEquals(2, region.sampleSize) }
    }
  }

  @Test
  fun aPainterBecomesASourceWithItsOwnPixels() = runComposeUiTest {
    var source: CropSource? = null
    setContent {
      source = rememberCropSource(SolidPainter(Color.Red, Size(24f, 16f)), cacheKey = "painter")
    }
    waitForIdle()

    val image = assertIs<CropSource.Image>(assertNotNull(source))
    assertEquals(24, image.bitmap.width)
    assertEquals(16, image.bitmap.height)
    assertEquals("painter", image.cacheKey)
    val map = image.bitmap.toPixelMap()
    assertTrue(
      map[12, 8].red > map[12, 8].blue,
      "the painter's own colour did not reach the source",
    )
  }

  @Test
  fun aPainterWithNoIntrinsicSizeNeedsOneGiven() = runComposeUiTest {
    var withoutSize: CropSource? = null
    var withSize: CropSource? = null
    setContent {
      withoutSize = rememberCropSource(SolidPainter(Color.Red, Size.Unspecified), cacheKey = "a")
      withSize = rememberCropSource(
        painter = SolidPainter(Color.Red, Size.Unspecified),
        cacheKey = "b",
        size = IntSize(8, 8),
      )
    }
    waitForIdle()

    assertNull(withoutSize, "a painter with no size was rasterised at a size nobody chose")
    assertNotNull(withSize, "an explicit size was ignored")
  }

  // -------------------------------------------------------------------------------------------
  // Output
  // -------------------------------------------------------------------------------------------

  @Test
  fun cropToImageReturnsPixelsRatherThanBytes() = runComposeUiTest {
    val state = readyState()

    val result = runBlocking { state.cropToImage() }
    val success = assertIs<CropImage.Success>(result)

    assertEquals(success.image.width, success.size.width)
    assertEquals(success.image.height, success.size.height)
    assertTrue(success.size.width > 0 && success.size.height > 0)
    assertTrue(
      success.region.width > 0 && success.region.height > 0,
      "the reported source region is empty: ${success.region}",
    )
  }

  /**
   * The returned pixels are still readable after the crop has finished.
   *
   * The assertion that was missing. The first version of this file checked `width` and `height` and
   * stopped, and both are recorded on the wrapper rather than read from the bitmap, so the suite
   * stayed green while `cropToImage` handed back a buffer it had just recycled. It took running the
   * demo to find: "Canvas: trying to use a recycled bitmap" on the first frame that drew the result.
   *
   * `toPixelMap` reads the pixels, which is the same thing drawing does and the cheapest way to ask
   * whether they are still there.
   */
  @Test
  fun theReturnedImageSurvivesTheCropThatProducedIt() = runComposeUiTest {
    val state = readyState()
    val success = assertIs<CropImage.Success>(runBlocking { state.cropToImage() })

    val map = success.image.toPixelMap()
    assertEquals(success.size.width, map.width)
    assertEquals(success.size.height, map.height)
    // Reading a corner touches the backing buffer rather than the wrapper's own fields.
    assertTrue(map[0, 0].alpha > 0f, "the returned image reads back as empty")
    assertTrue(
      map[map.width - 1, map.height - 1].alpha > 0f,
      "the far corner of the returned image reads back as empty",
    )
  }

  /** A `Painter` is identity compared by every cache that holds one, so it has to be stable. */
  @Test
  fun theSuccessPainterIsTheSameInstanceEveryTime() = runComposeUiTest {
    val state = readyState()
    val success = assertIs<CropImage.Success>(runBlocking { state.cropToImage() })

    assertSame(success.painter, success.painter)
  }

  @Test
  fun cropToImageAndCropAgreeOnWhatWasSelected() = runComposeUiTest {
    val state = readyState()
    state.normalizedCropRect = FloatRect(0.1f, 0.1f, 0.6f, 0.6f)

    val image = assertIs<CropImage.Success>(runBlocking { state.cropToImage() })
    val bytes = assertIs<CropResult.Success>(runBlocking { state.crop() })

    assertEquals(bytes.size, image.size, "the two tails disagree about the crop's size")
    assertEquals(bytes.region, image.region, "the two tails disagree about the source region")
  }

  @Test
  fun cropToImageOnAFailedStateReportsTheSameReason() = runComposeUiTest {
    val state = RealCropState(CropSource.Bytes(ByteArray(0), "broken"), AspectRatio.Free)
    state.status = CropStatus.Failed(CropResult.Failure.Reason.SourceUnreadable)

    val failure = assertIs<CropImage.Failure>(runBlocking { state.cropToImage() })
    assertEquals(CropResult.Failure.Reason.SourceUnreadable, failure.reason)
  }

  private fun androidx.compose.ui.test.ComposeUiTest.readyState(): RealCropState {
    var held: CropState? = null
    setContent {
      val source = rememberCropSource(drawnFixture(400, 300), cacheKey = "output")
      val state = rememberCropState(source)
      held = state
      Cropper(state = state, modifier = Modifier.fillMaxSize())
    }
    waitUntil(timeoutMillis = 10_000) { held?.status is CropStatus.Ready }
    waitUntil(timeoutMillis = 10_000) { held?.viewportSize?.isEmpty == false }
    waitForIdle()
    return assertNotNull(held) as RealCropState
  }
}

/** A painter with a chosen intrinsic size, standing in for one a loader would hand over. */
private class SolidPainter(private val color: Color, override val intrinsicSize: Size) :
  Painter() {
  override fun DrawScope.onDraw() {
    drawRect(color)
  }
}
