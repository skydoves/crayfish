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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.github.skydoves.crayfish.decode.CropSource
import com.github.skydoves.crayfish.decode.TestImages
import com.github.skydoves.crayfish.encode.EncodeOptions
import com.github.skydoves.crayfish.encode.EncodedFormat
import com.github.skydoves.crayfish.geometry.FloatRect
import com.github.skydoves.crayfish.geometry.FloatSize
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one-line API, which is the only thing most callers will ever touch.
 *
 * It had no test at all until an API-surface audit pointed at it. The layer with the fewest lines
 * is not the layer with the least risk, because it is the one every caller runs through.
 */
@OptIn(ExperimentalTestApi::class)
class ImageCropperTest {

  private fun source() = TestImages.source(TestImages.pngBytes())

  @Test
  fun hasNothingPendingBeforeAnyoneAsksForACrop() = runComposeUiTest {
    var cropper: ImageCropper? = null
    setContent { cropper = rememberImageCropper() }

    assertNull(assertNotNull(cropper).pending)
  }

  @Test
  fun theDialogShowsNothingUntilACropIsRequested() = runComposeUiTest {
    setContent {
      val cropper = rememberImageCropper()
      ImageCropperDialog(cropper)
    }

    onNodeWithTag(CONFIRM).assertDoesNotExist()
  }

  @Test
  fun confirmingTheDialogCompletesTheSuspendingCall() = runComposeUiTest {
    var result: CropResult? = null

    setContent {
      val cropper = rememberImageCropper()
      val scope = rememberCoroutineScope()
      Row(
        Modifier.testTag(START).clickable {
          scope.launch {
            result = cropper.crop(source(), EncodeOptions(EncodedFormat.PNG))
          }
        },
      ) {
        BasicText("start")
      }
      ImageCropperDialog(cropper, controls = { confirm, cancel -> TestControls(confirm, cancel) })
    }

    onNodeWithTag(START).performClick()
    waitUntil(timeoutMillis = TIMEOUT) {
      onAllNodesWithTag(CONFIRM).fetchSemanticsNodes().isNotEmpty()
    }
    onNodeWithTag(CONFIRM).assertIsDisplayed()

    onNodeWithTag(CONFIRM).performClick()
    waitUntil(timeoutMillis = TIMEOUT) { result != null }

    val success = assertIs<CropResult.Success>(assertNotNull(result), "crop returned $result")
    assertTrue(success.bytes.size > 16, "the confirmed crop produced ${success.bytes.size} bytes")
  }

  @Test
  fun cancellingTheDialogResolvesTheCallAsCancelled() = runComposeUiTest {
    var result: CropResult? = null

    setContent {
      val cropper = rememberImageCropper()
      val scope = rememberCoroutineScope()
      Row(
        Modifier.testTag(START).clickable {
          scope.launch { result = cropper.crop(source()) }
        },
      ) {
        BasicText("start")
      }
      ImageCropperDialog(cropper, controls = { confirm, cancel -> TestControls(confirm, cancel) })
    }

    onNodeWithTag(START).performClick()
    waitUntil(timeoutMillis = TIMEOUT) {
      onAllNodesWithTag(CANCEL).fetchSemanticsNodes().isNotEmpty()
    }
    onNodeWithTag(CANCEL).performClick()
    waitUntil(timeoutMillis = TIMEOUT) { result != null }

    assertEquals(CropResult.Cancelled, result)
  }

  /** The default chrome has to exist and be clickable, or the one-liner is not one. */
  @Test
  fun theDefaultControlsRenderWithoutACallerSupplyingAny() = runComposeUiTest {
    var confirmed = false
    setContent {
      DefaultCropControls(confirm = { confirmed = true }, cancel = {})
    }

    onNodeWithText("Crop").performClick()
    assertTrue(confirmed, "the default confirm control did nothing")
  }

  @androidx.compose.runtime.Composable
  private fun TestControls(confirm: () -> Unit, cancel: () -> Unit) {
    Row {
      BasicText("ok", Modifier.testTag(CONFIRM).clickable(onClick = confirm))
      BasicText("no", Modifier.testTag(CANCEL).clickable(onClick = cancel))
    }
  }

  private companion object {
    const val START = "start-crop"
    const val CONFIRM = "confirm-crop"
    const val CANCEL = "cancel-crop"
    const val TIMEOUT = 10_000L
  }
}
