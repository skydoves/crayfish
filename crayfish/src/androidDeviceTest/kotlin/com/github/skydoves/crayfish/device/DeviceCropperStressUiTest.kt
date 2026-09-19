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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.StateRestorationTester
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipe
import com.github.skydoves.crayfish.decode.CropSource
import com.github.skydoves.crayfish.decode.ImageSize
import com.github.skydoves.crayfish.encode.EncodeOptions
import com.github.skydoves.crayfish.encode.EncodedFormat
import com.github.skydoves.crayfish.ui.CropImage
import com.github.skydoves.crayfish.ui.CropResult
import com.github.skydoves.crayfish.ui.CropState
import com.github.skydoves.crayfish.ui.CropStatus
import com.github.skydoves.crayfish.ui.Cropper
import com.github.skydoves.crayfish.ui.rememberCropState
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The composable under load, on the hardware.
 *
 * Everything in [DeviceStressTest] talks to the decoder directly. That leaves out the part with the
 * most moving pieces: the tile store, the base layer job, and the state that sits between a user's
 * finger and a decode. Every defect that reached a user in this library so far lived there, and
 * every one of them needed something to be in flight when something else happened.
 */
@OptIn(ExperimentalTestApi::class)
class DeviceCropperStressUiTest {

  private fun fixture(name: String) = File("/data/local/tmp/crayfish-fixtures", name).also {
    check(it.isFile && it.length() > 0) {
      "missing device fixture $it: run ./gradlew :crayfish:pushDeviceFixtures"
    }
  }

  private fun source(name: String) = CropSource.FilePath(fixture(name).absolutePath)

  /**
   * Five sources in a row, none of them given time to finish opening.
   *
   * This is the gallery user who taps through photos, and it is the path that once left the cropper
   * permanently blank: a cancelled base layer job kept its slot, so the next source had nowhere to
   * land. What matters is not that the intermediate ones rendered, it is that the last one did.
   */
  @Test
  fun switchingSourceRepeatedlyLeavesTheLastPhotoReadyAndCroppable() = runComposeUiTest {
    val names = listOf(
      "sensor-108mp.jpg",
      "panorama-1x14.jpg",
      "tall-2x28.jpg",
      "sensor-48mp.jpg",
      "square-5000.jpg",
    )
    var index by mutableStateOf(0)
    var state: CropState? = null

    setContent {
      val cropState = rememberCropState(source(names[index]))
      state = cropState
      Cropper(state = cropState, modifier = Modifier.fillMaxSize().testTag(CROPPER))
    }

    onNodeWithTag(CROPPER).assertExists()
    // Deliberately without waiting for Ready: the point is to interrupt an open in progress.
    for (next in 1 until names.size) {
      index = next
      waitForIdle()
    }

    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { state?.status is CropStatus.Ready }
    val ready = assertNotNull(state?.status as? CropStatus.Ready)
    assertEquals(
      ImageSize(5_000, 5_000),
      ready.imageSize,
      "after switching through ${names.size} sources the cropper settled on the wrong one",
    )

    val result = runBlocking { state!!.crop(EncodeOptions(EncodedFormat.JPEG)) }
    val success = assertNotNull(
      result as? CropResult.Success,
      "the cropper could not crop after the switches: $result",
    )
    assertTrue(success.bytes.size > 1024, "the crop produced only ${success.bytes.size} bytes")
  }

  /**
   * Dragging the frame while the photo is still arriving.
   *
   * The gesture writes crop state from the pointer thread while the tile store publishes decoded
   * tiles from an IO worker, which is the pair that corrupted the tile cache before. No wait for
   * Ready before the first drag, on purpose.
   */
  @Test
  fun aStormOfDragsWhileTilesAreStillLoadingEndsWithACroppableFrame() = runComposeUiTest {
    var state: CropState? = null

    setContent {
      val cropState = rememberCropState(source("sensor-108mp.jpg"))
      state = cropState
      Cropper(state = cropState, modifier = Modifier.fillMaxSize().testTag(CROPPER))
    }

    onNodeWithTag(CROPPER).assertExists()
    repeat(DRAG_ROUNDS) { round ->
      onNodeWithTag(CROPPER).performTouchInput {
        val drift = (round % 4) * 20f
        swipe(
          start = androidx.compose.ui.geometry.Offset(centerX, centerY),
          end = androidx.compose.ui.geometry.Offset(left + 40f + drift, top + 40f + drift),
          durationMillis = 40,
        )
      }
      waitForIdle()
    }

    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { state?.status is CropStatus.Ready }
    val result = runBlocking { state!!.crop(EncodeOptions(EncodedFormat.JPEG)) }
    val success = assertNotNull(
      result as? CropResult.Success,
      "after $DRAG_ROUNDS drags the cropper could not crop: $result",
    )
    assertTrue(success.region.width > 0 && success.region.height > 0, "the crop region collapsed")
    println("[ui-stress] $DRAG_ROUNDS drags then a crop of ${success.size}")
  }

  /**
   * Cropping to an [androidx.compose.ui.graphics.ImageBitmap] over and over, reading each result.
   *
   * `cropToImage` hands out a bitmap the pipeline also holds, which is the shape that once returned
   * something already recycled. The read at the end of each round is the assertion; the loop is
   * what makes a one in ten race show up.
   */
  @Test
  fun repeatedCropsToImageAlwaysHandBackReadablePixels() = runComposeUiTest {
    var state: CropState? = null

    setContent {
      val cropState = rememberCropState(source("square-5000.jpg"))
      state = cropState
      Cropper(state = cropState, modifier = Modifier.fillMaxSize().testTag(CROPPER))
    }

    onNodeWithTag(CROPPER).assertExists()
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { state?.status is CropStatus.Ready }

    repeat(CROP_ROUNDS) { round ->
      val result = runBlocking { state!!.cropToImage() }
      val success = assertNotNull(
        result as? CropImage.Success,
        "round $round did not produce an image: $result",
      )
      val bitmap = success.image.asAndroidBitmap()
      assertTrue(
        bitmap.width > 0 && bitmap.height > 0,
        "round $round produced ${bitmap.width}x${bitmap.height}",
      )
      // Throws IllegalStateException if the pipeline handed back a bitmap it had already freed.
      bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
    }
    println("[ui-stress] $CROP_ROUNDS crops to image, every result readable")
  }

  /**
   * Save and restore, over and over, on a source large enough that opening it is not instant.
   *
   * One recreation is what [DeviceCropStateRestorationTest] covers. A storm is different: each
   * restore lands while the previous one's decode may still be running, which is the combination a
   * tablet produces when someone rotates it twice in a second, and Android 16 no longer lets an app
   * opt out of that with a portrait lock.
   *
   * Both the crop rectangle and the transform have to come back the same every round, not merely
   * come back.
   */
  @Test
  fun aStormOfActivityRecreationsKeepsTheSameCropRectangle() = runComposeUiTest {
    val restorer = StateRestorationTester(this)
    var state: CropState? = null

    restorer.setContent {
      val cropState = rememberCropState(source("sensor-108mp.jpg"))
      state = cropState
      Cropper(state = cropState, modifier = Modifier.fillMaxSize().testTag(CROPPER))
    }
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { state?.status is CropStatus.Ready }
    val opening = assertNotNull(state).cropRect

    // Both halves of what gets saved are moved, because they are restored by different fields of
    // the recipe and a storm that only moves one cannot see the other being dropped.
    onNodeWithTag(CROPPER).performTouchInput {
      swipe(
        start = androidx.compose.ui.geometry.Offset(centerX, centerY),
        end = androidx.compose.ui.geometry.Offset(centerX - 120f, centerY - 90f),
        durationMillis = 120,
      )
    }
    state!!.rotateBy(90f)
    waitForIdle()

    val expectedRect = state!!.cropRect
    val expectedRotation = state!!.transform.rotationDegrees
    // Preconditions, not assertions about the library. Without them a restore that returns nothing
    // still matches, because the default crop rectangle is what the fresh state would produce: an
    // earlier version of this test passed with the saver deliberately broken.
    assertTrue(
      expectedRotation != 0f,
      "the rotation never took effect, so the storm proves nothing",
    )
    assertTrue(
      abs(expectedRect.left - opening.left) > TOLERANCE ||
        abs(expectedRect.top - opening.top) > TOLERANCE,
      "the drag never moved the crop rectangle off $opening, so the storm proves nothing",
    )

    repeat(RECREATION_ROUNDS) { round ->
      restorer.emulateSaveAndRestore()
      waitUntil(timeoutMillis = TIMEOUT_MILLIS) { state?.status is CropStatus.Ready }

      val restored = assertNotNull(state)
      assertEquals(
        expectedRotation,
        restored.transform.rotationDegrees,
        "round $round lost the rotation",
      )
      val runtime = Runtime.getRuntime()
      val javaUsed = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
      val native = android.os.Debug.getNativeHeapAllocatedSize() / 1024 / 1024
      println("[ui-stress] recreation $round: java=${javaUsed}MB native=${native}MB")

      val rect = restored.cropRect
      assertTrue(
        abs(rect.left - expectedRect.left) < TOLERANCE &&
          abs(rect.top - expectedRect.top) < TOLERANCE &&
          abs(rect.right - expectedRect.right) < TOLERANCE &&
          abs(rect.bottom - expectedRect.bottom) < TOLERANCE,
        "round $round restored $rect instead of $expectedRect",
      )
    }

    val result = runBlocking { state!!.crop(EncodeOptions(EncodedFormat.JPEG)) }
    assertNotNull(
      result as? CropResult.Success,
      "after $RECREATION_ROUNDS recreations the cropper could not crop: $result",
    )
    println("[ui-stress] $RECREATION_ROUNDS recreations, crop rectangle held")
  }

  /**
   * A quarter turn on the largest fixture, cropped to bytes.
   *
   * Isolated from the recreation storm that first produced it, because a failure inside a loop of
   * eight restores reads as a leak and this is not one: the rotated path allocates its buffers on
   * the Java heap, and `DecodeBudget` only ever bounded the native decode.
   */
  @Test
  fun aRotatedCropOfA108MegapixelSourceStaysInsideTheHeap() = runComposeUiTest {
    var state: CropState? = null

    setContent {
      val cropState = rememberCropState(source("sensor-108mp.jpg"))
      state = cropState
      Cropper(state = cropState, modifier = Modifier.fillMaxSize().testTag(CROPPER))
    }
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { state?.status is CropStatus.Ready }

    assertNotNull(state).rotateBy(90f)
    waitForIdle()
    assertTrue(state!!.transform.rotationDegrees != 0f, "the rotation never took effect")

    val result = runBlocking { state!!.crop(EncodeOptions(EncodedFormat.JPEG)) }
    val success = assertNotNull(
      result as? CropResult.Success,
      "a rotated crop of a 108MP source did not succeed: $result",
    )
    println("[ui-stress] rotated 108MP crop: ${success.size}, ${success.bytes.size / 1024}KB")
  }

  private companion object {
    const val CROPPER = "cropper"
    const val TIMEOUT_MILLIS = 30_000L
    const val DRAG_ROUNDS = 12
    const val CROP_ROUNDS = 12
    const val RECREATION_ROUNDS = 8
    const val TOLERANCE = 0.5f
  }
}
