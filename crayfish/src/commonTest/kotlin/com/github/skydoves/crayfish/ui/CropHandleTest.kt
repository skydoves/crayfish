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
import kotlin.test.assertTrue

/**
 * Which edges each grab point moves.
 *
 * The whole resize path routes through these four predicates, so an inverted one silently drags the
 * wrong side of the rectangle. Exhaustive rather than sampled: there are only nine handles and four
 * questions, and the table is the specification.
 */
class CropHandleTest {

  /** handle to (left, top, right, bottom). */
  private val expected = mapOf(
    CropHandle.TopLeft to listOf(true, true, false, false),
    CropHandle.TopRight to listOf(false, true, true, false),
    CropHandle.BottomLeft to listOf(true, false, false, true),
    CropHandle.BottomRight to listOf(false, false, true, true),
    CropHandle.Top to listOf(false, true, false, false),
    CropHandle.Bottom to listOf(false, false, false, true),
    CropHandle.Left to listOf(true, false, false, false),
    CropHandle.Right to listOf(false, false, true, false),
    CropHandle.Inside to listOf(true, true, true, true),
  )

  @Test
  fun everyHandleMovesExactlyTheEdgesItShould() {
    CropHandle.entries.forEach { handle ->
      val want = requireNotNull(expected[handle]) { "no expectation for $handle" }
      val got = listOf(
        handle.movesLeftEdge,
        handle.movesTopEdge,
        handle.movesRightEdge,
        handle.movesBottomEdge,
      )
      assertEquals(want, got, "$handle moves the wrong edges (left, top, right, bottom)")
    }
  }

  /** Every handle is covered by the table above; a new one must not slip through untested. */
  @Test
  fun theTableCoversEveryHandle() {
    assertEquals(CropHandle.entries.toSet(), expected.keys)
  }

  @Test
  fun onlyTheFourCornersAreCorners() {
    val corners = CropHandle.entries.filter { it.isCorner }.toSet()

    assertEquals(
      setOf(
        CropHandle.TopLeft,
        CropHandle.TopRight,
        CropHandle.BottomLeft,
        CropHandle.BottomRight,
      ),
      corners,
    )
  }

  /** A corner moves one horizontal and one vertical edge; an edge handle moves exactly one. */
  @Test
  fun eachHandleMovesAConsistentNumberOfEdges() {
    CropHandle.entries.forEach { handle ->
      val moved = listOf(
        handle.movesLeftEdge,
        handle.movesTopEdge,
        handle.movesRightEdge,
        handle.movesBottomEdge,
      ).count { it }
      val want = when {
        handle == CropHandle.Inside -> 4
        handle.isCorner -> 2
        else -> 1
      }
      assertEquals(want, moved, "$handle moves $moved edges")
    }
  }

  @Test
  fun onlyInsideMovesBothSidesOfAnAxis() {
    CropHandle.entries.filter { it != CropHandle.Inside }.forEach { handle ->
      assertTrue(
        !(handle.movesLeftEdge && handle.movesRightEdge),
        "$handle drags both the left and right edges, which would only translate it",
      )
      assertTrue(
        !(handle.movesTopEdge && handle.movesBottomEdge),
        "$handle drags both the top and bottom edges",
      )
    }
  }
}
