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

import com.github.skydoves.crayfish.decode.ImageRegion
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.IRect
import org.jetbrains.skia.Image

/**
 * The Skia half of the crop, shared by every target whose image loader speaks Skia.
 *
 * Not the actual itself: `skiaMain` covers desktop, iOS, macOS and wasm, and desktop's loader hands
 * over a `java.awt.image.BufferedImage` that the other three cannot even name. Each leaf actualises
 * `cropPlatformImage` and calls this first.
 */
internal fun cropSkiaImage(input: Any, region: ImageRegion): Any? {
  val size = when (input) {
    is Bitmap -> input.width to input.height
    is Image -> input.width to input.height
    else -> return null
  }
  val clipped = region.intersect(ImageRegion(0, 0, size.first, size.second)) ?: return null
  val rect = IRect.makeLTRB(clipped.left, clipped.top, clipped.right, clipped.bottom)

  return try {
    when (input) {
      is Bitmap -> input.copyRegion(rect)

      // `Bitmap.makeFromImage` already copies, but the copy is the whole raster, so the region
      // still has to be lifted out of it before the full size one is released. Explicit
      // try/finally rather than `use`: Skia's Bitmap is not AutoCloseable on Kotlin/Native, and
      // the JVM build compiles either way, so `use` breaks only the iOS and macOS targets.
      is Image -> {
        val whole = Bitmap.makeFromImage(input)
        try {
          whole.copyRegion(rect)?.let(Image::makeFromBitmap)
        } finally {
          whole.close()
        }
      }

      else -> null
    }
  } catch (_: Throwable) {
    null
  }
}

/**
 * [rect] of this bitmap, in its own tightly packed allocation.
 *
 * Not `extractSubset`, which is what this used to call. Skia's own documentation for it is explicit:
 * "Shares PixelRef with dst. **Pixels are not copied**; this and dst point to the same pixels." So a
 * 16x16 crop of a 512x512 bitmap came back still carrying the source's 2048 byte stride and holding
 * the entire 1MB allocation alive, which is the opposite of what a transformation feeding an image
 * cache should do: Landscapist would hold one full raster per cached crop while accounting for the
 * small one. `extractSubset` also calls `setImmutable` on the source to make the sharing safe, so
 * cropping froze the caller's bitmap as a side effect.
 *
 * `readPixels` copies, and the destination is allocated at the region's own width, so the result
 * owns exactly its own pixels and the caller's bitmap is left untouched.
 */
private fun Bitmap.copyRegion(rect: IRect): Bitmap? {
  val info = imageInfo.withWidthHeight(rect.width, rect.height)
  val rowBytes = rect.width * info.bytesPerPixel
  val pixels = readPixels(info, rowBytes, rect.left, rect.top) ?: return null

  val destination = Bitmap()
  if (!destination.installPixels(info, pixels, rowBytes)) {
    destination.close()
    return null
  }
  destination.setImmutable()
  return destination
}
