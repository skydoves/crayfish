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

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

public actual class PlatformImage(public val bitmap: Bitmap) : AutoCloseable {

  /**
   * Skia frees the native bitmap on [close], and reading it afterwards is a use-after-free that
   * aborts the process rather than throwing: a JVM test runner dies with SIGABRT and takes every
   * remaining test with it. The Android actual gets this free from `Bitmap.isRecycled`; here it
   * has to be tracked. A tile loop that closes tiles as it advances is the ordinary case.
   */
  private var closed = false

  // Captured eagerly: reading them off a freed bitmap would abort for the same reason.
  public actual val isClosed: Boolean get() = closed

  public actual val width: Int = bitmap.width
  public actual val height: Int = bitmap.height

  public actual fun readArgbPixels(): IntArray? {
    if (closed) return null
    // An explicit ImageInfo rather than the bitmap's own: Skia's native colour type differs by
    // platform, so reading in the bitmap's format would hand back BGRA on one target and RGBA on
    // another under the same API. UNPREMUL because the caller wants the colours, not the
    // premultiplied storage form.
    val info = ImageInfo(
      width = width,
      height = height,
      colorType = ColorType.BGRA_8888,
      alphaType = ColorAlphaType.UNPREMUL,
      colorSpace = null,
    )
    val bytes = bitmap.readPixels(info, width * BYTES_PER_PIXEL, 0, 0) ?: return null
    if (bytes.size < width * height * BYTES_PER_PIXEL) return null

    val pixels = IntArray(width * height)
    for (index in pixels.indices) {
      val offset = index * BYTES_PER_PIXEL
      val blue = bytes[offset].toInt() and 0xFF
      val green = bytes[offset + 1].toInt() and 0xFF
      val red = bytes[offset + 2].toInt() and 0xFF
      val alpha = bytes[offset + 3].toInt() and 0xFF
      pixels[index] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }
    return pixels
  }

  actual override fun close() {
    if (closed) return
    closed = true
    bitmap.close()
  }

  private companion object {
    const val BYTES_PER_PIXEL = 4
  }
}
