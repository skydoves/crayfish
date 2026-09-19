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
package com.github.skydoves.crayfish.geometry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CropBoundsTest {

  /**
   * A thousandth of a viewport unit. The fixtures here are whole numbers whose arithmetic is exact
   * in binary floating point apart from the trigonometry, so anything above a few ulps of a
   * four-digit coordinate (around 1e-3) would be a real error.
   */
  private val tolerance = 1e-3f

  private val viewport = FloatSize(1000f, 800f)
  private val viewportRect = FloatRect.of(viewport)

  /**
   * The second branch, and the one that is usually missing.
   *
   * A 200x200 image letterboxed into a 1000x800 viewport cannot cover it at any offset, so there
   * is no legal interval to clamp into. The wrong answer, and the one a single-branch clamp gives,
   * is to leave the image wherever the last clamp shoved it, which after a fling is a corner. The
   * right answer is to align it, which for a cropper means the middle.
   */
  @Test
  fun contentSmallerThanTheRegionIsAlignedRatherThanLeftInACorner() {
    val content = FloatRect(400f, 300f, 600f, 500f)
    val flungIntoACorner = CropTransform(offset = FloatPoint(-5000f, 5000f))

    val coerced = CropBounds.coerceOffset(flungIntoACorner, content, viewportRect)
    val placed = CropTransform(offset = coerced).mapRect(content, content.center)

    assertClose(FloatPoint(0f, 0f), coerced, tolerance, "offset should undo the fling exactly")
    assertClose(
      viewportRect.center,
      placed.center,
      tolerance,
      "content should settle in the middle",
    )
    assertTrue(placed.left > viewportRect.left, "not flush to the left edge: $placed")
    assertTrue(placed.right < viewportRect.right, "not flush to the right edge: $placed")
    assertTrue(placed.top > viewportRect.top, "not flush to the top edge: $placed")
    assertTrue(placed.bottom < viewportRect.bottom, "not flush to the bottom edge: $placed")
  }

  /** Alignment is a parameter, not a hard-coded centre: `0f` is flush to the left and top. */
  @Test
  fun theAlignmentOfSmallContentIsConfigurable() {
    val content = FloatRect(400f, 300f, 600f, 500f)
    val transform = CropTransform(offset = FloatPoint(-5000f, 5000f))

    val topLeft = CropTransform(
      offset = CropBounds.coerceOffset(transform, content, viewportRect, FloatPoint(0f, 0f)),
    ).mapRect(content, content.center)
    val bottomRight = CropTransform(
      offset = CropBounds.coerceOffset(transform, content, viewportRect, FloatPoint(1f, 1f)),
    ).mapRect(content, content.center)

    assertClose(FloatRect(0f, 0f, 200f, 200f), topLeft, tolerance, "flush to the top-left")
    assertClose(FloatRect(800f, 600f, 1000f, 800f), bottomRight, tolerance, "flush bottom-right")
  }

  /**
   * The first branch. A 2000x1600 content in a 1000x800 viewport dragged 900 units left has run
   * off the right-hand edge by 400; the clamp must give back exactly those 400 and not a unit more,
   * because a clamp that overshoots reads as the image springing back.
   */
  @Test
  fun contentLargerThanTheRegionIsClampedByTheSmallestMovePossible() {
    val content = FloatRect(-500f, -400f, 1500f, 1200f)
    val draggedTooFar = CropTransform(offset = FloatPoint(-900f, 0f))

    val coerced = CropBounds.coerceOffset(draggedTooFar, content, viewportRect)

    // Content right edge sits at 1500 - 900 = 600, which is 400 short of the viewport's 1000.
    assertClose(FloatPoint(-500f, 0f), coerced, tolerance, "give back exactly the overshoot")
  }

  /** A legal pan comes back bit-identical: a clamp that nudges every frame is one that drifts. */
  @Test
  fun anOffsetThatAlreadyCoversTheRegionIsReturnedUntouched() {
    val content = FloatRect(-500f, -400f, 1500f, 1200f)
    val legal = CropTransform(offset = FloatPoint(-123f, 77f))

    assertEquals(legal.offset, CropBounds.coerceOffset(legal, content, viewportRect))
  }

  /**
   * The clamp is against the content's fitted rectangle, not the container.
   *
   * A 16:9 image letterboxed into a portrait viewport is 1080x607.5 inside 1080x1920: it does not
   * fill it and never will. A container-relative clamp, which is what Landscapist's equivalent
   * computes, would demand the image cover all 1920 and pull it somewhere arbitrary. Against the
   * content's own bounds the answer is that no pan is needed at all.
   */
  @Test
  fun theRegionToCoverIsTheContentRectangleNotTheViewport() {
    val portrait = FloatRect(0f, 0f, 1080f, 1920f)
    val content = fitInside(FloatSize(1600f, 900f), portrait)
    val cropFrame = FloatRect.fromCenter(portrait.center, FloatSize(1080f, 607.5f))
    val untouched = CropTransform()

    assertClose(
      expected = untouched.offset,
      actual = CropBounds.coerceOffset(untouched, content, cropFrame),
      tolerance = tolerance,
      message = "a letterboxed image already covering its own bounds needs no pan",
    )
  }

  /**
   * At a rotation the clamp still works, because it is computed in the frame where the content is
   * axis-aligned again, and the two branches can then disagree per axis.
   *
   * A 1000x800 content turned 90 degrees about its centre (500, 400) occupies 800 wide by 1000
   * tall. It is too narrow to cover the 1000-wide viewport, so x takes the alignment branch and
   * settles at 100..900; it is tall enough to cover the 800-tall viewport, so y takes the clamp
   * branch and gives back exactly the 500 units of the overshoot, landing at -200..800. Hard-coding
   * a single branch gets one of these two axes wrong whichever branch is chosen.
   */
  @Test
  fun bothBranchesCanApplyAtOnceOnDifferentAxes() {
    val content = FloatRect(0f, 0f, 1000f, 800f)
    val turned = CropTransform(rotationDegrees = 90f, offset = FloatPoint(300f, -600f))

    val coerced = CropBounds.coerceOffset(turned, content, viewportRect)
    val placed = turned.copy(offset = coerced).mapRect(content, content.center)

    assertClose(FloatPoint(0f, -100f), coerced, tolerance, "offset in viewport space")
    assertClose(FloatRect(100f, -200f, 900f, 800f), placed, tolerance, "mixed branches")
  }

  @Test
  fun degenerateRectanglesLeaveTheOffsetAloneWithoutThrowing() {
    val content = FloatRect(0f, 0f, 200f, 200f)
    val transform = CropTransform(offset = FloatPoint(37f, -11f))
    val broken = listOf(
      FloatRect.Zero,
      FloatRect(0f, 0f, Float.NaN, 100f),
      FloatRect(100f, 100f, 0f, 0f),
      FloatRect(0f, 0f, Float.POSITIVE_INFINITY, 100f),
    )

    broken.forEach { rect ->
      assertEquals(
        transform.offset,
        CropBounds.coerceOffset(transform, content, rect),
        "degenerate cover region $rect",
      )
      assertEquals(
        transform.offset,
        CropBounds.coerceOffset(transform, rect, viewportRect),
        "degenerate content $rect",
      )
    }
  }

  @Test
  fun aNonFiniteTransformOrAlignmentStillProducesAFiniteOffset() {
    val content = FloatRect(0f, 0f, 200f, 200f)
    val poisoned = CropTransform(scale = Float.NaN, offset = FloatPoint(Float.NaN, 3f))

    assertFinite(
      CropBounds.coerceOffset(poisoned, content, viewportRect),
      "non-finite transform",
    )
    assertFinite(
      CropBounds.coerceOffset(
        CropTransform(),
        content,
        viewportRect,
        FloatPoint(Float.NaN, Float.NaN),
      ),
      "non-finite alignment",
    )
  }
}
