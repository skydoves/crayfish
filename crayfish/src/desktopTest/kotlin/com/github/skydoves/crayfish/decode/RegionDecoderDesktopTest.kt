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
import java.awt.Rectangle
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegionDecoderDesktopTest {

  private val opened = mutableListOf<RegionDecoder>()
  private val tempFiles = mutableListOf<File>()

  @AfterTest
  fun tearDown() {
    opened.forEach { it.close() }
    tempFiles.forEach { it.delete() }
  }

  private suspend fun open(bytes: ByteArray): RegionDecoder {
    val decoder = assertNotNull(
      createRegionDecoder(TestImages.source(bytes)),
      "expected a decoder for these bytes",
    )
    opened += decoder
    return decoder
  }

  // -----------------------------------------------------------------------------------------
  // 1. Round trip
  // -----------------------------------------------------------------------------------------

  @Test
  fun `header size is read without decoding`() = runTest {
    val decoder = open(TestImages.pngBytes())

    assertEquals(ImageSize(TestImages.WIDTH, TestImages.HEIGHT), decoder.imageSize)
    assertEquals(ImageFormat.PNG, decoder.format)
  }

  @Test
  fun `decodes the requested sub-rectangle with its own pixels`() = runTest {
    val decoder = open(TestImages.pngBytes())

    // Straddles all four quadrants and is offset from the origin, so an ignored origin, a flipped
    // axis or a transposed size all land on the wrong colour.
    val region = ImageRegion(left = 16, top = 8, right = 48, bottom = 40)
    val image = assertNotNull(decoder.decodeRegion(region, sampleSize = 1))

    assertEquals(32, image.width)
    assertEquals(32, image.height)
    assertColour(TestImages.TOP_LEFT, image.pixelAt(0, 0), "top-left of the region")
    assertColour(TestImages.TOP_RIGHT, image.pixelAt(31, 0), "top-right of the region")
    assertColour(TestImages.BOTTOM_LEFT, image.pixelAt(0, 31), "bottom-left of the region")
    assertColour(TestImages.BOTTOM_RIGHT, image.pixelAt(31, 31), "bottom-right of the region")
    image.close()
  }

  @Test
  fun `every pixel of a decoded region matches the source`() = runTest {
    val decoder = open(TestImages.pngBytes())

    val region = ImageRegion(left = 20, top = 12, right = 52, bottom = 44)
    val image = assertNotNull(decoder.decodeRegion(region, sampleSize = 1))
    val pixels = assertNotNull(image.image.readArgbPixels())

    for (y in 0 until image.height) {
      for (x in 0 until image.width) {
        assertColour(
          TestImages.colourAt(region.left + x, region.top + y),
          pixels[y * image.width + x],
          "pixel ($x, $y)",
        )
      }
    }
    image.close()
  }

  @Test
  fun `decodes from a file path`() = runTest {
    val file = File.createTempFile("crayfish", ".png").also { tempFiles += it }
    file.writeBytes(TestImages.pngBytes())

    val decoder = assertNotNull(createRegionDecoder(CropSource.FilePath(file.absolutePath)))
    opened += decoder

    assertEquals(ImageSize(TestImages.WIDTH, TestImages.HEIGHT), decoder.imageSize)
    val image = assertNotNull(
      decoder.decodeRegion(ImageRegion(0, 0, 8, 8), sampleSize = 1),
    )
    assertColour(TestImages.TOP_LEFT, image.pixelAt(4, 4), "top-left quadrant from a file")
    image.close()
  }

  // -----------------------------------------------------------------------------------------
  // 2. Channel order
  // -----------------------------------------------------------------------------------------

  @Test
  fun `pure red survives as ARGB red and not as blue`() = runTest {
    val decoder = open(TestImages.pngBytes())

    val image = assertNotNull(decoder.decodeRegion(ImageRegion(0, 0, 4, 4), sampleSize = 1))
    val red = image.pixelAt(2, 2)

    // The whole point: 0xFF0000FF is what a BGRA/RGBA mix-up produces, and it is a perfectly
    // plausible-looking image, so only an exact comparison catches it.
    assertColour(0xFFFF0000.toInt(), red, "pure red must not come back as pure blue")
    assertTrue(red != 0xFF0000FF.toInt(), "red and blue are swapped")
    image.close()
  }

  @Test
  fun `pure blue survives as ARGB blue`() = runTest {
    val decoder = open(TestImages.pngBytes())

    val image = assertNotNull(
      decoder.decodeRegion(ImageRegion(0, TestImages.HEIGHT - 4, 4, TestImages.HEIGHT), 1),
    )

    assertColour(0xFF0000FF.toInt(), image.pixelAt(2, 2), "pure blue must not come back as red")
    image.close()
  }

  @Test
  fun `a JPEG source keeps its channel order through the BGR raster`() = runTest {
    // The JPEG reader hands back TYPE_3BYTE_BGR rather than TYPE_INT_ARGB, which is the other
    // layout the conversion has to survive. JPEG is lossy, so the check is per-channel dominance
    // rather than equality.
    val decoder = open(TestImages.jpegBytes())
    assertEquals(ImageFormat.JPEG, decoder.format)

    val image = assertNotNull(decoder.decodeRegion(ImageRegion(0, 0, 8, 8), sampleSize = 1))
    val pixel = image.pixelAt(4, 4)
    val red = (pixel shr 16) and 0xFF
    val green = (pixel shr 8) and 0xFF
    val blue = pixel and 0xFF

    assertTrue(red > 200, "red channel was $red, expected a red pixel")
    assertTrue(green < 80, "green channel was $green")
    assertTrue(blue < 80, "blue channel was $blue")
    assertEquals(0xFF, (pixel ushr 24) and 0xFF, "an opaque JPEG must decode opaque")
    image.close()
  }

  @Test
  fun `translucent pixels are not premultiplied`() = runTest {
    val decoder = open(TestImages.pngBytes(TestImages.translucent()))

    val image = assertNotNull(decoder.decodeRegion(ImageRegion(0, 0, 4, 4), sampleSize = 1))

    assertColour(0x80FF0000.toInt(), image.pixelAt(0, 0), "half-alpha red")
    assertColour(0x40008080.toInt(), image.pixelAt(3, 0), "quarter-alpha teal")
    image.close()
  }

  @Test
  fun `the JDK JPEG reader applies a source region and a subsampling period`() {
    // Backs a claim the KDoc on createRegionDecoder makes. Note what it does and does not show:
    // that the parameters are applied, not that the codec skips any work. Whether the decode is
    // actually cheaper is invisible from here, which is exactly why the KDoc refuses to promise
    // a memory saving the way the Android implementation can.
    val stream = ImageIO.createImageInputStream(ByteArrayInputStream(TestImages.jpegBytes()))
    val reader = ImageIO.getImageReaders(stream).next()
    reader.setInput(stream, false, true)
    val param = reader.defaultReadParam
    param.sourceRegion = Rectangle(16, 8, 32, 32)
    param.setSourceSubsampling(2, 2, 0, 0)

    val image = reader.read(0, param)

    assertEquals(16, image.width, "an ignored source region would give the full width")
    assertEquals(16, image.height, "an ignored source region would give the full height")
    reader.dispose()
    stream.close()
  }

  // -----------------------------------------------------------------------------------------
  // 3. Subsampling
  // -----------------------------------------------------------------------------------------

  @Test
  fun `sample size two halves both dimensions`() = runTest {
    val decoder = open(TestImages.pngBytes())

    val whole = ImageRegion.of(decoder.imageSize)
    val image = assertNotNull(decoder.decodeRegion(whole, sampleSize = 2))

    assertEquals(TestImages.WIDTH / 2, image.width)
    assertEquals(TestImages.HEIGHT / 2, image.height)
    // Subsampling picks every second pixel, so the quadrants stay where they were.
    assertColour(TestImages.TOP_LEFT, image.pixelAt(0, 0), "top-left after subsampling")
    assertColour(TestImages.TOP_RIGHT, image.pixelAt(31, 0), "top-right after subsampling")
    assertColour(TestImages.BOTTOM_LEFT, image.pixelAt(0, 23), "bottom-left after subsampling")
    assertColour(TestImages.BOTTOM_RIGHT, image.pixelAt(31, 23), "bottom-right after subsampling")
    image.close()
  }

  @Test
  fun `sample size four quarters both dimensions`() = runTest {
    val decoder = open(TestImages.pngBytes())

    val image = assertNotNull(decoder.decodeRegion(ImageRegion.of(decoder.imageSize), 4))

    assertEquals(TestImages.WIDTH / 4, image.width)
    assertEquals(TestImages.HEIGHT / 4, image.height)
  }

  @Test
  fun `an odd dimension rounds up, as ImageReadParam specifies`() = runTest {
    // Pinned because the KDoc claims it: ImageIO rounds the subsampled length up while
    // SampleSize.sampledBy rounds it down, and the two disagree by a pixel on an odd dimension.
    val decoder = open(TestImages.pngBytes())

    val image = assertNotNull(decoder.decodeRegion(ImageRegion(0, 0, 33, 33), sampleSize = 2))

    assertEquals(17, image.width)
    assertEquals(17, image.height)
  }

  @Test
  fun `a sample size of zero is treated as one`() = runTest {
    val decoder = open(TestImages.pngBytes())

    val image = assertNotNull(decoder.decodeRegion(ImageRegion(0, 0, 8, 8), sampleSize = 0))

    assertEquals(8, image.width)
  }

  // -----------------------------------------------------------------------------------------
  // 4. Clipping
  // -----------------------------------------------------------------------------------------

  @Test
  fun `a region running past the edge is clipped, not thrown`() = runTest {
    val decoder = open(TestImages.pngBytes())

    val image = assertNotNull(
      decoder.decodeRegion(ImageRegion(left = 48, top = 32, right = 400, bottom = 400), 1),
    )

    assertEquals(16, image.width)
    assertEquals(16, image.height)
    assertColour(TestImages.BOTTOM_RIGHT, image.pixelAt(8, 8), "clipped bottom-right quadrant")
    image.close()
  }

  @Test
  fun `a region starting before the origin is clipped`() = runTest {
    val decoder = open(TestImages.pngBytes())

    val image = assertNotNull(
      decoder.decodeRegion(ImageRegion(left = -20, top = -20, right = 10, bottom = 10), 1),
    )

    assertEquals(10, image.width)
    assertEquals(10, image.height)
    assertColour(TestImages.TOP_LEFT, image.pixelAt(0, 0), "clipped top-left quadrant")
    image.close()
  }

  @Test
  fun `a region entirely outside the image decodes to null`() = runTest {
    val decoder = open(TestImages.pngBytes())

    assertNull(decoder.decodeRegion(ImageRegion(200, 200, 300, 300), sampleSize = 1))
  }

  // -----------------------------------------------------------------------------------------
  // 6. Bad input and lifecycle
  // -----------------------------------------------------------------------------------------

  @Test
  fun `garbage bytes yield null rather than an exception`() = runTest {
    val garbage = ByteArray(256) { (it * 7).toByte() }

    assertNull(createRegionDecoder(TestImages.source(garbage)))
  }

  @Test
  fun `empty bytes yield null`() = runTest {
    assertNull(createRegionDecoder(TestImages.source(ByteArray(0))))
  }

  @Test
  fun `a truncated PNG header yields null`() = runTest {
    val truncated = TestImages.pngBytes().copyOf(20)

    assertNull(createRegionDecoder(TestImages.source(truncated)))
  }

  @Test
  fun `a PNG whose pixel data is corrupt never throws`() = runTest {
    // The header survives, so a decoder opens; the deflate stream does not. Whether a reader
    // reports that by throwing or by handing back what it managed to inflate is its own business.
    // What this pins is that neither reaches the caller as an exception.
    val bytes = TestImages.pngBytes()
    for (index in 100 until bytes.size) {
      bytes[index] = 0
    }

    val decoder = createRegionDecoder(TestImages.source(bytes))
    if (decoder != null) {
      opened += decoder
      val image = decoder.decodeRegion(ImageRegion(0, 0, 8, 8), sampleSize = 1)
      if (image != null) {
        assertEquals(8, image.width)
        image.close()
      }
    }
  }

  @Test
  fun `a missing file yields null`() = runTest {
    assertNull(createRegionDecoder(CropSource.FilePath("/definitely/not/here.png")))
  }

  @Test
  fun `HEIC yields null because no JDK reader exists`() = runTest {
    // Recognised by ImageProbe as HEIF, whose supportsRegionDecoding is true, so this reaches
    // ImageIO, finds nothing that can read it, and says so rather than pretending.
    assertNull(createRegionDecoder(TestImages.source(heicHeader())))
  }

  @Test
  fun `GIF yields null because the format does not support region decoding`() = runTest {
    val gif = TestImages.encode(TestImages.quadrants(), "gif")

    // ImageIO's GIF reader would accept a source region, but ImageFormat.supportsRegionDecoding
    // is the library-wide contract callers gate on, and desktop does not get to disagree with it.
    assertNull(createRegionDecoder(TestImages.source(gif)))
  }

  @Test
  fun `close is idempotent and a closed decoder decodes to null`() = runTest {
    val decoder = open(TestImages.pngBytes())

    decoder.close()
    decoder.close()

    assertNull(decoder.decodeRegion(ImageRegion(0, 0, 8, 8), sampleSize = 1))
  }

  @Test
  fun `repeated decodes from one decoder stay correct`() = runTest {
    // The reader keeps its own stream position, so a second read has to seek back. A decoder set
    // up seek-forward-only would serve the first region and then fail every one after it.
    val decoder = open(TestImages.pngBytes())

    repeat(3) {
      val image = assertNotNull(decoder.decodeRegion(ImageRegion(0, 0, 8, 8), sampleSize = 1))
      assertColour(TestImages.TOP_LEFT, image.pixelAt(1, 1), "attempt $it")
      image.close()
    }
  }

  private fun heicHeader(): ByteArray {
    val brands = "ftypheic" + "    " + "heicmif1"
    val body = brands.map { it.code.toByte() }.toByteArray()
    val size = body.size + 4
    return byteArrayOf(0, 0, 0, size.toByte()) + body
  }
}
