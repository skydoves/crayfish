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

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.github.skydoves.crayfish.decode.DecodeBudget
import com.github.skydoves.crayfish.decode.ImageRegion
import com.github.skydoves.crayfish.decode.ImageSize
import com.github.skydoves.crayfish.decode.RegionDecoder
import com.github.skydoves.crayfish.decode.SampleSize
import com.github.skydoves.crayfish.decode.decodeOrientedRegion
import com.github.skydoves.crayfish.exif.ImageOrientation
import com.github.skydoves.crayfish.geometry.FloatSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.roundToInt

/** What there is to draw for one frame, and the revision that produced it. */
internal data class TileFrame(
  internal val revision: Int,
  internal val base: PreviewTile?,
  internal val tiles: List<PreviewTile>,
)

/**
 * Where [CropPreview] gets its tile store.
 *
 * A composition local with a null default rather than a parameter on the composable: which store
 * the preview uses is not a caller's decision, but a test that drives a real composition needs a
 * handle on the instance to assert what it decoded. Production reads `null` and the preview
 * remembers its own.
 */
internal val LocalTileStore = staticCompositionLocalOf<TileStore?> { null }

/**
 * The pixels behind the crop frame: one resident base layer, plus tiles for what is on screen.
 *
 * ## What is resident and what is not
 *
 * The **base layer** is a single decode of the whole image, sampled down to the viewport by
 * [SampleSize.forDecode] under [DecodeBudget.ForDisplay], held for the lifetime of the source.
 * One decode, not one per gesture: it is the floor under every frame, and re-decoding it would
 * make panning a decode storm.
 *
 * **Tiles** are everything above that floor. They exist only for the region the crop frame is
 * looking at, at a sample size taken from the current zoom, and live in a [TileCache] with a byte
 * budget that closes what it evicts. The cache's budget is the only thing keeping anything alive.
 *
 * ## Threading
 *
 * Every method is called from the composition's thread, and every field below is read and written
 * there. The decode itself runs wherever the platform decoder puts it, `Dispatchers.IO` on the
 * JVM, but the result is handed back before it touches this class. That is why there is no lock:
 * the cache is only ever seen from one thread, including by the draw lambda, which runs on the
 * same thread as the composition it belongs to.
 *
 * ## Lifetime
 *
 * The [RegionDecoder] is **not** owned here. `RealCropState` opens it and closes it, and a store
 * that closed it would close it out from under the crop pipeline. What is owned is every decoded
 * pixel, and [close] releases all of them.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class TileStore(
  /**
   * The tile cache, and with it the byte budget.
   *
   * A constructor parameter rather than a hard-wired field, because a test that wants to watch
   * eviction happen needs a budget small enough to overflow without decoding sixty-four megabytes
   * of a real photograph to get there.
   */
  internal val tiles: TileCache<PreviewTile> = TileCache(),
) : AutoCloseable {

  private val maxTiles: Int = TileGrid.maxTiles(tiles.maxByteCount)
  private val inFlight = LinkedHashMap<TileKey, Job>()

  /**
   * The one piece of snapshot state here, and the only reason there is any.
   *
   * A tile arriving has to repaint the preview, but it must not recompose it: recomposition would
   * re-run the effect that watches the gesture, and during a pinch that is the difference between
   * a repaint and a rebuild. Bumping an int that only the draw lambda reads invalidates exactly
   * the draw phase.
   */
  private val revision = mutableIntStateOf(0)

  private var decoder: RegionDecoder? = null
  private var imageSize: ImageSize = ImageSize.Zero
  private var orientation: ImageOrientation = ImageOrientation.NORMAL
  private var base: PreviewTile? = null
  private var baseJob: Job? = null
  private var plan: TilePlan = TilePlan.Empty
  private var visible: List<PreviewTile> = emptyList()
  private var closed = false

  /**
   * Decoded tiles waiting to be taken into [tiles], published from whatever thread decoded them.
   *
   * ## Why this exists
   *
   * A decode is launched into the scope of the effect that drives requests, so which thread its
   * continuation resumes on belongs to whoever composed the cropper, not to this class. Measured on
   * a Galaxy S23 under the Compose test harness: `request` and `close` ran on `main`, while the
   * tail of eleven different decodes resumed on eleven different `DefaultDispatcher-worker`
   * threads. Those tails were writing straight into [tiles], a plain `LinkedHashMap`, while `close`
   * read it. The result was a null element out of `values.toList()` and a `NullPointerException`
   * that took the process down as the user left the crop screen.
   *
   * Writing "everything happens on the caller's thread" in a comment did not make it true, and an
   * earlier fix of the same shape had already established it for `inFlight` and stopped there.
   *
   * ## How it fixes it
   *
   * A decode publishes here with a compare-and-set and touches nothing else. Every structure this
   * class owns is then read and written in exactly one place, [drain], which runs only from the
   * owner's own entry points. No lock, because a lock held across a bitmap close is a UI stall, and
   * no dispatcher, because choosing one would be this class guessing at the caller's threading.
   */
  private val pendingTiles = AtomicReference<List<Pair<TileKey, PreviewTile>>>(emptyList())

  /** The base layer's own hand-off. Separate because it replaces rather than accumulates. */
  private val pendingBase = AtomicReference<PreviewTile?>(null)

  /**
   * Called on every touch of this store's own state, for the test that asserts they are all on one
   * thread. Null in production, and the only cost then is a null check.
   */
  internal var onStateTouched: (() -> Unit)? = null

  /** The resident low-resolution decode of the whole image, once it has arrived. */
  internal val baseLayer: PreviewTile? get() = base

  /**
   * What to draw right now.
   *
   * Call this **inside** a draw lambda. It reads [revision], which is snapshot state, so the read
   * subscribes whatever phase is running, and only the draw phase is meant to be subscribed.
   */
  internal fun frame(): TileFrame {
    // The draw phase is the one caller guaranteed to run whenever `revision` changes, so it is
    // also where a tile that arrived since the last frame gets taken in. Reading `revision` here
    // is what subscribes the draw phase to that.
    val current = revision.intValue
    drain()
    return TileFrame(revision = current, base = base, tiles = visible)
  }

  /**
   * Points this store at [decoder] for an image of [imageSize] in oriented space.
   *
   * Idempotent for the same triple, because the effect that calls it re-runs whenever the status it
   * keys on settles. A genuinely different source releases everything first: pixels decoded from
   * the old image are not merely stale, they are the largest thing the process is holding.
   *
   * @param imageSize the **oriented** size, the one `CropStatus.Ready` reports and every rectangle
   *   reaching this class is measured in.
   * @param orientation what is still outstanding on the decoder's pixels. Every region below is
   *   mapped into the file's grid with it and every tile is turned back, so a caller that leaves it
   *   `NORMAL` for a quarter turned photo gets regions read off the wrong axis and tiles drawn
   *   sideways.
   */
  internal fun open(
    decoder: RegionDecoder,
    imageSize: ImageSize,
    orientation: ImageOrientation = ImageOrientation.NORMAL,
  ) {
    if (closed) return
    if (this.decoder === decoder && this.imageSize == imageSize &&
      this.orientation == orientation
    ) {
      return
    }
    releasePixels()
    this.decoder = decoder
    this.imageSize = imageSize
    this.orientation = orientation
  }

  /**
   * Brings the tile set into line with what [request] is looking at.
   *
   * Three things happen, in this order and for this reason:
   *
   * 1. The base layer is started if it is not there yet, so there is always something to draw.
   * 2. Decodes for tiles that are no longer in the plan are **cancelled**. A region decoder
   *    serialises its reads, so a tile for somewhere the user has left is not merely wasted: it
   *    is queued in front of a tile they are looking at.
   * 3. Decodes are started for the tiles in the plan that are neither cached nor already running.
   *
   * @param scope the caller's scope. Decodes are its children, so leaving composition cancels them
   *   without this class having to own a scope it would then have to remember to cancel.
   */
  internal fun request(request: TileRequest, scope: CoroutineScope) {
    onStateTouched?.invoke()
    drain()
    if (closed || decoder == null) return
    startBaseLayer(request.viewportSize, scope)

    val next = TileGrid.plan(
      space = request.space,
      cropRect = request.cropRect,
      maxTiles = maxTiles,
    )
    // Before anything reads `inFlight`, so a tile whose decode finished is not mistaken for one
    // still running and skipped at the loop below.
    pruneCompleted()

    val wanted = next.keys.toHashSet()
    for (key in inFlight.keys.toList()) {
      if (key !in wanted) inFlight.remove(key)?.cancel()
    }

    plan = next
    for (key in next.keys) {
      if (key in tiles || key in inFlight) continue
      start(key, scope)
    }
    refreshVisible()
  }

  /**
   * Joins every decode this store has started. For tests, which cannot otherwise tell a store that
   * is finished from one that never started.
   */
  internal suspend fun awaitIdle() {
    baseJob?.join()
    drain()
    // Prunes after joining, because nothing removes an entry on its own any more. Without that
    // this loop never ends: every job is complete and the map is still full.
    while (inFlight.isNotEmpty()) {
      inFlight.values.toList().forEach { it.join() }
      pruneCompleted()
      drain()
    }
  }

  override fun close() {
    if (closed) return
    closed = true
    releasePixels()
    // Nulled, never closed: the decoder belongs to the crop state, which closes it when it leaves
    // composition. Closing it here would pull the file handle out from under the crop itself.
    decoder = null
  }

  private fun startBaseLayer(viewportSize: FloatSize, scope: CoroutineScope) {
    if (base != null || viewportSize.isEmpty) return
    // A cancelled job is not an attempt.
    //
    // The decode is launched into the scope of the effect that drives requests, and that effect
    // restarts whenever its keys change. A restart inside the couple of hundred milliseconds a base
    // decode takes killed the job and left this field holding a corpse, and the guard below then
    // declined forever: the cropper stayed blank for as long as the screen was open, with nothing
    // logged and nothing to retry it.
    //
    // A job that ran to completion without producing a tile is a different thing and still counts.
    // A decoder that cannot manage the whole image at a display sample size will not manage it on
    // the next frame either, and retrying per frame is the decode storm this rule exists to stop.
    val running = baseJob
    if (running != null && !running.isCancelled) return
    val open = decoder ?: return
    if (imageSize.width <= 0 || imageSize.height <= 0) return

    // Sized to the viewport rather than to the budget's ceiling. The base layer only has to look
    // right when the image is not zoomed in; anything finer than the viewport can show is a tile's
    // job, and decoding it here would spend 64MiB to draw a thumbnail.
    val target = ImageSize(
      width = viewportSize.width.roundToInt().coerceAtLeast(1),
      height = viewportSize.height.roundToInt().coerceAtLeast(1),
    )
    val sampleSize = SampleSize.forDecode(
      sourceSize = imageSize,
      targetSize = target,
      budget = DecodeBudget.ForDisplay,
    )

    // One attempt per source, deliberately: a decoder that cannot produce the whole image at a
    // display sample size will not manage it on the next frame either, and retrying per frame
    // would turn a failure into a decode storm. The preview stays empty until tiles arrive.
    baseJob = scope.launch {
      val decoded = open.decodeOrientedRegion(ImageRegion.of(imageSize), sampleSize, orientation)
      val tile = decoded?.let { PreviewTile.of(it) }
      if (tile == null) return@launch
      if (closed) {
        tile.close()
        return@launch
      }
      // Same hand-off as a tile, and for the same reason: this continuation is not on the
      // owner's thread either.
      pendingBase.exchange(tile)?.close()
      revision.intValue++
    }
  }

  private fun start(key: TileKey, scope: CoroutineScope) {
    inFlight[key] = scope.launch { load(key) }
  }

  /**
   * Drops the decodes that have finished, on the thread that owns the map.
   *
   * This used to be an `invokeOnCompletion` on each job, which is the obvious place for it and is
   * wrong: the handler runs on whatever thread completes the job, and a decode suspended inside
   * `withContext(Dispatchers.IO)` completes on an IO thread. So `inFlight` was a plain
   * `LinkedHashMap` being written from an IO thread while the composition thread iterated it in
   * `request` and `close`. That corrupts the map, and the symptom is a `NullPointerException`
   * inside `LinkedHashMap.values` during teardown, which is to say a crash when the user leaves
   * the crop screen.
   *
   * Every mutation now happens on the caller's thread, and since `request` runs on every frame the
   * geometry changes, an entry never lingers long. `isCompleted` is safe to read from anywhere.
   */
  private fun pruneCompleted() {
    if (inFlight.isEmpty()) return
    val finished = inFlight.entries.filter { it.value.isCompleted }.map { it.key }
    finished.forEach { inFlight.remove(it) }
  }

  private suspend fun load(key: TileKey) {
    val open = decoder ?: return
    val region = key.region(imageSize) ?: return
    val decoded = open.decodeOrientedRegion(region, key.sampleSize, orientation) ?: return
    val tile = PreviewTile.of(decoded) ?: return
    // Nothing suspends between here and the cache taking ownership, so a cancellation cannot land
    // in the gap and strand the pixels. The one window this cannot close is inside `decodeRegion`
    // itself: it returns through a `withContext`, and a job cancelled at that instant drops the
    // result before this frame ever sees it. Closing that would take a hand-off in the decoder
    // contract, which is not this layer's to change.
    if (closed) {
      tile.close()
      return
    }
    // Published, not stored. This line may be running on any thread at all; see [pendingTiles].
    publishTile(key, tile)
    // Snapshot state, which is safe to write from anywhere, and what wakes the draw phase up to
    // come and collect.
    revision.intValue++
  }

  /**
   * Re-reads the planned tiles out of the cache.
   *
   * Reading is what marks a tile recently used, so this is also what puts everything on screen at
   * the young end of the eviction order. Tiles that have not arrived are simply absent from the
   * list; the base layer covers where they will go.
   */
  private fun refreshVisible() {
    onStateTouched?.invoke()
    visible = plan.keys.mapNotNull { tiles[it] }
  }

  /** Appends to [pendingTiles] without a lock, from any thread. */
  private fun publishTile(key: TileKey, tile: PreviewTile) {
    while (true) {
      val current = pendingTiles.load()
      if (pendingTiles.compareAndSet(current, current + (key to tile))) return
    }
  }

  /**
   * Takes everything decoded since the last call into this store's own structures.
   *
   * The single place [tiles] and [base] are written, and it runs only from [request], [frame],
   * [awaitIdle] and [close], which is what confines them to one thread. Taking the whole inbox in
   * one `exchange` rather than draining item by item means a decode that finishes mid-drain lands
   * in the next round instead of racing this one.
   */
  private fun drain() {
    val delivered = pendingTiles.exchange(emptyList())
    val deliveredBase = pendingBase.exchange(null)
    if (delivered.isEmpty() && deliveredBase == null) return
    onStateTouched?.invoke()
    if (closed) {
      // Arrived after teardown. Nobody wants these pixels any more, and they are the largest thing
      // the process is holding.
      delivered.forEach { (_, tile) -> tile.close() }
      deliveredBase?.close()
      return
    }
    delivered.forEach { (key, tile) -> tiles.put(key, tile) }
    if (deliveredBase != null) {
      base?.close()
      base = deliveredBase
    }
    refreshVisible()
  }

  private fun releasePixels() {
    onStateTouched?.invoke()
    // Before anything is torn down, so a tile that arrived while this was being called is closed
    // rather than stranded in an inbox nobody will read again.
    pendingTiles.exchange(emptyList()).forEach { (_, tile) -> tile.close() }
    pendingBase.exchange(null)?.close()
    inFlight.values.toList().forEach { it.cancel() }
    inFlight.clear()
    baseJob?.cancel()
    baseJob = null
    base?.close()
    base = null
    plan = TilePlan.Empty
    visible = emptyList()
    tiles.clear()
    revision.intValue++
  }
}
