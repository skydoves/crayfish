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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The base layer survives a cancelled decode.
 *
 * The base layer is the only thing drawn until tiles arrive, so a store that never produces one
 * shows a blank cropper. `startBaseLayer` starts exactly one, on purpose: a decoder that cannot
 * produce the whole image at a display sample size will not manage it next frame either, and
 * retrying per frame turns a failure into a decode storm.
 *
 * The flaw was that it counted a **cancellation** as that one attempt. The job is launched into the
 * scope of the effect that drives requests, and that effect restarts whenever its keys change, so a
 * key changing inside the couple of hundred milliseconds the decode takes killed the job and left
 * `baseJob` non null forever. Nothing retried, and the preview stayed empty for as long as the
 * screen was open.
 *
 * Found from a device run: three mounts of the same cropper, the third blank, and timings from the
 * passing runs showing the base layer arriving in 205 to 412 ms against a 30 second wait. A slow
 * decode and a decode that never happens look the same from the outside, and the timings are what
 * told them apart.
 */
class TileStoreBaseLayerTest {

  private val imageSize = ImageSize(40, 30)

  @Test
  fun aBaseLayerCancelledWithItsScopeIsRetriedOnTheNextRequest() = runTest {
    val decoder = BaseLayerDecoder(imageSize)
    val store = TileStore()
    store.open(decoder, imageSize)

    // The effect that drives requests. Its scope owns the decode.
    val first = Job(coroutineContext[Job])
    store.request(request(), CoroutineScope(coroutineContext + first))
    assertTrue(decoder.started.await(), "the base decode never started")

    // The effect restarts: its keys changed, or the screen recomposed around it.
    first.cancel()

    decoder.gate.complete(Unit)
    store.request(request(), backgroundScope)
    store.awaitIdle()

    assertNotNull(
      store.baseLayer,
      "the cancelled decode was counted as the one attempt, so nothing ever decoded the base " +
        "layer again and the preview is blank for the life of the screen",
    )
  }

  /**
   * The other half of the rule, which the fix must not break: a decode that ran and produced
   * nothing is a real attempt and is not repeated.
   *
   * Zoomed into a large image on purpose. The base layer asks for the whole image and a tile asks
   * for a sub rectangle, and at scale 1 on a small image those are the same rectangle, so a counter
   * keyed on the region counts both. The first version of this test did exactly that and read 6
   * where it expected 1.
   */
  @Test
  fun aBaseLayerThatFailedIsNotRetriedOnEveryFrame() = runTest {
    val large = ImageSize(4_000, 3_000)
    val decoder = BaseLayerDecoder(large, answer = null)
    val store = TileStore()
    store.open(decoder, large)
    decoder.gate.complete(Unit)

    repeat(5) {
      store.request(request(large, CropTransform(scale = 8f)), backgroundScope)
      store.awaitIdle()
    }

    assertTrue(
      decoder.attempts.any { it != ImageRegion.of(large) },
      "every decode asked for the whole image, so this counter cannot tell a base layer from a " +
        "tile and the assertion below would be measuring both",
    )
    assertEquals(
      1,
      decoder.baseAttempts,
      "a failed base decode was retried ${decoder.baseAttempts} times, which is the decode storm " +
        "the single attempt rule exists to prevent",
    )
  }

  private fun request(
    size: ImageSize = imageSize,
    transform: CropTransform = CropTransform.Identity,
  ) = TileRequest(
    imageSize = size,
    viewportSize = FloatSize(300f, 400f),
    transform = transform,
    cropRect = FloatRect(30f, 40f, 270f, 360f),
  )
}

/** Blocks until [gate] completes, then answers with [answer]. */
private class BaseLayerDecoder(
  override val imageSize: ImageSize,
  private val answer: Boolean? = true,
) : RegionDecoder {

  val gate = CompletableDeferred<Unit>()
  val started = CompletableDeferred<Boolean>()
  val attempts = mutableListOf<ImageRegion>()

  /** Decodes of the whole image, which is what `startBaseLayer` and only it asks for. */
  val baseAttempts: Int get() = attempts.count { it == ImageRegion.of(imageSize) }

  override val format: ImageFormat = ImageFormat.JPEG
  override val appliedOrientation: ImageOrientation = ImageOrientation.NORMAL

  override suspend fun decodeRegion(region: ImageRegion, sampleSize: Int): DecodedRegion? {
    attempts += region
    started.complete(true)
    gate.await()
    if (answer == null) return null
    val clipped = region.intersect(ImageRegion.of(imageSize)) ?: return null
    val image = platformImageOfArgbPixels(
      IntArray(clipped.width * clipped.height) { -1 },
      ImageSize(clipped.width, clipped.height),
    ) ?: return null
    return DecodedRegion(image = image, region = clipped, sampleSize = 1)
  }

  override fun close(): Unit = Unit
}
