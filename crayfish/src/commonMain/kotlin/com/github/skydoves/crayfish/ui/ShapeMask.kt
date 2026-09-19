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

import com.github.skydoves.crayfish.decode.ImageSize
import com.github.skydoves.crayfish.decode.PlatformImage
import com.github.skydoves.crayfish.decode.platformImageOfArgbPixels
import kotlin.math.max
import kotlin.math.min

/**
 * Cuts [shape] out of [image], clearing everything outside it to transparent.
 *
 * What makes `CropShape` more than a viewfinder. A circular mask that only ever existed in the
 * overlay produced a square photograph, which is not what anyone picking "circle" is asking for,
 * and the shape's own documentation had promised the cut out since it was written.
 *
 * Anti-aliased along the edge, because a hard test per pixel leaves a staircase that is plainly
 * visible on a circle at any size a crop is actually used at. The coverage of the boundary pixel is
 * approximated from its distance to the edge, which is exact enough at one pixel and costs one
 * multiply.
 *
 * @param cornerRadiusPx [CropShape.RoundedRectangle]'s radius already converted into this image's
 *   own pixels, since a `Dp` means nothing here.
 * @return a new image the caller owns, or `null` when the shape needs no mask, when the pixels
 *   cannot be read, or when the allocation was refused.
 */
internal fun maskToShape(
  image: PlatformImage,
  shape: CropShape,
  cornerRadiusPx: Float,
): PlatformImage? {
  val width = image.width
  val height = image.height
  if (width <= 0 || height <= 0) return null

  val coverage: (Float, Float) -> Float = when (shape) {
    // Nothing to cut: the rectangle is the image.
    CropShape.Rectangle -> return null

    CropShape.Circle -> ellipseCoverage(width.toFloat(), height.toFloat())

    is CropShape.RoundedRectangle -> roundedRectangleCoverage(
      width = width.toFloat(),
      height = height.toFloat(),
      radius = cornerRadiusPx.coerceIn(0f, min(width, height) / 2f),
    )

    // A caller-built path, which this layer cannot evaluate without rasterising it. Drawn in the
    // overlay, left alone in the output, and said so in `CropShape.Custom`.
    is CropShape.Custom -> return null
  }

  val pixels = image.readArgbPixels() ?: return null
  for (y in 0 until height) {
    val row = y * width
    val py = y + 0.5f
    for (x in 0 until width) {
      val cover = coverage(x + 0.5f, py)
      if (cover >= 1f) continue
      val pixel = pixels[row + x]
      if (cover <= 0f) {
        pixels[row + x] = 0
      } else {
        val alpha = ((pixel ushr 24) and 0xFF) * cover
        pixels[row + x] = (alpha.toInt() shl 24) or (pixel and 0x00FFFFFF)
      }
    }
  }
  return platformImageOfArgbPixels(pixels, ImageSize(width, height))
}

/** 1 inside, 0 outside, and the fraction covered within a pixel of the edge. */
private fun ellipseCoverage(width: Float, height: Float): (Float, Float) -> Float {
  val cx = width / 2f
  val cy = height / 2f
  val rx = width / 2f
  val ry = height / 2f
  return { x, y ->
    val nx = (x - cx) / rx
    val ny = (y - cy) / ry
    // The normalised radius scaled back into pixels by the smaller semi-axis, which is the
    // direction the edge is thinnest in and therefore the one that decides the softness.
    val distance = (1f - kotlin.math.sqrt(nx * nx + ny * ny)) * min(rx, ry)
    softEdge(distance)
  }
}

private fun roundedRectangleCoverage(
  width: Float,
  height: Float,
  radius: Float,
): (Float, Float) -> Float = { x, y ->
  // Distance from the rounded rectangle's boundary, positive inside. Reduced to the distance from
  // the corner circle's centre in the corner regions and to the nearer edge elsewhere.
  val dx = max(radius - x, x - (width - radius))
  val dy = max(radius - y, y - (height - radius))
  val distance = when {
    dx > 0f && dy > 0f -> radius - kotlin.math.sqrt(dx * dx + dy * dy)
    else -> min(min(x, width - x), min(y, height - y))
  }
  softEdge(distance)
}

/** One pixel of feathering, which is the whole of the anti-aliasing. */
private fun softEdge(distance: Float): Float = (distance + 0.5f).coerceIn(0f, 1f)
