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

import com.github.skydoves.crayfish.geometry.FloatPoint
import com.github.skydoves.crayfish.geometry.FloatRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which grab point a touch resolves to.
 *
 * The fixture is deliberately asymmetric: off-centre in its viewport, and wider than it is tall.
 * A mirrored axis, a swapped pair of corners or an x/y transposition then cannot pass by landing
 * on the same number twice.
 */
class CropHandleHitTestTest {

  /** 280 x 120, off-centre. No two edges are the same distance from any corner. */
  private val rect = FloatRect(80f, 140f, 360f, 260f)

  private val radius = 24f

  // -----------------------------------------------------------------------------------------------
  // Corner versus edge, at the point where the two targets overlap.
  // -----------------------------------------------------------------------------------------------

  /**
   * The ambiguous touch, stated once per corner.
   *
   * Every one of these points is within [radius] of a corner *and* exactly on an edge, so both
   * targets are in range and the tie has to be broken by a rule rather than by whichever list is
   * iterated first. The rule is the standard one: the corner wins, because resizing two edges at
   * once is the more common intent and is the only thing an edge drag cannot reproduce.
   */
  @Test
  fun aTouchInTheOverlapBetweenACornerAndAnEdgeResolvesToTheCorner() {
    val ambiguous = mapOf(
      // 18px down the left edge from the top-left corner: on the edge, inside the corner's radius.
      FloatPoint(rect.left, rect.top + 18f) to CropHandle.TopLeft,
      // 18px along the top edge from the top-right corner.
      FloatPoint(rect.right - 18f, rect.top) to CropHandle.TopRight,
      FloatPoint(rect.left + 18f, rect.bottom) to CropHandle.BottomLeft,
      FloatPoint(rect.right, rect.bottom - 18f) to CropHandle.BottomRight,
    )

    ambiguous.forEach { (point, corner) ->
      assertEquals(
        corner,
        hitTestCropHandle(rect, point, radius),
        "$point is in range of both $corner and an edge, so it must resolve to the corner",
      )
    }
  }

  /**
   * The other side of the same tie: past the corner's radius, the edge takes over.
   *
   * Without this the test above would also pass for an implementation that answered "corner" for
   * every touch anywhere near the rectangle.
   */
  @Test
  fun aTouchFurtherAlongAnEdgeThanTheCornerRadiusResolvesToTheEdge() {
    val onlyTheEdge = mapOf(
      FloatPoint(rect.left, rect.top + 40f) to CropHandle.Left,
      FloatPoint(rect.right, rect.bottom - 40f) to CropHandle.Right,
      FloatPoint(rect.left + 40f, rect.top) to CropHandle.Top,
      FloatPoint(rect.right - 40f, rect.bottom) to CropHandle.Bottom,
    )

    onlyTheEdge.forEach { (point, edge) ->
      assertEquals(
        edge,
        hitTestCropHandle(rect, point, radius),
        "$point is 40px from the nearest corner, well outside the ${radius}px corner target",
      )
    }
  }

  // -----------------------------------------------------------------------------------------------
  // The touch target is not the drawn handle.
  // -----------------------------------------------------------------------------------------------

  /**
   * `CropStyle.handleTouchRadius` is a published accessibility claim, the WCAG 2.2 24dp floor, and
   * it is only true if the target extends *outside* the drawn rectangle, where a finger aiming at
   * a corner actually lands. A corner handle is drawn 3dp thick and 20dp long and reaches nothing
   * outside the frame at all.
   */
  @Test
  fun aTouchOutsideTheDrawnHandleButInsideTheTouchTargetStillGrabsIt() {
    // 16px out on each axis: 22.6px from the corner, outside anything that is drawn, inside 24.
    val outsideTheCorners = mapOf(
      FloatPoint(rect.left - 16f, rect.top - 16f) to CropHandle.TopLeft,
      FloatPoint(rect.right + 16f, rect.top - 16f) to CropHandle.TopRight,
      FloatPoint(rect.left - 16f, rect.bottom + 16f) to CropHandle.BottomLeft,
      FloatPoint(rect.right + 16f, rect.bottom + 16f) to CropHandle.BottomRight,
    )

    outsideTheCorners.forEach { (point, corner) ->
      assertEquals(corner, hitTestCropHandle(rect, point, radius), "$point should grab $corner")
    }
  }

  /** And the target stops somewhere: 30px out on both axes is 42px away and grabs nothing. */
  @Test
  fun aTouchBeyondTheTouchTargetGrabsNothing() {
    assertNull(hitTestCropHandle(rect, FloatPoint(rect.left - 30f, rect.top - 30f), radius))
    assertNull(hitTestCropHandle(rect, FloatPoint(rect.right + 30f, rect.bottom + 30f), radius))
  }

  /**
   * A touch level with an edge but off the end of it is not on that edge.
   *
   * The left edge spans 140..260; a touch at y = 400 shares the edge's x and nothing else. Without
   * the span test it would read as a left-edge grab from anywhere down the screen.
   */
  @Test
  fun aTouchLevelWithAnEdgeButPastItsEndGrabsNothing() {
    assertNull(
      hitTestCropHandle(rect, FloatPoint(rect.left, rect.bottom + 140f), radius),
      "past the bottom end of the left edge",
    )
    assertNull(
      hitTestCropHandle(rect, FloatPoint(rect.left, rect.top - 140f), radius),
      "past the top end of the left edge",
    )
    assertNull(
      hitTestCropHandle(rect, FloatPoint(rect.right + 140f, rect.top), radius),
      "past the right end of the top edge",
    )
    assertNull(
      hitTestCropHandle(rect, FloatPoint(rect.left - 140f, rect.top), radius),
      "past the left end of the top edge",
    )
  }

  /**
   * A rectangle narrower than two touch radii puts both side edges in range at once, and the
   * nearer one has to win. This is the only case where the edge search has more than one candidate
   * to choose between, and it is reachable: nothing stops a crop rectangle being dragged down to
   * `minimumCropSize`, which is 56dp against a 24dp radius.
   */
  @Test
  fun aRectangleNarrowerThanTwoTouchRadiiPicksTheNearerEdge() {
    val narrow = FloatRect(120f, 60f, 150f, 400f)

    assertEquals(
      CropHandle.Left,
      hitTestCropHandle(narrow, FloatPoint(130f, 230f), radius),
      "10px from the left edge and 20px from the right",
    )
    assertEquals(
      CropHandle.Right,
      hitTestCropHandle(narrow, FloatPoint(144f, 230f), radius),
      "24px from the left edge and 6px from the right",
    )
  }

  @Test
  fun aTouchInTheMiddleGrabsTheWholeRectangle() {
    assertEquals(CropHandle.Inside, hitTestCropHandle(rect, FloatPoint(220f, 200f), radius))
  }

  // -----------------------------------------------------------------------------------------------
  // Degenerate input.
  // -----------------------------------------------------------------------------------------------

  /**
   * Nothing here can throw or return a handle for geometry that does not exist. A crop rectangle
   * is NaN for one frame whenever a viewport is measured as zero, and a pointer position arrives
   * from the platform rather than from this library.
   */
  @Test
  fun geometryThatDoesNotExistGrabsNothing() {
    val inside = FloatPoint(220f, 200f)

    assertNull(
      hitTestCropHandle(FloatRect(80f, 140f, Float.NaN, 260f), inside, radius),
      "a NaN edge",
    )
    assertNull(
      hitTestCropHandle(FloatRect(80f, 140f, Float.POSITIVE_INFINITY, 260f), inside, radius),
      "an infinite edge",
    )
    assertNull(
      hitTestCropHandle(FloatRect(80f, 140f, 80f, 260f), inside, radius),
      "a rectangle of zero width",
    )
    assertNull(
      hitTestCropHandle(FloatRect(360f, 140f, 80f, 260f), inside, radius),
      "an inverted rectangle",
    )
    assertNull(hitTestCropHandle(rect, FloatPoint(Float.NaN, 200f), radius), "a NaN touch")
    assertNull(
      hitTestCropHandle(rect, FloatPoint(220f, Float.NEGATIVE_INFINITY), radius),
      "an infinite touch",
    )
  }

  /**
   * An unusable touch radius degrades to an exact hit rather than to a radius of NaN, which would
   * make every comparison false and leave the rectangle ungrabbable except through `Inside`.
   */
  @Test
  fun anUnusableTouchRadiusFallsBackToAnExactHit() {
    val unusableRadii = listOf(
      0f,
      -8f,
      Float.NaN,
      Float.POSITIVE_INFINITY,
      Float.NEGATIVE_INFINITY,
    )
    unusableRadii.forEach { unusable ->
      assertEquals(
        CropHandle.TopLeft,
        hitTestCropHandle(rect, FloatPoint(rect.left, rect.top), unusable),
        "a touch exactly on the corner, radius $unusable",
      )
      assertEquals(
        CropHandle.Inside,
        hitTestCropHandle(rect, FloatPoint(rect.left + 1f, rect.top + 1f), unusable),
        "one pixel inside the corner is no longer the corner at radius $unusable",
      )
      assertNull(
        hitTestCropHandle(rect, FloatPoint(rect.left - 1f, rect.top - 1f), unusable),
        "one pixel outside the corner grabs nothing at radius $unusable",
      )
    }
  }
}
