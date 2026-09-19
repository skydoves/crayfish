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
package com.github.skydoves.crayfish.landscapist

import android.graphics.Bitmap
import android.os.Build
import com.github.skydoves.crayfish.decode.ImageRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual suspend fun cropPlatformImage(input: Any, region: ImageRegion): Any? {
  val source = input as? Bitmap ?: return null
  if (source.isRecycled) return null
  // A hardware bitmap has no readable pixels, so createBitmap cannot copy out of one.
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && source.config == Bitmap.Config.HARDWARE) {
    return null
  }

  val clipped = region.intersect(ImageRegion(0, 0, source.width, source.height)) ?: return null
  return withContext(Dispatchers.Default) {
    try {
      Bitmap.createBitmap(source, clipped.left, clipped.top, clipped.width, clipped.height)
    } catch (_: OutOfMemoryError) {
      // The caller keeps the uncropped image rather than losing the picture entirely.
      null
    } catch (_: IllegalArgumentException) {
      null
    }
  }
}
