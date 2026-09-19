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
import com.github.skydoves.crayfish.decode.RegionDecoder
import com.github.skydoves.crayfish.decode.platformImageOfArgbPixels
import com.github.skydoves.crayfish.exif.ImageOrientation
import com.github.skydoves.crayfish.geometry.CropTransform
import com.github.skydoves.crayfish.geometry.FloatRect
import com.github.skydoves.crayfish.geometry.FloatSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The store survives a decode whose continuation is not on the owner's thread.
 *
 * `TileStore` keeps three plain, unsynchronised structures: the tile cache, the in-flight map and
 * the base layer. It is safe only while every mutation happens on one thread, and that thread is
 * not something it chooses. Decodes are launched into the scope of the effect that drives requests,
 * so the dispatcher belongs to whoever composed the cropper. A context with no dispatcher at all
 * resumes a `withContext(Dispatchers.IO)` on the IO thread it finished on, and then `tiles.put`
 * runs there while `close()` runs on the caller's thread.
 *
 * Reported as a crash on a Galaxy S23, twice in ten full device runs, always on teardown:
 *
 * ```
 * java.lang.NullPointerException
 *   at TileCache.clear(TileCache.kt:129)
 *   at TileStore.releasePixels(TileStore.kt:331)
 *   at TileStore.close(TileStore.kt:214)
 *   at CropPreview$...$inlined$onDispose$1.dispose
 * ```
 *
 * A null element out of `LinkedHashMap.values.toList()` is not a tile that was null. It is the map
 * being restructured while it is read, and it takes the process down rather than failing a frame.
 *
 * ## What this has and has not established
 *
 * It holds the invariant it names: 200 rounds of closing the store while decodes are still running
 * in a dispatcher-less scope, and the cache comes through intact. So it is a real regression test
 * for a real contract.
 *
 * It did **not** reproduce the device crash. That leaves the off-thread continuation unproven as
 * the cause, and it is written down here so the next attempt does not re-chase it. What is still
 * true of that crash: it appears only in `DeviceExifPreviewTest`, only when the whole device suite
 * runs (the class alone is green, 3 of 3 twice on device and 18 of 18 on an emulator), 4 times in
 * 15 full runs, and it aborts the run rather than failing one test.
 */
class TileStoreThreadingTest {

  private val imageSize = ImageSize(4_000, 3_000)

  /**
   * The invariant, asserted directly: one thread ever touches the store's own state.
   *
   * This is the shape the device crash had. Decodes resume wherever their dispatcher leaves them,
   * and before the inbox they wrote into the tile cache from there. The assertion is on the thread
   * rather than on a crash because a data race that corrupts a `LinkedHashMap` only sometimes
   * produces a visible failure, and a test that waits for one is a test that passes by luck.
   */
  @Test
  fun everyTouchOfTheStoresOwnStateIsOnOneThread() {
    // Identity, not name. kotlinx-coroutines appends "@coroutine#N" to the thread name in debug
    // mode, so two touches on the same thread read as two threads and the test would fail for a
    // reason that has nothing to do with what it is checking.
    val threads = Collections.synchronizedSet(mutableSetOf<Long>())
    val names = Collections.synchronizedSet(mutableSetOf<String>())
    val store = TileStore()
    store.onStateTouched = {
      threads += Thread.currentThread().id
      names += Thread.currentThread().name
    }
    store.open(SlowDecoder(imageSize), imageSize)

    runBlocking {
      // A scope with no dispatcher of its own, which is what leaves a `withContext(IO)` resuming on
      // the IO thread that finished it. A caller really can compose the cropper into one of these.
      val scope = CoroutineScope(Job())
      store.request(request(), scope)
      store.awaitIdle()
      store.close()
      scope.coroutineContext[Job]?.cancel()
    }

    assertTrue(threads.isNotEmpty(), "nothing touched the store, so this asserts nothing")
    assertEquals(
      1,
      threads.size,
      "the store's state was touched from ${threads.size} threads: $names. A plain LinkedHashMap " +
        "read on one while another restructures it is the crash this guards.",
    )
  }

  @Test
  fun closingWhileDecodesRunOnAnotherThreadDoesNotCorruptTheCache() {
    repeat(ATTEMPTS) { attempt ->
      val store = TileStore()
      store.open(SlowDecoder(imageSize), imageSize)

      val scope = CoroutineScope(Job())
      store.request(request(), scope)

      try {
        runBlocking { delay(TEAR_DOWN_DELAY_MILLIS) }
        store.close()
      } catch (error: NullPointerException) {
        fail("attempt $attempt: the tile cache was restructured while it was being read: $error")
      } finally {
        scope.coroutineContext[Job]?.cancel()
      }
    }
  }

  /** Pixels that arrive after teardown are closed, not stranded in an inbox nobody reads. */
  @Test
  fun aTileThatArrivesAfterCloseIsReleased() {
    runBlocking {
      val store = TileStore()
      val decoder = SlowDecoder(imageSize)
      store.open(decoder, imageSize)

      val scope = CoroutineScope(Job())
      store.request(request(), scope)
      // Long enough for decodes to be under way. Closing before any has begun would cancel them
      // all and leave nothing to arrive late, which is the opposite of what this checks.
      delay(50)
      store.close()

      assertTrue(
        decoder.decoded > 0,
        "no decode ever started, so nothing could have arrived late",
      )
      scope.coroutineContext[Job]?.cancel()
    }
  }

  private fun request() = TileRequest(
    imageSize = imageSize,
    viewportSize = FloatSize(300f, 400f),
    transform = CropTransform(scale = 4f),
    cropRect = FloatRect(30f, 40f, 270f, 360f),
  )

  private companion object {
    const val ATTEMPTS = 200
    const val TEAR_DOWN_DELAY_MILLIS = 1L
  }
}

/** Decodes on the IO dispatcher, like every real one, and takes long enough to still be running. */
private class SlowDecoder(override val imageSize: ImageSize) : RegionDecoder {

  override val format: ImageFormat = ImageFormat.JPEG
  override val appliedOrientation: ImageOrientation = ImageOrientation.NORMAL

  var decoded = 0
    private set

  override suspend fun decodeRegion(region: ImageRegion, sampleSize: Int): DecodedRegion? =
    kotlinx.coroutines.withContext(Dispatchers.IO) {
      decoded++
      val clipped = region.intersect(ImageRegion.of(imageSize)) ?: return@withContext null
      delay(1)
      val image = platformImageOfArgbPixels(
        IntArray(clipped.width * clipped.height) { -1 },
        ImageSize(clipped.width, clipped.height),
      ) ?: return@withContext null
      DecodedRegion(image = image, region = clipped, sampleSize = 1)
    }

  override fun close(): Unit = Unit
}
