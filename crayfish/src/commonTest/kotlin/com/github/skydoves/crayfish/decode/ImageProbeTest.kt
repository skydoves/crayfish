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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImageProbeTest {

  @Test
  fun readsPngSize() {
    val result = ImageProbe.probe(ImageHeaders.png(width = 1920, height = 1080))

    assertEquals(ImageFormat.PNG, result?.format)
    assertEquals(ImageSize(1920, 1080), result?.size)
  }

  @Test
  fun readsJpegSizeAcrossTheMarkerSegments() {
    val result = ImageProbe.probe(ImageHeaders.jpeg(width = 4032, height = 3024))

    assertEquals(ImageFormat.JPEG, result?.format)
    assertEquals(ImageSize(4032, 3024), result?.size)
  }

  /**
   * The size must survive a frame header pushed thousands of bytes in by camera metadata. A parser
   * that reads a fixed offset instead of walking segments passes the previous test and fails this.
   */
  @Test
  fun readsJpegSizeBehindALargeExifSegment() {
    val result = ImageProbe.probe(ImageHeaders.jpegWithLargeExif(width = 8000, height = 6000))

    assertEquals(ImageFormat.JPEG, result?.format)
    assertEquals(ImageSize(8000, 6000), result?.size)
  }

  /**
   * 0xC4, 0xC8 and 0xCC sit inside the start-of-frame range but are a Huffman table, a JPEG
   * extension and an arithmetic-coding table. Reading their payload as a frame header yields a
   * confident, wrong size.
   */
  @Test
  fun doesNotMistakeHuffmanTablesForFrameHeaders() {
    listOf(0xC4, 0xC8, 0xCC).forEach { marker ->
      val result = ImageProbe.probe(ImageHeaders.jpeg(640, 480, startOfFrameMarker = marker))

      assertEquals(ImageFormat.JPEG, result?.format, "marker 0x${marker.toString(16)}")
      assertNull(result?.size, "marker 0x${marker.toString(16)} must not yield a size")
    }
  }

  @Test
  fun readsEveryStartOfFrameFlavour() {
    // Baseline, extended sequential, progressive, lossless, and their arithmetic-coded twins.
    listOf(0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF)
      .forEach { marker ->
        val result = ImageProbe.probe(ImageHeaders.jpeg(1024, 768, startOfFrameMarker = marker))

        assertEquals(
          ImageSize(1024, 768),
          result?.size,
          "marker 0x${marker.toString(16)} should be a start of frame",
        )
      }
  }

  @Test
  fun readsGifSize() {
    listOf("GIF87a", "GIF89a").forEach { version ->
      val result = ImageProbe.probe(ImageHeaders.gif(320, 240, version))

      assertEquals(ImageFormat.GIF, result?.format, version)
      assertEquals(ImageSize(320, 240), result?.size, version)
    }
  }

  @Test
  fun readsAllThreeWebPFlavours() {
    assertEquals(ImageSize(800, 600), ImageProbe.probe(ImageHeaders.webPLossy(800, 600))?.size)
    assertEquals(ImageSize(800, 600), ImageProbe.probe(ImageHeaders.webPLossless(800, 600))?.size)
    assertEquals(ImageSize(800, 600), ImageProbe.probe(ImageHeaders.webPExtended(800, 600))?.size)

    assertEquals(ImageFormat.WEBP, ImageProbe.probe(ImageHeaders.webPLossy(8, 8))?.format)
  }

  /**
   * Lossy WebP stores its dimensions in 14 bits with the top two bits used as a scale hint, so a
   * parser that reads the full 16 bits reports a wrong size only for images wide enough to set
   * them. 16383 is the largest value that must round-trip.
   */
  @Test
  fun masksTheWebPScaleHintBits() {
    val withScaleBitsSet = ImageHeaders.webPLossy(width = 0xC000 or 1234, height = 0xC000 or 567)

    assertEquals(ImageSize(1234, 567), ImageProbe.probe(withScaleBitsSet)?.size)
  }

  @Test
  fun readsWebPAtItsMaximumDimensions() {
    assertEquals(
      ImageSize(16383, 16383),
      ImageProbe.probe(ImageHeaders.webPLossy(16383, 16383))?.size,
    )
    assertEquals(
      ImageSize(16384, 16384),
      ImageProbe.probe(ImageHeaders.webPLossless(16384, 16384))?.size,
    )
  }

  @Test
  fun recognisesHeifAndAvifBrands() {
    assertEquals(ImageFormat.HEIF, ImageProbe.probe(ImageHeaders.isoBaseMedia("heic"))?.format)
    assertEquals(ImageFormat.HEIF, ImageProbe.probe(ImageHeaders.isoBaseMedia("heix"))?.format)
    assertEquals(ImageFormat.AVIF, ImageProbe.probe(ImageHeaders.isoBaseMedia("avif"))?.format)
  }

  /**
   * AVIF files routinely declare `mif1` as their major brand and name `avif` only among the
   * compatible brands. Reading the major brand alone classifies them as plain HEIF, and they then
   * take a region-decoding path no platform supports for AVIF.
   */
  @Test
  fun readsAvifDeclaredOnlyInCompatibleBrands() {
    val header = ImageHeaders.isoBaseMedia("mif1", "mif1", "avif", "miaf")

    assertEquals(ImageFormat.AVIF, ImageProbe.probe(header)?.format)
  }

  /** Their sizes live behind `ispe`/`ipma`; guessing is worse than deferring to the decoder. */
  @Test
  fun reportsNoSizeForIsoBaseMediaContainers() {
    assertNull(ImageProbe.probe(ImageHeaders.isoBaseMedia("heic"))?.size)
    assertNull(ImageProbe.probe(ImageHeaders.isoBaseMedia("avif"))?.size)
  }

  @Test
  fun returnsNullForUnrecognisedBytes() {
    assertNull(ImageProbe.probe(byteArrayOf()))
    assertNull(ImageProbe.probe(byteArrayOf(0, 1, 2, 3)))
    assertNull(ImageProbe.probe("not an image at all".encodeToByteArray()))
    // An ISO base media file that is a video, not an image.
    assertNull(ImageProbe.probe(ImageHeaders.isoBaseMedia("isom", "mp42")))
  }

  /**
   * A truncated file is an ordinary event on a photo picker. Every prefix of every fixture must
   * return a result or null, and must never throw.
   */
  @Test
  fun neverThrowsOnATruncatedFile() {
    val fixtures = listOf(
      ImageHeaders.png(100, 100),
      ImageHeaders.jpeg(100, 100),
      ImageHeaders.jpegWithLargeExif(100, 100, exifPayloadSize = 64),
      ImageHeaders.gif(100, 100),
      ImageHeaders.webPLossy(100, 100),
      ImageHeaders.webPLossless(100, 100),
      ImageHeaders.webPExtended(100, 100),
      ImageHeaders.isoBaseMedia("heic", "mif1"),
    )

    fixtures.forEach { fixture ->
      for (length in 0..fixture.size) {
        ImageProbe.probe(fixture.copyOf(length))
      }
    }
  }

  /** A header claiming a zero dimension would make a sample-size calculation divide by zero. */
  @Test
  fun rejectsZeroDimensions() {
    assertNull(ImageProbe.probe(ImageHeaders.png(0, 100))?.size)
    assertNull(ImageProbe.probe(ImageHeaders.png(100, 0))?.size)
    assertNull(ImageProbe.probe(ImageHeaders.gif(0, 0))?.size)
    assertNull(ImageProbe.probe(ImageHeaders.jpeg(0, 480))?.size)
  }

  /** A PNG may legally claim a size that overflows a signed Int once read as unsigned. */
  @Test
  fun rejectsAPngClaimingAnImpossibleSize() {
    val absurd = ImageHeaders.png(width = -1, height = -1) // writes 0xFFFFFFFF for both

    assertNull(ImageProbe.probe(absurd)?.size)
  }
}
