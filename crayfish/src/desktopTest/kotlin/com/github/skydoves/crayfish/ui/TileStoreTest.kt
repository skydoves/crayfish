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

import com.github.skydoves.crayfish.decode.CropSource
import com.github.skydoves.crayfish.decode.DecodedRegion
import com.github.skydoves.crayfish.decode.ImageFormat
import com.github.skydoves.crayfish.decode.ImageRegion
import com.github.skydoves.crayfish.decode.ImageSize
import com.github.skydoves.crayfish.decode.RegionDecoder
import com.github.skydoves.crayfish.decode.createRegionDecoder
import com.github.skydoves.crayfish.e2e.Fixtures
import com.github.skydoves.crayfish.e2e.MemoryProbe
import com.github.skydoves.crayfish.exif.ImageOrientation
import com.github.skydoves.crayfish.geometry.CropTransform
import com.github.skydoves.crayfish.geometry.FloatPoint
import com.github.skydoves.crayfish.geometry.FloatRect
import com.github.skydoves.crayfish.geometry.FloatSize
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A decoder that starts every region and finishes none, so cancellation can be observed.
 *
 * The real decoder returns too quickly to catch a stale decode in flight, and slowing it down with
 * a delay would make the test a race. A gate makes "the user panned before this tile came back"
 * the ordinary case instead of a rare one.
 */
private class GatedDecoder(override val imageSize: ImageSize) : RegionDecoder {

  val requested = mutableListOf<ImageRegion>()
  val cancelled = mutableListOf<ImageRegion>()

  private val gate = CompletableDeferred<Unit>()

  override val format: ImageFormat = ImageFormat.JPEG
  override val appliedOrientation: ImageOrientation = ImageOrientation.NORMAL

  override suspend fun decodeRegion(region: ImageRegion, sampleSize: Int): DecodedRegion? {
    requested += region
    try {
      gate.await()
    } catch (error: CancellationException) {
      cancelled += region
      throw error
    }
    return null
  }

  override fun close(): Unit = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class TileStoreTest {

  private val imageSize = ImageSize(12_000, 9_000)
  private val viewport = FloatSize(1080f, 1920f)
  private val cropRect = FloatRect(108f, 192f, 972f, 1728f)

  private fun request(transform: CropTransform) = TileRequest(
    imageSize = imageSize,
    viewportSize = viewport,
    transform = transform,
    cropRect = cropRect,
  )

  private fun TileRequest.plannedRegions(): List<ImageRegion> = TileGrid
    .plan(space, cropRect, TileGrid.maxTiles(TileCache.DEFAULT_MAX_BYTE_COUNT))
    .keys
    .mapNotNull { it.region(imageSize) }

  /**
   * A tile the user has scrolled away from is not merely wasted work.
   *
   * A region decoder serialises its reads, so a stale tile still in the queue is *in front of* one
   * that is on screen, and if it did arrive it would be pure eviction pressure on the tiles that
   * are. Cancelling is the only behaviour that is not actively harmful.
   */
  @Test
  fun decodesForRegionsThatHaveScrolledAwayAreCancelled() = runTest {
    val decoder = GatedDecoder(imageSize)
    val store = TileStore()
    store.open(decoder, imageSize)

    val here = request(CropTransform(scale = 12f, offset = FloatPoint(2_400f, 1_800f)))
    val there = request(CropTransform(scale = 12f, offset = FloatPoint(-2_400f, -1_800f)))
    val stale = here.plannedRegions() - there.plannedRegions().toSet()
    val kept = there.plannedRegions().intersect(here.plannedRegions().toSet())

    assertTrue(stale.isNotEmpty(), "the two viewpoints must not overlap, or nothing is stale")

    store.request(here, backgroundScope)
    runCurrent()
    assertTrue(
      decoder.requested.containsAll(stale),
      "the first viewpoint's tiles were never started: ${decoder.requested.size} requests",
    )

    store.request(there, backgroundScope)
    runCurrent()

    assertTrue(
      decoder.cancelled.containsAll(stale),
      "${stale.size} tiles scrolled away and only ${decoder.cancelled.size} decodes were " +
        "cancelled; a decoder that serialises its reads is now working through them",
    )
    assertTrue(
      kept.none { it in decoder.cancelled },
      "a tile that is still on screen had its decode cancelled",
    )

    store.close()
  }

  /** Closing the store must stop everything it started, or the decodes outlive the composable. */
  @Test
  fun closingTheStoreCancelsEveryDecodeInFlight() = runTest {
    val decoder = GatedDecoder(imageSize)
    val store = TileStore()
    store.open(decoder, imageSize)
    val geometry = request(CropTransform(scale = 10f))

    store.request(geometry, backgroundScope)
    runCurrent()
    assertTrue(decoder.requested.isNotEmpty())

    store.close()
    runCurrent()

    assertTrue(
      decoder.cancelled.containsAll(geometry.plannedRegions()),
      "decodes survived the store that started them",
    )
    assertEquals(0, store.tiles.size)
    assertEquals(0L, store.tiles.byteCount)
  }

  /**
   * The whole claim, against the fixture that breaks other croppers: a 108MP source previewed at a
   * phone-sized viewport, zoomed in, costs a fraction of what one full decode of it would.
   *
   * 412MiB is what decoding this image the obvious way costs ([MemoryProbe.BUDGET_BYTES] is 384MB
   * precisely so that it cannot be done), and the control that a naive decode really does exhaust
   * that is already established by `MemoryHarnessTest`.
   */
  @Test
  fun previewingA108MegapixelSourceStaysInsideTheBudgetAndTheCache() {
    val source = CropSource.FilePath(Fixtures.sensor108mp.absolutePath)
    val store = TileStore()
    val decoder = runBlocking { assertNotNull(createRegionDecoder(source), "fixture unreadable") }
    assertEquals(imageSize, decoder.imageSize, "the fixture is not the size it should be")

    val (_, reading) = MemoryProbe.measure {
      runBlocking {
        store.open(decoder, decoder.imageSize)
        store.request(request(CropTransform(scale = 8f)), this)
        store.awaitIdle()
      }
    }

    try {
      assertNotNull(store.baseLayer, "the base layer never arrived, so nothing was measured")
      assertTrue(store.tiles.size > 0, "no tiles were decoded, so nothing was measured")
      assertTrue(
        store.tiles.byteCount <= store.tiles.maxByteCount,
        "the cache holds ${store.tiles.byteCount} bytes against a ${store.tiles.maxByteCount} " +
          "budget",
      )
      assertTrue(
        store.tiles.byteCount + assertNotNull(store.baseLayer).byteCount <
          ImageSize(12_000, 9_000).argb8888ByteCount,
        "the tiled preview is holding as much as a full decode would have",
      )
      assertTrue(
        reading.allocatedBytes < MemoryProbe.BUDGET_BYTES,
        "previewing the 108MP fixture allocated $reading, over the " +
          "${MemoryProbe.BUDGET_BYTES / 1024 / 1024}MB budget",
      )
      println(
        "[preview] 108MP tiled: $reading, base ${assertNotNull(store.baseLayer).region}, " +
          "${store.tiles.size} tiles / ${store.tiles.byteCount / 1024 / 1024}MB",
      )
    } finally {
      store.close()
      decoder.close()
    }
  }

  /**
   * And it stays inside the cache under movement, which is when a bounded cache is actually tested.
   *
   * A pan across a zoomed-in 108MP source asks for far more tiles than fit; the cache has to shed
   * them as it goes, and every byte it sheds has to come back.
   */
  @Test
  fun panningAcrossA108MegapixelSourceNeverExceedsTheCacheBudget() = runBlocking {
    val source = CropSource.FilePath(Fixtures.sensor108mp.absolutePath)
    val decoder = assertNotNull(createRegionDecoder(source))
    // Eight tiles' worth, so the pan overflows it in a handful of real decodes rather than the
    // sixty-four the shipping budget would take. The policy under test is the same either way.
    val store = TileStore(TileCache(8 * TileGrid.TILE_BYTE_COUNT))

    try {
      store.open(decoder, decoder.imageSize)
      for (step in -2..2) {
        val transform = CropTransform(
          scale = 24f,
          offset = FloatPoint(step * 1_400f, step * 968f),
        )
        store.request(request(transform), this)
        store.awaitIdle()
        assertTrue(
          store.tiles.byteCount <= store.tiles.maxByteCount,
          "after step $step the cache held ${store.tiles.byteCount} bytes, over the " +
            "${store.tiles.maxByteCount} budget",
        )
      }

      assertTrue(
        store.tiles.evictionCount > 0,
        "the pan never overflowed the cache, so eviction was not exercised at all",
      )
      println(
        "[preview] 108MP pan: ${store.tiles.size} resident, " +
          "${store.tiles.evictionCount} evicted",
      )
    } finally {
      store.close()
      decoder.close()
    }
  }
}
