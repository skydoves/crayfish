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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.runComposeUiTest
import com.github.skydoves.crayfish.decode.CropSource
import com.github.skydoves.crayfish.geometry.CropTransform
import com.github.skydoves.crayfish.ui.CropGestures
import com.github.skydoves.crayfish.ui.CropState
import com.github.skydoves.crayfish.ui.CropStatus
import com.github.skydoves.crayfish.ui.Cropper
import com.github.skydoves.crayfish.ui.rememberCropState
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The image holds still on a real phone.
 *
 * Reported from a Galaxy S23: pinching the photo and turning the fingers rotated it under the crop
 * frame, and nothing in the screen's UI had offered a rotation. Two earlier reports from the same
 * device were the other half of the same complaint, that the photo moved and rescaled while the
 * frame was being placed.
 *
 * Desktop coverage of this lives in `CropGestureSetTest`, which mounts the gesture modifier
 * directly. This runs the whole `Cropper` on the hardware the reports came from, against a real
 * file, through the platform's own touch pipeline, because the desktop harness injects synthetic
 * pointers into a bare Box and the claim being checked is about what a finger does to the shipped
 * composable.
 */
@OptIn(ExperimentalTestApi::class)
class DeviceFixedImageTest {

  private fun source(): CropSource {
    val file = File("/data/local/tmp/crayfish-fixtures", "uhd.png")
    check(file.isFile) {
      "missing device fixture $file: run ./gradlew :crayfish:pushDeviceFixtures"
    }
    return CropSource.FilePath(file.absolutePath)
  }

  @Test
  fun aTwoFingerTwistLeavesThePhotoExactlyWhereItOpened() = runComposeUiTest {
    val state = mountCropper(CropGestures.Default)
    val opened = state.transform

    onNodeWithTag(TAG).performTouchInput {
      val middle = Offset(centerX, centerY)
      val arm = 200f
      pinch(
        start0 = middle + Offset(-arm, 0f),
        end0 = middle + rotated(-arm, 0f, 60f),
        start1 = middle + Offset(arm, 0f),
        end1 = middle + rotated(arm, 0f, 60f),
      )
    }
    waitForIdle()

    assertEquals(
      opened,
      state.transform,
      "a twist moved the image: it opened at $opened and is now ${state.transform}",
    )
  }

  @Test
  fun aPinchLeavesThePhotoExactlyWhereItOpened() = runComposeUiTest {
    val state = mountCropper(CropGestures.Default)
    val opened = state.transform

    onNodeWithTag(TAG).performTouchInput {
      val middle = Offset(centerX, centerY)
      pinch(
        start0 = middle + Offset(-60f, 0f),
        end0 = middle + Offset(-300f, 0f),
        start1 = middle + Offset(60f, 0f),
        end1 = middle + Offset(300f, 0f),
      )
    }
    waitForIdle()

    assertEquals(
      opened,
      state.transform,
      "a pinch moved the image: it opened at $opened and is now ${state.transform}",
    )
  }

  /**
   * The positive control. Without it the two assertions above would also pass on a build whose
   * touch injection never reached the cropper at all.
   */
  @Test
  fun theSameTwistTurnsThePhotoWhenTheGesturesAreEnabled() = runComposeUiTest {
    val state = mountCropper(CropGestures.All)

    onNodeWithTag(TAG).performTouchInput {
      val middle = Offset(centerX, centerY)
      val arm = 200f
      pinch(
        start0 = middle + Offset(-arm, 0f),
        end0 = middle + rotated(-arm, 0f, 60f),
        start1 = middle + Offset(arm, 0f),
        end1 = middle + rotated(arm, 0f, 60f),
      )
    }
    waitForIdle()

    assertTrue(
      state.transform.rotationDegrees != 0f,
      "the twist reached nothing even with every gesture enabled, so the two tests above prove " +
        "nothing about the default",
    )
  }

  private fun androidx.compose.ui.test.ComposeUiTest.mountCropper(
    gestures: CropGestures,
  ): CropState {
    var held: CropState? = null
    setContent {
      val cropState = rememberCropState(source())
      held = cropState
      Cropper(
        state = cropState,
        modifier = Modifier.fillMaxSize().testTag(TAG),
        gestures = gestures,
      )
    }
    waitUntil(timeoutMillis = 20_000) { held?.status is CropStatus.Ready }
    waitUntil(timeoutMillis = 20_000) { held?.viewportSize?.isEmpty == false }
    waitForIdle()
    val state = assertNotNull(held)
    assertEquals(
      CropTransform.Identity,
      state.transform,
      "the cropper did not open fitted, so this test is measuring the wrong starting point",
    )
    return state
  }
}

private fun rotated(x: Float, y: Float, degrees: Float): Offset {
  val radians = degrees * PI.toFloat() / 180f
  return Offset(x * cos(radians) - y * sin(radians), x * sin(radians) + y * cos(radians))
}

private const val TAG = "cropper"
