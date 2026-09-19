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
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.StateRestorationTester
import androidx.compose.ui.test.runComposeUiTest
import com.github.skydoves.crayfish.decode.CropSource
import com.github.skydoves.crayfish.geometry.FloatRect
import com.github.skydoves.crayfish.ui.AspectRatio
import com.github.skydoves.crayfish.ui.CropState
import com.github.skydoves.crayfish.ui.CropStatus
import com.github.skydoves.crayfish.ui.Cropper
import com.github.skydoves.crayfish.ui.rememberCropState
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The crop surviving the activity being destroyed under the user, on the platform where that
 * happens.
 *
 * Android 16 ignores `screenOrientation` on any display 600dp or wider, so the portrait lock every
 * existing cropper relies on to keep its geometry simple is gone: a tablet rotates, the activity is
 * recreated, and an unsaved crop rectangle is a lost edit. Compose Multiplatform's desktop
 * `StateRestorationTester.emulateSaveAndRestore()` is an unimplemented stub, so this is the only
 * place the round trip can actually be driven.
 */
@OptIn(ExperimentalTestApi::class)
class DeviceCropStateRestorationTest {

  private fun source() = CropSource.FilePath(
    File("/data/local/tmp/crayfish-fixtures", "uhd.png").also {
      check(it.isFile) { "missing device fixture $it: run ./gradlew :crayfish:pushDeviceFixtures" }
    }.absolutePath,
  )

  @Test
  fun theCropSurvivesActivityRecreation() = runComposeUiTest {
    val restorer = StateRestorationTester(this)
    var state: CropState? = null

    restorer.setContent {
      val cropState = rememberCropState(source(), AspectRatio.Square)
      state = cropState
      Cropper(state = cropState, modifier = Modifier.fillMaxSize())
    }
    waitUntil(timeoutMillis = TIMEOUT) { state?.status is CropStatus.Ready }

    assertNotNull(state).rotateBy(90f)
    state!!.toggleFlipHorizontal()
    waitForIdle()

    val rotation = state!!.transform.rotationDegrees
    val flipped = state!!.transform.flipHorizontal
    val rect = state!!.cropRect
    assertTrue(rotation != 0f, "the rotation never took effect, so the test proves nothing")

    restorer.emulateSaveAndRestore()
    waitUntil(timeoutMillis = TIMEOUT) { state?.status is CropStatus.Ready }

    val restored = assertNotNull(state)
    assertEquals(rotation, restored.transform.rotationDegrees, "the rotation was lost")
    assertEquals(flipped, restored.transform.flipHorizontal, "the flip was lost")
    assertRectRestored(rect, restored.cropRect)
  }

  /** And the caller's aspect ratio wins over whatever was restored, because the caller owns it. */
  @Test
  fun theCallersAspectRatioSurvivesRecreationToo() = runComposeUiTest {
    val restorer = StateRestorationTester(this)
    var state: CropState? = null

    restorer.setContent {
      val cropState = rememberCropState(source(), AspectRatio.Widescreen16x9)
      state = cropState
      Cropper(state = cropState, modifier = Modifier.fillMaxSize())
    }
    waitUntil(timeoutMillis = TIMEOUT) { state?.status is CropStatus.Ready }

    restorer.emulateSaveAndRestore()
    waitUntil(timeoutMillis = TIMEOUT) { state?.status is CropStatus.Ready }

    assertEquals(AspectRatio.Widescreen16x9, assertNotNull(state).aspectRatio)
  }

  private companion object {
    const val TIMEOUT = 20_000L
  }

  /**
   * The crop rectangle came back, to within a fraction of a pixel.
   *
   * Exact equality is the wrong test here and produced a real false failure: the rectangle is saved
   * as fractions of a viewport and read back multiplied by one, so a 128.0 restores as a 128.00006.
   * That is the saver working, not failing. The tolerance is a hundredth of a pixel: far below
   * anything drawable, and far below any error that would mean the rectangle had actually moved.
   */
  private fun assertRectRestored(expected: FloatRect, actual: FloatRect) {
    listOf(
      "left" to (expected.left to actual.left),
      "top" to (expected.top to actual.top),
      "right" to (expected.right to actual.right),
      "bottom" to (expected.bottom to actual.bottom),
    ).forEach { (edge, pair) ->
      assertTrue(
        abs(pair.first - pair.second) < 0.01f,
        "the crop rectangle's $edge edge was lost: expected $expected but was $actual",
      )
    }
  }
}
