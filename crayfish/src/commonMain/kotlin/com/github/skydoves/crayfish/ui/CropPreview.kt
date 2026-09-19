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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import com.github.skydoves.crayfish.geometry.CoordinateSpace

/**
 * Draws the image under the crop rectangle.
 *
 * Tiled: a low-resolution base layer that is always resident, plus higher-resolution tiles decoded
 * for the visible region only and evicted by a bounded cache. That is the whole reason a 108MP
 * photo is croppable at all: a single full-resolution bitmap of one is 412MiB, against a hardware
 * canvas that refuses anything past roughly 100MB.
 *
 * ## Where state is read
 *
 * Every gesture-driven read happens inside a draw lambda or a `snapshotFlow`, never in this
 * function's body. A read of `transform` in the body subscribes *composition* to it, so every
 * frame of a pinch re-runs this composable, its `remember`, its effects and its modifier chain.
 * A draw-lambda read invalidates the draw phase alone, which is all that has to happen: the
 * pixels are already decoded and only the matrix moved. The one body read is [CropState.status],
 * which settles once per source.
 *
 * ## Lifetime
 *
 * The decoded pixels live in a [TileStore] remembered against the source's cache key, closed when
 * the source changes or this composable leaves composition. Nothing decoded reaches saved state:
 * `remember`, never `rememberSaveable`, because a bitmap in a saved-state bundle is a
 * `TransactionTooLargeException` waiting for a big enough photo.
 *
 * ## Coordinates
 *
 * Image coordinates here are the oriented ones `CropState.imageSize` uses, which are the decoder's
 * own only while the Exif orientation is `NORMAL`. The store is handed the orientation and does the
 * round trip itself: regions go down into the file's grid, tiles come back turned.
 *
 * This used to be left undone, on the reasoning that un-rotating the region belonged with the crop
 * pipeline. The pipeline did do it, and the preview did not, so a quarter turned photo was drawn
 * from the wrong pixels and cropped from the right ones. The two paths disagreeing is worse than
 * either being wrong, because each looks correct on its own.
 */
@Composable
internal fun CropPreview(state: RealCropState, modifier: Modifier = Modifier) {
  // Keyed on the source: a new image gets a new store, and the store it replaces is closed by the
  // effect below. `remember`, not `rememberSaveable`; see the note above on saved state.
  val injected = LocalTileStore.current
  val store = remember(state, state.source.cacheKey, injected) { injected ?: TileStore() }

  // Native memory the collector cannot see and will not reclaim on its own. Closing on dispose is
  // what makes leaving the cropper give the pixels back.
  DisposableEffect(store) { onDispose { store.close() } }

  // `status` is not gesture-driven; it settles once per source, so reading it here costs one
  // recomposition rather than one per frame. `decoder` is a plain field assigned before `status`
  // becomes Ready, so it is in place by the time this recomposition runs.
  val ready = state.status as? CropStatus.Ready
  val decoder = if (ready == null) null else state.decoder

  LaunchedEffect(store, decoder, ready?.imageSize, ready?.orientation) {
    if (decoder == null || ready == null) return@LaunchedEffect
    store.open(
      decoder = decoder,
      imageSize = ready.imageSize,
      orientation = ready.orientation,
    )

    val scope = this
    // A snapshotFlow, not a composition read. It observes the same state a composable body would,
    // but the observation belongs to this coroutine instead of to the composition, and it
    // re-emits only when the geometry actually differs, so a finger held still queues nothing.
    snapshotFlow {
      TileRequest(
        imageSize = ready.imageSize,
        viewportSize = state.viewportSize,
        transform = state.transform,
        cropRect = state.cropRect,
      )
    }.collect { request -> store.request(request, scope) }
  }

  Box(
    modifier
      .fillMaxSize()
      .drawWithCache {
        // Allocated once per layout and rewritten in place each frame. The transform changes too
        // often for the matrix's *contents* to be worth caching; its backing array is another
        // matter, and a fresh one per frame at 120Hz is pure garbage.
        val matrix = Matrix()
        onDrawBehind {
          // Everything gesture-driven is read here, inside the draw lambda.
          val frame = store.frame()
          val space = CoordinateSpace.fitting(
            imageSize = state.imageSize,
            viewportSize = state.viewportSize,
            transform = state.transform,
          )
          if (!space.isValid) return@onDrawBehind

          clipRect {
            // The transform goes on the canvas and the tiles are drawn in the image's own pixel
            // coordinates. A pan or a zoom then moves the matrix and nothing else: the tiles that
            // are already decoded stay valid, which is what the whole cache depends on.
            withTransform({ transform(space.writeInto(matrix)) }) {
              drawTileLayer(base = frame.base, tiles = frame.tiles)
            }
          }
        }
      },
  )
}
