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

import java.lang.management.ManagementFactory

/** What a block of work cost in memory. */
internal data class MemoryReading(
  /** The high-water mark across every heap pool while the block ran. */
  val peakHeapBytes: Long,
  /**
   * Total bytes allocated across **every** thread, whether or not they survived.
   *
   * Summed over all threads rather than the caller's, because the decode runs on `Dispatchers.IO`.
   * Measuring only the calling thread reported 0MB for a decode that really allocated tens of
   * megabytes: an assertion that could not fail, which is worse than no assertion at all.
   *
   * It complements peak heap rather than replacing it: peak depends on when the collector happens
   * to run, while allocation is counted at the allocation site. A pipeline that allocates 400MB and
   * frees it promptly looks innocent by peak and guilty here, and on a phone it is guilty, because
   * the allocation still had to succeed.
   */
  val allocatedBytes: Long,
) {
  override fun toString(): String =
    "peak=${peakHeapBytes / 1024 / 1024}MB allocated=${allocatedBytes / 1024 / 1024}MB"
}

/**
 * Measures what a block of work costs, so the library's "never materialises a huge bitmap" claim is
 * a number rather than a sentence.
 */
internal object MemoryProbe {

  /**
   * The memory ceiling the cropping pipeline is required to work under.
   *
   * A stated product constraint, deliberately **not** `Runtime.maxMemory()`: tying it to this JVM's
   * heap would mean that giving the test runner more memory (to host a Compose UI test, say)
   * silently relaxes the only thing the end-to-end tests measure. 384MB is under a mid-range
   * phone's per-app heap, and far under the 412MiB a single full-resolution decode of the 108MP
   * fixture costs.
   */
  const val BUDGET_BYTES: Long = 384L * 1024 * 1024

  fun <T> measure(block: () -> T): Pair<T, MemoryReading> {
    val threadBean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    val heapPools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type.name == "HEAP" }

    // Settle first: an un-collected allocation from a previous test would otherwise be attributed
    // to this block. A single gc() is a request, not a guarantee, which is why the allocation
    // counter above carries the real signal.
    System.gc()
    Thread.sleep(SETTLE_MILLIS)
    heapPools.forEach { runCatching { it.resetPeakUsage() } }

    // `totalThreadAllocatedBytes`, not a per-thread sum. Per-thread accounting loses a worker that
    // finishes inside the measured block: `getThreadAllocatedBytes` reports -1 once a thread has
    // terminated, so a short-lived worker's allocation disappears entirely. This counter includes
    // threads that have since died, which is the only reading that cannot be quietly zero.
    val allocatedBefore = threadBean.totalThreadAllocatedBytes
    val result = block()
    val allocatedAfter = threadBean.totalThreadAllocatedBytes

    val peak = heapPools.sumOf { runCatching { it.peakUsage.used }.getOrDefault(0L) }
    return result to MemoryReading(
      peakHeapBytes = peak,
      allocatedBytes = allocatedAfter - allocatedBefore,
    )
  }

  private const val SETTLE_MILLIS = 50L
}
