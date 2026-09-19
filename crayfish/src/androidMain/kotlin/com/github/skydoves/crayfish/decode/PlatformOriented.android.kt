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
package com.github.skydoves.crayfish.decode

import android.graphics.Bitmap
import android.graphics.Matrix
import com.github.skydoves.crayfish.exif.ImageOrientation

/**
 * Skia's own transform, reached through `Bitmap.createBitmap`, with no native code of ours.
 *
 * The matrix is built to match [ImageOrientation.applyTo] exactly: `postRotate` first, so the
 * rotation happens first, then `postScale(-1f, 1f)`, which post-concatenates and therefore mirrors
 * **after** the rotation, in the rotated frame. Writing those two the other way round produces the
 * right answer for the four unmirrored values and a back to front image for the other four, which
 * is the Exif bug this library exists to end.
 *
 * `filter = false` because every one of the eight is a permutation of whole pixels. Filtering would
 * cost time and interpolate values that already land exactly on their destination.
 */
internal actual fun PlatformImage.platformOriented(orientation: ImageOrientation): PlatformImage? {
  if (orientation == ImageOrientation.NORMAL) return null
  if (bitmap.isRecycled) return null

  val matrix = Matrix()
  matrix.postRotate(orientation.rotationDegrees.toFloat())
  if (orientation.isMirrored) matrix.postScale(-1f, 1f)

  return try {
    val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
    // `createBitmap` returns the *same* instance when the matrix turns out to be the identity, and
    // handing that back would give the caller an image that is closed underneath it the moment the
    // decode buffer is released.
    if (turned === bitmap) null else PlatformImage(turned)
  } catch (_: OutOfMemoryError) {
    // The same outcome every other allocation in this pipeline reports: a null, not an exception.
    null
  } catch (_: IllegalArgumentException) {
    null
  }
}
