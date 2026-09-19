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
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.github.skydoves.crayfish.decode.CropSource
import com.github.skydoves.crayfish.decode.ImageSize
import com.github.skydoves.crayfish.e2e.Fixtures
import com.github.skydoves.crayfish.e2e.MemoryProbe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The preview inside a real composition, on the fixture that is the whole point of the library.
 *
 * Everything else here tests a piece: the cache in isolation, the planner in isolation, the store
 * against a decoder. This is the one that puts them behind a `Cropper` and renders it, because the
 * failure this library exists to prevent (`Canvas: trying to draw too large bitmap`, or an
 * `OutOfMemoryError` on a 412MiB decode) happens at render time and nowhere else.
 *
 * The store is injected through [LocalTileStore] so the assertions can be about what was decoded
 * rather than about the absence of a crash. A render that quietly did nothing would otherwise
 * report a very small memory reading and pass.
 */
@OptIn(ExperimentalTestApi::class)
class TilePreviewComposeTest {

  @Test
  fun renderingA108MegapixelSourceStaysInsideTheBudget() {
    // Skiko's first window, its native library load and its class loading are one-time costs of
    // the harness, not of the preview. Paid before the probe starts so they are not attributed to
    // a decode they have nothing to do with.
    warmUpTheToolkit()

    val store = TileStore()
    val source = CropSource.FilePath(Fixtures.sensor108mp.absolutePath)

    val (_, reading) = MemoryProbe.measure {
      runComposeUiTest {
        var observed: CropState? = null
        setContent {
          CompositionLocalProvider(LocalTileStore provides store) {
            val state = rememberCropState(source)
            observed = state
            Cropper(state, Modifier.size(400.dp).testTag(TAG))
          }
        }

        waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) {
          observed?.status is CropStatus.Ready
        }
        assertEquals(
          ImageSize(12_000, 9_000),
          observed?.imageSize,
          "the fixture did not open at the size it should have",
        )

        // The render is only evidence if pixels really were decoded and drawn.
        waitUntil(timeoutMillis = DECODE_TIMEOUT_MILLIS) {
          store.baseLayer != null && store.tiles.size > 0
        }
        onNodeWithTag(TAG).assertIsDisplayed()

        // Rasterised, not merely composed. Everything above this line would still pass if the
        // draw lambda were empty; this is the assertion that says the pixels reached a canvas.
        val rendered = onNodeWithTag(TAG).captureToImage().toPixelMap()
        val painted = (0 until rendered.height step 8).sumOf { y ->
          (0 until rendered.width step 8).count { x -> rendered[x, y].alpha > 0f }
        }
        assertTrue(
          painted > 0,
          "the cropper rendered ${rendered.width}x${rendered.height} of nothing",
        )

        val base = assertNotNull(store.baseLayer, "no base layer, so nothing was rendered")
        assertTrue(
          store.tiles.byteCount <= store.tiles.maxByteCount,
          "the cache holds ${store.tiles.byteCount} bytes against ${store.tiles.maxByteCount}",
        )
        assertTrue(
          base.byteCount + store.tiles.byteCount < FULL_DECODE_BYTES,
          "the preview is holding as much as a full decode of the fixture would have",
        )
        assertTrue(
          store.frame().base != null && store.frame().tiles.isNotEmpty(),
          "the store would have handed the draw lambda nothing to draw",
        )
        println(
          "[preview] composed 108MP: base ${base.region} ${base.byteCount / 1024}KB, " +
            "${store.tiles.size} tiles / ${store.tiles.byteCount / 1024}KB, " +
            "$painted painted samples",
        )
      }
    }

    // Disposal happened when the composition ended; the pixels must have gone with it.
    assertEquals(null, store.baseLayer, "the base layer outlived the composition")
    assertEquals(0, store.tiles.size, "tiles outlived the composition")
    assertEquals(0L, store.tiles.byteCount)

    assertTrue(
      reading.allocatedBytes < MemoryProbe.BUDGET_BYTES,
      "rendering the 108MP fixture allocated $reading, over the " +
        "${MemoryProbe.BUDGET_BYTES / 1024 / 1024}MB budget",
    )
    println("[preview] compose render of 108MP: $reading")
  }

  private fun warmUpTheToolkit() = runComposeUiTest {
    setContent { Box(Modifier.size(8.dp).testTag("warmup")) }
    onNodeWithTag("warmup").assertIsDisplayed()
  }

  private companion object {
    const val TAG = "cropper"
    const val OPEN_TIMEOUT_MILLIS = 30_000L
    const val DECODE_TIMEOUT_MILLIS = 60_000L

    /** 12000x9000 as ARGB_8888: 412MiB, and the reason none of this can be done the obvious way. */
    val FULL_DECODE_BYTES: Long = ImageSize(12_000, 9_000).argb8888ByteCount
  }
}
