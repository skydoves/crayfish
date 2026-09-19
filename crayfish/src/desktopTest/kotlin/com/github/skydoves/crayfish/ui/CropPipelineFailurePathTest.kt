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

import com.github.skydoves.crayfish.decode.DecodeBudget
import com.github.skydoves.crayfish.decode.DecodedRegion
import com.github.skydoves.crayfish.decode.ImageFormat
import com.github.skydoves.crayfish.decode.ImageRegion
import com.github.skydoves.crayfish.decode.ImageSize
import com.github.skydoves.crayfish.decode.RegionDecoder
import com.github.skydoves.crayfish.decode.TestImages
import com.github.skydoves.crayfish.decode.platformImageOfArgbPixels
import com.github.skydoves.crayfish.encode.EncodeOptions
import com.github.skydoves.crayfish.encode.EncodedFormat
import com.github.skydoves.crayfish.exif.ImageOrientation
import com.github.skydoves.crayfish.geometry.FloatRect
import com.github.skydoves.crayfish.geometry.FloatSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * How [cropToBytes] behaves when the platform underneath it says no.
 *
 * Every one of these paths returns a [CropResult] rather than throwing, which is the contract the
 * whole API rests on; `crop()` is what a button calls, and a button that can crash the app on a
 * corrupt file is worse than one that shows a message. None of them had ever run: the happy-path
 * tests use a real decoder that always succeeds, so the failure arms of the pipeline were dead code
 * as far as the suite was concerned.
 *
 * The lever throughout is a **closed** `PlatformImage`. `readArgbPixels()` answers `null` once an
 * image is closed, which is the same answer the platform gives for the cases these guards were
 * written for (an Android hardware bitmap, or an allocation the system refused), and it is the
 * only one a test can produce on demand.
 */
class CropPipelineFailurePathTest {

  private val imageSize = ImageSize(TestImages.WIDTH, TestImages.HEIGHT)

  // -----------------------------------------------------------------------------------------
  // The decode
  // -----------------------------------------------------------------------------------------

  @Test
  fun reportsADecodeFailureWhenTheDecoderProducesNothing() = runTest {
    val state = stateWith(FailingDecoder(imageSize))

    val failure = assertFailure(state.crop(EncodeOptions(EncodedFormat.PNG)))

    assertEquals(CropResult.Failure.Reason.DecodeFailed, failure.reason)
  }

  /**
   * The same null, told apart by asking the job rather than the decoder.
   *
   * A decoder that was cancelled mid-read and one that ran out of memory both come back `null`, and
   * reporting a cancelled crop as a failure puts an error in front of a user who pressed back. The
   * distinction is made from the coroutine's own state, so this test cancels the job the crop is
   * running under and asserts the *other* answer to the same input.
   */
  @Test
  fun reportsCancellationRatherThanFailureWhenTheJobEndedDuringTheDecode() = runBlocking<Unit> {
    // The cancellation has to be visible to the pipeline's own coroutine, so the crop runs in a
    // job the decoder itself cancels on the way out. `launch` absorbs the cancellation and `join`
    // waits for the body, which has already written its answer to `result`.
    var running: Job? = null
    val state = stateWith(FailingDecoder(imageSize, onDecode = { running?.cancel() }))

    var result: CropResult? = null
    val scope = CoroutineScope(Dispatchers.Default)
    val job = scope.launch { result = state.crop(EncodeOptions(EncodedFormat.PNG)) }
    running = job
    job.join()

    assertIs<CropResult.Cancelled>(
      assertNotNull(result, "the pipeline returned nothing at all"),
      "a cancelled decode was reported as a failure",
    )
  }

  // -----------------------------------------------------------------------------------------
  // Bringing the pixels upright
  // -----------------------------------------------------------------------------------------

  /**
   * An Exif-rotated source whose pixels the platform will not hand over fails loudly.
   *
   * The alternative, encoding them as they are, is a sideways photograph, which is precisely the
   * bug this library exists to end, and it would be invisible to the caller. The message has to
   * name the orientation, because that is what tells a maintainer reading a bug report that the
   * file was tagged rather than that the crop was wrong.
   */
  @Test
  fun refusesToEncodeASourceItCouldNotBringUpright() = runTest {
    val state = stateWith(
      ClosedPixelsDecoder(imageSize, ImageFormat.JPEG),
      orientation = ImageOrientation.ROTATE_90,
    )

    val failure = assertFailure(state.crop(EncodeOptions(EncodedFormat.PNG)))

    assertEquals(CropResult.Failure.Reason.DecodeFailed, failure.reason)
    val message = assertNotNull(failure.cause?.message, "the failure carries no explanation")
    assertTrue(
      ImageOrientation.ROTATE_90.name in message,
      "the message does not say which orientation was outstanding: $message",
    )
  }

  /**
   * A decoder that has already applied the tag is trusted, and the tag is not applied twice.
   *
   * This is the browser's behaviour: `createImageBitmap` honours Exif during decode and the request
   * to suppress it is not obeyed everywhere, so the decoder reports what it did. Subtracting it is
   * what keeps a photograph from coming back turned 180 degrees on the web and upright everywhere
   * else. Reached here with a decoder whose pixels cannot be read, so that a pipeline which *did*
   * try to reorient would fail visibly rather than quietly producing a turned image.
   */
  @Test
  fun doesNotApplyAnOrientationTheDecoderAlreadyBakedIn() = runTest {
    val state = stateWith(
      ClosedPixelsDecoder(imageSize, ImageFormat.JPEG, applied = ImageOrientation.ROTATE_90),
      orientation = ImageOrientation.ROTATE_90,
    )

    val result = state.crop(EncodeOptions(EncodedFormat.JPEG))

    assertTrue(
      result !is CropResult.Failure || result.reason != CropResult.Failure.Reason.DecodeFailed,
      "the orientation was applied a second time on top of the decoder's own",
    )
  }

  // -----------------------------------------------------------------------------------------
  // The encode
  // -----------------------------------------------------------------------------------------

  /**
   * Pixels that cannot be scanned for alpha are refused, not written.
   *
   * The rule this pipeline holds is that it never flattens transparency silently, and the scan is
   * how it knows there is any. Pixels it cannot read are unproven, so the safe answer is to refuse
   * rather than to assume they are opaque and write a black fringe around a cropped logo that the
   * caller can never get back.
   */
  @Test
  fun refusesAFormatWithoutAlphaWhenItCannotProveThePixelsAreOpaque() = runTest {
    val state = stateWith(ClosedPixelsDecoder(imageSize, ImageFormat.PNG))

    val failure = assertFailure(state.crop(EncodeOptions(EncodedFormat.JPEG)))

    assertEquals(CropResult.Failure.Reason.EncodeUnsupported, failure.reason)
  }

  /**
   * A closed image reaching the encoder is a failure value, not a dead process.
   *
   * ## Regression test for a SIGSEGV
   *
   * `Image.makeFromBitmap` on a closed Skia bitmap dereferences freed memory. It does not throw:
   * the JVM dies where it stands, with no exception, no stack and nothing for an application to
   * catch, taking the host app down with it. The first version of this test file did exactly that
   * and killed the Gradle test worker with `exit value 134`.
   *
   * Android is not symmetric here: `Bitmap.compress` on a recycled bitmap throws an
   * `IllegalStateException`, which this pipeline already catches. So the Skia encoder checks
   * `PlatformImage.isClosed` and throws the same thing, and both platforms now answer a closed
   * image with [CropResult.Failure.Reason.EncodeFailed].
   *
   * The alpha scan is skipped on this path (a JPEG source cannot carry transparency) so the
   * closed pixels reach the encoder rather than being refused before it, which is what makes this
   * test about the encoder at all.
   */
  @Test
  fun answersAClosedImageWithAFailureInsteadOfKillingTheProcess() = runTest {
    val state = stateWith(ClosedPixelsDecoder(imageSize, ImageFormat.JPEG))

    val failure = assertFailure(state.crop(EncodeOptions(EncodedFormat.JPEG)))

    assertEquals(CropResult.Failure.Reason.EncodeFailed, failure.reason)
    assertIs<IllegalStateException>(
      failure.cause,
      "the encoder failed for some reason other than the closed image",
    )
  }

  /**
   * The control: the same unreadable pixels into a format that has an alpha channel are not refused
   * by the *alpha* rule; they get as far as the encoder, and fail there for the reason above.
   */
  @Test
  fun doesNotRefuseAFormatThatCanHoldAlphaOverAnAlphaScan() = runTest {
    val state = stateWith(ClosedPixelsDecoder(imageSize, ImageFormat.PNG))

    val failure = assertFailure(state.crop(EncodeOptions(EncodedFormat.PNG)))

    assertEquals(
      CropResult.Failure.Reason.EncodeFailed,
      failure.reason,
      "a format with alpha was refused over an alpha scan it never needed to run",
    )
  }

  // -----------------------------------------------------------------------------------------
  // Before the decoder is even consulted
  // -----------------------------------------------------------------------------------------

  @Test
  fun repeatsTheReasonTheSourceFailedToOpenRatherThanInventingOne() = runTest {
    val state = stateWith(FailingDecoder(imageSize))
    val cause = IllegalStateException("the file was deleted under us")
    state.status = CropStatus.Failed(CropResult.Failure.Reason.SourceUnreadable, cause)

    val failure = assertFailure(state.crop(EncodeOptions(EncodedFormat.PNG)))

    assertEquals(CropResult.Failure.Reason.SourceUnreadable, failure.reason)
    assertEquals(cause, failure.cause, "the original cause was thrown away")
  }

  // -----------------------------------------------------------------------------------------
  // Harness
  // -----------------------------------------------------------------------------------------

  private fun stateWith(
    decoder: RegionDecoder,
    orientation: ImageOrientation = ImageOrientation.NORMAL,
  ): RealCropState = RealCropState(
    source = TestImages.source(ByteArray(0), key = "failure-path"),
    initialAspectRatio = AspectRatio.Free,
  ).apply {
    viewportSize = FloatSize(640f, 480f)
    status = CropStatus.Ready(
      imageSize = orientation.transformSize(decoder.imageSize),
      orientation = orientation,
    )
    this.decoder = decoder
    normalizedCropRect = FloatRect(0.2f, 0.2f, 0.8f, 0.8f)
  }

  private fun assertFailure(result: CropResult): CropResult.Failure =
    assertIs<CropResult.Failure>(result, "expected a failure but got $result")

  // -----------------------------------------------------------------------------------------
  // The budget the caller asked for
  // -----------------------------------------------------------------------------------------

  /**
   * A tighter budget produces a smaller image, rather than being accepted and ignored.
   *
   * `DecodeBudget` was fully public and documented while nothing accepted one: both consumers
   * hardcoded a constant. Memory is this library's headline claim, so a caller producing a 200
   * pixel avatar had no way to say that 192MiB was not needed. The assertion is on the pixels,
   * because a parameter that is accepted and never applied is the defect this exists to catch.
   */
  @Test
  fun aTighterBudgetProducesASmallerDecode() = runTest {
    val state = stateWith(SolidDecoder(ImageSize(2_048, 2_048)))

    val generous = assertIs<CropResult.Success>(
      state.crop(EncodeOptions(EncodedFormat.PNG), DecodeBudget.ForOutput),
    )
    val tight = assertIs<CropResult.Success>(
      state.crop(
        EncodeOptions(EncodedFormat.PNG),
        DecodeBudget(maxByteCount = 64L * 1024, maxDimension = 128),
      ),
    )

    assertTrue(
      tight.size.width < generous.size.width,
      "the budget was ignored: ${tight.size} against ${generous.size}",
    )
    assertTrue(tight.size.width <= 128, "the tight budget's dimension cap was not applied")
  }
}

/** Answers every decode with `null`, the way a platform out of memory does. */
private class FailingDecoder(
  override val imageSize: ImageSize,
  private val onDecode: () -> Unit = {},
) : RegionDecoder {
  override val format: ImageFormat = ImageFormat.JPEG
  override val appliedOrientation: ImageOrientation = ImageOrientation.NORMAL
  override suspend fun decodeRegion(region: ImageRegion, sampleSize: Int): DecodedRegion? {
    onDecode()
    return null
  }
  override fun close(): Unit = Unit
}

/**
 * Succeeds, but hands back an image whose pixels cannot be read.
 *
 * Closing the image immediately is the one way to produce, on demand, the `null` that
 * `readArgbPixels` returns for an Android hardware bitmap or a refused allocation. `width` and
 * `height` are captured before the close, so the image still describes itself correctly, which is
 * exactly the shape of the real case.
 */
private class ClosedPixelsDecoder(
  override val imageSize: ImageSize,
  override val format: ImageFormat,
  private val applied: ImageOrientation = ImageOrientation.NORMAL,
) : RegionDecoder {
  override val appliedOrientation: ImageOrientation get() = applied
  override suspend fun decodeRegion(region: ImageRegion, sampleSize: Int): DecodedRegion? {
    val size = ImageSize(region.width, region.height)
    val image = platformImageOfArgbPixels(
      IntArray(size.width * size.height) { 0xFF112233.toInt() },
      size,
    ) ?: return null
    image.close()
    return DecodedRegion(image = image, region = region, sampleSize = sampleSize)
  }
  override fun close(): Unit = Unit
}

/** Produces a real image at whatever sample size it is asked for, so the budget is observable. */
private class SolidDecoder(override val imageSize: ImageSize) : RegionDecoder {
  override val format: ImageFormat = ImageFormat.JPEG
  override val appliedOrientation: ImageOrientation = ImageOrientation.NORMAL
  override suspend fun decodeRegion(region: ImageRegion, sampleSize: Int): DecodedRegion? {
    val size = ImageSize(
      (region.width / sampleSize).coerceAtLeast(1),
      (region.height / sampleSize).coerceAtLeast(1),
    )
    val image = platformImageOfArgbPixels(
      IntArray(size.width * size.height) { 0xFF2288CC.toInt() },
      size,
    ) ?: return null
    return DecodedRegion(image = image, region = region, sampleSize = sampleSize)
  }
  override fun close(): Unit = Unit
}
