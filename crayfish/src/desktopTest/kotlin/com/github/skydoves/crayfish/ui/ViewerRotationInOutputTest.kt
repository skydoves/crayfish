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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.skydoves.crayfish.decode.TestImages
import com.github.skydoves.crayfish.encode.EncodeOptions
import com.github.skydoves.crayfish.encode.EncodedFormat
import com.github.skydoves.crayfish.geometry.FloatRect
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Turning the image in the viewer turns the bytes that come out of it.
 *
 * It used not to. `toImageRegion` inverts the whole transform when it chooses *which* pixels to
 * decode, so the selection was always right, but nothing turned them afterwards. Measured before
 * the fix: a 52x38 crop rotated 90 degrees came back 36x48, which is neither the original nor its
 * transpose. It was a different region of the photo, un turned.
 *
 * The fixture's four quadrants are four different colours, so a quarter turn is visible in where
 * each lands and cannot be satisfied by anything that merely resizes.
 */
@OptIn(ExperimentalTestApi::class)
class ViewerRotationInOutputTest {

  @Test
  fun aQuarterTurnInTheViewerComesOutTurned() = runComposeUiTest {
    val state = readyState()
    selectTheWholeImage(state)
    val before = cropPixels(state)
    assertNear(TestImages.TOP_LEFT, before.quadrant(0, 0), "the fixture's own top-left")

    state.rotateBy(90f)
    waitForIdle()
    selectTheWholeImage(state)
    val after = cropPixels(state)

    // Turning 90 degrees clockwise sends the original top-left to the top-right.
    assertNear(TestImages.BOTTOM_LEFT, after.quadrant(0, 0), "top-left after a quarter turn")
    assertNear(TestImages.TOP_LEFT, after.quadrant(1, 0), "top-right after a quarter turn")
    assertNear(TestImages.BOTTOM_RIGHT, after.quadrant(0, 1), "bottom-left after a quarter turn")
    assertNear(TestImages.TOP_RIGHT, after.quadrant(1, 1), "bottom-right after a quarter turn")
  }

  @Test
  fun aHalfTurnComesOutTurned() = runComposeUiTest {
    val state = readyState()
    state.rotateBy(180f)
    waitForIdle()
    selectTheWholeImage(state)

    val out = cropPixels(state)

    assertNear(TestImages.BOTTOM_RIGHT, out.quadrant(0, 0), "top-left after a half turn")
    assertNear(TestImages.TOP_LEFT, out.quadrant(1, 1), "bottom-right after a half turn")
  }

  @Test
  fun aHorizontalFlipComesOutMirrored() = runComposeUiTest {
    val state = readyState()
    state.toggleFlipHorizontal()
    waitForIdle()
    selectTheWholeImage(state)

    val out = cropPixels(state)

    assertNear(TestImages.TOP_RIGHT, out.quadrant(0, 0), "top-left after a horizontal flip")
    assertNear(TestImages.TOP_LEFT, out.quadrant(1, 0), "top-right after a horizontal flip")
  }

  @Test
  fun aVerticalFlipComesOutMirrored() = runComposeUiTest {
    val state = readyState()
    state.toggleFlipVertical()
    waitForIdle()
    selectTheWholeImage(state)

    val out = cropPixels(state)

    assertNear(TestImages.BOTTOM_LEFT, out.quadrant(0, 0), "top-left after a vertical flip")
    assertNear(TestImages.TOP_LEFT, out.quadrant(0, 1), "bottom-left after a vertical flip")
  }

  /** The control: with no rotation and no flip, nothing moves and nothing is resampled away. */
  @Test
  fun anUnturnedCropIsUnchanged() = runComposeUiTest {
    val state = readyState()
    selectTheWholeImage(state)

    val out = cropPixels(state)

    assertNear(TestImages.TOP_LEFT, out.quadrant(0, 0), "untouched top-left")
    assertNear(TestImages.TOP_RIGHT, out.quadrant(1, 0), "untouched top-right")
    assertNear(TestImages.BOTTOM_LEFT, out.quadrant(0, 1), "untouched bottom-left")
    assertNear(TestImages.BOTTOM_RIGHT, out.quadrant(1, 1), "untouched bottom-right")
  }

  /**
   * A free angle produces the frame's contents, not the bounding box of the tilted frame.
   *
   * The output has to be the frame's own proportions. Before the fix it was the axis aligned
   * bounding box, which at 45 degrees is roughly 1.4 times larger on each axis and carries wedges
   * of whatever lies outside the frame.
   */
  @Test
  fun aFreeAngleProducesTheFramesOwnShape() = runComposeUiTest {
    val state = readyState()
    selectTheWholeImage(state)
    val square = FloatRect(
      left = state.cropRect.left,
      top = state.cropRect.top,
      right = state.cropRect.left + 200f,
      bottom = state.cropRect.top + 200f,
    )
    (state as RealCropState).normalizedCropRect = FloatRect(
      square.left / state.viewportSize.width,
      square.top / state.viewportSize.height,
      square.right / state.viewportSize.width,
      square.bottom / state.viewportSize.height,
    )
    state.rotateBy(37f)
    waitForIdle()

    val out = cropPixels(state)

    val ratio = out.width.toFloat() / out.height
    assertTrue(
      abs(ratio - 1f) < 0.05f,
      "the frame is square and the output is ${out.width}x${out.height}, so this is the tilted " +
        "frame's bounding box rather than its contents",
    )
  }

  // -----------------------------------------------------------------------------------------
  // Harness
  // -----------------------------------------------------------------------------------------

  private fun ComposeUiTest.readyState(): RealCropState {
    var state: CropState? = null
    setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        Box(Modifier.size(900.dp, 700.dp)) {
          val cropState = rememberCropState(
            TestImages.source(TestImages.pngBytes(), key = "viewer-rotation"),
          )
          state = cropState
          Cropper(state = cropState, modifier = Modifier.fillMaxSize())
        }
      }
    }
    waitUntil(timeoutMillis = 10_000) { state?.status is CropStatus.Ready }
    waitUntil(timeoutMillis = 10_000) { state?.viewportSize?.isEmpty == false }
    waitForIdle()
    return assertNotNull(state) as RealCropState
  }

  /** Points the frame at the whole image as it is currently drawn. */
  private fun ComposeUiTest.selectTheWholeImage(state: RealCropState) {
    val bounds = state.transform.mapRect(
      state.coordinateSpace.contentBounds,
      state.coordinateSpace.pivot,
    )
    val viewport = state.viewportSize
    state.normalizedCropRect = FloatRect(
      left = bounds.left / viewport.width,
      top = bounds.top / viewport.height,
      right = bounds.right / viewport.width,
      bottom = bounds.bottom / viewport.height,
    )
    waitForIdle()
  }

  private fun cropPixels(state: RealCropState): BufferedImage {
    val result = runBlocking { state.crop(EncodeOptions(EncodedFormat.PNG)) }
    val success = assertIs<CropResult.Success>(result, "crop failed: $result")
    return assertNotNull(ImageIO.read(ByteArrayInputStream(success.bytes)))
  }

  private fun BufferedImage.quadrant(column: Int, row: Int): Int =
    getRGB(width / 4 + column * width / 2, height / 4 + row * height / 2)

  private fun assertNear(expected: Int, actual: Int, message: String) {
    val off = listOf(16, 8, 0).any { shift ->
      abs(((expected shr shift) and 0xFF) - ((actual shr shift) and 0xFF)) > 40
    }
    assertTrue(
      !off,
      "$message: expected ~${expected.toUInt().toString(
        16,
      )} but was ${actual.toUInt().toString(16)}",
    )
  }
}
