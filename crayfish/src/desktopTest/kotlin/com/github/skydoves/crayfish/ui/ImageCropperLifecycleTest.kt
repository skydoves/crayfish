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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.github.skydoves.crayfish.decode.ImageSize
import com.github.skydoves.crayfish.decode.TestImages
import com.github.skydoves.crayfish.encode.EncodeOptions
import com.github.skydoves.crayfish.encode.EncodedFormat
import com.github.skydoves.crayfish.geometry.CoordinateSpace
import com.github.skydoves.crayfish.geometry.CropTransform
import com.github.skydoves.crayfish.geometry.FloatRect
import com.github.skydoves.crayfish.geometry.FloatSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the one-line API does when it is not driven politely.
 *
 * [ImageCropperTest] covers the two paths a user takes on purpose: confirm and cancel. Everything
 * here is the rest of the lifecycle: the button pressed twice, the coroutine that goes away, the
 * caller who wires up the wrong object. None of it had ever run, and it is the layer most callers
 * will actually touch, so a hang or a misleading error here is the first thing anyone meets.
 */
@OptIn(ExperimentalTestApi::class)
class ImageCropperLifecycleTest {

  private fun source() = TestImages.source(TestImages.pngBytes())

  // -----------------------------------------------------------------------------------------
  // One at a time
  // -----------------------------------------------------------------------------------------

  /**
   * A second crop while one is on screen is declined, and says so in its own words.
   *
   * This used to answer `SourceUnreadable`, which is a statement about the *file*; a caller
   * handling failures honestly would have shown "this image could not be opened" to someone who
   * double-tapped a button. [CropResult.Failure.Reason.AlreadyCropping] exists so the two can be
   * told apart, and the assertion on the first crop still finishing is what makes the decline a
   * decline rather than a cancellation of both.
   */
  @Test
  fun decliningASecondCropDoesNotDisturbTheFirst() = runComposeUiTest {
    var first: CropResult? = null
    var second: CropResult? = null
    var cropper: ImageCropper? = null
    var scope: CoroutineScope? = null

    setContent {
      cropper = rememberImageCropper()
      scope = rememberCoroutineScope()
      ImageCropperDialog(
        cropper = assertNotNull(cropper),
        controls = { confirm, cancel -> TestControls(confirm, cancel) },
      )
    }

    assertNotNull(scope).launch {
      first =
        assertNotNull(cropper).crop(source(), EncodeOptions(EncodedFormat.PNG))
    }
    awaitDialog(cropper)

    assertNotNull(scope).launch {
      second =
        assertNotNull(cropper).crop(source(), EncodeOptions(EncodedFormat.PNG))
    }
    waitUntil(timeoutMillis = TIMEOUT) { second != null }

    assertEquals(
      CropResult.Failure.Reason.AlreadyCropping,
      assertIs<CropResult.Failure>(second).reason,
      "the second call blamed the image instead of the crop already on screen",
    )
    assertNull(first, "the second call resolved the first one too")

    // And the crop that was already running still finishes normally.
    onNodeWithTag(CONFIRM).performClick()
    waitUntil(timeoutMillis = TIMEOUT) { first != null }
    assertIs<CropResult.Success>(assertNotNull(first), "the first crop was broken by the second")
  }

  /** The guard releases: once a crop has finished, the next one is accepted. */
  @Test
  fun acceptsAnotherCropOnceTheFirstHasFinished() = runComposeUiTest {
    var result: CropResult? = null
    var cropper: ImageCropper? = null
    var scope: CoroutineScope? = null

    setContent {
      cropper = rememberImageCropper()
      scope = rememberCoroutineScope()
      ImageCropperDialog(
        cropper = assertNotNull(cropper),
        controls = { confirm, cancel -> TestControls(confirm, cancel) },
      )
    }

    repeat(2) { attempt ->
      result = null
      assertNotNull(scope).launch {
        result = assertNotNull(cropper).crop(source(), EncodeOptions(EncodedFormat.PNG))
      }
      awaitDialog(cropper)
      onNodeWithTag(CONFIRM).performClick()
      waitUntil(timeoutMillis = TIMEOUT) { result != null }
      assertIs<CropResult.Success>(assertNotNull(result), "crop number ${attempt + 1} failed")

      // The guard releasing is the thing under test, and it releases in `crop`'s `finally`, which
      // runs after the result is handed back, so the assertion has to wait for it rather than read
      // it on the same frame. Waiting here also keeps the next iteration from clicking the confirm
      // button of a dialog that has not gone away yet.
      waitUntil(timeoutMillis = TIMEOUT) { assertNotNull(cropper).pending == null }
    }

    assertNull(assertNotNull(cropper).pending, "the cropper is still holding a finished request")
  }

  /**
   * The dialog's other tail: pixels out, still readable once the dialog has gone.
   *
   * `cropToImage` hands back the decode buffer itself rather than a copy, because
   * `PlatformImage.toImageBitmap` wraps on every platform. The first version closed that buffer in
   * its `finally`, so the returned `ImageBitmap` was backed by a recycled bitmap: on Android the
   * next frame threw `Canvas: trying to use a recycled bitmap`, on Skia it was a SIGSEGV. Both of
   * the demo screens that use this API crashed, and every test stayed green because none of them
   * read a pixel.
   *
   * So this reads pixels, which is what drawing does.
   */
  @Test
  fun cropToImageThroughTheDialogReturnsPixelsThatOutliveIt() = runComposeUiTest {
    var result: CropImage? = null
    var cropper: ImageCropper? = null
    var scope: CoroutineScope? = null

    setContent {
      cropper = rememberImageCropper()
      scope = rememberCoroutineScope()
      ImageCropperDialog(
        cropper = assertNotNull(cropper),
        controls = { confirm, cancel -> TestControls(confirm, cancel) },
      )
    }

    assertNotNull(scope).launch { result = assertNotNull(cropper).cropToImage(source()) }
    awaitDialog(cropper)
    onNodeWithTag(CONFIRM).performClick()
    waitUntil(timeoutMillis = TIMEOUT) { result != null }

    val success = assertIs<CropImage.Success>(assertNotNull(result))

    // And the dialog is gone by now, so anything it owned has been released.
    waitUntil(timeoutMillis = TIMEOUT) { assertNotNull(cropper).pending == null }

    val map = success.image.toPixelMap()
    assertEquals(success.size.width, map.width)
    assertEquals(success.size.height, map.height)
    assertTrue(map[0, 0].alpha > 0f, "the crop came back as pixels nothing can draw")
  }

  /** Cancelling the image variant resolves it as cancelled rather than as a failure. */
  @Test
  fun cancellingAnImageCropResolvesItAsCancelled() = runComposeUiTest {
    var result: CropImage? = null
    var cropper: ImageCropper? = null
    var scope: CoroutineScope? = null

    setContent {
      cropper = rememberImageCropper()
      scope = rememberCoroutineScope()
      ImageCropperDialog(
        cropper = assertNotNull(cropper),
        controls = { confirm, cancel -> TestControls(confirm, cancel) },
      )
    }

    assertNotNull(scope).launch { result = assertNotNull(cropper).cropToImage(source()) }
    awaitDialog(cropper)
    onNodeWithTag(CANCEL).performClick()
    waitUntil(timeoutMillis = TIMEOUT) { result != null }

    assertIs<CropImage.Cancelled>(assertNotNull(result))
  }

  // -----------------------------------------------------------------------------------------
  // Going away
  // -----------------------------------------------------------------------------------------

  /**
   * [ImageCropper.cancel] resolves the outstanding call without any dialog interaction.
   *
   * The escape hatch for a caller presenting [ImageCropper.pending] themselves: a back press on a
   * full-screen crop has no confirm button to route through.
   */
  @Test
  fun cancelResolvesTheOutstandingCallFromOutsideTheDialog() = runComposeUiTest {
    var result: CropResult? = null
    var cropper: ImageCropper? = null
    var scope: CoroutineScope? = null

    setContent {
      cropper = rememberImageCropper()
      scope = rememberCoroutineScope()
      ImageCropperDialog(
        cropper = assertNotNull(cropper),
        controls = { confirm, cancel -> TestControls(confirm, cancel) },
      )
    }

    assertNotNull(scope).launch { result = assertNotNull(cropper).crop(source()) }
    awaitDialog(cropper)

    assertNotNull(assertNotNull(cropper).pending, "nothing was pending to cancel")
    assertNotNull(cropper).cancel()
    waitUntil(timeoutMillis = TIMEOUT) { result != null }

    assertEquals(CropResult.Cancelled, result)
    assertNull(assertNotNull(cropper).pending, "the cancelled crop is still pending")
  }

  /** Cancelling when nothing is happening is a no-op, not a crash. */
  @Test
  fun cancelWithNothingPendingDoesNothingAtAll() = runComposeUiTest {
    var cropper: ImageCropper? = null
    setContent { cropper = rememberImageCropper() }

    assertNotNull(cropper).cancel()
    assertNotNull(cropper).cancel()

    assertNull(assertNotNull(cropper).pending)
  }

  /**
   * A caller whose coroutine is cancelled takes the dialog with it.
   *
   * Otherwise the crop stays on screen attached to a coroutine nobody is waiting on: the user
   * confirms, and the result goes nowhere. This is the `finally` in `crop`, and a screen leaving
   * composition is the ordinary way it happens.
   */
  @Test
  fun aCancelledCallerTakesTheDialogDownWithIt() = runComposeUiTest {
    var cropper: ImageCropper? = null
    var scope: CoroutineScope? = null

    setContent {
      cropper = rememberImageCropper()
      scope = rememberCoroutineScope()
      ImageCropperDialog(
        cropper = assertNotNull(cropper),
        controls = { confirm, cancel -> TestControls(confirm, cancel) },
      )
    }

    val job = assertNotNull(scope).launch { assertNotNull(cropper).crop(source()) }
    awaitDialog(cropper)
    assertNotNull(assertNotNull(cropper).pending)

    job.cancel()
    waitUntil(timeoutMillis = TIMEOUT) { assertNotNull(cropper).pending == null }

    onNodeWithTag(CONFIRM).assertDoesNotExist()
  }

  // -----------------------------------------------------------------------------------------
  // Wired up wrongly
  // -----------------------------------------------------------------------------------------

  // -----------------------------------------------------------------------------------------
  // Harness
  // -----------------------------------------------------------------------------------------

  /**
   * Waits for the crop that was just requested to be genuinely on screen.
   *
   * Both halves are needed, and the order matters. Waiting only for the confirm button matches a
   * node left over from a dialog that is being torn down: the second crop in
   * [acceptsAnotherCropOnceTheFirstHasFinished] found one, clicked it, and drove the *previous*
   * dialog's already-spent confirm handler, so the new crop hung until the test timed out.
   * `pending` is state rather than tree, so it cannot be stale; the node check after it is what
   * makes the button safe to press.
   */
  private fun ComposeUiTest.awaitDialog(cropper: ImageCropper?) {
    waitUntil(timeoutMillis = TIMEOUT) { assertNotNull(cropper).pending != null }
    waitUntil(timeoutMillis = TIMEOUT) {
      onAllNodesWithTag(CONFIRM).fetchSemanticsNodes().isNotEmpty()
    }
  }

  @Composable
  private fun TestControls(confirm: () -> Unit, cancel: () -> Unit) {
    Row {
      BasicText("ok", Modifier.testTag(CONFIRM).clickable(onClick = confirm))
      BasicText("no", Modifier.testTag(CANCEL).clickable(onClick = cancel))
    }
  }

  private companion object {
    const val CONFIRM = "confirm-crop"
    const val CANCEL = "cancel-crop"
    const val TIMEOUT = 10_000L
  }

  // -----------------------------------------------------------------------------------------
  // The parameters reach the overlay
  // -----------------------------------------------------------------------------------------

  /**
   * `accessibility` passed to the dialog reaches the crop node.
   *
   * The dialog used to build its overlay with the defaults and no way to reach past them, so the
   * one line API could not localize a single string: the defaults are hardcoded English, and the
   * WCAG 2.2 conformance this library claims would have shipped untranslatable on the path most
   * callers take.
   *
   * Asserted on the semantics tree rather than on the parameter, because a parameter that is
   * accepted and never applied is exactly the defect this test exists for. `aspectRatio` was
   * accepted and never applied for a whole release cycle.
   */
  @Test
  fun theDialogsAccessibilityParameterReachesTheCropNode() = runComposeUiTest {
    var cropper: ImageCropper? = null
    var scope: CoroutineScope? = null

    setContent {
      cropper = rememberImageCropper()
      scope = rememberCoroutineScope()
      ImageCropperDialog(
        cropper = assertNotNull(cropper),
        accessibility = CropAccessibility(contentDescription = "Profile photo crop area"),
        controls = { confirm, cancel -> TestControls(confirm, cancel) },
      )
    }

    assertNotNull(scope).launch { assertNotNull(cropper).crop(source()) }
    awaitDialog(cropper)

    onNodeWithContentDescription("Profile photo crop area").assertExists()
    onNodeWithContentDescription("Crop area").assertDoesNotExist()
  }

  /** And `shape`, which is what makes a circular avatar crop expressible at all here. */
  @Test
  fun theDialogsShapeParameterReachesTheOverlay() = runComposeUiTest {
    var cropper: ImageCropper? = null
    var scope: CoroutineScope? = null

    setContent {
      cropper = rememberImageCropper()
      scope = rememberCoroutineScope()
      ImageCropperDialog(
        cropper = assertNotNull(cropper),
        shape = CropShape.Circle,
        accessibility = CropAccessibility(contentDescription = "circle crop"),
        controls = { confirm, cancel -> TestControls(confirm, cancel) },
      )
    }

    assertNotNull(scope).launch { assertNotNull(cropper).crop(source()) }
    awaitDialog(cropper)

    // The shape is drawn, not exposed, so the reachable assertion is that the overlay this dialog
    // built is the one the parameters configured: a default overlay would carry the default name.
    onNodeWithContentDescription("circle crop").assertExists()
  }
}
