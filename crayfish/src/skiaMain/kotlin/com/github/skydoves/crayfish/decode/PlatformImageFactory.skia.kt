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

internal actual fun platformImageOfArgbPixels(pixels: IntArray, size: ImageSize): PlatformImage? {
  if (size.width <= 0 || size.height <= 0) return null
  val count = size.width * size.height
  if (pixels.size < count) return null

  // BGRA_8888 / UNPREMUL, the exact inverse of what `readArgbPixels` reads through. Letting Skia
  // pick its native order instead would round-trip correctly on one platform and swap red and blue
  // on another, which looks plausible enough in a screenshot to ship.
  val bytes = ByteArray(count * BYTES_PER_PIXEL)
  for (index in 0 until count) {
    val argb = pixels[index]
    val offset = index * BYTES_PER_PIXEL
    bytes[offset] = (argb and 0xFF).toByte()
    bytes[offset + 1] = ((argb ushr 8) and 0xFF).toByte()
    bytes[offset + 2] = ((argb ushr 16) and 0xFF).toByte()
    bytes[offset + 3] = ((argb ushr 24) and 0xFF).toByte()
  }

  val info = ImageInfo(
    width = size.width,
    height = size.height,
    colorType = ColorType.BGRA_8888,
    alphaType = ColorAlphaType.UNPREMUL,
    colorSpace = null,
  )
  val bitmap = Bitmap()
  val installed = try {
    bitmap.installPixels(info, bytes, size.width * BYTES_PER_PIXEL)
  } catch (_: Throwable) {
    false
  }
  if (!installed) {
    bitmap.close()
    return null
  }
  // Immutable so the encoder can take the pixels without copying them a second time.
  bitmap.setImmutable()
  return PlatformImage(bitmap)
}

private const val BYTES_PER_PIXEL = 4
