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
package com.github.skydoves.crayfish.exif

import com.github.skydoves.crayfish.decode.ByteWriter
import com.github.skydoves.crayfish.decode.jfifPayload
import com.github.skydoves.crayfish.decode.jpegOf
import com.github.skydoves.crayfish.decode.segment
import com.github.skydoves.crayfish.decode.sofPayload
import com.github.skydoves.crayfish.decode.startOfScan
import com.github.skydoves.crayfish.decode.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals

/** Exif `Orientation`, TIFF 6.0 tag 274. */
private const val TAG_ORIENTATION = 0x0112

/** TIFF 6.0 tag 256, `ImageWidth`: an ordinary entry that is not the one being looked for. */
private const val TAG_IMAGE_WIDTH = 0x0100

/** One IFD entry: tag(2) type(2) count(4) value-or-offset(4). */
private class TiffEntry(
  val tag: Int,
  val type: Int = ExifBytes.TYPE_SHORT,
  val count: Int = 1,
  val value: Int,
)

/**
 * What [ExifReader] does with files whose metadata is wrong rather than merely absent.
 *
 * [ExifReaderTest] proves the reader agrees with the specifications on files written to them. This
 * covers the other direction: a marker walk driven by lengths the file chose, and a TIFF block
 * whose every field is a number some other program wrote. The orientation tag is the one piece of
 * metadata in this library that can rotate a correct photo into a wrong one, so "unreadable" has to
 * come back as [ImageOrientation.NORMAL] every single time rather than as a best guess.
 */
class ExifReaderMalformedTest {

  private fun read(bytes: ByteArray): ImageOrientation = ExifReader.readOrientation(bytes)

  // ---------------------------------------------------------------------------------------------
  // The JPEG marker walk
  // ---------------------------------------------------------------------------------------------

  /**
   * Any number of 0xFF bytes may precede a marker. Reading `FF FF` as a marker instead takes the
   * second 0xFF for the marker byte and the two bytes after it for a length, which skips the Exif
   * segment entirely and reports every such photo upright.
   */
  @Test
  fun stepsOverFillBytesBeforeTheExifSegment() {
    val jpeg = jpegOf {
      segment(0xE0, jfifPayload())
      bytes(0xFF, 0xFF) // Two fill bytes; the 0xFF that starts APP1 below is the third.
      raw(exifApp1(tiffBlock(value = ImageOrientation.ROTATE_90.exifValue)))
      segment(0xC0, sofPayload(width = 4032, height = 3024))
      startOfScan()
    }

    assertEquals(ImageOrientation.ROTATE_90, read(jpeg))
  }

  /**
   * The standalone markers carry no length field. 0x01 is TEM and 0xD0..0xD9 are the eight restart
   * markers plus SOI and EOI; every value in the range is named rather than one from the middle,
   * because an `in` test is wrong at its ends before it is wrong anywhere else.
   */
  @Test
  fun stepsOverEveryStandaloneMarkerBeforeTheExifSegment() {
    (listOf(0x01) + (0xD0..0xD9)).forEach { marker ->
      val jpeg = jpegOf {
        bytes(0xFF, marker)
        raw(exifApp1(tiffBlock(value = ImageOrientation.TRANSVERSE.exifValue)))
        segment(0xC0, sofPayload(width = 4032, height = 3024))
        startOfScan()
      }

      assertEquals(
        ImageOrientation.TRANSVERSE,
        read(jpeg),
        "standalone marker 0x${marker.toString(16)}",
      )
    }
  }

  /**
   * A segment whose declared length is shorter than its payload desynchronises the walk, and the
   * only safe response is to stop.
   *
   * Scanning forward for the next 0xFF is the tempting repair: it would find the Exif segment here
   * and be right. It is still wrong, because the bytes it scans are payload, and payload that
   * happens to spell `FF E1` followed by the Exif identifier is a decoy the file chose. The
   * positive control is the same file with an honest length.
   */
  @Test
  fun stopsWhereADeclaredSegmentLengthDesynchronisesTheWalk() {
    fun jpegDeclaring(length: Int) = jpegOf {
      segment(0xE0, byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D), declaredLength = length)
      raw(exifApp1(tiffBlock(value = ImageOrientation.ROTATE_90.exifValue)))
      segment(0xC0, sofPayload(width = 4032, height = 3024))
      startOfScan()
    }

    assertEquals(ImageOrientation.ROTATE_90, read(jpegDeclaring(6)), "the positive control")
    assertEquals(ImageOrientation.NORMAL, read(jpegDeclaring(4)))
  }

  /**
   * A length field counts its own two bytes, so 0 and 1 are impossible values.
   *
   * The payload is inspected before the walk advances, so a parser that does not reject the length
   * first reads an Exif identifier and a whole TIFF block out of a segment that declares room for
   * neither. The fixture puts a real, readable block there, which is why this failure produces a
   * confident answer rather than a crash.
   */
  @Test
  fun refusesASegmentDeclaringALengthBelowTwo() {
    val payload = writeBytes {
      ascii(EXIF_IDENTIFIER)
      raw(tiffBlock(value = ImageOrientation.ROTATE_180.exifValue))
    }

    fun jpegDeclaring(length: Int) = jpegOf {
      segment(0xE1, payload, declaredLength = length)
      segment(0xC0, sofPayload(width = 4032, height = 3024))
      startOfScan()
    }

    assertEquals(
      ImageOrientation.ROTATE_180,
      read(jpegDeclaring(payload.size + 2)),
      "the positive control",
    )
    listOf(0, 1).forEach { length ->
      assertEquals(ImageOrientation.NORMAL, read(jpegDeclaring(length)), "declared length $length")
    }
  }

  /**
   * An `APP1` is only the Exif one if its payload begins with `Exif` and two NUL bytes. Phone
   * photos carry XMP in an `APP1` too, and Multi-Picture Format uses a nearly identical prefix, so
   * "the first APP1" is the wrong segment on a large share of real files.
   *
   * The decoy here differs from the identifier in its last byte alone, and it declares a different
   * orientation, so a prefix comparison that stops early answers with it.
   */
  @Test
  fun ignoresAnApp1WhoseIdentifierIsOnlyNearlyExif() {
    val decoy = writeBytes {
      ascii("Exif")
      bytes(0x00, 0x01) // The second pad byte must be NUL, and this one is not.
      raw(tiffBlock(value = ImageOrientation.FLIP_HORIZONTAL.exifValue))
    }
    val jpeg = jpegOf {
      segment(0xE0, jfifPayload())
      segment(0xE1, decoy)
      raw(exifApp1(tiffBlock(value = ImageOrientation.ROTATE_270.exifValue)))
      segment(0xC0, sofPayload(width = 4032, height = 3024))
      startOfScan()
    }

    assertEquals(ImageOrientation.ROTATE_270, read(jpeg))
  }

  // ---------------------------------------------------------------------------------------------
  // The TIFF block
  // ---------------------------------------------------------------------------------------------

  /**
   * The block opens with the two bytes that name its byte order, and there are exactly two legal
   * values. Anything else means the segment is not a TIFF block, and picking a default order would
   * read the next field's bytes in the wrong direction and get a plausible number out of them.
   */
  @Test
  fun refusesATiffBlockWithoutAByteOrderMark() {
    listOf("XX", "IM", "MI", "ii", "mm").forEach { mark ->
      val jpeg = jpegWithExif(tiffBlock(byteOrderMark = mark))

      assertEquals(ImageOrientation.NORMAL, read(jpeg), "byte order mark $mark")
    }
  }

  /**
   * The 42 after the byte-order mark is what confirms the block really is TIFF. Without it, any
   * payload whose first two bytes happen to be `II` or `MM` would be walked as an IFD.
   */
  @Test
  fun refusesATiffBlockWhoseMagicNumberIsNot42() {
    listOf(0, 41, 43, 0x2A00, 0xFFFF).forEach { magic ->
      val jpeg = jpegWithExif(tiffBlock(magic = magic))

      assertEquals(ImageOrientation.NORMAL, read(jpeg), "magic $magic")
    }
  }

  /**
   * An IFD's entry count is a 16-bit number the file chose, and the entries are read at a stride
   * from it. Trusting a count of 1000 in a block that holds one entry walks 12000 bytes past the
   * end of a file that is a few hundred long.
   *
   * The fixture is a JPEG cut off directly after its Exif segment, which is what an interrupted
   * download looks like, and its single entry is deliberately not the Orientation tag so that the
   * walk cannot stop before it reaches the lie.
   */
  @Test
  fun refusesAnIfdWhoseEntryCountRunsPastTheEndOfTheFile() {
    fun jpegDeclaring(entries: List<TiffEntry>, declaredEntryCount: Int) = jpegOf {
      segment(0xE0, jfifPayload())
      raw(exifApp1(tiffBlock(entries = entries, declaredEntryCount = declaredEntryCount)))
    }

    val honest = listOf(
      TiffEntry(TAG_IMAGE_WIDTH, value = 4032),
      TiffEntry(TAG_ORIENTATION, value = ImageOrientation.ROTATE_90.exifValue),
    )
    assertEquals(
      ImageOrientation.ROTATE_90,
      read(jpegDeclaring(honest, declaredEntryCount = 2)),
      "the positive control: the same walk, over a count the file can back up",
    )

    val liar = listOf(TiffEntry(TAG_IMAGE_WIDTH, value = 4032))
    listOf(2, 1000, 65535).forEach { count ->
      assertEquals(
        ImageOrientation.NORMAL,
        read(jpegDeclaring(liar, declaredEntryCount = count)),
        "an IFD of one entry declaring $count",
      )
    }
  }

  /**
   * `Orientation` is defined as exactly one SHORT. A count of anything else means the four-byte
   * value field holds an offset to an array rather than the value itself, and reading it as a value
   * yields a number that is not an orientation at all.
   */
  @Test
  fun refusesAnOrientationTagWhoseCountIsNotOne() {
    listOf(0, 2, 65536).forEach { count ->
      val jpeg = jpegWithExif(
        tiffBlock(
          entries = listOf(
            TiffEntry(
              TAG_ORIENTATION,
              count = count,
              value = ImageOrientation.ROTATE_90.exifValue,
            ),
          ),
        ),
      )

      assertEquals(ImageOrientation.NORMAL, read(jpeg), "count $count")
    }
  }

  /**
   * An IFD that simply does not carry the tag is the ordinary case for a scanned or edited image,
   * and it is upright by definition. The fixture walks past four other tags to get there, so a
   * reader that stops at the first entry would report the same answer for the wrong reason.
   */
  @Test
  fun returnsNormalForAnIfdCarryingNoOrientationTag() {
    val jpeg = jpegWithExif(
      tiffBlock(
        entries = listOf(
          TiffEntry(0x0100, value = 4032), // ImageWidth
          TiffEntry(0x0101, value = 3024), // ImageLength
          TiffEntry(0x0128, value = 2), // ResolutionUnit
          TiffEntry(0x0213, value = 1), // YCbCrPositioning
        ),
      ),
    )

    assertEquals(ImageOrientation.NORMAL, read(jpeg))
  }

  /**
   * The tag is defined for 1..8. Every other value is a malformed file, and the only choice that
   * cannot rotate an already-correct image is to treat it as upright: a wrongly rotated photo
   * breaks a file that worked, where leaving it alone leaves a problem that was already there.
   */
  @Test
  fun treatsAnOrientationValueOutsideOneToEightAsUpright() {
    listOf(0, 9, 10, 255, 65535).forEach { value ->
      val jpeg = jpegWithExif(tiffBlock(value = value))

      assertEquals(ImageOrientation.NORMAL, read(jpeg), "orientation value $value")
    }
    // The positive control: every value that is defined still reads back.
    ImageOrientation.entries.forEach { expected ->
      assertEquals(expected, read(jpegWithExif(tiffBlock(value = expected.exifValue))))
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The WebP chunk walk
  // ---------------------------------------------------------------------------------------------

  /**
   * An `EXIF` chunk that cannot be read is not the end of the walk.
   *
   * RIFF allows more than one chunk of a type, and an editor that appends rather than rewrites
   * leaves a stale one in front of the live one. Stopping at the first unreadable chunk would
   * report upright for a file that says otherwise a few hundred bytes later.
   */
  @Test
  fun keepsWalkingPastAnUnreadableExifChunk() {
    val webP = webPWithExifChunks(
      byteArrayOf(0x00, 0x00, 0x00, 0x00), // No byte-order mark, so nothing to read.
      tiffBlock(value = ImageOrientation.TRANSPOSE.exifValue),
    )

    assertEquals(ImageOrientation.TRANSPOSE, read(webP))
  }

  // ---------------------------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------------------------

  /** An Exif `APP1`: the marker, the length, the six-byte identifier, then the TIFF block. */
  private fun exifApp1(tiff: ByteArray): ByteArray = writeBytes {
    segment(
      0xE1,
      writeBytes {
        ascii(EXIF_IDENTIFIER)
        raw(tiff)
      },
    )
  }

  /** The ordinary camera layout, with [tiff] in its Exif `APP1`. */
  private fun jpegWithExif(tiff: ByteArray): ByteArray = jpegOf {
    segment(0xE0, jfifPayload())
    raw(exifApp1(tiff))
    segment(0xC0, sofPayload(width = 4032, height = 3024))
    startOfScan()
  }

  /**
   * A TIFF block with an IFD0, every field of which these fixtures are free to lie about.
   *
   * @param byteOrderMark the two bytes that name the byte order. Only `II` and `MM` are legal.
   * @param magic the confirmation word, which is 42 in a real block.
   * @param declaredEntryCount what the IFD says it holds, which need not be what it holds.
   */
  private fun tiffBlock(
    order: ByteOrder = ByteOrder.BIG,
    byteOrderMark: String = if (order == ByteOrder.LITTLE) "II" else "MM",
    magic: Int = 42,
    value: Int = ImageOrientation.ROTATE_90.exifValue,
    entries: List<TiffEntry> = listOf(TiffEntry(TAG_ORIENTATION, value = value)),
    declaredEntryCount: Int = entries.size,
  ): ByteArray = writeBytes {
    ascii(byteOrderMark)
    u16(magic, order)
    u32(8, order) // Offset of IFD0, measured from the byte-order mark above.
    u16(declaredEntryCount, order)
    entries.forEach { entry ->
      u16(entry.tag, order)
      u16(entry.type, order)
      u32(entry.count, order)
      if (entry.type == ExifBytes.TYPE_SHORT) {
        // A SHORT occupies the first two bytes of the four-byte value field, in the declared
        // order. The other two are undefined padding, filled here so that a reader taking all four
        // gets nonsense rather than accidentally the right answer.
        u16(entry.value, order)
        bytes(0xFF, 0xFF)
      } else {
        u32(entry.value, order)
      }
    }
    u32(0, order) // No IFD1.
  }

  /** An extended WebP carrying one `EXIF` chunk per block given, in order. */
  private fun webPWithExifChunks(vararg blocks: ByteArray): ByteArray {
    val chunks = writeBytes {
      ascii("VP8X")
      leU32(10)
      bytes(0x08, 0x00, 0x00, 0x00) // flags: EXIF present
      leU24(4031)
      leU24(3023)
      blocks.forEach { block ->
        ascii("EXIF")
        leU32(block.size)
        raw(block)
        if (block.size % 2 == 1) bytes(0x00)
      }
    }
    return writeBytes {
      ascii("RIFF")
      leU32(4 + chunks.size)
      ascii("WEBP")
      raw(chunks)
    }
  }

  private fun ByteWriter.u16(value: Int, order: ByteOrder) =
    if (order == ByteOrder.LITTLE) leU16(value) else beU16(value)

  private fun ByteWriter.u32(value: Int, order: ByteOrder) =
    if (order == ByteOrder.LITTLE) leU32(value) else beU32(value)
}
