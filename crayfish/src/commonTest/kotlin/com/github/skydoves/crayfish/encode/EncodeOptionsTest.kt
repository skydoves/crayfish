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
package com.github.skydoves.crayfish.encode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The two quality numbers, which are not interchangeable.
 *
 * Lossy quality and lossless effort share a 0..100 range and mean opposite things: one is how much
 * detail to keep, the other how long to spend keeping all of it. A pipeline that reads the wrong
 * one produces a file that is right in size and wrong in content, or the reverse. They are
 * separate fields for that reason, and [EncodeOptions.platformQuality] is the one place the choice
 * between them is made.
 */
class EncodeOptionsTest {

  @Test
  fun lossyFormatsReadTheLossyQualityAndTheLosslessOneReadsItsEffort() {
    val options = EncodeOptions(
      format = EncodedFormat.JPEG,
      lossyQuality = 55,
      losslessEffort = 77,
    )

    assertEquals(55, options.copy(format = EncodedFormat.JPEG).platformQuality)
    assertEquals(55, options.copy(format = EncodedFormat.WEBP_LOSSY).platformQuality)
    assertEquals(
      77,
      options.copy(format = EncodedFormat.WEBP_LOSSLESS).platformQuality,
      "the lossless format read the lossy number",
    )
  }

  /**
   * PNG is lossless and has no quality dial at all, so whatever it reports must not vary with the
   * lossy setting. A caller that lowers quality to shrink a PNG is asking for something the format
   * cannot do, and silently honouring it with a different number would be worse than ignoring it.
   */
  @Test
  fun pngsQualityDoesNotFollowTheLossySetting() {
    val low = EncodeOptions(EncodedFormat.PNG, lossyQuality = 1).platformQuality
    val high = EncodeOptions(EncodedFormat.PNG, lossyQuality = 100).platformQuality

    assertEquals(low, high, "PNG's quality changed with a setting PNG cannot use")
  }

  @Test
  fun theDefaultsAreTheOnesTheApiDocuments() {
    val options = EncodeOptions(EncodedFormat.JPEG)

    assertEquals(90, options.lossyQuality)
    assertEquals(80, options.losslessEffort)
  }

  @Test
  fun acceptsBothEndsOfBothRanges() {
    listOf(0, 100).forEach { edge ->
      assertEquals(edge, EncodeOptions(EncodedFormat.JPEG, lossyQuality = edge).lossyQuality)
      assertEquals(
        edge,
        EncodeOptions(EncodedFormat.WEBP_LOSSLESS, losslessEffort = edge).losslessEffort,
      )
    }
  }

  /**
   * Out of range is refused rather than clamped.
   *
   * Every platform encoder underneath treats an out-of-range quality differently: some clamp, some
   * throw, Skia's `encodeToData` simply returns null. A value that is nonsense here has to fail
   * here, where the message can name the field, rather than three layers down as an empty result.
   */
  @Test
  fun refusesAQualityOutsideZeroToOneHundred() {
    listOf(-1, 101, Int.MIN_VALUE, Int.MAX_VALUE).forEach { bad ->
      assertFailsWith<IllegalArgumentException>("lossyQuality $bad was accepted") {
        EncodeOptions(EncodedFormat.JPEG, lossyQuality = bad)
      }
      assertFailsWith<IllegalArgumentException>("losslessEffort $bad was accepted") {
        EncodeOptions(EncodedFormat.JPEG, losslessEffort = bad)
      }
    }
  }

  @Test
  fun aValidLossyQualityDoesNotExcuseAnInvalidEffort() {
    assertFailsWith<IllegalArgumentException> {
      EncodeOptions(EncodedFormat.WEBP_LOSSLESS, lossyQuality = 90, losslessEffort = 101)
    }
    assertFailsWith<IllegalArgumentException> {
      EncodeOptions(EncodedFormat.WEBP_LOSSLESS, lossyQuality = 101, losslessEffort = 80)
    }
  }

  @Test
  fun everyFormatReportsAQualityTheEncodersCanUse() {
    EncodedFormat.entries.forEach { format ->
      val quality = EncodeOptions(format).platformQuality
      assertEquals(
        quality.coerceIn(0, 100),
        quality,
        "$format reports a platform quality of $quality, outside every encoder's range",
      )
    }
  }
}
