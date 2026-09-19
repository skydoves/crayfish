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

/**
 * What [ImageProbe] does with headers that are wrong rather than merely short.
 *
 * [ImageProbeTest] covers the files a camera writes. These are the files a cancelled download, a
 * fuzzer or a writer with an off-by-one produces, and they are the half of the input space where a
 * plausible-but-wrong parser still answers confidently: every fixture below declares a size that a
 * missing check would happily report.
 */
class ImageProbeMalformedTest {

  // ---------------------------------------------------------------------------------------------
  // JPEG: the marker walk
  // ---------------------------------------------------------------------------------------------

  /**
   * Any number of 0xFF bytes may precede a marker, and each one has to be stepped over singly.
   *
   * Reading `FF FF` as a marker instead takes 0xFF for the marker byte, reads the two bytes after
   * it as a segment length, and jumps tens of kilobytes forward into whatever is there.
   */
  @Test
  fun stepsOverFillBytesOneAtATime() {
    val jpeg = jpegOf {
      segment(0xE0, jfifPayload())
      bytes(0xFF, 0xFF) // Two fill bytes; the third 0xFF below introduces the real marker.
      segment(0xC0, sofPayload(width = 640, height = 480))
      startOfScan()
    }

    assertEquals(ImageSize(640, 480), ImageProbe.probe(jpeg)?.size)
  }

  /**
   * The standalone markers carry no length field, so the walk steps exactly two bytes for each.
   *
   * 0x01 is TEM and 0xD0..0xD9 are the eight restart markers plus SOI and EOI. Reading a length
   * after one of them reads the *next* marker's bytes as a number, which is how a parser walks off
   * the end of a perfectly ordinary file. Every value in the range is named here rather than one
   * from the middle, because the bounds are where an `in` test is written wrong.
   */
  @Test
  fun stepsOverEveryStandaloneMarkerWithoutReadingALength() {
    (listOf(0x01) + (0xD0..0xD9)).forEach { marker ->
      val jpeg = jpegOf {
        bytes(0xFF, marker)
        segment(0xC0, sofPayload(width = 640, height = 480))
        startOfScan()
      }

      assertEquals(
        ImageSize(640, 480),
        ImageProbe.probe(jpeg)?.size,
        "standalone marker 0x${marker.toString(16)}",
      )
    }
  }

  /**
   * A segment whose declared length is shorter than its payload desynchronises the walk, and the
   * parser has to stop rather than hunt for the next 0xFF.
   *
   * Resynchronising is the tempting repair and it is wrong: the bytes it would scan are payload,
   * and a payload byte pair that happens to read as a frame header yields a confident wrong size.
   * The fixture puts a real 640x480 frame behind the desync so that a scanning parser has something
   * to find; a correct one must report no size at all.
   */
  @Test
  fun stopsWhereADeclaredSegmentLengthDesynchronisesTheWalk() {
    val jpeg = jpegOf {
      // Four bytes of payload behind a length field that accounts for two of them.
      segment(0xE0, byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()), 4)
      segment(0xC0, sofPayload(width = 640, height = 480))
      startOfScan()
    }

    val result = ImageProbe.probe(jpeg)

    assertEquals(ImageFormat.JPEG, result?.format, "the file is still a JPEG")
    assertNull(result?.size, "nothing behind a desynchronised walk may be read as a frame header")
  }

  /**
   * A length field counts its own two bytes, so 0 and 1 are impossible values.
   *
   * The frame header is read *before* the walk advances, so a parser that does not reject the
   * length first reads height and width from bytes that the segment does not contain. Here those
   * bytes spell a plausible 640x480, which is exactly how this failure stays invisible.
   */
  @Test
  fun refusesASegmentDeclaringALengthBelowTwo() {
    listOf(0, 1).forEach { declaredLength ->
      val jpeg = jpegOf {
        segment(0xC0, sofPayload(width = 640, height = 480), declaredLength)
        startOfScan()
      }

      val result = ImageProbe.probe(jpeg)

      assertEquals(ImageFormat.JPEG, result?.format, "declared length $declaredLength")
      assertNull(result?.size, "a segment of declared length $declaredLength holds no frame header")
    }
  }

  /**
   * Markers below 0xC0 are reserved, not frame headers, even when their payload is shaped like one.
   *
   * The start-of-frame test is a range check with three holes punched in it, and a range check is
   * wrong at its ends before it is wrong anywhere else. The decoy segment here declares 200x100 in
   * exactly the layout a frame header uses; only the real frame's 640x480 may come back.
   */
  @Test
  fun doesNotMistakeAReservedMarkerBelowTheFrameRangeForAFrameHeader() {
    val jpeg = jpegOf {
      segment(0x02, sofPayload(width = 200, height = 100))
      segment(0xC0, sofPayload(width = 640, height = 480))
      startOfScan()
    }

    assertEquals(ImageSize(640, 480), ImageProbe.probe(jpeg)?.size)
  }

  // ---------------------------------------------------------------------------------------------
  // PNG
  // ---------------------------------------------------------------------------------------------

  /**
   * A good signature is not a good header. `IHDR` is read at a fixed offset because the spec pins
   * it there, so a file that stops inside it has no size to give and must say so rather than read
   * whatever four bytes follow.
   */
  @Test
  fun refusesAPngWhoseIhdrIsTruncated() {
    val truncated = pngOf {
      beU32(13)
      ascii("IHDR")
      beU32(1920)
      bytes(0x00, 0x00) // Two of the height's four bytes, and then the file ends.
    }

    assertNull(ImageProbe.probe(truncated))
  }

  /**
   * PNG requires `IHDR` to be the first chunk. A file that puts something else there is not one
   * this fixed-offset read can answer for, and guessing means reading a colour profile's bytes as
   * a pixel count.
   */
  @Test
  fun refusesAPngWhoseFirstChunkIsNotIhdr() {
    val sRgbFirst = pngOf {
      beU32(1)
      ascii("sRGB")
      bytes(0x00)
      beU32(0) // CRC
      ihdr(width = 1920, height = 1080)
    }

    assertNull(ImageProbe.probe(sRgbFirst))
  }

  // ---------------------------------------------------------------------------------------------
  // GIF
  // ---------------------------------------------------------------------------------------------

  /**
   * The signature is six bytes and the dimensions are the four after it. A download cut between
   * the two is a GIF that cannot state its size, and reporting one anyway means reporting whatever
   * the allocator left behind.
   */
  @Test
  fun refusesAGifTruncatedInsideItsLogicalScreenDescriptor() {
    val header = "GIF89a".encodeToByteArray() + byteArrayOf(0x20, 0x01, 0xE0.toByte())

    for (length in 6..header.size) {
      assertNull(ImageProbe.probe(header.copyOf(length)), "truncated to $length bytes")
    }
    // The positive control: one more byte and the same fixture reads 288x480.
    assertEquals(
      ImageSize(288, 480),
      ImageProbe.probe(header + byteArrayOf(0x01))?.size,
    )
  }

  // ---------------------------------------------------------------------------------------------
  // WebP
  // ---------------------------------------------------------------------------------------------

  /**
   * A `VP8L` header whose packed word has its top bit set must not become a negative `Int`.
   *
   * This is the classic bug in every hand-rolled reader: four bytes assembled through `Byte`
   * arithmetic wrap, the sign propagates through the shift, and the mask then yields a 1x1 image
   * that every caller downstream believes. The word here is 0x80000000 - an out-of-range
   * `version_number`, which is malformed - and the only honest answer to it is no size at all.
   */
  @Test
  fun refusesAVp8lHeaderWhosePackedWordHasItsTopBitSet() {
    // Written as bytes rather than as a number, because the number is what the bug turns into.
    val packedWords = mapOf(
      "0x80000000" to intArrayOf(0x00, 0x00, 0x00, 0x80),
      "0xFFFFFFFF" to intArrayOf(0xFF, 0xFF, 0xFF, 0xFF),
    )

    packedWords.forEach { (name, packed) ->
      val webP = webPOf {
        chunk(
          "VP8L",
          writeBytes {
            bytes(0x2F) // VP8L signature
            bytes(*packed)
          },
        )
      }

      val result = ImageProbe.probe(webP)

      assertEquals(ImageFormat.WEBP, result?.format, "packed word $name")
      assertNull(result?.size, "packed word $name must not read as a negative Int")
    }
  }

  /**
   * The size in the RIFF header is not what the probe reads, and a file whose declared size is a
   * lie has to come out the same as one whose declared size is right.
   *
   * A truncated download leaves that field describing bytes that are no longer there, and a probe
   * that trusted it would report nothing for a file whose chunks are perfectly readable.
   */
  @Test
  fun ignoresTheDeclaredRiffSize() {
    listOf(null, 0, 1, Int.MAX_VALUE).forEach { declared ->
      val webP = webPOf(declaredRiffSize = declared) {
        chunk("VP8 ", vp8Payload(width = 800, height = 600))
      }

      assertEquals(
        ImageSize(800, 600),
        ImageProbe.probe(webP)?.size,
        "declared RIFF size $declared",
      )
    }
  }

  /**
   * A WebP whose first chunk is none of the three bitstream flavours is still a WebP; there is
   * simply nothing to read a size from. Reporting `null` for the *format* would send the file down
   * a path that no longer knows it can be decoded at all.
   */
  @Test
  fun reportsTheFormatButNoSizeForAnUnknownBitstreamChunk() {
    val webP = webPOf {
      chunk("ICCP", ByteArray(16))
      chunk("VP8 ", vp8Payload(width = 800, height = 600))
    }

    val result = ImageProbe.probe(webP)

    assertEquals(ImageFormat.WEBP, result?.format)
    assertNull(result?.size, "only the first chunk is inspected, and it is not a bitstream")
  }

  /**
   * A `VP8 ` frame whose 14-bit dimension field is zero describes nothing decodable, and a caller
   * that believed it would divide by zero picking a sample size.
   */
  @Test
  fun refusesAVp8FrameDeclaringAZeroDimension() {
    listOf(0 to 600, 800 to 0, 0 to 0).forEach { (width, height) ->
      val webP = webPOf { chunk("VP8 ", vp8Payload(width, height)) }

      assertNull(ImageProbe.probe(webP)?.size, "${width}x$height")
    }
  }

  /**
   * A `VP8 ` chunk whose sync code is wrong is not a key frame, whatever the two bytes after it
   * happen to say. The sync code is the only thing distinguishing a key frame's header from the
   * middle of an inter frame.
   */
  @Test
  fun refusesAVp8FrameWithoutItsSyncCode() {
    val webP = webPOf {
      chunk(
        "VP8 ",
        writeBytes {
          bytes(0x00, 0x00, 0x00)
          bytes(0x9D, 0x01, 0x2B) // One bit wrong in the last byte of the sync code.
          leU16(800)
          leU16(600)
        },
      )
    }

    val result = ImageProbe.probe(webP)

    assertEquals(ImageFormat.WEBP, result?.format)
    assertNull(result?.size)
  }

  /**
   * The extended flavour states its canvas as two 24-bit minus-one values, and a file that ends
   * inside the second one carries only half an answer.
   */
  @Test
  fun refusesAVp8xCanvasThatIsCutInHalf() {
    val full = webPOf {
      chunk(
        "VP8X",
        writeBytes {
          bytes(0x00, 0x00, 0x00, 0x00) // flags
          leU24(4031)
          leU24(3023)
        },
      )
    }

    assertEquals(ImageSize(4032, 3024), ImageProbe.probe(full)?.size, "the positive control")
    for (length in 24 until full.size) {
      assertNull(ImageProbe.probe(full.copyOf(length))?.size, "truncated to $length bytes")
    }
  }
}
