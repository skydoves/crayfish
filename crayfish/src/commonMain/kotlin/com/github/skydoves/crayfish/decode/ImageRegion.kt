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

/**
 * A rectangle in an image's own pixel coordinates, with the origin at the top-left.
 *
 * Deliberately not `androidx.compose.ui.unit.IntRect`: this package stays usable without a Compose
 * runtime so cropping can be driven headlessly and the geometry unit-tested on its own.
 */
public data class ImageRegion(
  public val left: Int,
  public val top: Int,
  public val right: Int,
  public val bottom: Int,
) {
  public val width: Int get() = right - left
  public val height: Int get() = bottom - top
  public val isEmpty: Boolean get() = width <= 0 || height <= 0
  public val size: ImageSize get() = ImageSize(width, height)

  /**
   * This region clipped to [bounds], or `null` when they do not overlap.
   *
   * Platform region decoders differ on what they do with a rectangle that runs off the edge of the
   * image: some clamp, some throw, some return a bitmap padded with undefined pixels. The
   * rectangle is made legal here rather than at four call sites.
   */
  public fun intersect(bounds: ImageRegion): ImageRegion? {
    val clipped = ImageRegion(
      left = maxOf(left, bounds.left),
      top = maxOf(top, bounds.top),
      right = minOf(right, bounds.right),
      bottom = minOf(bottom, bounds.bottom),
    )
    return if (clipped.isEmpty) null else clipped
  }

  public companion object {
    /** The whole of an image of [size]. */
    public fun of(size: ImageSize): ImageRegion =
      ImageRegion(left = 0, top = 0, right = size.width, bottom = size.height)
  }
}
