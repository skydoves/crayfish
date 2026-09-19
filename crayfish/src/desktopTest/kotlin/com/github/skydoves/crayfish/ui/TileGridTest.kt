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
import com.github.skydoves.crayfish.decode.SampleSize.sampledBy
import com.github.skydoves.crayfish.geometry.CoordinateSpace
import com.github.skydoves.crayfish.geometry.CropTransform
import com.github.skydoves.crayfish.geometry.FloatPoint
import com.github.skydoves.crayfish.geometry.FloatRect
import com.github.skydoves.crayfish.geometry.FloatSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the preview asks the decoder for, at a range of zooms.
 *
 * Two things have to be true at once and they pull in opposite directions: the tiles must cover
 * the crop frame (a gap is a visibly blurry stripe where the base layer shows through) and they
 * must not cover the whole image when the user has zoomed into a corner of it, which is the entire
 * reason the tile layer exists. A planner that satisfied only the first would be a full decode
 * with extra steps.
 */
class TileGridTest {

  private val imageSize = ImageSize(12_000, 9_000)
  private val viewport = FloatSize(1080f, 1920f)
  private val maxTiles = TileGrid.maxTiles(TileCache.DEFAULT_MAX_BYTE_COUNT)

  /** The crop frame the cropper opens on, in viewport pixels. */
  private val cropRect: FloatRect = RealCropState.DEFAULT_NORMALIZED_CROP.let { normalized ->
    FloatRect(
      left = viewport.width * normalized.left,
      top = viewport.height * normalized.top,
      right = viewport.width * normalized.right,
      bottom = viewport.height * normalized.bottom,
    )
  }

  private fun space(transform: CropTransform) =
    CoordinateSpace.fitting(imageSize, viewport, transform)

  private fun TilePlan.regions(): List<ImageRegion> = keys.mapNotNull { it.region(imageSize) }

  private fun List<ImageRegion>.bounds(): ImageRegion = ImageRegion(
    left = minOf { it.left },
    top = minOf { it.top },
    right = maxOf { it.right },
    bottom = maxOf { it.bottom },
  )

  private fun List<ImageRegion>.area(): Long = sumOf { it.width.toLong() * it.height.toLong() }

  @Test
  fun everyPlanCoversTheCropFrameAndWastesNothingOutsideIt() {
    val transforms = listOf(
      CropTransform.Identity,
      CropTransform(scale = 2f),
      CropTransform(scale = 8f, offset = FloatPoint(-220f, 140f)),
      CropTransform(scale = 4f, rotationDegrees = 31f),
      CropTransform(scale = 6f, rotationDegrees = -90f, flipHorizontal = true),
      CropTransform(scale = 24f, offset = FloatPoint(400f, -300f)),
    )

    for (transform in transforms) {
      val space = space(transform)
      val wanted = assertNotNull(
        space.toImageRegion(cropRect),
        "$transform put the crop frame off the image entirely",
      )
      val plan = TileGrid.plan(space, cropRect, maxTiles)
      val regions = plan.regions()

      assertTrue(regions.isNotEmpty(), "$transform planned no tiles for an on-image crop frame")
      assertEquals(
        plan.keys.size,
        plan.keys.toSet().size,
        "$transform planned the same tile twice",
      )
      assertTrue(
        plan.keys.size <= maxTiles,
        "$transform planned ${plan.keys.size} tiles, past the $maxTiles the cache can hold",
      )

      // Coverage: the grid is a partition of the plane into cells, so a bounding box that contains
      // the wanted region, with every cell of that box present, is complete coverage.
      val bounds = regions.bounds()
      assertTrue(
        bounds.left <= wanted.left && bounds.top <= wanted.top &&
          bounds.right >= wanted.right && bounds.bottom >= wanted.bottom,
        "$transform left $wanted uncovered; the tiles only span $bounds",
      )

      // No waste: every tile asked for touches the crop frame. This is the half that stops a
      // "covering" planner from simply requesting the whole image.
      assertTrue(
        regions.all { it.intersect(wanted) != null },
        "$transform planned a tile that does not touch the crop frame",
      )

      // And every tile is inside the image, because a region decoder is entitled to refuse one
      // that is not.
      assertTrue(
        regions.all { it.intersect(ImageRegion.of(imageSize)) == it },
        "$transform planned a tile that runs off the image",
      )
    }
  }

  @Test
  fun aZoomedInPlanDoesNotCoverTheWholeImage() {
    val wholeImage = imageSize.pixelCount

    for (scale in listOf(6f, 12f, 24f, 48f)) {
      val space = space(CropTransform(scale = scale))
      val plan = TileGrid.plan(space, cropRect, maxTiles)
      val covered = plan.regions().area()

      assertTrue(
        covered < wholeImage / 8,
        "at ${scale}x the plan covers $covered of $wholeImage source pixels, and the tile " +
          "layer is " +
          "decoding the whole image, which is the thing it exists to avoid",
      )
    }

    // The control: zoomed out, the crop frame really does span most of the image, so the
    // assertion above is about the zoom and not about the planner always being small.
    val wideOpen = TileGrid.plan(space(CropTransform.Identity), cropRect, maxTiles)
    assertTrue(
      wideOpen.regions().area() > wholeImage / 2,
      "with no zoom the crop frame spans most of the image and the plan should say so",
    )
  }

  @Test
  fun zoomingInAsksForMoreDetail() {
    val sampleSizes = listOf(1f, 2f, 4f, 8f, 16f, 32f).map { scale ->
      TileGrid.plan(space(CropTransform(scale = scale)), cropRect, maxTiles).sampleSize
    }

    assertTrue(
      sampleSizes.zipWithNext().all { (coarser, finer) -> finer <= coarser },
      "sample size must not rise as the image is zoomed into: $sampleSizes",
    )
    assertTrue(sampleSizes.first() > sampleSizes.last(), "zoom changed nothing: $sampleSizes")
    assertEquals(1, sampleSizes.last(), "at 32x the tiles should be full resolution")
  }

  /**
   * Every tile the planner asks for has to be one the decoder would be allowed to hand back.
   *
   * `SampleSize.forDecode` admits the crop frame's whole region under the display budget; a single
   * tile is a piece of that, so it is admitted too, but the arithmetic is worth pinning, because
   * a tile over `maxDimension` is a texture upload that fails on half the devices in the world.
   */
  @Test
  fun everyPlannedTileFitsTheDisplayBudget() {
    val budget = DecodeBudget.ForDisplay
    val transforms = listOf(
      CropTransform.Identity,
      CropTransform(scale = 3f, rotationDegrees = 44f),
      CropTransform(scale = 20f, offset = FloatPoint(-500f, 200f)),
    )

    for (transform in transforms) {
      val plan = TileGrid.plan(space(transform), cropRect, maxTiles)
      val edge = TileGrid.TILE_EDGE_PIXELS
      for (region in plan.regions()) {
        val decoded = region.size.sampledBy(plan.sampleSize)
        assertTrue(
          decoded.width <= edge && decoded.height <= edge,
          "$transform planned a tile decoding to $decoded, past one tile's ${edge}px edge",
        )
        assertTrue(decoded.argb8888ByteCount <= budget.maxByteCount)
        assertTrue(decoded.width <= budget.maxDimension && decoded.height <= budget.maxDimension)
      }

      val total = plan.regions().sumOf { it.size.sampledBy(plan.sampleSize).argb8888ByteCount }
      assertTrue(
        total <= TileCache.DEFAULT_MAX_BYTE_COUNT,
        "$transform plans $total bytes of tiles against a ${TileCache.DEFAULT_MAX_BYTE_COUNT} " +
          "budget; the cache would spend the frame evicting tiles it is about to be asked for",
      )
    }
  }

  @Test
  fun aDegenerateRequestPlansNothing() {
    assertEquals(
      TilePlan.Empty,
      TileGrid.plan(CoordinateSpace.fitting(ImageSize.Zero, viewport), cropRect, maxTiles),
    )
    assertEquals(
      TilePlan.Empty,
      TileGrid.plan(space(CropTransform.Identity), FloatRect.Zero, maxTiles),
    )
    assertEquals(
      TilePlan.Empty,
      TileGrid.plan(space(CropTransform.Identity), cropRect, maxTiles = 0),
    )
    // A crop frame dragged clean off a zoomed-in image maps to no pixels at all.
    val offImage = space(CropTransform(scale = 40f, offset = FloatPoint(-90_000f, 0f)))
    assertEquals(TilePlan.Empty, TileGrid.plan(offImage, cropRect, maxTiles))
  }

  /** The tile nearest the middle of the crop frame is planned first, so it decodes first. */
  @Test
  fun tilesAreOrderedFromTheCentreOfTheCropFrameOutwards() {
    val space = space(CropTransform(scale = 3f))
    val wanted = assertNotNull(space.toImageRegion(cropRect))
    val plan = TileGrid.plan(space, cropRect, maxTiles)
    val centerX = (wanted.left + wanted.right) / 2f
    val centerY = (wanted.top + wanted.bottom) / 2f
    val span = TileGrid.TILE_EDGE_PIXELS * plan.sampleSize

    val distances = plan.keys.map { key ->
      val dx = (key.column * span + span / 2f) - centerX
      val dy = (key.row * span + span / 2f) - centerY
      dx * dx + dy * dy
    }

    assertTrue(plan.keys.size > 1, "this fixture should need more than one tile")
    assertTrue(
      distances.zipWithNext().all { (nearer, further) -> nearer <= further },
      "tiles were not ordered centre-first: $distances",
    )
  }
}
