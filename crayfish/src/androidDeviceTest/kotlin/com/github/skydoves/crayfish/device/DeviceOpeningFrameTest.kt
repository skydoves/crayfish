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

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.github.skydoves.crayfish.decode.CropSource
import com.github.skydoves.crayfish.encode.EncodeOptions
import com.github.skydoves.crayfish.encode.EncodedFormat
import com.github.skydoves.crayfish.geometry.FloatRect
import com.github.skydoves.crayfish.ui.CropResult
import com.github.skydoves.crayfish.ui.CropState
import com.github.skydoves.crayfish.ui.CropStatus
import com.github.skydoves.crayfish.ui.Cropper
import com.github.skydoves.crayfish.ui.RealCropState
import com.github.skydoves.crayfish.ui.rememberCropState
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the cropper opens on, measured on a real phone.
 *
 * Reported from a Galaxy S23 and reproduced there. The opening crop rectangle was 10% to 90% of the
 * **viewport**, and a 3840x2160 photo on a 1080x2340 screen fits to 607 pixels of height, so the
 * frame was three times taller than the photo. Coverage then resolved the only way it could:
 *
 * ```
 * contentBounds = y 866..1474     cropRect  = y 234..2106
 * transform     = scale 3.0816462 selection = 26% of the photo's width
 * ```
 *
 * The photo opened blown up and panned, every gesture snapped the scale back as coverage re-clamped
 * it, and pressing Crop returned a narrow vertical slice of a landscape photograph.
 *
 * This runs on device rather than only on the desktop because the desktop test window is 1024x768
 * and the shape of a real phone is the whole point: on a viewport whose aspect ratio matches the
 * image, the defect cannot appear.
 */
@OptIn(ExperimentalTestApi::class)
class DeviceOpeningFrameTest {

  private fun source(): CropSource {
    val file = File("/data/local/tmp/crayfish-fixtures", "uhd.png")
    check(file.isFile) {
      "missing device fixture $file: run ./gradlew :crayfish:pushDeviceFixtures"
    }
    return CropSource.FilePath(file.absolutePath)
  }

  @Test
  fun theCropperOpensOnThePhotoRatherThanZoomingToMeetTheFrame() = runComposeUiTest {
    var state: CropState? = null
    setContent {
      val cropState = rememberCropState(source())
      state = cropState
      Cropper(state = cropState, modifier = Modifier.fillMaxSize())
    }
    waitUntil(timeoutMillis = 20_000) { state?.status is CropStatus.Ready }
    waitUntil(timeoutMillis = 20_000) { state?.viewportSize?.isEmpty == false }
    waitForIdle()
    val cropState = assertNotNull(state)

    val bounds = cropState.coordinateSpace.contentBounds
    val crop = cropState.cropRect

    // The viewport has to be a different shape from the image, or this proves nothing.
    val viewportRatio = cropState.viewportSize.width / cropState.viewportSize.height
    val imageRatio = cropState.imageSize.width.toFloat() / cropState.imageSize.height
    assertTrue(
      kotlin.math.abs(viewportRatio - imageRatio) > 0.3f,
      "this device's viewport is the same shape as the fixture, so the defect cannot appear here",
    )

    assertTrue(
      cropState.transform.scale in 0.99f..1.01f,
      "the cropper opened at ${cropState.transform.scale}x: the frame $crop encloses letterbox " +
        "outside the image $bounds, and coverage is zooming the photo to meet it",
    )
    assertTrue(
      crop.top >= bounds.top - 1f && crop.bottom <= bounds.bottom + 1f,
      "the opening frame $crop is outside the photo $bounds",
    )

    val region = assertNotNull(cropState.coordinateSpace.toImageRegion(crop))
    val fraction = region.width.toFloat() / cropState.imageSize.width
    assertTrue(
      fraction > 0.7f,
      "pressing Crop now would return ${(fraction * 100).toInt()}% of the photo's width",
    )
  }

  /**
   * Turning the image turns the bytes, through the real Android decoder and encoder.
   *
   * The desktop suite proves the mapping; this proves it survives `BitmapRegionDecoder`,
   * `Bitmap.compress` and the device's own memory budget.
   *
   * The frame is re-pointed at the whole photo *after* the turn, and that is the whole subtlety.
   * Rotating turns the image underneath a frame that does not move, so a fixed frame keeps its
   * shape and so does the output: the first version of this test asserted that a quarter turn swaps
   * the output's axes and failed at 2160x1215, which was the correct answer to a different
   * question. Re-framing the turned photo is what makes the axes swap.
   */
  @Test
  fun aQuarterTurnReachesTheBytesOnDevice() = runComposeUiTest {
    var state: CropState? = null
    setContent {
      val cropState = rememberCropState(source())
      state = cropState
      Cropper(state = cropState, modifier = Modifier.fillMaxSize())
    }
    waitUntil(timeoutMillis = 20_000) { state?.status is CropStatus.Ready }
    waitUntil(timeoutMillis = 20_000) { state?.viewportSize?.isEmpty == false }
    waitForIdle()
    val cropState = assertNotNull(state) as RealCropState

    frameTheWholePhoto(cropState)
    waitForIdle()
    val before = assertIs<CropResult.Success>(
      runBlocking { cropState.crop(EncodeOptions(EncodedFormat.PNG)) },
    )
    assertTrue(
      before.size.width > before.size.height,
      "the photo is ${before.size}, so a quarter turn would not be visible in the axes",
    )

    cropState.rotateBy(90f)
    waitForIdle()
    frameTheWholePhoto(cropState)
    waitForIdle()
    val after = assertIs<CropResult.Success>(
      runBlocking { cropState.crop(EncodeOptions(EncodedFormat.PNG)) },
    )

    assertTrue(
      after.size.height > after.size.width,
      "the turned photo came out ${after.size}, still landscape, so the rotation did not reach " +
        "the bytes",
    )
    assertNotNull(
      BitmapFactory.decodeByteArray(after.bytes, 0, after.bytes.size),
      "the turned crop is not a decodable image",
    )
  }

  /** Points the frame at the whole photo as it is currently drawn, turn included. */
  private fun frameTheWholePhoto(state: RealCropState) {
    val drawn = state.transform.mapRect(
      state.coordinateSpace.contentBounds,
      state.coordinateSpace.pivot,
    )
    val viewport = state.viewportSize
    state.normalizedCropRect = FloatRect(
      left = drawn.left / viewport.width,
      top = drawn.top / viewport.height,
      right = drawn.right / viewport.width,
      bottom = drawn.bottom / viewport.height,
    )
  }
}
