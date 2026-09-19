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

import com.github.skydoves.crayfish.decode.DecodeBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A tile that holds nothing and counts its own closes.
 *
 * The budget is only real if every eviction is paired with a `close`, and the cheapest honest way
 * to check that is a tile whose whole behaviour is to notice when it happens. Counting rather than
 * flagging, so a double close (which would let a real tile free native memory twice) shows up as
 * a number rather than being absorbed.
 */
private class CountingTile(override val byteCount: Long) : TileEntry {

  var closeCount: Int = 0
    private set

  val isClosed: Boolean get() = closeCount > 0

  override fun close() {
    closeCount++
  }
}

private fun key(index: Int, sampleSize: Int = 1) =
  TileKey(sampleSize = sampleSize, column = index, row = 0)

class TileCacheTest {

  /**
   * The deliverable, stated as an invariant: drive far more tiles through the cache than fit, and
   * at no point does it hold more bytes than it was given, and at no point does it drop a tile
   * without closing it.
   *
   * Sizes vary because a uniform tile would let a cache that counted entries instead of bytes
   * pass, and the real grid produces uneven tiles at every edge of the image.
   */
  @Test
  fun theCacheNeverHoldsMoreThanItsBudgetAndClosesEveryTileItEvicts() {
    val budget = 16 * TileGrid.TILE_BYTE_COUNT
    val cache = TileCache<CountingTile>(budget)
    val issued = mutableListOf<CountingTile>()

    repeat(400) { index ->
      val tile = CountingTile(TileGrid.TILE_BYTE_COUNT / (1 + index % 4))
      issued += tile
      cache.put(key(index), tile)
      assertTrue(
        cache.byteCount <= budget,
        "after ${index + 1} tiles the cache held ${cache.byteCount} bytes, over the $budget " +
          "budget. Eviction is not keeping up, so the budget is a number and not a limit",
      )
    }

    val closed = issued.count { it.isClosed }
    assertEquals(
      cache.evictionCount,
      closed,
      "the cache says it evicted ${cache.evictionCount} tiles but only $closed were closed; " +
        "an eviction that does not close is a native-memory leak the collector cannot see",
    )
    assertEquals(
      issued.size - cache.size,
      closed,
      "every tile is either still held or closed, and nothing else",
    )
    assertTrue(issued.none { it.closeCount > 1 }, "a tile was closed twice")
    assertTrue(cache.size in 1..64, "the cache emptied or kept everything: size ${cache.size}")

    // And the residents really are the resident ones, not merely counted as such.
    val resident = cache.keys.toList()
    assertTrue(resident.all { assertNotNull(cache[it]).isClosed.not() })
  }

  /** Least recently used goes first, where "used" is a read, which is what drawing does. */
  @Test
  fun theLeastRecentlyUsedTileIsEvictedFirst() {
    val unit = TileGrid.TILE_BYTE_COUNT
    val cache = TileCache<CountingTile>(3 * unit)
    val a = CountingTile(unit)
    val b = CountingTile(unit)
    val c = CountingTile(unit)
    val d = CountingTile(unit)
    val e = CountingTile(unit)

    cache.put(key(0), a)
    cache.put(key(1), b)
    cache.put(key(2), c)
    assertEquals(0, cache.evictionCount, "three unit tiles fit a three-unit budget")

    // Touch the oldest. That is the whole point of LRU over FIFO: being looked at saves you.
    assertNotNull(cache[key(0)], "a resident tile must be readable")

    cache.put(key(3), d)
    assertTrue(b.isClosed, "b was the least recently used and should have gone first")
    assertFalse(a.isClosed, "a was read after b was inserted, so a must outlive b")
    assertFalse(c.isClosed)
    assertNull(cache[key(1)])

    cache.put(key(4), e)
    assertTrue(c.isClosed, "c was then the oldest")
    assertFalse(a.isClosed)
    assertFalse(d.isClosed)
    assertEquals(setOf(key(0), key(3), key(4)), cache.keys.toSet())
    assertEquals(2, cache.evictionCount)
  }

  /** `contains` is a scheduling question: asking it must not reprieve a tile. */
  @Test
  fun askingWhetherATileIsPresentDoesNotCountAsUsingIt() {
    val unit = TileGrid.TILE_BYTE_COUNT
    val cache = TileCache<CountingTile>(2 * unit)
    val a = CountingTile(unit)
    val b = CountingTile(unit)

    cache.put(key(0), a)
    cache.put(key(1), b)
    assertTrue(key(0) in cache)

    cache.put(key(2), CountingTile(unit))
    assertTrue(a.isClosed, "`contains` must not move a tile to the young end")
    assertFalse(b.isClosed)
  }

  @Test
  fun aTileDisplacedByANewerDecodeOfTheSameRegionIsClosed() {
    val cache = TileCache<CountingTile>(TileGrid.TILE_BYTE_COUNT * 4)
    val first = CountingTile(TileGrid.TILE_BYTE_COUNT)
    val second = CountingTile(TileGrid.TILE_BYTE_COUNT)

    cache.put(key(0), first)
    cache.put(key(0), second)

    assertTrue(first.isClosed)
    assertFalse(second.isClosed)
    assertEquals(1, cache.size)
    assertEquals(TileGrid.TILE_BYTE_COUNT, cache.byteCount, "the displaced bytes were not released")
    assertEquals(1, cache.evictionCount)
  }

  @Test
  fun clearClosesEverythingItWasHolding() {
    val cache = TileCache<CountingTile>(TileGrid.TILE_BYTE_COUNT * 8)
    val held = List(5) { CountingTile(TileGrid.TILE_BYTE_COUNT) }
    held.forEachIndexed { index, tile -> cache.put(key(index), tile) }

    cache.clear()

    assertTrue(held.all { it.isClosed })
    assertEquals(0, cache.size)
    assertEquals(0L, cache.byteCount)
    assertEquals(held.size, cache.evictionCount)
  }

  /**
   * The budget is derived from the display budget rather than picked, and it is a whole number of
   * tiles, which is what makes [TileGrid.maxTiles] a cap and not a rounding.
   */
  @Test
  fun theDefaultBudgetIsTheDisplayBudget() {
    assertEquals(DecodeBudget.ForDisplay.maxByteCount, TileCache.DEFAULT_MAX_BYTE_COUNT)
    assertEquals(TileCache.DEFAULT_MAX_BYTE_COUNT, TileCache<CountingTile>().maxByteCount)
    assertEquals(
      TileCache.DEFAULT_MAX_BYTE_COUNT,
      TileGrid.maxTiles(TileCache.DEFAULT_MAX_BYTE_COUNT) * TileGrid.TILE_BYTE_COUNT,
      "the budget must divide into whole tiles or the cap under-counts what fits",
    )
  }
}
