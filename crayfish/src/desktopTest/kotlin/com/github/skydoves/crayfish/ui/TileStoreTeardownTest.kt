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

import com.github.skydoves.crayfish.decode.DecodedRegion
import com.github.skydoves.crayfish.decode.ImageFormat
import com.github.skydoves.crayfish.decode.ImageRegion
import com.github.skydoves.crayfish.decode.ImageSize
import com.github.skydoves.crayfish.decode.PlatformImage
import com.github.skydoves.crayfish.decode.RegionDecoder
import com.github.skydoves.crayfish.decode.platformImageOfArgbPixels
import com.github.skydoves.crayfish.exif.ImageOrientation
import com.github.skydoves.crayfish.geometry.CropTransform
import com.github.skydoves.crayfish.geometry.FloatRect
import com.github.skydoves.crayfish.geometry.FloatSize
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What happens to pixels that arrive after the store that asked for them is gone.
 *
 * The decode of a preview tile is tens of milliseconds of work on another dispatcher, and the
 * composable that started it can leave at any point during that window: a back press, a pager
 * swipe, a dialog dismissed. If the tile that comes back is simply dropped on the floor, its native
 * bitmap is dropped with it: on Skia there is no finaliser to catch it, and on Android the pixels
 * sit outside the Java heap where no GC pressure will ever be felt. Repeat that once per dismissal
 * and the gallery screen that opens the cropper runs the device out of memory.
 *
 * [TileStore] guards this by re-checking `closed` after every decode returns and closing the tile
 * itself. Those guards are the last two branches in the class and nothing had ever executed them,
 * because a decoder that is cancelled at its suspension point never gets far enough to produce a
 * tile at all. The decoders here close the store from *inside* the decode, which is the race made
 * deterministic rather than simulated.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TileStoreTeardownTest {

  private val imageSize = ImageSize(4_000, 3_000)
  private val viewport = FloatSize(1080f, 1920f)
  private val cropRect = FloatRect(108f, 192f, 972f, 1728f)

  private fun request(scale: Float = 10f) = TileRequest(
    imageSize = imageSize,
    viewportSize = viewport,
    transform = CropTransform(scale = scale),
    cropRect = cropRect,
  )

  // -----------------------------------------------------------------------------------------
  // The two race guards
  // -----------------------------------------------------------------------------------------

  @Test
  fun aTileThatFinishesDecodingAfterTheStoreClosedIsFreedNotRetained() = runTest {
    val store = TileStore()
    // Closes the store part way through the batch, so the first tiles come back to a live store
    // and the rest to a closed one. Both halves have to end with no pixels held.
    val decoder = RacingDecoder(imageSize, closeAfter = 1) { store.close() }
    store.open(decoder, imageSize)

    store.request(request(), backgroundScope)
    runCurrent()

    assertTrue(decoder.handedOut.size > 1, "the race never happened: only one decode ran")
    assertEquals(0, store.tiles.size, "a tile decoded after close was put into the cache")
    assertEquals(0L, store.tiles.byteCount)
    assertTrue(store.frame().tiles.isEmpty(), "a tile decoded after close is still being drawn")
    assertAllFreed(decoder.handedOut)
  }

  @Test
  fun aBaseLayerThatFinishesDecodingAfterTheStoreClosedIsFreedNotRetained() = runTest {
    val store = TileStore()
    // closeAfter = 0 closes before the very first decode returns, and the base layer is always the
    // first thing the store asks for.
    val decoder = RacingDecoder(imageSize, closeAfter = 0) { store.close() }
    store.open(decoder, imageSize)

    store.request(request(scale = 1f), backgroundScope)
    runCurrent()

    assertTrue(decoder.handedOut.isNotEmpty(), "the base layer was never decoded")
    assertNull(store.baseLayer, "the base layer decoded after close is still held")
    assertAllFreed(decoder.handedOut)
  }

  /**
   * The positive control for both tests above.
   *
   * Without it, a store that silently threw every tile away would pass them, and the guards would
   * look proven while the preview drew nothing.
   */
  @Test
  fun aTileThatFinishesWhileTheStoreIsOpenIsKeptAndDrawn() = runTest {
    val store = TileStore()
    val decoder = RacingDecoder(imageSize, closeAfter = Int.MAX_VALUE) {}
    store.open(decoder, imageSize)

    store.request(request(), backgroundScope)
    runCurrent()
    // What the draw phase does. A decode publishes its tile and bumps the revision; the frame that
    // the revision invalidates is what takes it into the cache, which is the only place the cache
    // is touched and therefore the only thread it is touched from.
    store.frame()

    assertTrue(store.tiles.size > 0, "no tile survived a store that was never closed")
    assertTrue(store.tiles.byteCount > 0L)
    assertNotNull(store.baseLayer, "the base layer was never published")
    assertTrue(
      decoder.handedOut.any { it.readArgbPixels() != null },
      "every tile was freed even though the store stayed open",
    )

    // And closing now frees them, which is the same guard on the ordinary path.
    store.close()
    assertEquals(0, store.tiles.size)
    assertAllFreed(decoder.handedOut)
  }

  // -----------------------------------------------------------------------------------------
  // The early returns around them
  // -----------------------------------------------------------------------------------------

  @Test
  fun reopeningWithTheSameSourceKeepsTheTilesAlreadyDecoded() = runTest {
    val store = TileStore()
    val decoder = RacingDecoder(imageSize, closeAfter = Int.MAX_VALUE) {}
    store.open(decoder, imageSize)
    store.request(request(), backgroundScope)
    runCurrent()
    store.frame()
    val decodedCount = store.tiles.size
    assertTrue(decodedCount > 0)

    store.open(decoder, imageSize)

    assertEquals(
      decodedCount,
      store.tiles.size,
      "re-opening the same decoder at the same size threw away tiles it could have kept",
    )

    // A different size is a different image, and those tiles no longer describe anything.
    store.open(decoder, ImageSize(2_000, 1_500))

    assertEquals(0, store.tiles.size, "tiles from the previous image survived a resize")
    assertAllFreed(decoder.handedOut)
    store.close()
  }

  @Test
  fun aClosedStoreAsksForNothing() = runTest {
    val store = TileStore()
    val decoder = RacingDecoder(imageSize, closeAfter = Int.MAX_VALUE) {}
    store.open(decoder, imageSize)
    store.close()

    store.request(request(), backgroundScope)
    runCurrent()

    assertTrue(
      decoder.requested.isEmpty(),
      "a closed store started ${decoder.requested.size} decodes",
    )
    // And it cannot be brought back to life by re-opening it.
    store.open(decoder, imageSize)
    store.request(request(), backgroundScope)
    runCurrent()
    assertTrue(decoder.requested.isEmpty(), "a closed store was reopened")
  }

  @Test
  fun noBaseLayerIsStartedBeforeTheViewportHasBeenMeasured() = runTest {
    val store = TileStore()
    val decoder = RacingDecoder(imageSize, closeAfter = Int.MAX_VALUE) {}
    store.open(decoder, imageSize)

    store.request(
      TileRequest(imageSize, FloatSize.Zero, CropTransform(), FloatRect.Zero),
      backgroundScope,
    )
    runCurrent()

    assertNull(store.baseLayer)
    assertTrue(decoder.requested.isEmpty(), "a zero-sized viewport still asked for pixels")
    store.close()
  }

  @Test
  fun noBaseLayerIsStartedForAnImageWithNoPixels() = runTest {
    val store = TileStore()
    val decoder = RacingDecoder(ImageSize.Zero, closeAfter = Int.MAX_VALUE) {}
    store.open(decoder, ImageSize.Zero)

    store.request(
      TileRequest(ImageSize.Zero, viewport, CropTransform(), cropRect),
      backgroundScope,
    )
    runCurrent()

    assertNull(store.baseLayer)
    assertTrue(decoder.requested.isEmpty(), "an empty image still asked for pixels")
    store.close()
  }

  private fun assertAllFreed(images: List<PlatformImage>) {
    val alive = images.count { it.readArgbPixels() != null }
    assertEquals(
      0,
      alive,
      "$alive of ${images.size} decoded images are still holding pixels; each one is " +
        "${images.firstOrNull()?.let { it.width * it.height * 4 } ?: 0} bytes of native memory",
    )
  }
}

/**
 * A decoder that hands back real pixels and lets the test close the store mid-flight.
 *
 * [closeAfter] is the number of decodes allowed to complete before [onRace] runs, so a test can
 * place the close before the first tile, between two of them, or never. Every image it produces is
 * kept in [handedOut] so the test can ask afterwards whether the pixels were freed: a closed
 * [PlatformImage] answers `null` to `readArgbPixels`, which is the only observable there is.
 */
private class RacingDecoder(
  override val imageSize: ImageSize,
  private val closeAfter: Int,
  private val onRace: () -> Unit,
) : RegionDecoder {

  val requested = mutableListOf<ImageRegion>()
  val handedOut = mutableListOf<PlatformImage>()

  private var completed = 0

  override val format: ImageFormat = ImageFormat.JPEG
  override val appliedOrientation: ImageOrientation = ImageOrientation.NORMAL

  override suspend fun decodeRegion(region: ImageRegion, sampleSize: Int): DecodedRegion? {
    requested += region
    // Small on purpose: the test is about ownership, not about memory pressure, and a realistic
    // tile here would make the suite allocate hundreds of megabytes for no extra assurance.
    val size = ImageSize(8, 8)
    val image =
      platformImageOfArgbPixels(IntArray(size.width * size.height) { 0xFF336699.toInt() }, size)
        ?: return null
    handedOut += image

    if (completed == closeAfter) onRace()
    completed++

    return DecodedRegion(image = image, region = region, sampleSize = sampleSize)
  }

  override fun close(): Unit = Unit
}
