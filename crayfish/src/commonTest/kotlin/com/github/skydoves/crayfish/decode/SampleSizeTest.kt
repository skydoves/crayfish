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
package com.github.skydoves.crayfish.decode

import com.github.skydoves.crayfish.decode.SampleSize.admits
import com.github.skydoves.crayfish.decode.SampleSize.sampledBy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SampleSizeTest {

  /** The sources that make existing croppers crash, at the sizes real sensors produce. */
  private val hostileSources = mapOf(
    "108MP phone photo (ISOCELL HM2)" to ImageSize(12_000, 9_000),
    "200MP phone photo (ISOCELL HP2)" to ImageSize(16_384, 12_288),
    "13MP portrait, small in bytes but past a 4096 texture cap" to ImageSize(3_120, 4_160),
    "1:14 stitched panorama" to ImageSize(28_000, 2_000),
    "square at Skia's per-dimension limit" to ImageSize(32_766, 32_766),
    "48MP" to ImageSize(8_000, 6_000),
  )

  private val viewport = ImageSize(1_080, 1_920)

  /**
   * The headline claim, as an executable check rather than a README sentence: whatever the source,
   * the size actually handed to a decoder is inside the budget.
   */
  @Test
  fun everyHostileSourceIsBroughtInsideTheDisplayBudget() {
    val budget = DecodeBudget.ForDisplay

    hostileSources.forEach { (name, source) ->
      val sampleSize = SampleSize.forDecode(source, viewport, budget)
      val decoded = source.sampledBy(sampleSize)

      assertTrue(
        budget.admits(decoded),
        "$name: sampleSize=$sampleSize still yields $decoded " +
          "(${decoded.argb8888ByteCount} bytes, max ${budget.maxByteCount}; " +
          "max dimension ${budget.maxDimension})",
      )
    }
  }

  /**
   * The positive control for the test above. If the unsampled sources were already inside the
   * budget, that test would pass with the sampling logic deleted and would be proving nothing.
   */
  @Test
  fun theHostileSourcesAreGenuinelyOverBudgetToBeginWith() {
    val budget = DecodeBudget.ForDisplay

    hostileSources.forEach { (name, source) ->
      assertFalse(
        budget.admits(source),
        "$name at full resolution should be over budget, otherwise it is not a hostile fixture",
      )
    }
  }

  @Test
  fun everyHostileSourceIsBroughtInsideTheOutputBudgetToo() {
    val budget = DecodeBudget.ForOutput

    hostileSources.forEach { (name, source) ->
      // Output wants full fidelity, so the target is the source itself; only the budget constrains.
      val sampleSize = SampleSize.forDecode(source, source, budget)
      val decoded = source.sampledBy(sampleSize)

      assertTrue(budget.admits(decoded), "$name: sampleSize=$sampleSize yields $decoded")
    }
  }

  /**
   * A dimension cap and a byte cap catch different images. This one is only 51MB decoded, inside
   * any byte budget, and is rejected purely for being 4160 pixels tall.
   */
  @Test
  fun samplesForTheDimensionCapEvenWhenTheByteCountIsFine() {
    val portrait = ImageSize(3_120, 4_160)
    val generousBytes = DecodeBudget(maxByteCount = Long.MAX_VALUE, maxDimension = 4_096)

    assertTrue(portrait.argb8888ByteCount < 64L * 1024 * 1024)
    assertEquals(2, SampleSize.forDecode(portrait, portrait, generousBytes))
  }

  /** And the converse: a size inside the dimension cap can still be far too many bytes. */
  @Test
  fun samplesForTheByteCapEvenWhenTheDimensionsAreFine() {
    val wide = ImageSize(4_000, 4_000)
    val generousDimensions = DecodeBudget(maxByteCount = 16L * 1024 * 1024, maxDimension = 32_766)

    assertTrue(wide.width < generousDimensions.maxDimension)
    assertTrue(
      wide.sampledBy(SampleSize.forDecode(wide, wide, generousDimensions)).argb8888ByteCount <=
        16L * 1024 * 1024,
    )
  }

  /** A caller asking for full resolution does not get to opt out of the allocation limit. */
  @Test
  fun theBudgetAppliesEvenWhenTheTargetIsTheFullSource() {
    val huge = ImageSize(16_384, 12_288)

    assertTrue(SampleSize.forDecode(huge, huge, DecodeBudget.ForDisplay) > 1)
  }

  @Test
  fun leavesSmallImagesAlone() {
    listOf(ImageSize(1, 1), ImageSize(512, 512), ImageSize(1_080, 1_920)).forEach {
      assertEquals(1, SampleSize.forDecode(it, viewport, DecodeBudget.ForDisplay), "$it")
    }
  }

  /**
   * Platform decoders round a requested sample size down to a power of two, so returning 3 would
   * silently decode at 2 and produce a bitmap larger than the budget just approved.
   */
  @Test
  fun alwaysReturnsAPowerOfTwo() {
    val sizes = buildList {
      for (width in 1..5_000 step 137) {
        for (height in 1..5_000 step 311) add(ImageSize(width, height))
      }
      addAll(hostileSources.values)
    }

    sizes.forEach { source ->
      val sampleSize = SampleSize.forDecode(source, viewport, DecodeBudget.ForDisplay)

      assertTrue(sampleSize >= 1, "$source -> $sampleSize")
      assertTrue(sampleSize <= SampleSize.MAX, "$source -> $sampleSize")
      assertEquals(
        0,
        sampleSize and (sampleSize - 1),
        "$source -> $sampleSize is not a power of two",
      )
    }
  }

  /** Sweeping every source shape, not just the curated ones: the budget must always be honoured. */
  @Test
  fun theBudgetHoldsAcrossASweepOfSourceShapes() {
    val budget = DecodeBudget.ForDisplay

    for (width in listOf(1, 17, 640, 4_000, 12_000, 28_000, 32_766)) {
      for (height in listOf(1, 17, 640, 4_000, 12_000, 28_000, 32_766)) {
        val source = ImageSize(width, height)
        val decoded = source.sampledBy(SampleSize.forDecode(source, viewport, budget))

        assertTrue(budget.admits(decoded), "$source -> $decoded exceeds $budget")
      }
    }
  }

  /** Flooring a dimension to zero would make every downstream rect calculation divide by zero. */
  @Test
  fun neverProducesAZeroSizedBitmap() {
    val sliver = ImageSize(1, 32_766)
    val decoded = sliver.sampledBy(SampleSize.forDecode(sliver, viewport, DecodeBudget.ForDisplay))

    assertTrue(decoded.width >= 1)
    assertTrue(decoded.height >= 1)
  }

  @Test
  fun degeneratesSafelyOnAnUnknownSourceSize() {
    assertEquals(1, SampleSize.forDecode(ImageSize.Zero, viewport, DecodeBudget.ForDisplay))
    assertEquals(1, SampleSize.forDecode(ImageSize(-1, -1), viewport, DecodeBudget.ForDisplay))
  }

  /** Asking for a target no larger than the source must not upsample the request. */
  @Test
  fun doesNotDecodeSmallerThanTheTargetAsks() {
    val source = ImageSize(4_000, 3_000)
    val target = ImageSize(1_000, 750)

    val decoded = source.sampledBy(SampleSize.forDecode(source, target, DecodeBudget.ForDisplay))

    assertTrue(decoded.width >= target.width, "$decoded should still cover $target")
    assertTrue(decoded.height >= target.height, "$decoded should still cover $target")
  }

  // -------------------------------------------------------------------------------------------
  // Edges
  // -------------------------------------------------------------------------------------------

  /**
   * Each dimension is checked on its own, so one missing side is enough to give up.
   *
   * This pins the answer, not the check that produces it: deleting the height half of the guard
   * leaves this green, because `sampledBy` coerces every dimension up to 1 further down and the
   * result comes out the same. That makes the guard belt and braces rather than load-bearing, and
   * the note is here so the green is not mistaken for proof that it is.
   */
  @Test
  fun degeneratesWhenEitherSourceDimensionIsMissing() {
    assertEquals(1, SampleSize.forDecode(ImageSize(0, 3_000), viewport, DecodeBudget.ForDisplay))
    assertEquals(1, SampleSize.forDecode(ImageSize(4_000, 0), viewport, DecodeBudget.ForDisplay))
    assertEquals(
      1,
      SampleSize.forDecode(ImageSize(-4_000, 3_000), viewport, DecodeBudget.ForDisplay),
    )
    assertEquals(
      1,
      SampleSize.forDecode(ImageSize(4_000, -3_000), viewport, DecodeBudget.ForDisplay),
    )
  }

  /**
   * A target missing a dimension means "no target", and the budget still applies.
   *
   * The two halves of this function are independent on purpose: the first only avoids decoding
   * more detail than can be shown, the second is what makes a huge source decodable at all. A
   * caller who cannot say how big the destination is must still not be handed 768MiB.
   */
  @Test
  fun stillHonoursTheBudgetWhenTheTargetHasNoSize() {
    val source = ImageSize(16_384, 12_288)

    listOf(ImageSize.Zero, ImageSize(0, 900), ImageSize(1_600, 0)).forEach { target ->
      val sampleSize = SampleSize.forDecode(source, target, DecodeBudget.ForDisplay)

      assertTrue(
        DecodeBudget.ForDisplay.admits(source.sampledBy(sampleSize)),
        "target $target produced sample size $sampleSize, which is still over budget",
      )
    }
  }

  /**
   * The halving stops at [SampleSize.MAX] rather than looping for ever.
   *
   * Both loops are `while (sampleSize < MAX && ...)`, and the guard is the only thing standing
   * between a source whose dimensions can never be brought under the cap and an infinite loop on
   * the decode path. The source here is contrived (300 million pixels across) precisely because
   * a real one cannot reach it, which is what makes the ceiling worth pinning rather than assuming.
   */
  @Test
  fun stopsAtTheCeilingInsteadOfLoopingForEver() {
    val unbounded = ImageSize(300_000_000, 1)

    val sampleSize = SampleSize.forDecode(unbounded, ImageSize.Zero, DecodeBudget.ForDisplay)

    assertEquals(SampleSize.MAX, sampleSize)
    // And it is still a usable answer: sampling by it never yields a zero-sized bitmap.
    assertTrue(unbounded.sampledBy(sampleSize).width >= 1)
  }

  /** The display budget is the default, because the common caller is drawing to a screen. */
  @Test
  fun usesTheDisplayBudgetWhenTheCallerNamesNone() {
    val source = ImageSize(12_000, 9_000)

    assertEquals(
      SampleSize.forDecode(source, viewport, DecodeBudget.ForDisplay),
      SampleSize.forDecode(source, viewport),
    )
  }

  /**
   * A sample size below one is refused rather than returning the source unchanged.
   *
   * Zero would divide by zero and a negative would produce a negative dimension, so both have to
   * fail where the caller can see them. Platform decoders treat anything below 1 as 1, which would
   * quietly hand back an image several times larger than the budget allows.
   */
  @Test
  fun refusesASampleSizeBelowOne() {
    listOf(0, -1, Int.MIN_VALUE).forEach { bad ->
      assertFailsWith<IllegalArgumentException>("$bad was accepted as a sample size") {
        ImageSize(4_000, 3_000).sampledBy(bad)
      }
    }
  }
}
