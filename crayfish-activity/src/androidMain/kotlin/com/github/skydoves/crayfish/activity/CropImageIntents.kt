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

import android.content.Intent
import android.net.Uri
import com.github.skydoves.crayfish.decode.ImageRegion
import com.github.skydoves.crayfish.decode.ImageSize
import com.github.skydoves.crayfish.encode.EncodedFormat
import com.github.skydoves.crayfish.ui.CropResult

/**
 * Everything that crosses the process boundary, written once.
 *
 * Primitives only, and deliberately so. Nothing here is a `Parcelable` of ours: a
 * `Parcelable`'s shape is a compatibility promise, and a library that adds a field to one breaks
 * every caller that was compiled against the old class. A handful of extras with names is boring and
 * survives.
 */
internal fun Intent.putRequest(request: CropImageRequest): Intent = apply {
  putExtra(EXTRA_SOURCE, request.source)
  request.aspectRatio?.let { putExtra(EXTRA_RATIO, it) }
  putExtra(EXTRA_MASK, request.mask.name)
  putExtra(EXTRA_CORNER_RADIUS, request.cornerRadiusDp)
  putExtra(EXTRA_FORMAT, request.format.name)
  putExtra(EXTRA_QUALITY, request.quality)
  putExtra(EXTRA_GESTURES, request.gesturesEnabled)
}

/**
 * @return the request, or `null` when the Intent did not come from [putRequest]. The activity
 *   finishes rather than guessing: a cropper with no image is a blank screen the user has to back
 *   out of.
 */
@Suppress("DEPRECATION")
internal fun Intent.readRequest(): CropImageRequest? {
  val source: Uri = getParcelableExtra(EXTRA_SOURCE) ?: return null
  return CropImageRequest(
    source = source,
    // -1 rather than a nullable extra: `getFloatExtra` has no null, and a ratio is positive.
    aspectRatio = getFloatExtra(EXTRA_RATIO, -1f).takeIf { it > 0f },
    mask = getStringExtra(EXTRA_MASK)
      ?.let { name -> CropMask.entries.firstOrNull { it.name == name } }
      ?: CropMask.Rectangle,
    cornerRadiusDp = getFloatExtra(EXTRA_CORNER_RADIUS, 0f),
    format = getStringExtra(EXTRA_FORMAT)
      ?.let { name -> EncodedFormat.entries.firstOrNull { it.name == name } }
      ?: EncodedFormat.JPEG,
    quality = getIntExtra(EXTRA_QUALITY, 90),
    gesturesEnabled = getBooleanExtra(EXTRA_GESTURES, false),
  )
}

internal fun Intent.putSuccess(uri: Uri, size: ImageSize, region: ImageRegion): Intent = apply {
  data = uri
  // Read permission travels with the result, so the caller can open it without declaring anything.
  addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  putExtra(EXTRA_WIDTH, size.width)
  putExtra(EXTRA_HEIGHT, size.height)
  putExtra(EXTRA_REGION, intArrayOf(region.left, region.top, region.right, region.bottom))
}

internal fun Intent.putFailure(reason: CropResult.Failure.Reason): Intent = apply {
  putExtra(EXTRA_FAILURE, reason.name)
}

internal fun Intent.readResult(): CropImageResult {
  getStringExtra(EXTRA_FAILURE)?.let { name ->
    val reason = CropResult.Failure.Reason.entries.firstOrNull { it.name == name }
      ?: CropResult.Failure.Reason.DecodeFailed
    return CropImageResult.Failure(reason)
  }
  val uri = data ?: return CropImageResult.Cancelled
  val region = getIntArrayExtra(EXTRA_REGION)?.takeIf { it.size == 4 }
    ?: return CropImageResult.Cancelled
  return CropImageResult.Success(
    uri = uri,
    size = ImageSize(getIntExtra(EXTRA_WIDTH, 0), getIntExtra(EXTRA_HEIGHT, 0)),
    region = ImageRegion(region[0], region[1], region[2], region[3]),
  )
}

private const val PREFIX = "com.github.skydoves.crayfish.activity."
private const val EXTRA_SOURCE = PREFIX + "SOURCE"
private const val EXTRA_RATIO = PREFIX + "RATIO"
private const val EXTRA_MASK = PREFIX + "MASK"
private const val EXTRA_CORNER_RADIUS = PREFIX + "CORNER_RADIUS"
private const val EXTRA_FORMAT = PREFIX + "FORMAT"
private const val EXTRA_QUALITY = PREFIX + "QUALITY"
private const val EXTRA_GESTURES = PREFIX + "GESTURES"
private const val EXTRA_WIDTH = PREFIX + "WIDTH"
private const val EXTRA_HEIGHT = PREFIX + "HEIGHT"
private const val EXTRA_REGION = PREFIX + "REGION"
private const val EXTRA_FAILURE = PREFIX + "FAILURE"
