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
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import com.github.skydoves.crayfish.decode.ImageRegion
import com.github.skydoves.crayfish.decode.ImageSize
import com.github.skydoves.crayfish.encode.EncodedFormat
import com.github.skydoves.crayfish.ui.CropResult

/**
 * Launches a full screen cropper and comes back with a `content://` Uri.
 *
 * For apps that are not hosting Compose on the screen doing the cropping: Views, Fragments, or
 * anything that would rather start something than embed it.
 *
 * ```kotlin
 * private val cropper = registerForActivityResult(CropImageContract()) { result ->
 *   when (result) {
 *     is CropImageResult.Success -> imageView.setImageURI(result.uri)
 *     is CropImageResult.Cancelled -> Unit
 *     is CropImageResult.Failure -> showError(result.reason)
 *   }
 * }
 *
 * cropper.launch(CropImageRequest(source = photoUri, aspectRatio = 1f))
 * ```
 *
 * ## Why a Uri and not the bytes
 *
 * Because bytes do not fit. An Activity result travels through a binder transaction, and that has a
 * hard ceiling. Measured on a device rather than quoted: **768 KB went through, 1 MB came back as
 * `FAILED BINDER TRANSACTION`**. A JPEG crop of an ordinary phone photo is larger than that, a PNG
 * one much larger, and the size is the caller's to choose, so an API that returned bytes would work
 * in testing and crash on somebody's holiday photo.
 *
 * So the crop is written to the host app's own cache and handed back as a `content://` Uri with
 * read permission attached, which is what every cropper in this category does and now with a number
 * saying why. [CropImageResult.Success.readBytes] is there for callers who did want bytes: the same
 * data, read in process, without a megabyte going through the kernel.
 */
public class CropImageContract : ActivityResultContract<CropImageRequest, CropImageResult>() {

  override fun createIntent(context: Context, input: CropImageRequest): Intent =
    Intent(context, CropImageActivity::class.java).putRequest(input)

  override fun parseResult(resultCode: Int, intent: Intent?): CropImageResult {
    if (resultCode != Activity.RESULT_OK || intent == null) return CropImageResult.Cancelled
    return intent.readResult()
  }
}

/**
 * What to crop, and how.
 *
 * Primitives, because an `Intent` carries primitives. The richer types they stand in for live in the
 * core and are rebuilt on the other side: a `Float?` becomes an `AspectRatio`, a [CropMask] becomes
 * a `CropShape`. Trying to pass those directly would mean making a lambda-carrying sealed type
 * parcelable, which it is not.
 *
 * @param source the image to crop. Anything the host app can open: a photo picker's `content://`
 *   Uri, a `file://` path it owns, anything with a registered provider.
 * @param aspectRatio width divided by height, or `null` for a rectangle the user can shape freely.
 * @param mask the shape drawn over the crop, and cut out of the result where the format allows
 *   alpha. See [CropMask].
 * @param cornerRadiusDp used only by [CropMask.RoundedRectangle].
 * @param format what to encode. [CropMask.Circle] and [EncodedFormat.JPEG] together produce a
 *   rectangle, because JPEG has nowhere to put the transparency.
 * @param quality 1 to 100, ignored by PNG.
 * @param gesturesEnabled whether the photo may be pinched, panned and turned. Off by default, which
 *   leaves the photo still and every gesture belonging to the crop rectangle.
 */
public class CropImageRequest(
  public val source: Uri,
  public val aspectRatio: Float? = null,
  public val mask: CropMask = CropMask.Rectangle,
  public val cornerRadiusDp: Float = 0f,
  public val format: EncodedFormat = EncodedFormat.JPEG,
  public val quality: Int = 90,
  public val gesturesEnabled: Boolean = false,
)

/** The shapes an `Intent` can name. `CropShape.Custom` cannot cross a process boundary. */
public enum class CropMask {
  Rectangle,
  RoundedRectangle,
  Circle,
}

/** The outcome, in the same vocabulary the rest of the library uses. */
public sealed interface CropImageResult {

  /**
   * @property uri a `content://` Uri in the host app's cache, with read permission granted to the
   *   calling activity. It is a temporary file: copy it somewhere of your own if it has to outlive
   *   the screen.
   * @property size the pixel size of what was written.
   * @property region the rectangle of the source it came from, after Exif correction.
   */
  public class Success(
    public val uri: Uri,
    public val size: ImageSize,
    public val region: ImageRegion,
  ) : CropImageResult

  /** The user backed out, or the cropper could not be shown. */
  public data object Cancelled : CropImageResult

  /** The crop could not be produced. [reason] is the core library's own vocabulary. */
  public class Failure(public val reason: CropResult.Failure.Reason) : CropImageResult
}
