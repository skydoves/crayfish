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
package com.github.skydoves.crayfish.activity

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.github.skydoves.crayfish.decode.ImageRegion
import com.github.skydoves.crayfish.decode.ImageSize
import com.github.skydoves.crayfish.encode.EncodedFormat
import com.github.skydoves.crayfish.ui.CropResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parts of the Activity route that only exist on a device: the marshalling, the manifest entry
 * and the FileProvider.
 *
 * Everything crossing the boundary is primitives, and primitives are exactly the kind of thing that
 * looks right and arrives wrong. So the request goes out and comes back, the result goes out and
 * comes back, and the file the caller is handed is one they can actually open.
 */
class CropImageIntentsTest {

  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  fun aRequestSurvivesTheIntentItTravelsIn() {
    val sent = CropImageRequest(
      source = Uri.parse("content://media/external/images/media/42"),
      aspectRatio = 16f / 9f,
      mask = CropMask.RoundedRectangle,
      cornerRadiusDp = 24f,
      format = EncodedFormat.PNG,
      quality = 55,
      gesturesEnabled = true,
    )

    val received = assertNotNull(Intent().putRequest(sent).readRequest())

    assertEquals(sent.source, received.source)
    assertEquals(16f / 9f, assertNotNull(received.aspectRatio), 1e-6f)
    assertEquals(CropMask.RoundedRectangle, received.mask)
    assertEquals(24f, received.cornerRadiusDp, 1e-6f)
    assertEquals(EncodedFormat.PNG, received.format)
    assertEquals(55, received.quality)
    assertTrue(received.gesturesEnabled)
  }

  /** A free ratio is the absence of one, not a zero. */
  @Test
  fun aFreeRatioArrivesAsNull() {
    val received = assertNotNull(
      Intent().putRequest(CropImageRequest(Uri.parse("content://x"))).readRequest(),
    )

    assertNull(received.aspectRatio)
    assertEquals(CropMask.Rectangle, received.mask)
    assertEquals(EncodedFormat.JPEG, received.format)
  }

  /** An Intent that did not come from the contract is refused rather than half read. */
  @Test
  fun anUnrelatedIntentIsNotARequest() {
    assertNull(Intent().readRequest())
    assertNull(Intent(Intent.ACTION_VIEW).readRequest())
  }

  @Test
  fun aSuccessSurvivesTheIntentItTravelsIn() {
    val intent = Intent().putSuccess(
      uri = Uri.parse("content://com.example.crayfish.fileprovider/crop.jpg"),
      size = ImageSize(1280, 720),
      region = ImageRegion(10, 20, 1290, 740),
    )

    val result = assertIs<CropImageResult.Success>(intent.readResult())

    assertEquals("content://com.example.crayfish.fileprovider/crop.jpg", result.uri.toString())
    assertEquals(ImageSize(1280, 720), result.size)
    assertEquals(ImageRegion(10, 20, 1290, 740), result.region)
    assertTrue(
      intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
      "the result does not carry read permission, so the caller cannot open it",
    )
  }

  @Test
  fun aFailureSurvivesTheIntentItTravelsIn() {
    val intent = Intent().putFailure(CropResult.Failure.Reason.SourceUnreadable)
    val result = assertIs<CropImageResult.Failure>(intent.readResult())

    assertEquals(CropResult.Failure.Reason.SourceUnreadable, result.reason)
  }

  @Test
  fun anEmptyResultIsCancelled() {
    assertIs<CropImageResult.Cancelled>(Intent().readResult())
    assertIs<CropImageResult.Cancelled>(
      CropImageContract().parseResult(Activity.RESULT_CANCELED, null),
    )
  }

  // -------------------------------------------------------------------------------------------
  // The file the caller is handed
  // -------------------------------------------------------------------------------------------

  /**
   * The FileProvider is declared, its authority matches what the code builds, and the Uri it
   * produces can be read back. Three things that fail independently and all look like a crash on
   * the confirm button.
   */
  @Test
  fun theResultFileIsWrittenAndReadableThroughTheProvider() = runBlocking {
    val bytes = ByteArray(2048) { (it % 251).toByte() }

    val uri = assertNotNull(
      context.writeResult(bytes, EncodedFormat.JPEG),
      "the crop could not be written, so no caller would ever get one",
    )
    assertEquals("content", uri.scheme, "the caller was handed something that is not a content Uri")
    assertTrue(
      uri.authority?.endsWith(".crayfish.fileprovider") == true,
      "the authority ${uri.authority} is not the one the manifest declares",
    )

    val readBack = CropImageResult.Success(uri, ImageSize(1, 1), ImageRegion(0, 0, 1, 1))
      .readBytes(context)
    assertTrue(bytes.contentEquals(assertNotNull(readBack)), "what came back is not what went in")
  }

  /** And an unreadable source is a null, not an exception, so the cropper can report it. */
  @Test
  fun aSourceThatCannotBeOpenedIsNull() = runBlocking {
    assertNull(context.readSourceBytes(Uri.parse("content://com.example.nothing/missing")))
  }

  @Test
  fun theContractBuildsAnIntentAimedAtTheCropper() {
    val intent = CropImageContract()
      .createIntent(context, CropImageRequest(Uri.parse("content://x")))

    assertEquals(
      "com.github.skydoves.crayfish.activity.CropImageActivity",
      intent.component?.className,
    )
  }
}
