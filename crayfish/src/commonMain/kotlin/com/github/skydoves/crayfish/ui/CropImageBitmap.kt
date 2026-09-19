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
package com.github.skydoves.crayfish.ui

import androidx.compose.ui.graphics.ImageBitmap
import com.github.skydoves.crayfish.decode.ImageRegion
import com.github.skydoves.crayfish.decode.ImageSize
import com.github.skydoves.crayfish.decode.platformImageOfArgbPixels

/**
 * Cuts [region] out of an image that is already decoded.
 *
 * The companion to [CropRecipe]: a recipe says where the crop is, and a stored
 * [CropResult.Success.region] says where an earlier one was. Either way, applying it to a picture
 * already in memory should not mean encoding a file and loading it back, and this is the one call
 * that does it.
 *
 * It is also what the image-loader bridges are built on, which is why it lives here rather than in
 * one of them. Three copies of a sub-rectangle copy is three places for the same mistake, and this
 * library has already made one of them: an earlier bridge used Skia's `extractSubset`, which
 * **aliases** the source rather than copying it, so every crop of one image was the same pixels.
 *
 * Common code, no expect/actual: `ImageBitmap.readPixels` is enough to do this on every target.
 *
 * @param region in the image's own pixel coordinates, clipped to its bounds.
 * @return the cropped pixels, or `null` when [region] falls outside the image entirely or the
 *   allocation was refused. Never an exception: a crop that cannot be applied is a worse picture,
 *   not a broken screen.
 */
public fun ImageBitmap.cropTo(region: ImageRegion): ImageBitmap? {
  val bounds = ImageRegion.of(ImageSize(width, height))
  val clipped = region.intersect(bounds) ?: return null
  if (clipped.width <= 0 || clipped.height <= 0) return null
  if (clipped == bounds) return this

  val pixels = IntArray(clipped.width * clipped.height)
  readPixels(
    buffer = pixels,
    startX = clipped.left,
    startY = clipped.top,
    width = clipped.width,
    height = clipped.height,
  )
  val size = ImageSize(clipped.width, clipped.height)
  val image = platformImageOfArgbPixels(pixels, size) ?: return null
  // Not closed. `toImageBitmap` wraps rather than copies on both Android and Skia, so this buffer
  // is what the returned ImageBitmap draws from, and releasing it here would hand back a recycled
  // bitmap. That exact bug shipped once in `cropToImage`; ownership passes with the image.
  return image.toImageBitmap()
}
