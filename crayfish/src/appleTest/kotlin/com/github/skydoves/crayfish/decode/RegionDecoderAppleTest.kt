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

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.remove
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The ImageIO decoder against a real image, on the one Apple target that runs on a build machine.
 *
 * iOS shares every line of this code path, but nothing here can execute on a device or simulator
 * from a Gradle build, so macOS is the only place these claims are checked at all.
 */
class RegionDecoderAppleTest {

  @Test
  fun readsSizeAndFormatFromTheContainerHeader() = runTest {
    assertNotNull(createRegionDecoder(pngBytes())).use { decoder ->
      assertEquals(ImageSize(PngFixture.WIDTH, PngFixture.HEIGHT), decoder.imageSize)
      assertEquals(ImageFormat.PNG, decoder.format)
    }
  }

  /**
   * The rectangle must land where [ImageRegion] says it does: origin top-left, y downwards.
   *
   * Core Graphics puts the origin of a bitmap context at the *bottom* left, so a decoder that
   * mixes the two conventions returns a plausible image of the wrong part of the photo. Four
   * corners in four differently coloured blocks is what catches that; a single sample, or four
   * samples inside one block, would not.
   */
  @Test
  fun decodesTheRequestedRectangleWithATopLeftOrigin() = runTest {
    assertNotNull(createRegionDecoder(pngBytes())).use { decoder ->
      // Deliberately off-centre vertically: a region whose top and bottom margins match survives
      // a decoder that measures y from the bottom, because the flipped rectangle is the same
      // rectangle. 16 from the top and 32 from the bottom is what makes the flip visible.
      val region = ImageRegion(left = 32, top = 16, right = 96, bottom = 64)
      val image = assertNotNull(decoder.decodeRegion(region, sampleSize = 1))
      try {
        assertEquals(64, image.width)
        assertEquals(48, image.height)

        val pixels = assertNotNull(image.image.readArgbPixels())
        assertEquals(0xFF, pixels[0] ushr 24 and 0xFF, "the fixture is opaque")
        assertEquals(PngFixture.colorAt(32, 16), pixels.rgbAt(0, 0, image.width))
        assertEquals(PngFixture.colorAt(95, 16), pixels.rgbAt(63, 0, image.width))
        assertEquals(PngFixture.colorAt(32, 63), pixels.rgbAt(0, 47, image.width))
        assertEquals(PngFixture.colorAt(95, 63), pixels.rgbAt(63, 47, image.width))
      } finally {
        image.close()
      }
    }
  }

  @Test
  fun subsamplingHalvesEachDimensionAndKeepsTheColours() = runTest {
    assertNotNull(createRegionDecoder(pngBytes())).use { decoder ->
      val whole = ImageRegion.of(decoder.imageSize)
      val image = assertNotNull(decoder.decodeRegion(whole, sampleSize = 2))
      try {
        assertEquals(PngFixture.WIDTH / 2, image.width)
        assertEquals(PngFixture.HEIGHT / 2, image.height)

        // Block interiors, so the assertion is about which pixels were kept rather than about the
        // filter ImageIO used at a block edge.
        val pixels = assertNotNull(image.image.readArgbPixels())
        assertEquals(PngFixture.colorAt(16, 16), pixels.rgbAt(8, 8, image.width))
        assertEquals(PngFixture.colorAt(112, 80), pixels.rgbAt(56, 40, image.width))
      } finally {
        image.close()
      }
    }
  }

  /**
   * `kCGImageSourceSubsampleFactor` accepts only 1, 2, 4 and 8, so a sample size of 16 is where
   * the clamp and the residual downscale have to meet. If the residual were dropped the image
   * would come back at 1/8: twice the requested size in each dimension, and four times the bytes
   * the caller budgeted for.
   */
  @Test
  fun aSampleSizePastTheSubsampleCapStillYieldsTheRequestedSize() = runTest {
    assertNotNull(createRegionDecoder(pngBytes())).use { decoder ->
      val whole = ImageRegion.of(decoder.imageSize)
      val image = assertNotNull(decoder.decodeRegion(whole, sampleSize = 16))
      try {
        assertEquals(PngFixture.WIDTH / 16, image.width)
        assertEquals(PngFixture.HEIGHT / 16, image.height)
      } finally {
        image.close()
      }
    }
  }

  @Test
  fun aRegionRunningOffTheEdgeIsClippedRatherThanRefused() = runTest {
    assertNotNull(createRegionDecoder(pngBytes())).use { decoder ->
      val overhanging = ImageRegion(left = 96, top = 64, right = 400, bottom = 400)
      val image = assertNotNull(decoder.decodeRegion(overhanging, sampleSize = 1))
      try {
        assertEquals(32, image.width)
        assertEquals(32, image.height)
        val pixels = assertNotNull(image.image.readArgbPixels())
        assertEquals(PngFixture.colorAt(100, 70), pixels.rgbAt(4, 6, image.width))
      } finally {
        image.close()
      }

      val outside = ImageRegion(left = 200, top = 200, right = 300, bottom = 300)
      assertNull(decoder.decodeRegion(outside, sampleSize = 1))
    }
  }

  @Test
  fun decodesFromAFilePathWithoutLoadingTheFile() = runTest {
    val path = assertNotNull(writeTempFile("crayfish-region-decoder.png", PngFixture.image()))
    try {
      assertNotNull(createRegionDecoder(CropSource.FilePath(path))).use { decoder ->
        assertEquals(ImageSize(PngFixture.WIDTH, PngFixture.HEIGHT), decoder.imageSize)
        assertEquals(ImageFormat.PNG, decoder.format)

        val region = ImageRegion(left = 0, top = 0, right = 32, bottom = 32)
        val image = assertNotNull(decoder.decodeRegion(region, sampleSize = 1))
        try {
          val pixels = assertNotNull(image.image.readArgbPixels())
          assertEquals(PngFixture.colorAt(0, 0), pixels.rgbAt(0, 0, image.width))
        } finally {
          image.close()
        }
      }
    } finally {
      remove(path)
    }
  }

  @Test
  fun closeIsIdempotentAndDecodingAfterItReturnsNull() = runTest {
    val decoder = assertNotNull(createRegionDecoder(pngBytes()))
    val whole = ImageRegion.of(decoder.imageSize)

    decoder.close()
    decoder.close()

    assertNull(decoder.decodeRegion(whole, sampleSize = 1))
  }

  /**
   * GIF is recognised and then declined. ImageIO would decode it, but
   * [ImageFormat.supportsRegionDecoding] is what gates the tiled path on every target, and a
   * format that is out on one platform has to be out on all of them or the layer above cannot
   * reason about it.
   */
  @Test
  fun formatsThatCannotBeRegionDecodedAreRefusedBeforeImageIoIsOpened() = runTest {
    val gif = CropSource.Bytes(ImageHeaders.gif(width = 64, height = 64), cacheKey = "gif")
    assertNull(createRegionDecoder(gif))
  }

  @Test
  fun unreadableSourcesYieldNullRatherThanThrowing() = runTest {
    assertNull(createRegionDecoder(CropSource.Bytes(ByteArray(64) { 0x7F }, "garbage")))
    assertNull(createRegionDecoder(CropSource.Bytes(ByteArray(0), "empty")))
    assertNull(createRegionDecoder(CropSource.FilePath("/nowhere/crayfish-missing.png")))
  }

  /**
   * A truncated file is an ordinary event on a photo picker. Either outcome below is legal (the
   * header still names a size, so ImageIO may well open it), but an exception escaping into a
   * tile loop is not.
   */
  @Test
  fun aTruncatedFileFailsAsAValueRatherThanAnException() = runTest {
    val truncated = PngFixture.image().copyOf(64)
    createRegionDecoder(CropSource.Bytes(truncated, "truncated"))?.use { decoder ->
      decoder.decodeRegion(ImageRegion.of(decoder.imageSize), sampleSize = 1)?.close()
    }
  }

  /**
   * The clamp itself, which the decoded output cannot show: whether ImageIO subsampled or Core
   * Graphics scaled afterwards, the returned image is the same size either way. Only the factor
   * handed to `kCGImageSourceSubsampleFactor` says which one did the work, and a value outside
   * 1/2/4/8 is ignored rather than rejected, so this is the one place the rule is observable.
   */
  @Test
  fun theSubsampleFactorIsAlwaysOneOfTheFourImageIoAccepts() {
    val legal = setOf(1, 2, 4, 8)
    for (sampleSize in -4..2048) {
      val factor = decodeFactorFor(sampleSize)
      assertContains(legal, factor, "sampleSize=$sampleSize produced factor $factor")
    }

    assertEquals(1, decodeFactorFor(1))
    assertEquals(8, decodeFactorFor(8))
    // Rounded down, never up: decoding more detail than asked is recoverable, less is not.
    assertEquals(2, decodeFactorFor(3))
    assertEquals(4, decodeFactorFor(7))
    // Past the cap the residual is somebody else's problem; see toPlatformImage.
    assertEquals(8, decodeFactorFor(16))
    assertEquals(8, decodeFactorFor(SampleSize.MAX))
  }

  private fun pngBytes(): CropSource = CropSource.Bytes(PngFixture.image(), cacheKey = "fixture")

  /** The `0xRRGGBB` of the pixel at ([x], [y]) in a row-major ARGB buffer [rowWidth] wide. */
  private fun IntArray.rgbAt(x: Int, y: Int, rowWidth: Int): Int = this[y * rowWidth + x] and
    0xFFFFFF

  @OptIn(ExperimentalForeignApi::class)
  private fun writeTempFile(name: String, bytes: ByteArray): String? {
    val path = NSTemporaryDirectory() + name
    val file = fopen(path, "wb") ?: return null
    try {
      val written = bytes.usePinned {
        fwrite(it.addressOf(0), 1uL, bytes.size.toULong(), file)
      }
      return if (written.toInt() == bytes.size) path else null
    } finally {
      fclose(file)
    }
  }
}
