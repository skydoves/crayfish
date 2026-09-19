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

import com.github.skydoves.crayfish.decode.DecodeBudget
import com.github.skydoves.crayfish.decode.ImageRegion
import com.github.skydoves.crayfish.decode.ImageSize
import com.github.skydoves.crayfish.decode.SampleSize
import com.github.skydoves.crayfish.geometry.CoordinateSpace
import com.github.skydoves.crayfish.geometry.CropTransform
import com.github.skydoves.crayfish.geometry.FloatRect
import com.github.skydoves.crayfish.geometry.FloatSize
import kotlin.math.roundToInt

/**
 * Which piece of the source a tile holds, at which level of detail.
 *
 * The grid is anchored to the **image**, not to the viewport: a tile's rectangle is a function of
 * its key alone, so panning by a pixel asks for the same tiles rather than a whole new set that
 * happens to overlap the old one by 99%. That is the property the cache is built on; without it
 * every frame of a drag would be a cache miss.
 *
 * [sampleSize] is part of the identity because the same square of the image at two zoom levels is
 * two different bitmaps. Keeping both keys live lets a zoom-out land straight on the coarser tiles
 * that were cached on the way in.
 */
internal data class TileKey(
  internal val sampleSize: Int,
  internal val column: Int,
  internal val row: Int,
) {

  /**
   * The rectangle of the source this tile covers, clipped to [imageSize].
   *
   * @return `null` when the tile lies wholly outside the image, which the edge of the grid does.
   */
  internal fun region(imageSize: ImageSize): ImageRegion? {
    val span = TileGrid.TILE_EDGE_PIXELS * sampleSize
    val left = column * span
    val top = row * span
    return ImageRegion(left = left, top = top, right = left + span, bottom = top + span)
      .intersect(ImageRegion.of(imageSize))
  }
}

/** The tiles one frame's worth of geometry asks for, and the level they are asked for at. */
internal data class TilePlan(internal val sampleSize: Int, internal val keys: List<TileKey>) {
  internal companion object {
    /** Nothing to draw: no image, no viewport, or a crop frame that is off the image entirely. */
    internal val Empty: TilePlan = TilePlan(sampleSize = 1, keys = emptyList())
  }
}

/**
 * The geometry the preview is looking at right now.
 *
 * A value rather than a set of reads, so the whole of it can come out of a single `snapshotFlow`
 * and be compared for equality. Gesture state changes on every frame; a flow that re-emits only
 * when the *value* changes is what keeps a held finger from queueing work.
 */
internal data class TileRequest(
  internal val imageSize: ImageSize,
  internal val viewportSize: FloatSize,
  internal val transform: CropTransform,
  internal val cropRect: FloatRect,
) {
  /** The mapping between the viewport and the image under this request's transform. */
  internal val space: CoordinateSpace
    get() = CoordinateSpace.fitting(
      imageSize = imageSize,
      viewportSize = viewportSize,
      transform = transform,
    )
}

/** Turns what is on screen into the list of tiles that would show it at full detail. */
internal object TileGrid {

  /**
   * The edge of a decoded tile, in pixels.
   *
   * 512 is a compromise the numbers pick rather than a taste: a tile is 1MiB as ARGB_8888, so the
   * cache budget below divides into a whole number of them, and a phone-sized crop frame needs
   * between four and twenty of them. Few enough that a pan decodes a handful, large enough that
   * the per-decode overhead of a region decoder is not paid hundreds of times.
   */
  internal const val TILE_EDGE_PIXELS: Int = 512

  /** What one full tile costs decoded, computed the same way every other budget here is. */
  internal val TILE_BYTE_COUNT: Long =
    ImageSize(TILE_EDGE_PIXELS, TILE_EDGE_PIXELS).argb8888ByteCount

  /**
   * How many tiles [budgetBytes] can hold.
   *
   * The plan is capped at this so that the whole visible set provably fits the cache. Without the
   * cap the grid's quantisation (a tile set is the region rounded outwards to tile boundaries)
   * could ask for more bytes than the cache may keep, and the cache would spend the frame evicting
   * tiles it is about to be asked for again. A bounded cache that thrashes is not bounded memory
   * with a nice property; it is a decoder running flat out forever.
   */
  internal fun maxTiles(budgetBytes: Long): Int = (budgetBytes / TILE_BYTE_COUNT)
    .coerceIn(1L, Int.MAX_VALUE.toLong())
    .toInt()

  /**
   * The tiles needed to show [cropRect] at the detail the screen can actually resolve.
   *
   * The level of detail comes from [SampleSize.forDecode] and nothing else: the source rectangle
   * is what the crop frame maps to, and the target is the crop frame's own size on screen, so
   * zooming in shrinks the source while the target stays put and the sample size falls out. There
   * is deliberately no second sampling rule here: one rule means one place for the arithmetic to
   * be wrong, and it is already tested.
   *
   * @param maxTiles the cap from [maxTiles]. When the grid is larger, the tiles nearest the centre
   *   of the crop frame win, because that is where the user is looking.
   */
  internal fun plan(space: CoordinateSpace, cropRect: FloatRect, maxTiles: Int): TilePlan {
    if (!space.isValid || maxTiles <= 0) return TilePlan.Empty
    val region = space.toImageRegion(cropRect) ?: return TilePlan.Empty

    // The crop frame's size in device pixels is the most detail the display can show of it. Under
    // a rotation the source rectangle is the bounding box of a tilted quad and so is larger than
    // the frame; asking for the frame's size rather than the box's is what stops a tilted image
    // from being decoded at a finer level than an upright one.
    val onScreen = ImageSize(
      width = cropRect.width.roundToInt().coerceAtLeast(1),
      height = cropRect.height.roundToInt().coerceAtLeast(1),
    )
    val sampleSize = SampleSize.forDecode(
      sourceSize = region.size,
      targetSize = onScreen,
      budget = DecodeBudget.ForDisplay,
    )

    val span = TILE_EDGE_PIXELS * sampleSize
    // `region` was clipped to the image by `toImageRegion`, so every edge is non-negative and
    // integer division is already a floor.
    val firstColumn = region.left / span
    val lastColumn = (region.right - 1) / span
    val firstRow = region.top / span
    val lastRow = (region.bottom - 1) / span

    val keys = ArrayList<TileKey>((lastColumn - firstColumn + 1) * (lastRow - firstRow + 1))
    for (row in firstRow..lastRow) {
      for (column in firstColumn..lastColumn) {
        keys += TileKey(sampleSize = sampleSize, column = column, row = row)
      }
    }

    // Centre-first, always: it fixes the order decodes are started in, so the tile under the
    // user's eye arrives first, and it decides which tiles survive the cap deterministically.
    val centerX = (region.left + region.right) / 2f
    val centerY = (region.top + region.bottom) / 2f
    keys.sortBy { key ->
      val dx = (key.column * span + span / 2f) - centerX
      val dy = (key.row * span + span / 2f) - centerY
      dx * dx + dy * dy
    }

    return TilePlan(
      sampleSize = sampleSize,
      keys = if (keys.size <= maxTiles) keys else keys.subList(0, maxTiles).toList(),
    )
  }
}
