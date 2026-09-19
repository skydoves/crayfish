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
 * The web decoder, running in a browser for the first time.
 *
 * Until this existed, nothing in the build executed a single line of the wasm glue. That is not a
 * figure of speech: a deliberate syntax error inside one of its `js()` snippets compiled clean,
 * because dead-code elimination removes what nothing calls. Every claim about `createImageBitmap`,
 * `OffscreenCanvas` and the RGBA-to-Skia channel order rested on reading the documentation.
 */
class WasmRegionDecoderTest {

  private fun source() = CropSource.Bytes(WasmPngFixture.BYTES, cacheKey = "wasm-fixture")

  @Test
  fun opensAPngAndReportsItsSize() = runTest {
    val decoder = assertNotNull(createRegionDecoder(source()), "the browser would not open the PNG")

    decoder.use {
      assertEquals(ImageSize(WasmPngFixture.WIDTH, WasmPngFixture.HEIGHT), it.imageSize)
      assertEquals(ImageFormat.PNG, it.format)
    }
  }

  /**
   * The channel order, which is the one thing a reading of the documentation cannot settle.
   *
   * `getImageData` hands back RGBA and Skia is told BGRA; whether the two line up is a question
   * only a running browser answers. A swap here renders red as blue, plausible enough in a
   * screenshot to ship.
   */
  @Test
  fun decodesTheRightPixelsInTheRightChannelOrder() = runTest {
    val decoder = assertNotNull(createRegionDecoder(source()))

    decoder.use {
      val decoded = assertNotNull(
        it.decodeRegion(ImageRegion.of(it.imageSize), sampleSize = 1),
        "the browser returned no pixels",
      )
      decoded.use { region ->
        assertEquals(WasmPngFixture.WIDTH, region.width)
        assertEquals(WasmPngFixture.HEIGHT, region.height)

        val pixels = assertNotNull(region.image.readArgbPixels(), "the pixels could not be read")
        assertEquals(WasmPngFixture.WIDTH * WasmPngFixture.HEIGHT, pixels.size)
        assertEquals(WasmPngFixture.TOP_LEFT, pixels[0], "top-left is not red")
        assertEquals(WasmPngFixture.TOP_RIGHT, pixels[WasmPngFixture.WIDTH - 1], "top-right")
        assertEquals(
          WasmPngFixture.BOTTOM_LEFT,
          pixels[(WasmPngFixture.HEIGHT - 1) * WasmPngFixture.WIDTH],
          "bottom-left",
        )
      }
    }
  }

  /** A sub-rectangle has to come from where it was asked for, not from the origin. */
  @Test
  fun decodesASubRectangleFromTheRightPlace() = runTest {
    val decoder = assertNotNull(createRegionDecoder(source()))

    decoder.use {
      val decoded = assertNotNull(it.decodeRegion(ImageRegion(2, 2, 4, 4), sampleSize = 1))
      decoded.use { region ->
        assertEquals(2, region.width)
        assertEquals(2, region.height)
        val pixels = assertNotNull(region.image.readArgbPixels())
        assertEquals(WasmPngFixture.BOTTOM_RIGHT, pixels[0], "the bottom-right quadrant")
      }
    }
  }

  @Test
  fun clipsARegionThatRunsPastTheEdge() = runTest {
    val decoder = assertNotNull(createRegionDecoder(source()))

    decoder.use {
      val decoded = assertNotNull(it.decodeRegion(ImageRegion(2, 2, 40, 40), sampleSize = 1))
      decoded.use { region ->
        assertEquals(2, region.width)
        assertEquals(2, region.height)
      }
    }
  }

  /** A browser has no filesystem, and the decoder has to say so rather than fail obscurely. */
  @Test
  fun refusesAFilePath() = runTest {
    assertNull(createRegionDecoder(CropSource.FilePath("/tmp/nothing.png")))
  }

  @Test
  fun refusesBytesThatAreNotAnImage() = runTest {
    assertNull(createRegionDecoder(CropSource.Bytes(byteArrayOf(1, 2, 3, 4), cacheKey = "junk")))
  }

  /** Closing twice has to be harmless: a tile loop closes as it advances. */
  @Test
  fun closingTwiceIsHarmless() = runTest {
    val decoder = assertNotNull(createRegionDecoder(source()))
    decoder.close()
    decoder.close()
    assertNull(decoder.decodeRegion(ImageRegion.of(decoder.imageSize), sampleSize = 1))
  }

  @Test
  fun encodesTheDecodedPixelsBackToBytes() = runTest {
    val decoder = assertNotNull(createRegionDecoder(source()))
    decoder.use {
      val decoded = assertNotNull(it.decodeRegion(ImageRegion.of(it.imageSize), sampleSize = 1))
      decoded.use { region ->
        val bytes = assertNotNull(
          com.github.skydoves.crayfish.encode.encodeImage(
            region.image,
            com.github.skydoves.crayfish.encode.EncodeOptions(
              com.github.skydoves.crayfish.encode.EncodedFormat.PNG,
            ),
          ),
          "Skia could not encode in the browser",
        )
        assertTrue(bytes.size > 8, "an encoded PNG of ${bytes.size} bytes is not one")
        assertEquals(0x89.toByte(), bytes[0])
        assertEquals('P'.code.toByte(), bytes[1])
      }
    }
  }
}
