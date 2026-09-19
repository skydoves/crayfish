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
package com.github.skydoves.crayfish.encode

import android.graphics.Bitmap
import android.os.Build
import com.github.skydoves.crayfish.decode.PlatformImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

public actual suspend fun encodeImage(image: PlatformImage, options: EncodeOptions): ByteArray? {
  // Outside the try below, deliberately, and matching the Skia actual line for line.
  //
  // `compress` on an already recycled bitmap raises this itself, but the catch inside turns it into
  // a `null`, which this function documents as "this platform cannot write that format" - a lie
  // about the format for what is really a gone image, and a different `CropResult` from the one
  // Skia produces for the identical mistake. Raising it here keeps both platforms answering
  // `EncodeFailed`, and leaves that catch for the case it was written for: a bitmap recycled *during*
  // the call, by a crop cancelled while this was queued.
  check(!image.isClosed) { "the image was closed before it could be encoded" }
  val compressFormat = options.format.toCompressFormat() ?: return null
  return withContext(Dispatchers.IO) {
    val sink = ByteArrayOutputStream()
    val compressed = try {
      image.bitmap.compress(compressFormat, options.platformQuality, sink)
    } catch (_: IllegalStateException) {
      // The bitmap was recycled while this was queued, which a cancelled crop can do.
      false
    }
    if (!compressed) return@withContext null

    // Cancellation is checked here rather than before the call. Bitmap.compress is a blocking JNI
    // call with no suspension point in it, so an ensureActive() ahead of it would only narrow the
    // race by microseconds and could not interrupt the encode itself. Once the work has happened
    // anyway, the honest response to a cancelled job is to discard what it produced.
    if (!coroutineContext.isActive) return@withContext null
    sink.toByteArray()
  }
}

/**
 * The platform constant for this format, or `null` when this API level cannot express it.
 *
 * `Bitmap.CompressFormat.WEBP` is deprecated at exactly API 30, which replaces it with a separate
 * lossy and lossless constant. Below 30 the one constant is quality-driven with no way to ask for
 * losslessness, so a lossless request is refused instead of being answered with a lossy file: the
 * caller chose that format precisely to keep every pixel, and a silent downgrade would be found
 * only after the original was gone.
 */
private fun EncodedFormat.toCompressFormat(): Bitmap.CompressFormat? = when (this) {
  EncodedFormat.JPEG -> Bitmap.CompressFormat.JPEG

  EncodedFormat.PNG -> Bitmap.CompressFormat.PNG

  EncodedFormat.WEBP_LOSSY ->
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      Bitmap.CompressFormat.WEBP_LOSSY
    } else {
      legacyWebP()
    }

  EncodedFormat.WEBP_LOSSLESS ->
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      Bitmap.CompressFormat.WEBP_LOSSLESS
    } else {
      null
    }
}

/** The pre-30 WebP constant, whose deprecation is unavoidable while minSdk is below 30. */
@Suppress("DEPRECATION")
private fun legacyWebP(): Bitmap.CompressFormat = Bitmap.CompressFormat.WEBP
