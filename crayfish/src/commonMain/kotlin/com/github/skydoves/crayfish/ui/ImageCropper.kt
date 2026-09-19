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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.github.skydoves.crayfish.decode.CropSource
import com.github.skydoves.crayfish.encode.EncodeOptions
import com.github.skydoves.crayfish.encode.EncodedFormat
import kotlinx.coroutines.CompletableDeferred

/**
 * The one-line way to crop an image.
 *
 * Built on [Cropper] and [CropState] rather than beside them, for the common case where a caller
 * wants a picture cropped and does not want to design a screen to do it.
 *
 * ```
 * val cropper = rememberImageCropper()
 * scope.launch {
 *   when (val result = cropper.crop(source)) {
 *     is CropResult.Success -> upload(result.bytes)
 *     is CropResult.Cancelled -> Unit
 *     is CropResult.Failure -> showError(result.reason)
 *   }
 * }
 * ImageCropperDialog(cropper)
 * ```
 */
@Stable
public sealed interface ImageCropper {

  /**
   * The crop in progress, or `null` when there is none.
   *
   * [ImageCropperDialog] shows itself exactly when this is non-null. It is public so a caller can
   * present the crop some other way (a full screen, a bottom sheet, a pane) without giving up
   * the suspending call.
   */
  public val pending: CropState?

  /**
   * Shows a cropper for [source] and suspends until the user confirms or cancels it.
   *
   * Requires an [ImageCropperDialog], or some other presenter of [pending], to be in composition;
   * without one this would suspend for ever. Cancelling the calling coroutine dismisses the crop.
   *
   * Only one crop runs at a time. A call made while another is on screen returns
   * [CropResult.Failure.Reason.AlreadyCropping] immediately and leaves the crop in progress alone.
   * It is not queued, because the second caller would otherwise wait on a dialog that is showing
   * somebody else's image.
   */
  public suspend fun crop(
    source: CropSource,
    options: EncodeOptions = EncodeOptions(EncodedFormat.JPEG),
    aspectRatio: AspectRatio = AspectRatio.Free,
  ): CropResult

  /**
   * The same dialog, resolving to an `ImageBitmap` instead of to encoded bytes.
   *
   * What a Compose app usually wants: the result goes straight into an `Image`, so encoding it and
   * decoding it back would be two full copies and, in a lossy format, quality that does not return.
   * There are no [EncodeOptions] because nothing is encoded.
   *
   * ```kotlin
   * when (val result = cropper.cropToImage(source)) {
   *   is CropImage.Success -> avatar = result.image
   *   is CropImage.Cancelled -> Unit
   *   is CropImage.Failure -> showError(result.reason)
   * }
   * ```
   */
  public suspend fun cropToImage(
    source: CropSource,
    aspectRatio: AspectRatio = AspectRatio.Free,
  ): CropImage

  /** Dismisses the crop in progress, resolving the call with its cancelled value. */
  public fun cancel()
}

/** Remembers an [ImageCropper] for the life of the composition. */
@Composable
public fun rememberImageCropper(): ImageCropper = remember { RealImageCropper() }

@Stable
internal class RealImageCropper : ImageCropper {

  internal var request: CropRequest? by mutableStateOf(null)
    private set

  /**
   * The state for the crop in progress.
   *
   * Set by [ImageCropperDialog] once it has composed a [CropState] for the request, because
   * `rememberCropState` is a composable and this class is not. Callers see it through [pending].
   */
  internal var attachedState: CropState? by mutableStateOf(null)

  override val pending: CropState? get() = attachedState

  override suspend fun crop(
    source: CropSource,
    options: EncodeOptions,
    aspectRatio: AspectRatio,
  ): CropResult {
    // One at a time. A second call while a crop is on screen would leave the first suspended for
    // ever with no way to reach its dialog.
    request?.let { return CropResult.Failure(CropResult.Failure.Reason.AlreadyCropping) }
    val outcome = await(CropRequest(source, CropOutput.Bytes(options), aspectRatio))
    return (outcome as? CropOutcome.OfBytes)?.result ?: CropResult.Cancelled
  }

  override suspend fun cropToImage(source: CropSource, aspectRatio: AspectRatio): CropImage {
    request?.let { return CropImage.Failure(CropResult.Failure.Reason.AlreadyCropping) }
    val outcome = await(CropRequest(source, CropOutput.Image, aspectRatio))
    return (outcome as? CropOutcome.OfImage)?.result ?: CropImage.Cancelled
  }

  private suspend fun await(pendingRequest: CropRequest): CropOutcome {
    request = pendingRequest
    return try {
      pendingRequest.result.await()
    } finally {
      // Runs on cancellation too, so a cancelled caller takes the dialog down with it rather than
      // leaving it on screen attached to a coroutine nobody is waiting on.
      clear()
    }
  }

  override fun cancel() {
    request?.let { it.result.complete(it.output.cancelled()) }
  }

  internal fun finish(outcome: CropOutcome) {
    request?.result?.complete(outcome)
  }

  private fun clear() {
    request = null
    attachedState = null
  }
}

internal class CropRequest(
  val source: CropSource,
  val output: CropOutput,
  val aspectRatio: AspectRatio,
  val result: CompletableDeferred<CropOutcome> = CompletableDeferred(),
)

/**
 * What the caller asked the dialog to produce.
 *
 * The two entry points differ only in the tail of the pipeline, so the request carries the choice
 * rather than the dialog having two of everything. Keeping the deferred typed as [CropOutcome] is
 * what lets one dialog serve both without either caller seeing the other's result type.
 */
internal sealed interface CropOutput {

  class Bytes(val options: EncodeOptions) : CropOutput

  data object Image : CropOutput

  fun cancelled(): CropOutcome = when (this) {
    is Bytes -> CropOutcome.OfBytes(CropResult.Cancelled)
    Image -> CropOutcome.OfImage(CropImage.Cancelled)
  }
}

internal sealed interface CropOutcome {
  class OfBytes(val result: CropResult) : CropOutcome
  class OfImage(val result: CropImage) : CropOutcome
}
