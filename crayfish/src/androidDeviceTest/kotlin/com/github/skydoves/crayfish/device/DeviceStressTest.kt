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
package com.github.skydoves.crayfish.device

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Debug
import com.github.skydoves.crayfish.decode.CropSource
import com.github.skydoves.crayfish.decode.DecodeBudget
import com.github.skydoves.crayfish.decode.ImageRegion
import com.github.skydoves.crayfish.decode.ImageSize
import com.github.skydoves.crayfish.decode.SampleSize
import com.github.skydoves.crayfish.decode.createRegionDecoder
import com.github.skydoves.crayfish.encode.EncodeOptions
import com.github.skydoves.crayfish.encode.EncodedFormat
import com.github.skydoves.crayfish.encode.encodeImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Breadth and repetition on the hardware, where the desktop suite has neither.
 *
 * Three things are only true here. A `Bitmap`'s pixels are native, so the Java heap cannot see a
 * leak of them. A recycled `Bitmap` raises `IllegalStateException` at the moment something reads
 * it, and nothing before that, which is how a crop that handed back a freed bitmap passed ten green
 * tests: none of them read a pixel. And the canvas has a size limit that is a property of the
 * device rather than a constant.
 *
 * So every result here is read, not just counted.
 */
class DeviceStressTest {

  private data class Source(val name: String, val fileName: String, val size: ImageSize)

  private val sources = listOf(
    Source("108MP 12000x9000", "sensor-108mp.jpg", ImageSize(12_000, 9_000)),
    Source("48MP 8000x6000", "sensor-48mp.jpg", ImageSize(8_000, 6_000)),
    Source("square 5000x5000", "square-5000.jpg", ImageSize(5_000, 5_000)),
    Source("panorama 28000x2000", "panorama-1x14.jpg", ImageSize(28_000, 2_000)),
    Source("tall 2000x28000", "tall-2x28.jpg", ImageSize(2_000, 28_000)),
    Source("sliver 1x32767", "sliver-1x32767.jpg", ImageSize(1, 32_767)),
    Source("uhd png 3840x2160", "uhd.png", ImageSize(3_840, 2_160)),
  )

  private fun fixture(name: String): File = File(FIXTURE_DIR, name).also {
    check(it.isFile && it.length() > 0) {
      "missing device fixture $it: run ./gradlew :crayfish:pushDeviceFixtures"
    }
  }

  private fun rectangles(size: ImageSize): List<Pair<String, ImageRegion>> {
    val w = size.width
    val h = size.height
    // The corners round up so they stay non-empty on a source that is one pixel wide. The centred
    // half rounds down for the same reason from the other side: rounding it up on a 1px source puts
    // its left edge past its right one, which is an inverted rectangle and correctly refused.
    val qw = (w / 4).coerceAtLeast(1)
    val qh = (h / 4).coerceAtLeast(1)
    return listOf(
      "whole image" to ImageRegion(0, 0, w, h),
      "top left corner" to ImageRegion(0, 0, qw, qh),
      "bottom right corner" to ImageRegion(w - qw, h - qh, w, h),
      "centred half" to ImageRegion(w / 4, h / 4, w - w / 4, h - h / 4),
      "single pixel at the origin" to ImageRegion(0, 0, 1, 1),
      "single pixel at the far corner" to ImageRegion(w - 1, h - 1, w, h),
      "one pixel wide, full height" to ImageRegion(w / 2, 0, w / 2 + 1, h),
      "one pixel tall, full width" to ImageRegion(0, h / 2, w, h / 2 + 1),
    )
  }

  /**
   * Every source, every rectangle, and every result both drawable and readable.
   *
   * Drawable is asked of the device rather than assumed: `Canvas.throwIfCannotDraw` is what raises
   * "trying to draw too large bitmap", and where that line sits depends on the hardware.
   */
  @Test
  fun everyRectangleOnEverySourceDecodesDrawsAndReadsOnDevice() = runBlocking {
    var examined = 0
    val declined = mutableListOf<String>()
    // The limits are a property of the device, not of the bitmap being asked about, and a Canvas
    // cannot be built over an immutable bitmap, which every decoded one here is. A 1x1 scratch
    // asks the same question without copying the pixels to do it.
    val scratch = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    val probe = Canvas(scratch)

    for (source in sources) {
      val decoder = assertNotNull(
        createRegionDecoder(CropSource.FilePath(fixture(source.fileName).absolutePath)),
        "the device could not open ${source.name}",
      )
      decoder.use {
        assertEquals(source.size, it.imageSize, "${source.name} reported the wrong size")

        for ((shape, region) in rectangles(source.size)) {
          val where = "${source.name} / $shape"
          val sampleSize = SampleSize.forDecode(region.size, VIEWPORT, DecodeBudget.ForDisplay)
          val decoded = it.decodeRegion(region, sampleSize)
          if (decoded == null) {
            declined += where
            continue
          }

          decoded.use { result ->
            examined++
            val bitmap = result.image.bitmap
            val maxWidth = probe.maximumBitmapWidth
            val maxHeight = probe.maximumBitmapHeight
            assertTrue(
              bitmap.width <= maxWidth && bitmap.height <= maxHeight,
              "$where decoded ${bitmap.width}x${bitmap.height}, past this device's maximum of " +
                "${maxWidth}x$maxHeight",
            )
            // Reading is what catches a bitmap handed back after it was freed, and a hardware
            // bitmap whose pixels the encode path cannot reach. Counting results catches neither.
            bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            assertEquals(
              result.region,
              result.region.intersect(ImageRegion.of(source.size)),
              "$where returned a region outside the source",
            )
          }
        }
      }
    }

    scratch.recycle()
    println("[device-stress] matrix: $examined decoded, ${declined.size} declined")
    assertEquals(
      sources.size * RECTANGLES_PER_SOURCE,
      examined,
      "only $examined of ${sources.size * RECTANGLES_PER_SOURCE} rectangles decoded on this " +
        "device; refused: ${declined.joinToString()}",
    )
  }

  /**
   * The user's 5000x5000, cropped over and over, with the **native** heap watched.
   *
   * `Debug.getNativeHeapAllocatedSize`, not `Runtime.totalMemory`: a `Bitmap`'s pixels have lived
   * off the Java heap since API 26, so a heap reading moves by almost nothing while a phone runs
   * out of memory. A gate on the wrong counter is a gate that cannot fail.
   */
  @Test
  fun repeatedlyCroppingTheSquareSourceDoesNotGrowTheNativeHeap() = runBlocking {
    val source = CropSource.FilePath(fixture("square-5000.jpg").absolutePath)

    repeat(WARMUP) { cropAndRead(source) }
    val before = settledNativeBytes()
    repeat(ITERATIONS) { cropAndRead(source) }
    val after = settledNativeBytes()

    val growth = after - before
    println(
      "[device-stress] $ITERATIONS crops of 5000x5000: native heap " +
        "${before / 1024 / 1024}MB -> ${after / 1024 / 1024}MB",
    )
    assertTrue(
      growth < MAX_NATIVE_GROWTH_BYTES,
      "the native heap grew ${growth / 1024 / 1024}MB over $ITERATIONS crops of a 5000x5000 " +
        "source; a decoder or a bitmap is being retained",
    )
  }

  /**
   * The control, and the reason the threshold above is a number rather than a guess.
   *
   * Holds every decoded bitmap instead of closing it. If this does not cross the threshold, the
   * test above could not see a leak either.
   */
  @Test
  fun deliberatelyRetainedBitmapsCrossTheSameThreshold() = runBlocking {
    val source = CropSource.FilePath(fixture("square-5000.jpg").absolutePath)

    repeat(WARMUP) { cropAndRead(source) }
    val before = settledNativeBytes()

    val retained = mutableListOf<AutoCloseable>()
    try {
      val decoder = assertNotNull(createRegionDecoder(source))
      decoder.use { open ->
        repeat(ITERATIONS) {
          val region = ImageRegion(0, 0, 2_048, 2_048)
          val sampleSize = SampleSize.forDecode(region.size, region.size, DecodeBudget.ForDisplay)
          open.decodeRegion(region, sampleSize)?.let { decoded -> retained += decoded }
        }
      }
      val growth = settledNativeBytes() - before
      println(
        "[device-stress] control: holding ${retained.size} regions grew ${growth / 1024 / 1024}MB",
      )
      assertTrue(
        growth > MAX_NATIVE_GROWTH_BYTES,
        "holding ${retained.size} decoded regions grew the native heap by only " +
          "${growth / 1024 / 1024}MB, under the " +
          "${MAX_NATIVE_GROWTH_BYTES / 1024 / 1024}MB threshold the test above uses",
      )
    } finally {
      retained.forEach { it.close() }
      retained.clear()
    }
  }

  /**
   * Crop, encode, and read the result back, in a tight loop.
   *
   * This is the exact shape of the defect that once shipped: the crop tail closed every image it
   * had touched, including the one it was about to hand out, and the caller got a recycled bitmap.
   * Ten tests were green because none of them read a pixel of the result, so the read at the end
   * here is the assertion and the loop is only what makes it likely.
   */
  @Test
  fun aTightLoopOfCropAndEncodeNeverHandsBackAFreedBitmap() = runBlocking {
    val decoder = assertNotNull(
      createRegionDecoder(CropSource.FilePath(fixture("square-5000.jpg").absolutePath)),
    )

    decoder.use { open ->
      repeat(ENCODE_ROUNDS) { round ->
        val region = ImageRegion(round * 10, round * 10, 3_000 + round * 10, 3_000 + round * 10)
        val sampleSize = SampleSize.forDecode(region.size, VIEWPORT, DecodeBudget.ForOutput)
        val decoded =
          assertNotNull(open.decodeRegion(region, sampleSize), "round $round decoded nothing")

        decoded.use { result ->
          val bytes =
            encodeImage(result.image, EncodeOptions(EncodedFormat.JPEG, lossyQuality = 85))
          assertNotNull(bytes, "round $round encoded nothing")
          assertTrue(bytes.size > 512, "round $round encoded only ${bytes.size} bytes")

          // After the encode, on purpose. An encoder that frees its input leaves this throwing
          // IllegalStateException, which is the failure mode this test exists for.
          val bitmap = result.image.bitmap
          assertTrue(bitmap.width > 0 && bitmap.height > 0)
          bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        }
      }
      println("[device-stress] $ENCODE_ROUNDS crop and encode rounds, every result still readable")
    }
  }

  /**
   * Several decodes at once against one decoder, on a device.
   *
   * Every request asks for a different width, so a crossed result is visible in the size alone.
   *
   * What this green does **not** prove: that the library's own mutex is what keeps it correct.
   * Removing that mutex and re-running leaves this test passing, because `decodeRegion` is
   * `synchronized` on a native lock inside `BitmapRegionDecoder` itself, so the platform serialises
   * whether or not the library does. The race the library's guard actually covers is a close
   * landing mid decode, which is the test below.
   */
  @Test
  fun concurrentDecodesOnOneDecoderEachGetTheirOwnRectangle() = runBlocking {
    val decoder = assertNotNull(
      createRegionDecoder(CropSource.FilePath(fixture("sensor-48mp.jpg").absolutePath)),
    )

    decoder.use { open ->
      val requests = (0 until CONCURRENCY).map { ImageRegion(0, 0, 800 + it * 400, 600) }
      val answers = coroutineScope {
        requests.map { region ->
          async(Dispatchers.Default) {
            val sampleSize = SampleSize.forDecode(region.size, VIEWPORT, DecodeBudget.ForDisplay)
            region to open.decodeRegion(region, sampleSize)
          }
        }.awaitAll()
      }

      answers.forEach { (region, decoded) ->
        assertNotNull(decoded, "the $region request came back empty").use {
          assertEquals(
            region,
            it.region,
            "a concurrent request was answered with another's rectangle",
          )
          it.image.bitmap.getPixel(0, 0)
        }
      }
      println(
        "[device-stress] $CONCURRENCY concurrent decodes, each answered with its own rectangle",
      )
    }
  }

  /**
   * Closing the decoder while decodes are in flight.
   *
   * This is what a user makes by leaving the screen while tiles are still loading, and on Android
   * it is lethal by default: `close` calls `BitmapRegionDecoder.recycle`, and the next native call
   * on a recycled decoder raises `IllegalStateException` from inside the platform. The contract is
   * that a crop reports a failed region rather than crashing, so every outcome here has to be
   * either a readable bitmap or nothing at all.
   *
   * Three guards stand between this test and that exception, and it takes removing two of them to
   * turn it red. Dropping only the decoder's `RuntimeException` catch leaves it green, and so does
   * dropping only the flag-before-recycle ordering in `close`, because `BitmapRegionDecoder` locks
   * `decodeRegion` and `recycle` against each other natively. Removing the ordering **and** the
   * catch produces `IllegalStateException: decodeRegion called on recycled region decoder`, which
   * is the failure this test exists to name. Recorded because a test nobody has seen fail is a test
   * nobody should believe.
   */
  @Test
  fun closingWhileDecodesAreInFlightIsRefusedRatherThanCrashing() = runBlocking {
    repeat(CLOSE_RACE_ROUNDS) { round ->
      val decoder = assertNotNull(
        createRegionDecoder(CropSource.FilePath(fixture("sensor-108mp.jpg").absolutePath)),
        "round $round could not open the fixture",
      )

      val inFlight = coroutineScope {
        val requests = (0 until CONCURRENCY).map { index ->
          async(Dispatchers.Default) {
            val region = ImageRegion(0, 0, 12_000 - index * 100, 9_000)
            val sampleSize = SampleSize.forDecode(region.size, VIEWPORT, DecodeBudget.ForDisplay)
            decoder.decodeRegion(region, sampleSize)
          }
        }
        // Closed from under them, on purpose, while they are still running.
        decoder.close()
        requests.awaitAll()
      }

      inFlight.forEach { decoded ->
        // Null is the correct answer for a decode that lost the race. A non-null one has to be a
        // bitmap whose pixels can still be read, not a handle to something already recycled.
        decoded?.use { it.image.bitmap.getPixel(0, 0) }
      }

      val after = ImageRegion(0, 0, 1_000, 1_000)
      assertNull(
        decoder.decodeRegion(
          after,
          SampleSize.forDecode(after.size, VIEWPORT, DecodeBudget.ForDisplay),
        ),
        "round $round: a closed decoder answered a new request",
      )
    }
    println("[device-stress] $CLOSE_RACE_ROUNDS close-during-decode races survived")
  }

  /** Rectangles that cannot be honoured come back as nothing, on the device's decoder too. */
  @Test
  fun degenerateRectanglesAreRefusedOnDevice() = runBlocking {
    val decoder = assertNotNull(
      createRegionDecoder(CropSource.FilePath(fixture("square-5000.jpg").absolutePath)),
    )
    val degenerate = listOf(
      "empty" to ImageRegion(100, 100, 100, 100),
      "inverted horizontally" to ImageRegion(400, 100, 100, 400),
      "wholly beyond the right edge" to ImageRegion(6_000, 100, 7_000, 400),
      "negative origin" to ImageRegion(-500, -500, -100, -100),
    )

    decoder.use {
      for ((shape, region) in degenerate) {
        val safe =
          ImageSize(region.size.width.coerceAtLeast(1), region.size.height.coerceAtLeast(1))
        val sampleSize = SampleSize.forDecode(safe, VIEWPORT, DecodeBudget.ForDisplay)
        assertNull(it.decodeRegion(region, sampleSize), "a $shape rectangle produced an image")
      }
      val good = ImageRegion(1_000, 1_000, 3_000, 3_000)
      assertNotNull(
        it.decodeRegion(good, SampleSize.forDecode(good.size, VIEWPORT, DecodeBudget.ForDisplay)),
        "the control rectangle was refused too, so the refusals above prove nothing",
      ).close()
    }
    println("[device-stress] ${degenerate.size} degenerate rectangles refused, control decoded")
  }

  private suspend fun cropAndRead(source: CropSource) {
    val decoder = createRegionDecoder(source) ?: error("could not open $source")
    decoder.use { open ->
      val region = ImageRegion(250, 250, 4_750, 4_750)
      val sampleSize = SampleSize.forDecode(region.size, VIEWPORT, DecodeBudget.ForDisplay)
      open.decodeRegion(region, sampleSize)?.use { result ->
        result.image.bitmap.getPixel(0, 0)
      }
    }
  }

  /** The native heap, after the collector has genuinely had a chance to run. */
  private fun settledNativeBytes(): Long {
    repeat(GC_ATTEMPTS) {
      Runtime.getRuntime().gc()
      System.runFinalization()
      Thread.sleep(GC_PAUSE_MILLIS)
    }
    return Debug.getNativeHeapAllocatedSize()
  }

  private companion object {
    const val FIXTURE_DIR = "/data/local/tmp/crayfish-fixtures"
    val VIEWPORT = ImageSize(1_080, 1_920)
    const val RECTANGLES_PER_SOURCE = 8
    const val WARMUP = 5
    const val ITERATIONS = 25
    const val ENCODE_ROUNDS = 20
    const val CONCURRENCY = 8
    const val CLOSE_RACE_ROUNDS = 6
    const val GC_ATTEMPTS = 4
    const val GC_PAUSE_MILLIS = 80L
    const val MAX_NATIVE_GROWTH_BYTES = 96L * 1024 * 1024
  }
}
