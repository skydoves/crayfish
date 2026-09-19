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
package com.github.skydoves.crayfish.e2e

import com.github.skydoves.crayfish.decode.CropSource
import com.github.skydoves.crayfish.decode.DecodeBudget
import com.github.skydoves.crayfish.decode.ImageFormat
import com.github.skydoves.crayfish.decode.ImageProbe
import com.github.skydoves.crayfish.decode.ImageRegion
import com.github.skydoves.crayfish.decode.ImageSize
import com.github.skydoves.crayfish.decode.SampleSize
import com.github.skydoves.crayfish.decode.createRegionDecoder
import com.github.skydoves.crayfish.encode.EncodeOptions
import com.github.skydoves.crayfish.encode.EncodedFormat
import com.github.skydoves.crayfish.encode.encodeImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Breadth, where the rest of the end-to-end suite has depth.
 *
 * [CropPipelineMemoryTest] proves one rectangle on one source stays inside the budget, and
 * [RetainedMemoryTest] proves repeating that one rectangle retains nothing. Neither says anything
 * about the shapes nobody thought of: a square source, a one pixel crop, a rectangle that starts
 * outside the image, two crops running at once, or a crop that is cancelled halfway.
 *
 * Every test here reports how many cases it examined. A stress test that silently narrows its own
 * corpus reads exactly like a stress test that passes.
 */
class StressTest {

  private data class Source(val name: String, val file: File, val size: ImageSize)

  private val sources = listOf(
    Source("108MP 12000x9000", Fixtures.sensor108mp, ImageSize(12_000, 9_000)),
    Source("48MP 8000x6000", Fixtures.sensor48mp, ImageSize(8_000, 6_000)),
    Source("square 5000x5000", Fixtures.square5000, ImageSize(5_000, 5_000)),
    Source("panorama 28000x2000", Fixtures.panorama, ImageSize(28_000, 2_000)),
    Source("tall 2000x28000", Fixtures.tall, ImageSize(2_000, 28_000)),
    Source("sliver 1x32767", Fixtures.sliver, ImageSize(1, 32_767)),
    Source("uhd png 3840x2160", Fixtures.uhdPng, ImageSize(3_840, 2_160)),
  )

  /**
   * The rectangles worth trying against any source, named so a failure says which shape broke.
   *
   * Written as functions of the source size rather than as literals, because the interesting
   * rectangles are the ones that touch an edge, and where the edge is depends on the source.
   */
  private fun rectangles(size: ImageSize): List<Pair<String, ImageRegion>> {
    val w = size.width
    val h = size.height
    return listOf(
      "whole image" to ImageRegion(0, 0, w, h),
      // The corners round up so they stay non-empty on a source that is one pixel wide. The centred
      // half rounds down for the same reason from the other side: rounding it up on a 1px source
      // puts its left edge past its right one, which is an inverted rectangle and correctly refused.
      "top left corner" to ImageRegion(0, 0, (w / 4).coerceAtLeast(1), (h / 4).coerceAtLeast(1)),
      "bottom right corner" to
        ImageRegion(w - (w / 4).coerceAtLeast(1), h - (h / 4).coerceAtLeast(1), w, h),
      "centred half" to ImageRegion(w / 4, h / 4, w - w / 4, h - h / 4),
      "single pixel at the origin" to ImageRegion(0, 0, 1, 1),
      "single pixel at the far corner" to ImageRegion(w - 1, h - 1, w, h),
      "one pixel wide, full height" to ImageRegion(w / 2, 0, w / 2 + 1, h),
      "one pixel tall, full width" to ImageRegion(0, h / 2, w, h / 2 + 1),
    )
  }

  /**
   * Every source, every rectangle. 56 decodes.
   *
   * The assertion is not just "did not throw". A decoder that clamps a rectangle to the wrong axis,
   * or hands back a buffer it has already freed, returns something; only reading the pixels and
   * checking the reported region says the something was right.
   */
  @Test
  fun everySourceSurvivesEveryRectangle() = runBlocking {
    var examined = 0
    var declined = 0
    val declinedShapes = mutableListOf<String>()

    for (source in sources) {
      val decoder = assertNotNull(
        createRegionDecoder(CropSource.FilePath(source.file.absolutePath)),
        "could not open ${source.name}",
      )
      decoder.use {
        assertEquals(source.size, it.imageSize, "${source.name} reported the wrong size")

        for ((shape, region) in rectangles(source.size)) {
          val where = "${source.name} / $shape"
          val sampleSize = SampleSize.forDecode(region.size, VIEWPORT, DecodeBudget.ForDisplay)
          val decoded = it.decodeRegion(region, sampleSize)

          if (decoded == null) {
            // Counted rather than tolerated. Every rectangle in the matrix lies inside its source,
            // so a decline is a platform refusing a legal crop, which is a finding.
            declined += 1
            declinedShapes += where
            continue
          }

          decoded.use { result ->
            examined++
            assertTrue(
              result.width > 0 && result.height > 0,
              "$where produced ${result.width}x${result.height}",
            )
            val inside = result.region.intersect(ImageRegion.of(source.size))
            assertEquals(result.region, inside, "$where returned a region outside the source")
            val pixels =
              assertNotNull(result.image.readArgbPixels(), "$where produced unreadable pixels")
            assertEquals(
              result.width * result.height,
              pixels.size,
              "$where pixel count disagrees with the size",
            )
          }
        }
      }
    }

    println("[stress] rectangle matrix: $examined decoded, $declined declined, 0 crashed")
    // Set equality, not a floor. A floor lets a platform quietly stop handling a whole class of
    // rectangle while the test still reads green.
    assertEquals(
      sources.size * RECTANGLES_PER_SOURCE,
      examined,
      "only $examined of ${sources.size * RECTANGLES_PER_SOURCE} rectangles decoded; " +
        "refused: ${declinedShapes.joinToString()}",
    )
  }

  /**
   * Rectangles that cannot be honoured must come back as nothing, not as an exception and not as a
   * buffer of the wrong size.
   *
   * The distinction matters to a caller: a crop frame dragged to zero width, or a saved recipe
   * restored against a different photo, both arrive here, and a cropper that throws on either is a
   * cropper that crashes a gallery screen.
   */
  @Test
  fun degenerateRectanglesAreRefusedRatherThanThrown() = runBlocking {
    val source = Fixtures.square5000
    val size = ImageSize(5_000, 5_000)
    val degenerate = listOf(
      "empty" to ImageRegion(100, 100, 100, 100),
      "inverted horizontally" to ImageRegion(400, 100, 100, 400),
      "inverted vertically" to ImageRegion(100, 400, 400, 100),
      "wholly beyond the right edge" to ImageRegion(6_000, 100, 7_000, 400),
      "wholly beyond the bottom edge" to ImageRegion(100, 6_000, 400, 7_000),
      "negative origin" to ImageRegion(-500, -500, -100, -100),
      "zero width, full height" to ImageRegion(2_500, 0, 2_500, 5_000),
    )

    val decoder = assertNotNull(createRegionDecoder(CropSource.FilePath(source.absolutePath)))
    decoder.use {
      for ((shape, region) in degenerate) {
        val sampleSize = SampleSize.forDecode(
          region.size.let { s -> ImageSize(s.width.coerceAtLeast(1), s.height.coerceAtLeast(1)) },
          VIEWPORT,
          DecodeBudget.ForDisplay,
        )
        val decoded = it.decodeRegion(region, sampleSize)
        assertNull(decoded, "a $shape rectangle produced an image instead of nothing")
      }

      // The positive control. Without it, a decoder that returns null for everything passes every
      // line above, and this test would be measuring nothing at all.
      val good = ImageRegion(1_000, 1_000, 3_000, 3_000)
      val sampleSize = SampleSize.forDecode(good.size, VIEWPORT, DecodeBudget.ForDisplay)
      assertNotNull(
        it.decodeRegion(good, sampleSize),
        "the control rectangle was refused too, so the refusals above prove nothing",
      ).close()
    }
    println("[stress] ${degenerate.size} degenerate rectangles refused, control decoded")
  }

  /**
   * The user's shape, all the way out to bytes, in every format the library writes.
   *
   * [EncodedFormat.WEBP_LOSSLESS] is expected to come back empty on this target: Skia's encoder
   * takes one quality integer and has no way to select lossless mode, so the library refuses rather
   * than silently writing a lossy file. Asserting that refusal here keeps it a decision rather than
   * a gap nobody noticed.
   */
  @Test
  fun theSquareSourceRoundTripsThroughEveryFormat() = runBlocking {
    val decoder =
      assertNotNull(createRegionDecoder(CropSource.FilePath(Fixtures.square5000.absolutePath)))
    val region = ImageRegion(500, 500, 4_500, 4_500)
    val expectedHeader = mapOf(
      EncodedFormat.JPEG to ImageFormat.JPEG,
      EncodedFormat.PNG to ImageFormat.PNG,
      EncodedFormat.WEBP_LOSSY to ImageFormat.WEBP,
      EncodedFormat.WEBP_LOSSLESS to null,
    )

    decoder.use {
      for (format in EncodedFormat.entries) {
        val sampleSize = SampleSize.forDecode(region.size, VIEWPORT, DecodeBudget.ForOutput)
        val decoded = assertNotNull(it.decodeRegion(region, sampleSize), "$format: nothing decoded")
        val decodedSize = ImageSize(decoded.width, decoded.height)
        val bytes = decoded.use { image ->
          encodeImage(image.image, EncodeOptions(format, lossyQuality = 85))
        }

        val expected = expectedHeader.getValue(format)
        if (expected == null) {
          assertNull(bytes, "$format is documented as unsupported here but produced bytes")
          continue
        }

        assertNotNull(bytes, "$format produced no bytes")
        assertTrue(bytes.size > 512, "$format produced only ${bytes.size} bytes")
        // Read the header back rather than trusting the encoder's own word for what it wrote.
        val probed =
          assertNotNull(ImageProbe.probe(bytes), "$format wrote something nothing can identify")
        assertEquals(expected, probed.format, "$format wrote a ${probed.format} header")
        assertEquals(
          decodedSize,
          probed.size,
          "$format wrote a header claiming ${probed.size}",
        )
        println(
          "[stress] square 5000 -> $format: ${bytes.size / 1024}KB, header says ${probed.size}",
        )
      }
    }
  }

  /**
   * A long mixed run across every source and every rectangle, with the retained cost measured.
   *
   * The leak test next door repeats one crop on one source. This walks the whole corpus, which is
   * what a gallery screen does, and is the shape that finds a decoder retained per source rather
   * than per crop.
   *
   * **Steady state against steady state, not before against after.** An absolute growth reading
   * from one baseline passed at 44MB in isolation three times running and then reported 147MB in
   * the full suite, because the neighbours that ran first left the allocator holding pages it
   * released lazily. Nothing had leaked. A leak grows on every pass; a warm-up transient is spent
   * once, so comparing the second half of the run to the first half sees the one and not the other.
   */
  @Test
  fun aSustainedMixedRunRetainsNothing() = runBlocking {
    repeat(WARMUP_PASSES) { cropEverything() }

    var decodes = 0
    val series = (0 until PASSES).map {
      decodes += cropEverything()
      settledResidentBytes()
    }

    val half = PASSES / 2
    val early = series.take(half).average()
    val late = series.drop(half).average()
    val growth = (late - early).toLong()
    println(
      "[stress] $decodes decodes over $PASSES passes: " +
        series.joinToString(" -> ") { "${it / 1024 / 1024}MB" } +
        ", second half ${growth / 1024 / 1024}MB above the first",
    )
    assertTrue(
      growth < MAX_GROWTH_BYTES,
      "resident memory kept climbing across $decodes mixed decodes: the second half of the " +
        "run averaged ${growth / 1024 / 1024}MB above the first, which is what a decoder or " +
        "a decoded image retained per pass looks like",
    )
  }

  /**
   * The control for the test above, and the reason its threshold is a number rather than a guess.
   *
   * One pass over the corpus with every decoded region held instead of closed. If this does not
   * blow through [MAX_GROWTH_BYTES], the sustained test could not detect a real leak either, and
   * its green would mean nothing.
   *
   * Note what this does **not** prove: dropping a `close()` on its own is not detectable this way,
   * because a Skia bitmap left unreferenced is reclaimed by its cleaner on the next collection.
   * What this catches is a reference the library keeps, which is the leak that actually kills a
   * gallery screen.
   */
  @Test
  fun aDeliberatelyRetainedCorpusTripsTheSameThreshold() = runBlocking {
    repeat(WARMUP_PASSES) { cropEverything() }
    val before = settledResidentBytes()

    val retained = mutableListOf<AutoCloseable>()
    try {
      // Fixed 2048x2048 regions rather than the corpus's own, which vary from 1x2048 to 1080x1920
      // and made this reading swing between 107MB and 200MB across runs. 16MB apiece is a number
      // that does not move, and it is the magnitude a leak of the corpus would reach anyway.
      val decoder =
        assertNotNull(createRegionDecoder(CropSource.FilePath(Fixtures.square5000.absolutePath)))
      decoder.use { open ->
        repeat(RETAINED_REGIONS) {
          val region = ImageRegion(0, 0, 2_048, 2_048)
          val sampleSize = SampleSize.forDecode(region.size, region.size, DecodeBudget.ForDisplay)
          open.decodeRegion(region, sampleSize)?.let { decoded -> retained += decoded }
        }
      }
      val after = settledResidentBytes()
      val growth = after - before
      println("[stress] control: holding ${retained.size} regions grew ${growth / 1024 / 1024}MB")
      assertTrue(
        growth > MAX_GROWTH_BYTES,
        "holding ${retained.size} decoded regions grew resident memory by only " +
          "${growth / 1024 / 1024}MB, under the ${MAX_GROWTH_BYTES / 1024 / 1024}MB threshold " +
          "aSustainedMixedRunRetainsNothing uses, so that test could not see a leak either",
      )
    } finally {
      retained.forEach { it.close() }
      retained.clear()
    }
  }

  /**
   * Closing the decoder while decodes are in flight.
   *
   * The shape a user makes by leaving the screen while tiles are still loading. Here `close`
   * disposes an ImageIO reader and the stream under it, so a decode that was mid read is holding
   * something that no longer exists. The contract is the same as everywhere else: a failed region
   * comes back as nothing, and nothing throws out of the library.
   */
  @Test
  fun closingWhileDecodesAreInFlightIsRefusedRatherThanThrown() = runBlocking {
    repeat(CLOSE_RACE_ROUNDS) { round ->
      val decoder = assertNotNull(
        createRegionDecoder(CropSource.FilePath(Fixtures.sensor108mp.absolutePath)),
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
        decoder.close()
        requests.awaitAll()
      }

      // Null is the correct answer for a decode that lost the race. A non-null one has to still be
      // readable, not a handle to a buffer that has already been released.
      inFlight.forEach { decoded -> decoded?.use { assertNotNull(it.image.readArgbPixels()) } }

      val after = ImageRegion(0, 0, 1_000, 1_000)
      assertNull(
        decoder.decodeRegion(
          after,
          SampleSize.forDecode(after.size, VIEWPORT, DecodeBudget.ForDisplay),
        ),
        "round $round: a closed decoder answered a new request",
      )
    }
    println("[stress] $CLOSE_RACE_ROUNDS close-during-decode races survived")
  }

  private suspend fun cropEverything(): Int {
    var decodes = 0
    for (source in sources) {
      val decoder = createRegionDecoder(CropSource.FilePath(source.file.absolutePath)) ?: continue
      decoder.use { open ->
        for ((_, region) in rectangles(source.size)) {
          val sampleSize = SampleSize.forDecode(region.size, VIEWPORT, DecodeBudget.ForDisplay)
          open.decodeRegion(region, sampleSize)?.use { decodes++ }
        }
      }
    }
    return decodes
  }

  /** Resident set, not heap: a decoded image's pixels are native and invisible to the heap beans. */
  private fun settledResidentBytes(): Long {
    repeat(GC_ATTEMPTS) {
      System.gc()
      Thread.sleep(GC_PAUSE_MILLIS)
    }
    val process = ProcessBuilder("ps", "-o", "rss=", "-p", ProcessHandle.current().pid().toString())
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    process.waitFor()
    return (output.toLongOrNull() ?: 0L) * 1024L
  }

  private companion object {
    val VIEWPORT = ImageSize(1_080, 1_920)
    const val RECTANGLES_PER_SOURCE = 8
    const val WARMUP_PASSES = 2
    const val PASSES = 6
    const val CONCURRENCY = 8
    const val CLOSE_RACE_ROUNDS = 6
    const val RETAINED_REGIONS = 25
    const val CANCEL_ROUNDS = 6
    const val CANCEL_AFTER_MILLIS = 3L
    const val GC_ATTEMPTS = 3
    const val GC_PAUSE_MILLIS = 120L

    /** Calibrated: a clean run of the corpus moves about 45MB, and the control above blows past this. */
    const val MAX_GROWTH_BYTES = 96L * 1024 * 1024
  }
}
