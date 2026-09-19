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

import com.github.skydoves.crayfish.decode.ImageProbe
import com.github.skydoves.crayfish.decode.ImageSize
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Checks the measuring apparatus before anything is measured with it.
 *
 * The end-to-end memory tests assert that a decode stayed inside a budget. That assertion is worth
 * nothing unless the budget is genuinely binding and the probe genuinely counts: a probe that
 * always reports zero, or a budget large enough to hold a full decode anyway, would report a
 * confident green over a library that allocates 412MiB.
 */
class MemoryHarnessTest {

  @Test
  fun theProbeCountsAKnownAllocation() {
    val megabytes = 32
    val (array, reading) = MemoryProbe.measure { ByteArray(megabytes * 1024 * 1024) }

    assertEquals(megabytes * 1024 * 1024, array.size)
    assertTrue(
      reading.allocatedBytes >= megabytes * 1024L * 1024L,
      "allocating ${megabytes}MB was measured as $reading",
    )
  }

  /**
   * The control for a flaw this harness actually shipped with: measuring only the calling thread
   * reported 0MB for a decode running on `Dispatchers.IO`, so the budget assertion could not fail.
   */
  @Test
  fun theProbeCountsAllocationOnOtherThreads() {
    val megabytes = 48
    // The buffer has to escape and be written to. An array whose only use is its `size` (which the
    // compiler already knows from the constructor argument) can be removed outright, and the probe
    // then correctly reports that nothing was allocated. That is a defect in the test, not the
    // probe, and it cost a debugging round to find.
    val (size, reading) = MemoryProbe.measure {
      val escaped = arrayOfNulls<ByteArray>(1)
      val worker = Thread {
        val array = ByteArray(megabytes * 1024 * 1024)
        array[array.size - 1] = 1
        array[0] = array[array.size - 1]
        escaped[0] = array
      }
      worker.start()
      worker.join()
      val buffer = assertNotNull(escaped[0])
      assertEquals(1.toByte(), buffer[0])
      buffer.size
    }

    assertEquals(megabytes * 1024 * 1024, size)
    assertTrue(
      reading.allocatedBytes >= megabytes * 1024L * 1024L,
      "allocating ${megabytes}MB on another thread was measured as $reading",
    )
  }

  @Test
  fun theProbeReportsNearlyNothingForWorkThatAllocatesNearlyNothing() {
    val (sum, reading) = MemoryProbe.measure { (1..1_000).sum() }

    assertEquals(500_500, sum)
    assertTrue(
      reading.allocatedBytes < 1024 * 1024,
      "trivial work should not read as a megabyte: $reading",
    )
  }

  /**
   * The budget has to be smaller than a naive decode, or the end-to-end tests pass no matter what
   * the library does. 12000x9000 is 412MiB as ARGB_8888 against a 384MB heap.
   */
  @Test
  fun theStatedBudgetIsSmallerThanANaiveDecodeOfTheLargestFixture() {
    val probe = ImageProbe.probe(
      Fixtures.sensor108mp.readBytes().copyOf(ImageProbe.HEADER_BYTE_COUNT * 4),
    )
    val declaredSize = ImageProbe.probe(Fixtures.sensor108mp.readBytes())?.size

    assertEquals(ImageSize(12_000, 9_000), declaredSize, "fixture is not the size it should be")
    assertTrue(probe != null)

    val naiveCost = declaredSize!!.argb8888ByteCount
    assertTrue(
      naiveCost > MemoryProbe.BUDGET_BYTES,
      "a full decode costs ${naiveCost / 1024 / 1024}MB and the heap limit is " +
        "${MemoryProbe.BUDGET_BYTES / 1024 / 1024}MB, and the budget is not binding, so nothing " +
        "downstream is actually being tested",
    )
  }

  /**
   * And empirically, not just arithmetically: decoding it the obvious way really does fail here.
   *
   * Run in a child JVM. Running out of memory is the expected result, and an `OutOfMemoryError`
   * inside the test process would leave every later test on a damaged heap.
   */
  @Test
  fun aNaiveFullDecodeOfTheLargestFixtureExhaustsThisBudget() {
    val result = runNaiveDecodeProbe(Fixtures.sensor108mp)

    assertEquals(
      NaiveDecodeProbe.EXIT_OUT_OF_MEMORY,
      result.exitCode,
      "expected the naive decode to run out of memory; it said: ${result.output}",
    )
  }

  /** The same probe on a small image must succeed, or its failure above proves nothing. */
  @Test
  fun theNaiveDecodeProbeSucceedsOnAnImageThatFits() {
    val result = runNaiveDecodeProbe(Fixtures.uhdPng)

    assertEquals(
      NaiveDecodeProbe.EXIT_DECODED,
      result.exitCode,
      "the control should decode a 4K image fine; it said: ${result.output}",
    )
    assertTrue(result.output.contains("3840x2160"), result.output)
  }

  private data class ProbeResult(val exitCode: Int, val output: String)

  private fun runNaiveDecodeProbe(file: File): ProbeResult {
    val javaBinary = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
    val process = ProcessBuilder(
      javaBinary,
      "-Xmx${MemoryProbe.BUDGET_BYTES / 1024 / 1024}m",
      "-XX:+UseSerialGC",
      "-cp",
      System.getProperty("java.class.path"),
      "com.github.skydoves.crayfish.e2e.NaiveDecodeProbeKt",
      file.absolutePath,
    ).redirectErrorStream(true).start()

    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      "the naive decode probe did not finish within $PROBE_TIMEOUT_SECONDS s"
    }
    return ProbeResult(process.exitValue(), output.trim())
  }

  private companion object {
    const val PROBE_TIMEOUT_SECONDS = 120L
  }
}
