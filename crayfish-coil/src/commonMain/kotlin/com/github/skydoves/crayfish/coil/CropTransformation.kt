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
package com.github.skydoves.crayfish.coil

import coil3.Bitmap
import coil3.size.Size
import coil3.transform.Transformation
import com.github.skydoves.crayfish.decode.ImageRegion

/**
 * Applies a crop while Coil is loading the original.
 *
 * The point is what it avoids. Once a user has chosen a rectangle, the usual way to show the result
 * is to encode a cropped copy and load that: a second file, a second decode, and a cache entry per
 * crop. This applies the same rectangle inside the load, so the original stays the only thing
 * stored and the crop becomes a property of how it is displayed.
 *
 * ```kotlin
 * val region = result.region
 * val request = remember(region) {
 *   ImageRequest.Builder(context)
 *     .data(url)
 *     .transformations(CropTransformation(region))
 *     .build()
 * }
 *
 * AsyncImage(model = request, contentDescription = null)
 * ```
 *
 * Pairs with [com.github.skydoves.crayfish.ui.CropRecipe]: store the recipe, keep the original, and
 * let the loader apply the rectangle every time it draws.
 *
 * @param region the rectangle to keep, in the image's own pixel coordinates after Exif correction.
 *   That is exactly what [com.github.skydoves.crayfish.ui.CropResult.Success.region] and
 *   [com.github.skydoves.crayfish.ui.CropImage.Success.region] report, which is why the two fit
 *   together with no arithmetic at the call site.
 */
public class CropTransformation(private val region: ImageRegion) : Transformation() {

  /**
   * Part of Coil's memory and disk cache key, so two crops of one image are two entries rather than
   * one that changes under whichever screen asks second.
   */
  override val cacheKey: String
    get() = "crayfish-crop:${region.left},${region.top},${region.right},${region.bottom}"

  /**
   * @return the cropped bitmap, or [input] unchanged when the rectangle cannot be applied.
   *
   * Returning the input rather than throwing keeps a transformation from turning a loadable image
   * into a failed one: a crop that could not be applied is a worse picture, not a broken screen.
   */
  override suspend fun transform(input: Bitmap, size: Size): Bitmap {
    if (region.isEmpty) return input
    return cropCoilBitmap(input, region) ?: input
  }
}

/**
 * Crops Coil's own bitmap type.
 *
 * Two lines per platform, because the cut itself is [com.github.skydoves.crayfish.ui.cropTo] in the
 * core module and this only has to get in and out of `coil3.Bitmap`. Keeping the arithmetic in one
 * place is deliberate: an earlier bridge in this repo wrote its own and used Skia's `extractSubset`,
 * which aliases rather than copies, so every crop of one image was the same pixels.
 */
internal expect fun cropCoilBitmap(input: Bitmap, region: ImageRegion): Bitmap?
