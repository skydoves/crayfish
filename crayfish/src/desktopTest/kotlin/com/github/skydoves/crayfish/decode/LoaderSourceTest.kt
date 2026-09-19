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
package com.github.skydoves.crayfish.decode

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A source whose bytes arrive later: a network image, a content Uri, anything with a loader.
 *
 * The library carries no HTTP client and is not going to, because zero external dependencies is
 * what makes the no-native-code claim unconditional. So the seam is a suspending function the
 * caller supplies, and everything downstream of it treats the result exactly like
 * [CropSource.Bytes]: the same region decoding, the same budget, the same failure values.
 */
class LoaderSourceTest {

  @Test
  fun aLoaderSourceDecodesLikeBytes() = runTest {
    val fromBytes = assertNotNull(
      createRegionDecoder(CropSource.Bytes(TestImages.pngBytes(), "direct")),
    )
    val fromLoader = assertNotNull(
      createRegionDecoder(CropSource.Loader("loaded") { TestImages.pngBytes() }),
      "a loader that produced bytes did not open",
    )

    fromBytes.use { direct ->
      fromLoader.use { loaded ->
        assertEquals(direct.imageSize, loaded.imageSize)
        assertEquals(direct.format, loaded.format)
      }
    }
  }

  @Test
  fun theLoaderIsCalledOnceAndOnlyWhenTheSourceIsOpened() = runTest {
    var calls = 0
    val source = CropSource.Loader("counted") {
      calls++
      TestImages.pngBytes()
    }

    assertEquals(0, calls, "the loader ran before anything asked for the image")

    createRegionDecoder(source)?.close()

    assertEquals(1, calls, "opening the source called the loader $calls times")
  }

  /** A loader that cannot produce bytes is an unreadable source, not an exception. */
  @Test
  fun aLoaderThatProducesNothingIsAnUnreadableSource() = runTest {
    assertNull(createRegionDecoder(CropSource.Loader("empty") { null }))
    assertNull(createRegionDecoder(CropSource.Loader("junk") { ByteArray(32) { 0x7A } }))
  }

  /** The key is the caller's, because it is what the crop state is saved under. */
  @Test
  fun theCacheKeyIsWhateverTheCallerGave() {
    assertEquals(
      "https://example.com/photo.jpg",
      CropSource.Loader("https://example.com/photo.jpg") { null }.cacheKey,
    )
  }

  /**
   * The header is read from the loaded bytes too, so Exif still applies to a network image.
   *
   * Reading the header used to be a separate path from opening the decoder, and a loader that was
   * resolved for one and not the other would have silently dropped the orientation.
   */
  @Test
  fun theExifHeaderIsReadFromTheLoadedBytes() = runTest {
    val source = CropSource.Loader("oriented") { TestImages.pngBytes() }

    val resolved = assertNotNull(source.resolved())
    val header = assertNotNull(resolved.readHeaderBytes())

    assertTrue(header.isNotEmpty())
    assertEquals(ImageFormat.PNG, assertNotNull(ImageProbe.probe(header)).format)
  }
}
