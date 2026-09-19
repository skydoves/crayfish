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

/**
 * What [TileCache] is allowed to hold.
 *
 * An interface rather than the concrete tile for one reason: the budget below is only real if
 * every eviction is paired with a [close], and a test that cannot count the closes cannot check
 * that. So the eviction rules can be driven by a tile that does nothing but count.
 */
internal interface TileEntry : AutoCloseable {

  /**
   * The decoded bytes this tile occupies.
   *
   * These are pixels, not objects: on Android a `Bitmap`'s pixels are native memory the collector
   * has no idea it is holding, and on Skia they are outside the heap entirely. Nothing about heap
   * pressure will make them go away, which is why the cache counts them itself.
   */
  val byteCount: Long
}

/**
 * A fixed-byte cache of decoded tiles that closes everything it evicts.
 *
 * ## The budget
 *
 * The cap is a **byte** count rather than a tile count. A tile count would be a lie on the two
 * axes that matter: the edge tiles of a grid are clipped to the image and so are smaller than the
 * rest, and a coarse tile and a fine tile of the same square of the source cost the same while
 * showing wildly different amounts of it. Bytes are the thing the platform actually runs out of.
 *
 * ## The eviction policy
 *
 * Least recently used, where "used" means read back out of the cache by [get], which the preview
 * does for every tile it is about to draw. So the tiles on screen are, by construction, the
 * youngest entries, and the first thing trimmed is whatever the user has scrolled away from. The
 * order is the insertion order of a [LinkedHashMap] with every read re-inserted at the tail;
 * `LinkedHashMap(accessOrder = true)` would say the same thing in one line and exists only on the
 * JVM.
 *
 * Trimming runs until the budget is met, with no reprieve for the entry just added: if a single
 * tile were larger than the whole budget it would be closed again immediately rather than allowed
 * to sit over the line. That keeps [byteCount] `<= maxByteCount` an invariant with no asterisk,
 * which is the only form of it worth asserting.
 *
 * Not thread safe, and deliberately so. Every call is made from the composition's thread: the
 * decode happens on a background dispatcher but hands its result back before touching this. A
 * lock here would cost every frame something to protect against a caller that does not exist.
 */
internal class TileCache<T : TileEntry>(internal val maxByteCount: Long = DEFAULT_MAX_BYTE_COUNT) {

  init {
    require(maxByteCount > 0) { "maxByteCount must be positive, was $maxByteCount" }
  }

  /** Insertion-ordered, and every [get] re-inserts, so the head is the least recently used. */
  private val entries = LinkedHashMap<TileKey, T>()

  /** The decoded bytes currently held. Never above [maxByteCount] once [put] has returned. */
  internal var byteCount: Long = 0L
    private set

  /**
   * How many tiles this cache has removed **and closed**: trimmed to budget, displaced by a newer
   * decode of the same key, or released by [clear].
   *
   * Every one of them was closed. That equality is the whole reason the budget means anything, so
   * it is counted rather than assumed, and there is a test that compares this against the closes
   * the tiles themselves observed.
   */
  internal var evictionCount: Int = 0
    private set

  internal val size: Int get() = entries.size

  internal val keys: Set<TileKey> get() = entries.keys

  /**
   * The tile for [key], marking it as the most recently used.
   *
   * Reading is what protects a tile, because reading is what drawing does.
   */
  internal operator fun get(key: TileKey): T? {
    val tile = entries.remove(key) ?: return null
    entries[key] = tile
    return tile
  }

  /** Whether [key] is held, without disturbing the eviction order: a scheduling question. */
  internal operator fun contains(key: TileKey): Boolean = entries.containsKey(key)

  /** Stores [tile] under [key], closing any tile it displaces, then trims to budget. */
  internal fun put(key: TileKey, tile: T) {
    entries.remove(key)?.let { displaced ->
      if (displaced === tile) {
        entries[key] = tile
        return
      }
      byteCount -= displaced.byteCount
      release(displaced)
    }
    entries[key] = tile
    byteCount += tile.byteCount
    trimToBudget()
  }

  /** Closes and forgets everything held. */
  internal fun clear() {
    val held = entries.values.toList()
    entries.clear()
    byteCount = 0L
    held.forEach { release(it) }
  }

  private fun trimToBudget() {
    val iterator = entries.entries.iterator()
    while (byteCount > maxByteCount && iterator.hasNext()) {
      val evicted = iterator.next().value
      iterator.remove()
      byteCount -= evicted.byteCount
      release(evicted)
    }
  }

  /**
   * The only place a tile leaves this cache, so the close cannot be forgotten on one path.
   *
   * [byteCount] is adjusted by the callers rather than here because [clear] zeroes it wholesale;
   * what is centralised is the pairing of "removed" with "closed".
   */
  private fun release(tile: T) {
    evictionCount++
    tile.close()
  }

  internal companion object {

    /**
     * The tile layer's byte budget: one display-sized image's worth of pixels.
     *
     * Derived from [DecodeBudget.ForDisplay] rather than chosen. That is this library's existing
     * answer to "how many decoded bytes may one thing on screen cost": 64MiB, exactly a 4096x4096
     * ARGB_8888 texture and comfortably under the ~100MB ceiling Android's hardware canvas
     * enforces. The tile layer *is* one thing on screen, so it gets one such allowance.
     *
     * [TileGrid.plan] sizes its tiles with `SampleSize.forDecode` against this same budget, so the
     * visible set is already known to fit inside it. The cache therefore never evicts a tile that
     * is on screen, and everything it does evict is something the user has moved away from.
     *
     * The base layer is accounted separately: one bitmap, admitted by the same
     * [DecodeBudget.ForDisplay], resident for the lifetime of the source rather than cached.
     */
    internal val DEFAULT_MAX_BYTE_COUNT: Long = DecodeBudget.ForDisplay.maxByteCount
  }
}
