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
import java.awt.image.BufferedImage
import java.util.Locale
import javax.imageio.ImageIO
import javax.imageio.ImageReadParam
import javax.imageio.ImageReader
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadata
import javax.imageio.spi.IIORegistry
import javax.imageio.spi.ImageReaderSpi
import javax.imageio.stream.ImageInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises the full-decode-then-crop fallback.
 *
 * Every reader in the stock JDK honours `ImageReadParam`, so the only way to reach the fallback is
 * to install a reader that does not. That is worth the machinery below: a fallback nothing ever
 * runs is a fallback nobody knows is broken, and this one carries hand-written arithmetic (the
 * round-up rule and the every-nth-pixel walk) that has to agree with what the fast path produces.
 */
class RegionDecoderFallbackTest {

  @Test
  fun `a reader that rejects read params still decodes the right region`() = runTest {
    withHostileReader {
      val decoder = assertNotNull(createRegionDecoder(TestImages.source(TestImages.pngBytes())))
      try {
        assertEquals(ImageSize(TestImages.WIDTH, TestImages.HEIGHT), decoder.imageSize)

        val region = ImageRegion(left = 16, top = 8, right = 48, bottom = 40)
        val image = assertNotNull(decoder.decodeRegion(region, sampleSize = 1))

        assertTrue(HostileReader.rejections > 0, "the hostile reader was never asked")
        assertEquals(32, image.width)
        assertEquals(32, image.height)
        // Same assertions as the fast path's: the two must agree on pixels, not just on size.
        assertColour(TestImages.TOP_LEFT, image.pixelAt(0, 0), "top-left of the region")
        assertColour(TestImages.TOP_RIGHT, image.pixelAt(31, 0), "top-right of the region")
        assertColour(TestImages.BOTTOM_LEFT, image.pixelAt(0, 31), "bottom-left of the region")
        assertColour(TestImages.BOTTOM_RIGHT, image.pixelAt(31, 31), "bottom-right")
        image.close()
      } finally {
        decoder.close()
      }
    }
  }

  @Test
  fun `the fallback subsamples to the same size and pixels as the fast path`() = runTest {
    val fastPath = assertNotNull(createRegionDecoder(TestImages.source(TestImages.pngBytes())))
    val region = ImageRegion(left = 1, top = 1, right = 34, bottom = 34)
    val expected = assertNotNull(fastPath.decodeRegion(region, sampleSize = 2))
    val expectedPixels = assertNotNull(expected.image.readArgbPixels())
    fastPath.close()

    withHostileReader {
      val decoder = assertNotNull(createRegionDecoder(TestImages.source(TestImages.pngBytes())))
      try {
        val image = assertNotNull(decoder.decodeRegion(region, sampleSize = 2))

        assertTrue(HostileReader.rejections > 0, "the hostile reader was never asked")
        // 33 pixels at every second one is 17, rounded up, on both paths.
        assertEquals(expected.width, image.width, "width")
        assertEquals(expected.height, image.height, "height")
        assertTrue(
          assertNotNull(image.image.readArgbPixels()).contentEquals(expectedPixels),
          "the fallback picked different pixels than the reader's own subsampling",
        )
        image.close()
      } finally {
        decoder.close()
      }
    }
    expected.close()
  }

  /**
   * Runs [block] with [HostileReader] ahead of the JDK's PNG reader in the ImageIO registry.
   *
   * `IIORegistry` is process-wide, so the registration is undone in a `finally` and the ordering
   * is expressed as a preference rather than by unregistering the real reader, which other tests
   * in this JVM still need.
   */
  private suspend fun withHostileReader(block: suspend () -> Unit) {
    val registry = IIORegistry.getDefaultInstance()
    val hostile = HostileReaderSpi()
    val png = registry.getServiceProviders(ImageReaderSpi::class.java, true)
      .asSequence()
      .first { it.formatNames.any { name -> name.equals("png", ignoreCase = true) } }

    HostileReader.rejections = 0
    registry.registerServiceProvider(hostile, ImageReaderSpi::class.java)
    registry.setOrdering(ImageReaderSpi::class.java, hostile, png)
    try {
      block()
    } finally {
      registry.deregisterServiceProvider(hostile, ImageReaderSpi::class.java)
    }
  }
}

/** A PNG reader that refuses every non-trivial [ImageReadParam], as some real codecs do. */
private class HostileReader(spi: ImageReaderSpi) : ImageReader(spi) {

  private val delegate: ImageReader = ImageIO.getImageReadersByFormatName("png").next()

  override fun setInput(input: Any?, seekForwardOnly: Boolean, ignoreMetadata: Boolean) {
    super.setInput(input, seekForwardOnly, ignoreMetadata)
    delegate.setInput(input, seekForwardOnly, ignoreMetadata)
  }

  override fun getNumImages(allowSearch: Boolean): Int = delegate.getNumImages(allowSearch)

  override fun getWidth(imageIndex: Int): Int = delegate.getWidth(imageIndex)

  override fun getHeight(imageIndex: Int): Int = delegate.getHeight(imageIndex)

  override fun getImageTypes(imageIndex: Int): MutableIterator<ImageTypeSpecifier> =
    delegate.getImageTypes(imageIndex)

  override fun getStreamMetadata(): IIOMetadata? = delegate.streamMetadata

  override fun getImageMetadata(imageIndex: Int): IIOMetadata? =
    delegate.getImageMetadata(imageIndex)

  override fun read(imageIndex: Int, param: ImageReadParam?): BufferedImage {
    if (param != null && (param.sourceRegion != null || param.sourceXSubsampling > 1)) {
      rejections++
      throw UnsupportedOperationException("this reader does not honour ImageReadParam")
    }
    return delegate.read(imageIndex, null)
  }

  override fun dispose() {
    delegate.dispose()
    super.dispose()
  }

  companion object {
    /** Proof that the hostile reader, and therefore the fallback, was actually reached. */
    var rejections: Int = 0
  }
}

private class HostileReaderSpi : ImageReaderSpi() {

  init {
    vendorName = "crayfish-test"
    version = "1.0"
    names = arrayOf("hostile-png")
    suffixes = arrayOf("png")
    MIMETypes = arrayOf("image/png")
    pluginClassName = HostileReader::class.java.name
    inputTypes = arrayOf(ImageInputStream::class.java)
  }

  override fun canDecodeInput(source: Any): Boolean {
    val stream = source as? ImageInputStream ?: return false
    val signature = ByteArray(PNG_SIGNATURE.size)
    stream.mark()
    try {
      stream.readFully(signature)
    } catch (error: Exception) {
      return false
    } finally {
      stream.reset()
    }
    return signature.contentEquals(PNG_SIGNATURE)
  }

  override fun createReaderInstance(extension: Any?): ImageReader = HostileReader(this)

  override fun getDescription(locale: Locale?): String = "PNG reader that rejects read params"

  private companion object {
    val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
  }
}
