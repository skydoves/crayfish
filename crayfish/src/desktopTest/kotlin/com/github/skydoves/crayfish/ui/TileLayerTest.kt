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

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Matrix
import com.github.skydoves.crayfish.decode.ImageRegion
import com.github.skydoves.crayfish.decode.ImageSize
import com.github.skydoves.crayfish.decode.RegionDecoder
import com.github.skydoves.crayfish.decode.TestImages
import com.github.skydoves.crayfish.decode.createRegionDecoder
import com.github.skydoves.crayfish.geometry.CoordinateSpace
import com.github.skydoves.crayfish.geometry.CropTransform
import com.github.skydoves.crayfish.geometry.FloatPoint
import com.github.skydoves.crayfish.geometry.FloatSize
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The draw side of eviction.
 *
 * A tile can be closed between the frame that planned it and the frame that draws it, which is
 * what a bounded cache under a pan *is*, and `PlatformImage.toImageBitmap()` answers `null` for a
 * closed image rather than throwing. On Skia the alternative is not an exception but a SIGABRT
 * that takes the whole process with it, so "handled, not crashed" has to be checked rather than
 * assumed.
 */
class TileLayerTest {

  private fun <T> withDecoder(block: suspend (RegionDecoder) -> T): T = runBlocking {
    val decoder = assertNotNull(
      createRegionDecoder(TestImages.source(TestImages.pngBytes())),
      "the fixture decoder could not be opened",
    )
    decoder.use { block(it) }
  }

  private suspend fun tile(decoder: RegionDecoder, region: ImageRegion): PreviewTile {
    val decoded = assertNotNull(decoder.decodeRegion(region, 1), "region $region did not decode")
    return assertNotNull(PreviewTile.of(decoded), "the region could not be handed to Compose")
  }

  @Test
  fun aClosedTileHandsOutNoPixelsAndIsSkippedRatherThanDrawn() = withDecoder { decoder ->
    val live = tile(decoder, ImageRegion(0, 0, 32, 24))
    val doomed = tile(decoder, ImageRegion(32, 0, 64, 24))

    assertNotNull(doomed.imageBitmap, "a live tile must have pixels before it is closed")
    doomed.close()
    assertNull(
      doomed.imageBitmap,
      "a closed tile handed out a bitmap over memory it has already freed",
    )

    val drawn = mutableListOf<ImageRegion>()
    drawTiles(base = null, tiles = listOf(live, doomed)) { _, region -> drawn += region }

    assertEquals(listOf(live.region), drawn, "the closed tile was drawn, or the live one was not")
    live.close()
  }

  /** The base layer is on the same path: closed means skipped, not a hole in the frame. */
  @Test
  fun aClosedBaseLayerIsSkippedToo() = withDecoder { decoder ->
    val base = tile(decoder, ImageRegion.of(ImageSize(TestImages.WIDTH, TestImages.HEIGHT)))
    base.close()

    var calls = 0
    drawTiles(base = base, tiles = emptyList()) { _, _ -> calls++ }

    assertEquals(0, calls)
  }

  /** Closing twice must be a no-op; the cache can be cleared after a store has already closed. */
  @Test
  fun closingATileTwiceIsHarmless() = withDecoder { decoder ->
    val subject = tile(decoder, ImageRegion(0, 0, 16, 16))
    subject.close()
    subject.close()
    assertNull(subject.imageBitmap)
  }

  /** A tile costs what came back, not what was asked for. */
  @Test
  fun aTileReportsTheBytesItActuallyHolds() = withDecoder { decoder ->
    val subject = tile(decoder, ImageRegion(0, 0, 32, 24))
    assertEquals(ImageSize(32, 24).argb8888ByteCount, subject.byteCount)
    assertEquals(ImageRegion(0, 0, 32, 24), subject.region)
    subject.close()
    assertEquals(
      ImageSize(32, 24).argb8888ByteCount,
      subject.byteCount,
      "the cache subtracts this after the close, so it must not change when the tile is released",
    )
  }

  /**
   * The canvas matrix and [CoordinateSpace] must agree, or every tile lands somewhere plausible
   * and wrong.
   *
   * This is the assertion that lets the draw path skip re-deriving the transform order. If the
   * matrix is sampled correctly from the space, then the pixels land exactly where the crop
   * pipeline's rectangles say they should, at every rotation and mirroring, for free.
   */
  @Test
  fun theCanvasMatrixAgreesWithTheCoordinateSpaceAtEveryTransform() {
    val imageSize = ImageSize(12_000, 9_000)
    val viewport = FloatSize(1080f, 1920f)
    val transforms = listOf(
      CropTransform.Identity,
      CropTransform(scale = 3.5f),
      CropTransform(scale = 2f, offset = FloatPoint(-140f, 260f)),
      CropTransform(scale = 1.75f, rotationDegrees = 37f),
      CropTransform(scale = 2.25f, rotationDegrees = -113f, offset = FloatPoint(90f, -40f)),
      CropTransform(scale = 1.5f, flipHorizontal = true),
      CropTransform(scale = 1.5f, flipVertical = true, rotationDegrees = 90f),
      CropTransform(
        scale = 4f,
        offset = FloatPoint(33f, -71f),
        rotationDegrees = 12.5f,
        flipHorizontal = true,
        flipVertical = true,
      ),
    )
    val probes = listOf(
      FloatPoint(0f, 0f),
      FloatPoint(imageSize.width.toFloat(), 0f),
      FloatPoint(0f, imageSize.height.toFloat()),
      FloatPoint(imageSize.width.toFloat(), imageSize.height.toFloat()),
      FloatPoint(5_137f, 2_048f),
    )
    val matrix = Matrix()

    for (transform in transforms) {
      val space = CoordinateSpace.fitting(imageSize, viewport, transform)
      space.writeInto(matrix)
      for (probe in probes) {
        val expected = space.imageToViewport(probe)
        val actual = matrix.map(Offset(probe.x, probe.y))
        assertTrue(
          abs(expected.x - actual.x) <= TOLERANCE && abs(expected.y - actual.y) <= TOLERANCE,
          "$transform maps $probe to $expected but the canvas matrix would put it at $actual",
        )
      }
    }
  }

  /** A degenerate space must leave the identity behind rather than a matrix full of NaN. */
  @Test
  fun aDegenerateSpaceProducesTheIdentityMatrix() {
    val matrix = Matrix()
    matrix[0, 0] = 17f
    val space = CoordinateSpace.fitting(ImageSize.Zero, FloatSize.Zero)

    space.writeInto(matrix)

    val mapped = matrix.map(Offset(9f, 4f))
    assertTrue(
      abs(mapped.x - 9f) <= TOLERANCE && abs(mapped.y - 4f) <= TOLERANCE,
      "a degenerate space left $mapped behind instead of the identity",
    )
  }

  private companion object {
    /** A tenth of a viewport pixel: far below anything that could move a tile visibly. */
    const val TOLERANCE = 0.1f
  }
}
