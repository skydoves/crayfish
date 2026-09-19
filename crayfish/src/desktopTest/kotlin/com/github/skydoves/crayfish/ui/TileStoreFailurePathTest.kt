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

import com.github.skydoves.crayfish.decode.DecodedRegion
import com.github.skydoves.crayfish.decode.ImageFormat
import com.github.skydoves.crayfish.decode.ImageRegion
import com.github.skydoves.crayfish.decode.ImageSize
import com.github.skydoves.crayfish.decode.RegionDecoder
import com.github.skydoves.crayfish.decode.platformImageOfArgbPixels
import com.github.skydoves.crayfish.exif.ImageOrientation
import com.github.skydoves.crayfish.geometry.CropTransform
import com.github.skydoves.crayfish.geometry.FloatRect
import com.github.skydoves.crayfish.geometry.FloatSize
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the preview does when the decoder cannot produce anything.
 *
 * A tiled preview is the one part of a cropper that keeps asking for work while the user does
 * nothing, so its failure paths are the ones that turn a single bad file into a decode storm or a
 * steadily growing cache of nothing. All of these have to end quietly: no tiles, no crash, and no
 * retry loop.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TileStoreFailurePathTest {

  private val imageSize = ImageSize(4_000, 3_000)
  private val viewport = FloatSize(1080f, 1920f)
  private val cropRect = FloatRect(108f, 192f, 972f, 1728f)

  private fun request(scale: Float = 10f) = TileRequest(
    imageSize = imageSize,
    viewportSize = viewport,
    transform = CropTransform(scale = scale),
    cropRect = cropRect,
  )

  @Test
  fun aStoreThatWasNeverOpenedAsksForNothing() = runTest {
    val store = TileStore()

    store.request(request(), backgroundScope)
    runCurrent()

    assertEquals(0, store.tiles.size)
    assertNull(store.baseLayer)
    store.close()
  }

  /**
   * A decoder that answers every region with `null` leaves nothing behind and does not retry.
   *
   * One attempt per source is deliberate: a decoder that cannot produce the base layer at a display
   * sample size will not manage it on the next frame either, and retrying per frame would turn one
   * unreadable file into a decode storm for as long as the screen is open.
   */
  @Test
  fun aDecoderThatProducesNothingIsAskedOnceAndLeavesNoTiles() = runTest {
    val decoder = NullTileDecoder(imageSize)
    val store = TileStore()
    store.open(decoder, imageSize)

    store.request(request(), backgroundScope)
    runCurrent()
    val baseAttemptsAfterFirst = decoder.wholeImageCalls
    assertTrue(baseAttemptsAfterFirst > 0, "the base layer was never attempted")

    // Three more frames at the same viewpoint must not re-attempt the base layer.
    repeat(3) {
      store.request(request(), backgroundScope)
      runCurrent()
    }

    assertEquals(0, store.tiles.size, "a null decode was cached as a tile")
    assertEquals(0L, store.tiles.byteCount)
    assertNull(store.baseLayer)
    assertEquals(
      baseAttemptsAfterFirst,
      decoder.wholeImageCalls,
      "the base layer was retried on every frame: ${decoder.wholeImageCalls} attempts for one " +
        "failure. Individual tiles do retry, deliberately - a tile decode can fail under " +
        "transient memory pressure and the user is still looking at it - but the base layer is " +
        "one attempt per source, because a decoder that cannot manage a thumbnail will not " +
        "manage it on the next frame either.",
    )
    store.close()
  }

  /**
   * Pixels the platform will not hand to Compose are released rather than cached.
   *
   * `PreviewTile.of` returns null for an image Compose cannot draw - an Android hardware bitmap, or
   * one already closed - and closes the region on that path. Caching a tile whose bitmap is null
   * would hold the native memory for a tile that can never be drawn.
   */
  @Test
  fun pixelsThatCannotBeDrawnAreNotCached() = runTest {
    val decoder = UndrawableTileDecoder(imageSize)
    val store = TileStore()
    store.open(decoder, imageSize)

    store.request(request(), backgroundScope)
    runCurrent()

    assertEquals(0, store.tiles.size, "an undrawable tile was cached")
    assertEquals(0L, store.tiles.byteCount)
    assertNull(store.baseLayer)
    assertTrue(decoder.handedOut.isNotEmpty(), "no decode ran, so nothing is being tested")
    assertEquals(
      0,
      decoder.handedOut.count { it.readArgbPixels() != null },
      "the pixels of an undrawable tile were left held",
    )
    store.close()
  }

  @Test
  fun awaitIdleReturnsAtOnceWhenNothingIsInFlight() = runTest {
    val store = TileStore()

    store.awaitIdle()
    store.open(NullTileDecoder(imageSize), imageSize)
    store.awaitIdle()

    assertEquals(0, store.tiles.size)
    store.close()
  }

  /** An image with no pixels is declined rather than divided by. */
  @Test
  fun anEmptyImageProducesNoTilesAndNoArithmetic() = runTest {
    val decoder = NullTileDecoder(ImageSize.Zero)
    val store = TileStore()
    store.open(decoder, ImageSize.Zero)

    store.request(
      TileRequest(ImageSize.Zero, viewport, CropTransform(), cropRect),
      backgroundScope,
    )
    runCurrent()

    assertEquals(0, decoder.calls, "an image with no pixels was still decoded")
    assertEquals(0, store.tiles.size)
    store.close()
  }
}

/** Answers every region with `null`, the way a platform out of memory does. */
private class NullTileDecoder(override val imageSize: ImageSize) : RegionDecoder {
  var calls = 0

  /** Base-layer attempts: the base layer is the only decode that asks for the whole image. */
  var wholeImageCalls = 0
  override val format: ImageFormat = ImageFormat.JPEG
  override val appliedOrientation: ImageOrientation = ImageOrientation.NORMAL
  override suspend fun decodeRegion(region: ImageRegion, sampleSize: Int): DecodedRegion? {
    calls++
    if (region == ImageRegion.of(imageSize)) wholeImageCalls++
    return null
  }
  override fun close(): Unit = Unit
}

/** Succeeds with pixels Compose will not accept: a closed image stands in for a hardware one. */
private class UndrawableTileDecoder(override val imageSize: ImageSize) : RegionDecoder {
  val handedOut = mutableListOf<com.github.skydoves.crayfish.decode.PlatformImage>()
  override val format: ImageFormat = ImageFormat.JPEG
  override val appliedOrientation: ImageOrientation = ImageOrientation.NORMAL
  override suspend fun decodeRegion(region: ImageRegion, sampleSize: Int): DecodedRegion? {
    val size = ImageSize(8, 8)
    val image = platformImageOfArgbPixels(IntArray(size.width * size.height), size) ?: return null
    image.close()
    handedOut += image
    return DecodedRegion(image = image, region = region, sampleSize = sampleSize)
  }
  override fun close(): Unit = Unit
}
