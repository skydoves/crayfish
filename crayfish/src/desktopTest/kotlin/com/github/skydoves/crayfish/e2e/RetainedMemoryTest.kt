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
import com.github.skydoves.crayfish.decode.ImageRegion
import com.github.skydoves.crayfish.decode.SampleSize
import com.github.skydoves.crayfish.decode.createRegionDecoder
import com.github.skydoves.crayfish.encode.EncodeOptions
import com.github.skydoves.crayfish.encode.EncodedFormat
import com.github.skydoves.crayfish.encode.encodeImage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Repeating the work must not grow what is retained.
 *
 * The budget tests in [CropPipelineMemoryTest] measure one crop. A leak does not show up there: a
 * decoder or a decoded image that is never closed costs the same on the first iteration as on the
 * hundredth, and the single-shot number stays comfortably inside budget while a gallery screen
 * walks a device into an out-of-memory kill. Only repetition separates "allocates a lot briefly"
 * from "keeps it".
 */
class RetainedMemoryTest {

  /**
   * The process's resident memory, after the collector has genuinely had a chance to run.
   *
   * **Resident set size, not heap.** A decoded image here is an `org.jetbrains.skia.Bitmap` whose
   * pixels live in native memory, which `MemoryMXBean.heapMemoryUsage` cannot see at all: holding
   * twenty-five 16MB regions moved the heap reading by 0MB. A heap-based leak gate would therefore
   * have passed over exactly the leak it exists to catch. The control test below is what found
   * that, and it is why it is in the file.
   *
   * RSS is noisier than a heap reading, which is why the thresholds are wide and the control
   * proves they still bite.
   */
  private fun settledResidentBytes(): Long {
    repeat(GC_ATTEMPTS) {
      System.gc()
      Thread.sleep(GC_PAUSE_MILLIS)
    }
    val pid = ProcessHandle.current().pid()
    val process = ProcessBuilder("ps", "-o", "rss=", "-p", pid.toString())
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    process.waitFor()
    // `ps` reports kilobytes on both macOS and Linux.
    return (output.toLongOrNull() ?: 0L) * 1024L
  }

  @Test
  fun repeatedCropsDoNotGrowRetainedMemory() = runBlocking {
    val source = CropSource.FilePath(Fixtures.sensor48mp.absolutePath)

    // Warm up first: class loading, the JIT and Skia's own lazily-allocated structures all land on
    // whichever iteration runs first, and attributing them to a leak would be wrong.
    repeat(WARMUP) { cropOnce(source) }
    val before = settledResidentBytes()

    repeat(ITERATIONS) { cropOnce(source) }
    val after = settledResidentBytes()

    val growth = after - before
    assertTrue(
      growth < MAX_GROWTH_BYTES,
      "resident memory grew by ${growth / 1024 / 1024}MB over $ITERATIONS crops " +
        "(${before / 1024 / 1024}MB -> ${after / 1024 / 1024}MB); a decoder or a decoded " +
        "image is not being closed",
    )
    println(
      "[leak] $ITERATIONS crops: retained " +
        "${before / 1024 / 1024}MB -> ${after / 1024 / 1024}MB",
    )
  }

  /**
   * The decoder alone, opened and closed without decoding anything.
   *
   * Separated from the crop so a failure says which half leaked. A region decoder holds a file
   * handle and, on some platforms, a native buffer sized by the source.
   */
  @Test
  fun repeatedlyOpeningAndClosingDecodersDoesNotGrowRetainedMemory() = runBlocking {
    val source = CropSource.FilePath(Fixtures.sensor108mp.absolutePath)

    repeat(WARMUP) { assertNotNull(createRegionDecoder(source)).close() }
    val before = settledResidentBytes()

    repeat(ITERATIONS * 2) { assertNotNull(createRegionDecoder(source)).close() }
    val after = settledResidentBytes()

    val growth = after - before
    assertTrue(
      growth < MAX_GROWTH_BYTES,
      "resident memory grew by ${growth / 1024 / 1024}MB over ${ITERATIONS * 2} open/close cycles",
    )
    println(
      "[leak] ${ITERATIONS * 2} decoder open/close: " +
        "${before / 1024 / 1024}MB -> ${after / 1024 / 1024}MB",
    )
  }

  /**
   * The control for both tests above: deliberately keeping every decoded image must be detected.
   *
   * Without this, a growth threshold that is simply too generous would let the leak tests pass over
   * a real leak, and nothing would say so.
   */
  @Test
  fun theLeakDetectorNoticesImagesThatAreDeliberatelyRetained() = runBlocking {
    val source = CropSource.FilePath(Fixtures.sensor48mp.absolutePath)
    val leaked = mutableListOf<Any>()

    repeat(WARMUP) { cropOnce(source) }
    val before = settledResidentBytes()

    val decoder = assertNotNull(createRegionDecoder(source))
    try {
      repeat(ITERATIONS) {
        val region = ImageRegion(0, 0, 2_048, 2_048)
        val sampleSize = SampleSize.forDecode(region.size, region.size, DecodeBudget.ForDisplay)
        // Held on purpose, and never closed.
        leaked += assertNotNull(decoder.decodeRegion(region, sampleSize))
      }
      val after = settledResidentBytes()

      assertTrue(
        after - before > MAX_GROWTH_BYTES,
        "holding $ITERATIONS decoded regions grew resident memory by only " +
          "${(after - before) / 1024 / 1024}MB, under the " +
          "${MAX_GROWTH_BYTES / 1024 / 1024}MB threshold the leak tests use, so those tests " +
          "could not detect a real leak either",
      )
      println(
        "[leak] control: holding $ITERATIONS regions grew " +
          "${(after - before) / 1024 / 1024}MB",
      )
    } finally {
      leaked.forEach { (it as? AutoCloseable)?.close() }
      leaked.clear()
      decoder.close()
    }
  }

  private suspend fun cropOnce(source: CropSource) {
    val decoder = assertNotNull(createRegionDecoder(source))
    decoder.use {
      val region = ImageRegion(0, 0, 2_048, 2_048)
      val sampleSize = SampleSize.forDecode(region.size, region.size, DecodeBudget.ForDisplay)
      val decoded = assertNotNull(it.decodeRegion(region, sampleSize))
      decoded.use { region2 ->
        encodeImage(region2.image, EncodeOptions(EncodedFormat.JPEG, lossyQuality = 80))
      }
    }
  }

  private companion object {
    /**
     * Long enough for the arena to settle, which is what makes the threshold below meaningful.
     *
     * Measured: resident memory over successive batches of 25 crops went 264MB, 304MB, 305MB,
     * 305MB. All of the growth is in the first batch and none of it after, so it is Skia's arenas
     * and the JIT rather than a leak, but a baseline taken before that settles would hide 40MB of
     * real growth inside the noise.
     */
    const val WARMUP = 25
    const val ITERATIONS = 25
    const val GC_ATTEMPTS = 4
    const val GC_PAUSE_MILLIS = 60L

    /**
     * Tight enough to matter, because [WARMUP] has already absorbed the arena growth. The control
     * test proves it bites: holding [ITERATIONS] regions of 2048x2048 retains roughly 275MB of
     * native pixels, nearly nine times this threshold.
     */
    const val MAX_GROWTH_BYTES = 32L * 1024 * 1024
  }
}
