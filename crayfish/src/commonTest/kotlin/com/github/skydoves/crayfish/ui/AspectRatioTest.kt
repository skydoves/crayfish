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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The named ratios, and the arithmetic that makes them mean anything.
 *
 * [AspectRatio.Fixed] is width divided by height, so a preset that is transposed (4:3 where 3:4
 * was meant) is still a perfectly valid, perfectly silent [AspectRatio]. Nothing downstream can
 * catch it: the crop rectangle holds whatever proportions it is given and the output is a
 * landscape photograph where the caller asked for a portrait one. The orientation assertions below
 * are the point of this file; the exact values are the cheaper half.
 */
class AspectRatioTest {

  @Test
  fun theNamedRatiosAreTheRatiosTheyAreNamedAfter() {
    assertEquals(AspectRatio.Fixed(1f), AspectRatio.Square)
    assertEquals(AspectRatio.Fixed(3f / 4f), AspectRatio.Portrait3x4)
    assertEquals(AspectRatio.Fixed(4f / 3f), AspectRatio.Landscape4x3)
    assertEquals(AspectRatio.Fixed(16f / 9f), AspectRatio.Widescreen16x9)
    assertEquals(AspectRatio.Fixed(9f / 16f), AspectRatio.Portrait9x16)
  }

  /** The assertion a transposed preset cannot survive. */
  @Test
  fun everyPortraitPresetIsTallerThanItIsWideAndEveryLandscapeOneIsNot() {
    assertTrue(ratioOf(AspectRatio.Portrait3x4) < 1f, "Portrait3x4 is not a portrait ratio")
    assertTrue(ratioOf(AspectRatio.Portrait9x16) < 1f, "Portrait9x16 is not a portrait ratio")
    assertTrue(ratioOf(AspectRatio.Landscape4x3) > 1f, "Landscape4x3 is not a landscape ratio")
    assertTrue(ratioOf(AspectRatio.Widescreen16x9) > 1f, "Widescreen16x9 is not a landscape ratio")
    assertEquals(1f, ratioOf(AspectRatio.Square), 0f, "Square is not square")
  }

  @Test
  fun thePortraitPresetsAreTheReciprocalsOfTheirLandscapeTwins() {
    assertEquals(1f, ratioOf(AspectRatio.Portrait3x4) * ratioOf(AspectRatio.Landscape4x3), 1e-6f)
    assertEquals(1f, ratioOf(AspectRatio.Portrait9x16) * ratioOf(AspectRatio.Widescreen16x9), 1e-6f)
  }

  @Test
  fun theFivePresetsAreFiveDifferentRatios() {
    val presets = listOf(
      AspectRatio.Square,
      AspectRatio.Portrait3x4,
      AspectRatio.Landscape4x3,
      AspectRatio.Widescreen16x9,
      AspectRatio.Portrait9x16,
    )

    assertEquals(presets.size, presets.map { ratioOf(it) }.toSet().size, "two presets are the same")
  }

  /**
   * A ratio that is not a positive finite number is rejected at construction.
   *
   * Zero and negatives have no rectangle; NaN poisons every comparison it touches, so a crop frame
   * built from one would neither hold its proportions nor fail: it would simply stop responding to
   * the handles. Failing here names the caller's mistake instead.
   */
  @Test
  fun refusesARatioThatCouldNotDescribeARectangle() {
    listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { bad ->
      assertFailsWith<IllegalArgumentException>("$bad was accepted as a ratio") {
        AspectRatio.Fixed(bad)
      }
    }
  }

  @Test
  fun acceptsTheExtremeButLegalRatiosARealImageCanHave() {
    // A 1:14 panorama and its transpose, both of which exist in the fixture corpus.
    assertEquals(14f, ratioOf(AspectRatio.Fixed(14f)), 0f)
    assertEquals(1f / 14f, ratioOf(AspectRatio.Fixed(1f / 14f)), 0f)
  }

  /**
   * Free is a third thing, not a ratio of 1.
   *
   * Collapsing it into `Fixed(1f)` is the tempting simplification and it is wrong: a free rectangle
   * may be dragged to any proportions, and a square one may not. Every preset therefore has to be
   * distinguishable from [AspectRatio.Free] by a `when`, which is what the exhaustive branch below
   * asserts. If a third subtype is ever added, this stops compiling rather than silently falling
   * into whichever arm happens to be last.
   */
  @Test
  fun freeIsItsOwnThingAndNotASquare() {
    val everyRatio: List<AspectRatio> = listOf(
      AspectRatio.Free,
      AspectRatio.Square,
      AspectRatio.Portrait3x4,
      AspectRatio.Landscape4x3,
      AspectRatio.Widescreen16x9,
      AspectRatio.Portrait9x16,
    )

    val held = everyRatio.map { aspectRatio ->
      when (aspectRatio) {
        is AspectRatio.Free -> null
        is AspectRatio.Fixed -> aspectRatio.ratio
      }
    }

    assertEquals(listOf(null, 1f, 3f / 4f, 4f / 3f, 16f / 9f, 9f / 16f), held)
    assertTrue(AspectRatio.Free != AspectRatio.Square, "Free was collapsed into a square")
  }

  private fun ratioOf(aspectRatio: AspectRatio): Float = (aspectRatio as AspectRatio.Fixed).ratio
}
