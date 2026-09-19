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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipe
import com.github.skydoves.crayfish.decode.CropSource
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The cropper composed and gestured on an Apple runtime.
 *
 * Everything else that runs here exercises the decoder. The gesture layer is proven on two Android
 * devices and in desktop compositions, but iOS and macOS deliver touch through their own runtime,
 * and until this existed nothing had ever put a pointer into one.
 */
@OptIn(ExperimentalTestApi::class)
class AppleCropperUiTest {

  private fun source() = CropSource.Bytes(ApplePngFixture.bytes(), cacheKey = "apple-ui-fixture")

  @Test
  fun rendersAndReachesReady() = runComposeUiTest {
    var state: CropState? = null
    setContent {
      val cropState = rememberCropState(source())
      state = cropState
      Cropper(state = cropState, modifier = Modifier.fillMaxSize().testTag(TAG))
    }

    onNodeWithTag(TAG).assertExists()
    waitUntil(timeoutMillis = TIMEOUT) { state?.status is CropStatus.Ready }
    assertTrue(assertNotNull(state).viewportSize.width > 0f, "the cropper was never laid out")
  }

  /**
   * Mounted with the gestures enabled, because the shipped default fixes the image.
   *
   * This asserted the old default and went red the day pinch stopped moving the photo, which is
   * behaviour the library dropped on purpose. It survived because nothing ran the Apple targets
   * between that change and a full sweep; the Android twin in `DeviceCropperUiTest` was updated at
   * the time and this one was not.
   */
  @Test
  fun aPinchZoomsOnAnAppleRuntime() = runComposeUiTest {
    var state: CropState? = null
    setContent {
      val cropState = rememberCropState(source())
      state = cropState
      Cropper(
        state = cropState,
        modifier = Modifier.fillMaxSize().testTag(TAG),
        gestures = CropGestures.Zoomable,
      )
    }
    waitUntil(timeoutMillis = TIMEOUT) { state?.status is CropStatus.Ready }
    val before = assertNotNull(state).transform.scale

    onNodeWithTag(TAG).performTouchInput {
      pinch(
        start0 = Offset(centerX - 30f, centerY + 20f),
        end0 = Offset(centerX - 180f, centerY + 20f),
        start1 = Offset(centerX + 30f, centerY + 20f),
        end1 = Offset(centerX + 180f, centerY + 20f),
      )
    }
    waitForIdle()

    assertTrue(state!!.transform.scale > before, "a pinch left the scale at $before")
  }

  @Test
  fun aDragMovesTheCropRectangleOnAnAppleRuntime() = runComposeUiTest {
    var state: CropState? = null
    setContent {
      val cropState = rememberCropState(source())
      state = cropState
      Cropper(state = cropState, modifier = Modifier.fillMaxSize().testTag(TAG))
    }
    waitUntil(timeoutMillis = TIMEOUT) { state?.status is CropStatus.Ready }
    val before = assertNotNull(state).cropRect

    onNodeWithTag(TAG).performTouchInput {
      swipe(start = center, end = Offset(center.x + 90f, center.y + 60f), durationMillis = 250)
    }
    waitForIdle()

    assertTrue(state!!.cropRect != before, "a drag left the crop rectangle at $before")
  }

  private companion object {
    const val TAG = "apple-cropper"
    const val TIMEOUT = 15_000L
  }
}
