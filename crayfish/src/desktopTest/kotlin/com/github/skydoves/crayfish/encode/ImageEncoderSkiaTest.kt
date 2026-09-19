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
package com.github.skydoves.crayfish.encode

import com.github.skydoves.crayfish.decode.ImageRegion
import com.github.skydoves.crayfish.decode.PlatformImage
import com.github.skydoves.crayfish.decode.TestImages
import com.github.skydoves.crayfish.decode.assertColour
import com.github.skydoves.crayfish.decode.createRegionDecoder
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageEncoderSkiaTest {

  /**
   * The image under test comes out of the real decoder rather than a hand-built Skia bitmap.
   *
   * Building one by hand here would mean writing the BGRA packing a second time, and a test that
   * repeats the code it is checking agrees with itself whether or not either is right.
   */
  private suspend fun quadrantImage(): PlatformImage {
    val decoder = assertNotNull(createRegionDecoder(TestImages.source(TestImages.pngBytes())))
    return try {
      assertNotNull(decoder.decodeRegion(ImageRegion.of(decoder.imageSize), sampleSize = 1)).image
    } finally {
      decoder.close()
    }
  }

  private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
    size >= prefix.size && prefix.withIndex().all { (i, b) -> this[i].toInt() and 0xFF == b }

  private fun ByteArray.fourCC(offset: Int): String =
    String(CharArray(4) { (this[offset + it].toInt() and 0xFF).toChar() })

  // -----------------------------------------------------------------------------------------
  // 5. Encoder round trip
  // -----------------------------------------------------------------------------------------

  @Test
  fun `PNG encodes to the PNG signature and decodes back unchanged`() = runTest {
    val image = quadrantImage()

    val bytes = assertNotNull(encodeImage(image, EncodeOptions(EncodedFormat.PNG)))

    assertTrue(
      bytes.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
      "expected the 8-byte PNG signature, got ${bytes.take(8)}",
    )
    val decoded = assertNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
    assertEquals(TestImages.WIDTH, decoded.width)
    assertEquals(TestImages.HEIGHT, decoded.height)
    // PNG is lossless, so this is an exact comparison in both directions: a channel swap anywhere
    // in decode-then-encode would land here as well as in the decoder's own tests.
    assertColour(TestImages.TOP_LEFT, decoded.getRGB(4, 4), "top-left")
    assertColour(TestImages.TOP_RIGHT, decoded.getRGB(60, 4), "top-right")
    assertColour(TestImages.BOTTOM_LEFT, decoded.getRGB(4, 44), "bottom-left")
    assertColour(TestImages.BOTTOM_RIGHT, decoded.getRGB(60, 44), "bottom-right")
    image.close()
  }

  @Test
  fun `PNG keeps un-premultiplied alpha`() = runTest {
    val source = TestImages.pngBytes(TestImages.translucent())
    val decoder = assertNotNull(createRegionDecoder(TestImages.source(source)))
    val image = assertNotNull(decoder.decodeRegion(ImageRegion(0, 0, 4, 4), sampleSize = 1))
    decoder.close()

    val bytes = assertNotNull(encodeImage(image.image, EncodeOptions(EncodedFormat.PNG)))

    val decoded = assertNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
    // Exactly the input values. A PREMUL bitmap anywhere along the way loses low-order bits here.
    assertColour(0x80FF0000.toInt(), decoded.getRGB(0, 0), "half-alpha red")
    assertColour(0x40008080.toInt(), decoded.getRGB(3, 0), "quarter-alpha teal")
    image.close()
  }

  @Test
  fun `JPEG encodes to the JPEG magic and decodes back at the same size`() = runTest {
    val image = quadrantImage()

    val bytes = assertNotNull(
      encodeImage(image, EncodeOptions(EncodedFormat.JPEG, lossyQuality = 90)),
    )

    assertTrue(bytes.startsWith(0xFF, 0xD8, 0xFF), "expected the SOI marker, got ${bytes.take(3)}")
    val decoded = assertNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
    assertEquals(TestImages.WIDTH, decoded.width)
    assertEquals(TestImages.HEIGHT, decoded.height)
    // Lossy, so channel dominance rather than equality, but red must still be red.
    val topLeft = decoded.getRGB(4, 4)
    assertTrue((topLeft shr 16) and 0xFF > 200, "red channel of the top-left quadrant")
    assertTrue(topLeft and 0xFF < 80, "blue channel of the top-left quadrant")
    image.close()
  }

  @Test
  fun `JPEG quality changes the output size`() = runTest {
    val image = quadrantImage()

    val low = assertNotNull(encodeImage(image, EncodeOptions(EncodedFormat.JPEG, 10)))
    val high = assertNotNull(encodeImage(image, EncodeOptions(EncodedFormat.JPEG, 95)))

    // Pins that platformQuality actually reaches the encoder rather than being dropped.
    assertTrue(low.size < high.size, "quality 10 produced ${low.size}, quality 95 ${high.size}")
    image.close()
  }

  @Test
  fun `lossy WebP encodes to a RIFF WEBP container`() = runTest {
    val image = quadrantImage()

    val bytes = assertNotNull(
      encodeImage(image, EncodeOptions(EncodedFormat.WEBP_LOSSY, lossyQuality = 80)),
    )

    assertEquals("RIFF", bytes.fourCC(0), "RIFF header")
    assertEquals("WEBP", bytes.fourCC(8), "WEBP form type")
    assertEquals("VP8 ", bytes.fourCC(12), "the lossy VP8 bitstream")
    image.close()
  }

  @Test
  fun `lossless WebP is reported as unsupported rather than silently written lossy`() = runTest {
    val image = quadrantImage()

    val bytes = encodeImage(image, EncodeOptions(EncodedFormat.WEBP_LOSSLESS, losslessEffort = 80))

    // The documented behaviour, and the reason for it is pinned by the next test: Skia's single
    // quality integer cannot select the lossless bitstream, so honouring the request is impossible
    // and writing a lossy file instead would be a silent, unrecoverable loss in the caller's crop.
    assertNull(bytes, "WEBP_LOSSLESS must be declined, not answered with a lossy file")
    image.close()
  }

  @Test
  fun `Skia cannot write lossless WebP at any quality`() = runTest {
    // The measurement the decision above rests on. Should a future Skia gain a lossless path,
    // this test goes red and the encoder can stop returning null, which is exactly when it should
    // be revisited.
    val image = quadrantImage()

    val chunks = intArrayOf(0, 25, 50, 75, 80, 99, 100).map { quality ->
      val options = EncodeOptions(EncodedFormat.WEBP_LOSSY, lossyQuality = quality)
      quality to assertNotNull(encodeImage(image, options)).fourCC(12)
    }

    assertTrue(
      chunks.all { it.second == "VP8 " },
      "expected every quality to produce a lossy VP8 chunk, got $chunks",
    )
    image.close()
  }

  @Test
  fun `PNG ignores quality entirely`() = runTest {
    val image = quadrantImage()

    val low = assertNotNull(encodeImage(image, EncodeOptions(EncodedFormat.PNG, 0)))
    val high = assertNotNull(encodeImage(image, EncodeOptions(EncodedFormat.PNG, 100)))

    assertTrue(low.contentEquals(high), "PNG output must not depend on lossyQuality")
    image.close()
  }

  @Test
  fun `an encoded crop round-trips back through the decoder`() = runTest {
    val image = quadrantImage()
    val encoded = assertNotNull(encodeImage(image, EncodeOptions(EncodedFormat.PNG)))

    val decoder = assertNotNull(createRegionDecoder(TestImages.source(encoded, key = "encoded")))
    val reDecoded = assertNotNull(
      decoder.decodeRegion(ImageRegion(0, 0, 8, 8), sampleSize = 1),
    )

    assertColour(
      TestImages.TOP_LEFT,
      assertNotNull(reDecoded.image.readArgbPixels())[0],
      "decode → encode → decode must be a fixed point",
    )
    reDecoded.close()
    decoder.close()
    image.close()
  }
}
