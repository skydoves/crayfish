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

import com.github.skydoves.crayfish.decode.PlatformImage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * The zlib level Skia compresses PNG with. PNG has no fidelity knob, so
 * [EncodeOptions.platformQuality] does not vary its output; this does. 6 is zlib's and Skia's
 * own default, named here only so the value stops being invisible.
 */
private const val PNG_COMPRESSION_LEVEL: Int = 6

/**
 * Encodes [image] with Skia, the encoder shared by desktop, iOS, macOS and wasm.
 *
 * Nothing here may be JVM-only: one function serves four targets, one of them a browser. That
 * rules out `Dispatchers.IO`, which does not exist off the JVM, so the encode runs on whichever
 * dispatcher the caller is already on.
 *
 * **[EncodedFormat.WEBP_LOSSLESS] returns `null` here, and that is deliberate.** Skia's
 * `Image.encodeToData` takes a single `quality` integer with no way to select `SkWebpEncoder`'s
 * lossless mode. Measured on skiko 0.150.1, every quality from 0 to 100 produces a lossy `VP8 `
 * chunk and never `VP8L`, so no magic value switches modes either. Writing a lossy file for a
 * caller who asked for lossless would be a silent, unrecoverable quality loss, so the format is
 * reported as unsupported instead. [EncodeOptions.losslessEffort] has no effect on Skia targets.
 *
 * Only PNG, JPEG and WebP are attempted at all: skiko throws
 * `RuntimeException("Only PNG, JPEG and WEBP formats are supported")` for every other member of
 * [EncodedImageFormat].
 *
 * @return the encoded bytes, or `null` for [EncodedFormat.WEBP_LOSSLESS], if Skia declines to
 *   encode, or if the caller was cancelled while the encode was running.
 */
public actual suspend fun encodeImage(image: PlatformImage, options: EncodeOptions): ByteArray? {
  val skiaFormat = when (options.format) {
    EncodedFormat.PNG -> EncodedImageFormat.PNG
    EncodedFormat.JPEG -> EncodedImageFormat.JPEG
    EncodedFormat.WEBP_LOSSY -> EncodedImageFormat.WEBP
    EncodedFormat.WEBP_LOSSLESS -> return null
  }

  // Checked rather than trusted. `Image.makeFromBitmap` on a closed bitmap does not throw: it
  // dereferences freed memory and the process dies with SIGSEGV, with no exception to catch and
  // nothing for `runCatching` to see. Android's `Bitmap.compress` throws IllegalStateException for
  // the same mistake, so throwing here is what makes the two platforms behave alike.
  check(!image.isClosed) { "the image was closed before it could be encoded" }
  val source = Image.makeFromBitmap(image.bitmap)
  val encoded = try {
    source.encodeToData(skiaFormat, options.platformQuality, PNG_COMPRESSION_LEVEL)
  } finally {
    // A second reference to a crop that may be tens of megabytes. Released before the bytes are
    // copied out so the peak is one copy of the pixels plus the encoded data, not two.
    source.close()
  }
  if (encoded == null) return null

  return try {
    // Skia's encode is one blocking native call with no cancellation hook, so a caller that gave
    // up part way through can only be honoured here, on the way out.
    if (currentCoroutineContext().isActive) encoded.bytes else null
  } finally {
    encoded.close()
  }
}
