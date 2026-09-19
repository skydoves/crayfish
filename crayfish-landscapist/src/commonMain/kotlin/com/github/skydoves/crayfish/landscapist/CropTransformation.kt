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
import com.skydoves.landscapist.core.transformation.Transformation

/**
 * Applies a crop to an image while Landscapist is loading it.
 *
 * The point is what it avoids. Once a user has chosen a rectangle, the usual way to show the result
 * is to encode a cropped copy and load that: a second file, a second decode, and a cache entry per
 * crop. This applies the same rectangle inside the load instead, so the original stays the only
 * thing stored and the crop is a property of how it is displayed.
 *
 * ```
 * LandscapistImage(
 *   imageModel = { url },
 *   component = rememberImageComponent { },
 *   requestOptions = { transformations(CropTransformation(result.region)) },
 * )
 * ```
 *
 * @param region the rectangle to keep, in the image's own pixel coordinates after Exif correction.
 *   That is exactly what [com.github.skydoves.crayfish.ui.CropResult.Success.region] reports, which
 *   is why the two fit together without arithmetic at the call site.
 */
public class CropTransformation(private val region: ImageRegion) : Transformation {

  /**
   * Part of Landscapist's cache key, so two crops of one image are two entries rather than one that
   * changes under whichever screen asks second.
   */
  override val key: String
    get() = "crayfish-crop:${region.left},${region.top},${region.right},${region.bottom}"

  override suspend fun transform(input: Any): Any {
    if (region.isEmpty) return input
    return cropPlatformImage(input, region) ?: input
  }
}

/**
 * Crops a platform bitmap, or returns `null` to leave it alone.
 *
 * `Any` is Landscapist's own contract, the input being whatever bitmap type the platform decoded,
 * so the seam has to narrow it here rather than being handed something typed.
 *
 * Returning `null` rather than throwing keeps a transformation from turning a loadable image into a
 * failed one: a crop that cannot be applied is a worse picture, not a broken screen.
 */
internal expect suspend fun cropPlatformImage(input: Any, region: ImageRegion): Any?
