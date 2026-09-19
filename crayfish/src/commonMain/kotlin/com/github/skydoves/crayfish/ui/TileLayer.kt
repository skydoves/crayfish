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

import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.github.skydoves.crayfish.decode.DecodedRegion
import com.github.skydoves.crayfish.decode.ImageRegion
import com.github.skydoves.crayfish.decode.ImageSize
import com.github.skydoves.crayfish.geometry.CoordinateSpace
import com.github.skydoves.crayfish.geometry.FloatPoint

/**
 * Decoded pixels for one rectangle of the source, ready to be drawn.
 *
 * Owns its [DecodedRegion] outright: [close] is the only way those pixels are freed, and nothing
 * else may call it. The cache is what calls [close], on eviction, which is what turns its byte
 * budget from a number it tracks into memory the process actually gives back.
 */
internal class PreviewTile private constructor(
  private val decoded: DecodedRegion,
  private var bitmap: ImageBitmap?,
) : TileEntry {

  /** Where these pixels came from, in the source's own coordinates. */
  internal val region: ImageRegion get() = decoded.region

  /**
   * What this tile cost, measured from the pixels that came back rather than the ones asked for.
   *
   * A region decoder may answer at a coarser sample size than it was given, retrying that way
   * instead of failing an out-of-memory, so the requested size is not what is being held.
   */
  override val byteCount: Long = ImageSize(decoded.width, decoded.height).argb8888ByteCount

  /**
   * The pixels, or `null` once they have been released.
   *
   * The null is the whole eviction story from the draw side. A tile can be closed between the
   * frame that planned it and the frame that draws it, and on Skia reading a closed bitmap aborts
   * the process rather than throwing, so the reference is dropped here at the moment of close and
   * the draw loop simply skips what it cannot get.
   */
  internal val imageBitmap: ImageBitmap? get() = bitmap

  override fun close() {
    if (bitmap == null) return
    bitmap = null
    decoded.close()
  }

  internal companion object {

    /**
     * Wraps [decoded] for drawing, taking ownership of it.
     *
     * @return `null` when the platform will not hand these pixels to Compose: an Android hardware
     *   bitmap, or an image already closed. The region is closed on that path rather than left to
     *   a collector, because a tile that cannot be drawn is still native memory.
     */
    internal fun of(decoded: DecodedRegion): PreviewTile? {
      val bitmap = decoded.image.toImageBitmap()
      if (bitmap == null) {
        decoded.close()
        return null
      }
      return PreviewTile(decoded = decoded, bitmap = bitmap)
    }
  }
}

/**
 * Hands every tile that still has pixels to [onTile], base layer first.
 *
 * Split out of the draw scope so the skipping can be tested without a canvas: "a closed tile is
 * skipped, not drawn, and does not take the process with it" is the behaviour that eviction
 * depends on, and it should not be observable only by rendering.
 *
 * Back to front: the base layer is a low-resolution decode of the whole image and is always
 * resident, so a tile that has not arrived yet leaves a blurry patch rather than a hole.
 */
internal inline fun drawTiles(
  base: PreviewTile?,
  tiles: List<PreviewTile>,
  onTile: (ImageBitmap, ImageRegion) -> Unit,
) {
  if (base != null) {
    val image = base.imageBitmap
    if (image != null) onTile(image, base.region)
  }
  for (tile in tiles) {
    val image = tile.imageBitmap ?: continue
    onTile(image, tile.region)
  }
}

/**
 * Draws the base layer and [tiles] in **image** coordinates.
 *
 * The caller is expected to have put the image-to-viewport map on the canvas already, so each tile
 * is drawn into the rectangle it was decoded from. Drawing this way is what makes a pinch free:
 * the transform moves, the tiles do not, and nothing is re-decoded for a new offset.
 */
internal fun DrawScope.drawTileLayer(base: PreviewTile?, tiles: List<PreviewTile>) {
  drawTiles(base = base, tiles = tiles) { image, region ->
    drawImage(
      image = image,
      srcOffset = IntOffset.Zero,
      srcSize = IntSize(image.width, image.height),
      dstOffset = IntOffset(region.left, region.top),
      dstSize = IntSize(region.width, region.height),
      // Bilinear. A tile is drawn scaled by definition, which is what a sample size is, and
      // nearest-neighbour turns a zoomed-out 108MP photo into aliasing rather than a photo.
      filterQuality = FilterQuality.Low,
    )
  }
}

/**
 * Writes this space's image-to-viewport map into [matrix], and returns it.
 *
 * Sampled from [CoordinateSpace] rather than rebuilt from the transform's fields. The map is
 * affine, so three mapped points determine it exactly. Rebuilding it here would mean a second copy
 * of the transform order, and a canvas quietly disagreeing with every rectangle the crop pipeline
 * computes.
 *
 * **The axes are sampled across the whole image, not over a unit step.** Every mapping pivots
 * about the content's centre, so a point one pixel from the origin computes `540 + (0.09 - 540)`,
 * subtracting two numbers that agree to five digits and leaving about three in single precision.
 * Scaled back out by the image width that is a third of a pixel of drift at the far edge, enough
 * to show as a seam between tiles. Over the full width the subtraction keeps every digit.
 *
 * [matrix] is passed in so a caller can keep one across frames: the transform changes every frame
 * of a pinch, so the matrix cannot be cached, but its backing array can.
 */
internal fun CoordinateSpace.writeInto(matrix: Matrix): Matrix {
  matrix.reset()
  if (!isValid) return matrix

  val width = imageSize.width.toFloat()
  val height = imageSize.height.toFloat()
  val origin = imageToViewport(FloatPoint.Zero)
  val xAxis = (imageToViewport(FloatPoint(width, 0f)) - origin) * (1f / width)
  val yAxis = (imageToViewport(FloatPoint(0f, height)) - origin) * (1f / height)

  matrix[0, 0] = xAxis.x
  matrix[0, 1] = xAxis.y
  matrix[1, 0] = yAxis.x
  matrix[1, 1] = yAxis.y
  matrix[3, 0] = origin.x
  matrix[3, 1] = origin.y
  return matrix
}
