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
package com.github.skydoves.crayfish.device

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipe
import com.github.skydoves.crayfish.decode.CropSource
import com.github.skydoves.crayfish.encode.EncodeOptions
import com.github.skydoves.crayfish.encode.EncodedFormat
import com.github.skydoves.crayfish.ui.CropGestures
import com.github.skydoves.crayfish.ui.CropResult
import com.github.skydoves.crayfish.ui.CropState
import com.github.skydoves.crayfish.ui.CropStatus
import com.github.skydoves.crayfish.ui.Cropper
import com.github.skydoves.crayfish.ui.rememberCropState
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The user's journey, on the hardware it ships to.
 *
 * Everything else that runs on a device here exercises the decoder and the encoder. This is the
 * only test that puts the actual composable on a real screen, sends real touch events through the
 * real view hierarchy, and asks for real bytes at the end, which is the difference between "the
 * pieces work" and "the thing works".
 */
@OptIn(ExperimentalTestApi::class)
class DeviceCropperUiTest {

  private fun fixture(name: String): File = File("/data/local/tmp/crayfish-fixtures", name).also {
    check(it.isFile && it.length() > 0) {
      "missing device fixture $it: run ./gradlew :crayfish:pushDeviceFixtures"
    }
  }

  private fun source() = CropSource.FilePath(fixture("sensor-108mp.jpg").absolutePath)

  @Test
  fun aCropperRendersA108MegapixelPhotoOnScreen() = runComposeUiTest {
    var state: CropState? = null

    setContent {
      val cropState = rememberCropState(source())
      state = cropState
      Cropper(state = cropState, modifier = Modifier.fillMaxSize().testTag(CROPPER))
    }

    onNodeWithTag(CROPPER).assertExists()
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { state?.status is CropStatus.Ready }

    val ready = assertNotNull(state?.status as? CropStatus.Ready)
    assertEquals(12_000, ready.imageSize.width)
    assertEquals(9_000, ready.imageSize.height)
    assertTrue(state!!.viewportSize.width > 0f, "the cropper was never laid out")
  }

  /**
   * A pinch on a real touchscreen has to reach the transform, not just the pointer input block.
   *
   * Mounted with the gestures enabled, because the shipped default fixes the image. That default is
   * what `DeviceFixedImageTest` covers.
   */
  @Test
  fun aPinchOnTheDeviceZoomsTheImage() = runComposeUiTest {
    var state: CropState? = null

    setContent {
      val cropState = rememberCropState(source())
      state = cropState
      Cropper(
        state = cropState,
        modifier = Modifier.fillMaxSize().testTag(CROPPER),
        gestures = CropGestures.Zoomable,
      )
    }
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { state?.status is CropStatus.Ready }
    val before = assertNotNull(state).transform.scale

    onNodeWithTag(CROPPER).performTouchInput {
      pinch(
        start0 = Offset(centerX - 40f, centerY + 30f),
        end0 = Offset(centerX - 260f, centerY + 30f),
        start1 = Offset(centerX + 40f, centerY + 30f),
        end1 = Offset(centerX + 260f, centerY + 30f),
      )
    }
    waitForIdle()

    assertTrue(
      state!!.transform.scale > before,
      "a pinch on the device left the scale at $before",
    )
  }

  @Test
  fun aDragOnTheDeviceMovesTheCropRectangle() = runComposeUiTest {
    var state: CropState? = null

    setContent {
      val cropState = rememberCropState(source())
      state = cropState
      Cropper(state = cropState, modifier = Modifier.fillMaxSize().testTag(CROPPER))
    }
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { state?.status is CropStatus.Ready }
    val before = assertNotNull(state).cropRect

    onNodeWithTag(CROPPER).performTouchInput {
      swipe(start = center, end = Offset(center.x + 120f, center.y + 80f), durationMillis = 300)
    }
    waitForIdle()

    assertTrue(
      state!!.cropRect != before,
      "a drag on the device left the crop rectangle at $before",
    )
  }

  /**
   * The whole journey: render, gesture, crop, bytes.
   *
   * This is the assertion that would have caught every integration mistake the unit tests could
   * not: a state that never reaches Ready, a gesture that never reaches the transform, a pipeline
   * that reads a rectangle the UI never wrote.
   */
  @Test
  fun renderGestureAndCropProducesRealBytesOnDevice() = runComposeUiTest {
    var state: CropState? = null
    var laidOut by mutableStateOf(false)

    setContent {
      val cropState = rememberCropState(source())
      state = cropState
      laidOut = cropState.viewportSize.width > 0f
      Cropper(state = cropState, modifier = Modifier.fillMaxSize().testTag(CROPPER))
    }
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { state?.status is CropStatus.Ready && laidOut }

    onNodeWithTag(CROPPER).performTouchInput {
      pinch(
        start0 = Offset(centerX - 40f, centerY),
        end0 = Offset(centerX - 200f, centerY),
        start1 = Offset(centerX + 40f, centerY),
        end1 = Offset(centerX + 200f, centerY),
      )
    }
    waitForIdle()

    val result = runBlocking {
      assertNotNull(state).crop(EncodeOptions(EncodedFormat.JPEG, lossyQuality = 85))
    }

    val success = assertNotNull(
      result as? CropResult.Success,
      "the crop failed on device: $result",
    )
    assertTrue(success.bytes.size > 2048, "a real crop should not be ${success.bytes.size} bytes")
    assertEquals(0xFF.toByte(), success.bytes[0])
    assertEquals(0xD8.toByte(), success.bytes[1])
    assertTrue(success.size.width > 0 && success.size.height > 0, "empty output ${success.size}")
    assertTrue(
      success.region.width in 1..12_000 && success.region.height in 1..9_000,
      "the region is outside the source: ${success.region}",
    )
  }

  private companion object {
    const val CROPPER = "cropper"
    const val TIMEOUT_MILLIS = 20_000L
  }
}
