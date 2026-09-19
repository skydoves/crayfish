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
import java.awt.image.BufferedImage

/**
 * Desktop hands over a `BufferedImage`, not a Skia type.
 *
 * Landscapist's desktop decoder ends in `Bitmap.toBufferedImage()` before it builds its result, and
 * its own tests are named after that fact. A transformation that understands only Skia types
 * therefore returned the input untouched here: the image displayed uncropped, with no error and
 * nothing in a log. The Skia branch still runs first, because a caller may hand one over directly.
 */
internal actual suspend fun cropPlatformImage(input: Any, region: ImageRegion): Any? =
  cropSkiaImage(input, region) ?: cropBufferedImage(input, region)

private fun cropBufferedImage(input: Any, region: ImageRegion): BufferedImage? {
  if (input !is BufferedImage) return null
  val clipped = region.intersect(ImageRegion(0, 0, input.width, input.height)) ?: return null

  return try {
    // `getSubimage` returns a view over the same raster, which would pin the whole source the way
    // Skia's `extractSubset` did. Drawing it into a new image is what makes the crop own its pixels.
    val view = input.getSubimage(clipped.left, clipped.top, clipped.width, clipped.height)
    val copy = BufferedImage(clipped.width, clipped.height, BufferedImage.TYPE_INT_ARGB)
    val graphics = copy.createGraphics()
    try {
      graphics.drawImage(view, 0, 0, null)
    } finally {
      graphics.dispose()
    }
    copy
  } catch (_: Throwable) {
    null
  }
}
