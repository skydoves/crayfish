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
import android.os.Build

public actual class PlatformImage(public val bitmap: Bitmap) : AutoCloseable {

  public actual val isClosed: Boolean get() = bitmap.isRecycled

  public actual val width: Int get() = bitmap.width
  public actual val height: Int get() = bitmap.height

  public actual fun readArgbPixels(): IntArray? {
    if (bitmap.isRecycled) return null
    // A hardware bitmap is immutable and its pixels are unreadable: getPixels throws. Decoders in
    // this library ask for software memory precisely so this branch is never taken, but a bitmap
    // can also arrive from a caller, and throwing here would turn a recoverable case into a crash.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE) {
      return null
    }
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    return pixels
  }

  actual override fun close() {
    if (!bitmap.isRecycled) bitmap.recycle()
  }
}
