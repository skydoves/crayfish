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

import com.github.skydoves.crayfish.decode.ImageHeaders
import kotlin.test.Test
import kotlin.test.assertEquals

class ExifReaderTest {

  private fun read(bytes: ByteArray): ImageOrientation = ExifReader.readOrientation(bytes)

  // ---------------------------------------------------------------------------------------------
  // JPEG
  // ---------------------------------------------------------------------------------------------

  /**
   * All eight values, little-endian. Half the tag values are the mirrored ones, which is where a
   * reader that only knows about rotations quietly loses information.
   */
  @Test
  fun readsEveryOrientationFromALittleEndianJpeg() {
    ImageOrientation.entries.forEach { expected ->
      val jpeg = ExifBytes.jpeg(ExifBytes.exifBlock(expected.exifValue, ByteOrder.LITTLE))

      assertEquals(expected, read(jpeg), "exif ${expected.exifValue}, byte order II")
    }
  }

  /**
   * All eight again, big-endian.
   *
   * The TIFF header names its own byte order and cameras ship both, so a parser that hardcodes
   * little-endian passes the test above and fails every case here, and in the field, fails on an
   * apparently random half of the user's library.
   */
  @Test
  fun readsEveryOrientationFromABigEndianJpeg() {
    ImageOrientation.entries.forEach { expected ->
      val jpeg = ExifBytes.jpeg(ExifBytes.exifBlock(expected.exifValue, ByteOrder.BIG))

      assertEquals(expected, read(jpeg), "exif ${expected.exifValue}, byte order MM")
    }
  }

  /**
   * IFD0 entries on both sides of the Orientation tag, so the entry walk is exercised rather than
   * a guess at the first entry's offset.
   */
  @Test
  fun walksPastIfd0EntriesOnEitherSideOfTheOrientationTag() {
    listOf(ByteOrder.LITTLE, ByteOrder.BIG).forEach { order ->
      val exif = ExifBytes.exifBlock(
        orientation = ImageOrientation.TRANSVERSE.exifValue,
        order = order,
        // Ascending as TIFF requires: ImageWidth, ImageLength, BitsPerSample, Compression ...
        tagsBefore = listOf(0x0100, 0x0101, 0x0102, 0x0103),
        // ... then ResolutionUnit, YCbCrPositioning, ISOSpeedRatings.
        tagsAfter = listOf(0x0128, 0x0213, 0x8827),
      )

      assertEquals(ImageOrientation.TRANSVERSE, read(ExifBytes.jpeg(exif)), "byte order $order")
    }
  }

  /**
   * IFD0 pushed away from the offset a parser would otherwise assume.
   *
   * The IFD0 pointer is measured from the TIFF header, not from the start of the file. With the
   * header at file offset 12 and IFD0 another 16 bytes past its own base, a file-relative reader
   * lands inside the TIFF header itself and reads a plausible, wrong entry count.
   */
  @Test
  fun treatsIfdOffsetsAsRelativeToTheTiffHeader() {
    val exif = ExifBytes.exifBlock(
      orientation = ImageOrientation.ROTATE_90.exifValue,
      order = ByteOrder.BIG,
      ifdPadding = 16,
    )

    assertEquals(ImageOrientation.ROTATE_90, read(ExifBytes.jpeg(exif)))
  }

  /** A JPEG with no Exif segment at all is upright until something says otherwise. */
  @Test
  fun returnsNormalForAJpegWithNoExif() {
    assertEquals(ImageOrientation.NORMAL, read(ExifBytes.jpeg(exif = null)))
  }

  /**
   * Phone photos carry an XMP `APP1` alongside the Exif one, and XMP is usually written first. A
   * parser that takes the first `APP1` it finds reads an XML namespace URL as a TIFF header.
   */
  @Test
  fun ignoresAnXmpApp1BeforeTheExifOne() {
    val exif = ExifBytes.exifBlock(ImageOrientation.ROTATE_180.exifValue, ByteOrder.LITTLE)

    assertEquals(ImageOrientation.ROTATE_180, read(ExifBytes.jpeg(exif, withXmpDecoy = true)))
  }

  /**
   * Entropy-coded scan data is not marker space, and the walk must stop when it reaches it. The
   * fixture plants something shaped like an Exif `APP1` inside the scan, declaring a different
   * orientation from the real one in front of it.
   */
  @Test
  fun stopsWalkingAtTheStartOfScan() {
    val real = ExifBytes.exifBlock(ImageOrientation.ROTATE_90.exifValue, ByteOrder.BIG)
    val decoy = ExifBytes.exifBlock(ImageOrientation.ROTATE_180.exifValue, ByteOrder.BIG)

    assertEquals(
      ImageOrientation.NORMAL,
      read(ExifBytes.jpeg(exif = null, decoyAfterScan = decoy)),
      "scan data is not marker space, and nothing in it may be read as a segment",
    )
    assertEquals(
      ImageOrientation.ROTATE_90,
      read(ExifBytes.jpeg(real, decoyAfterScan = decoy)),
      "the real Exif segment, which precedes the scan, still wins",
    )
  }

  /**
   * The Orientation tag is defined as a single SHORT. A tag of any other type is malformed, and
   * reading it anyway means guessing at a width, and a wrong guess rotates an upright image.
   */
  @Test
  fun refusesAnOrientationTagThatIsNotAShort() {
    val exif = ExifBytes.exifBlock(
      orientation = ImageOrientation.ROTATE_270.exifValue,
      order = ByteOrder.LITTLE,
      orientationType = ExifBytes.TYPE_LONG,
    )

    assertEquals(ImageOrientation.NORMAL, read(ExifBytes.jpeg(exif)))
  }

  // ---------------------------------------------------------------------------------------------
  // HEIF: the container's own transform properties
  // ---------------------------------------------------------------------------------------------

  /**
   * `irot` states its angle in 90-degree **anti-clockwise** units while
   * [ImageOrientation.rotationDegrees] is **clockwise**, so every value but 0 and 180 must come out
   * reflected. Shipping the conversion unnegated leaves angles 0 and 2 correct and swaps 1 with 3,
   * which is why this test names all four rather than spot-checking one.
   */
  @Test
  fun convertsIrotFromAntiClockwiseToClockwise() {
    val expected = mapOf(
      0 to ImageOrientation.NORMAL, //     0 anti-clockwise ==   0 clockwise
      1 to ImageOrientation.ROTATE_270, // 90 anti-clockwise == 270 clockwise
      2 to ImageOrientation.ROTATE_180, // 180 either way
      3 to ImageOrientation.ROTATE_90, // 270 anti-clockwise ==  90 clockwise
    )

    expected.forEach { (angle, orientation) ->
      assertEquals(orientation, read(ExifBytes.heif(irotAngle = angle)), "irot angle $angle")
    }
  }

  /**
   * `imir`'s axis bit, on the 2022 edition's reading: 0 exchanges the top and bottom parts of the
   * image, 1 exchanges the left and right parts. The first edition's "a vertical or horizontal
   * axis" wording reads the other way round, and a reader that follows it flips both cases.
   */
  @Test
  fun readsBothImirAxes() {
    assertEquals(
      ImageOrientation.FLIP_VERTICAL,
      read(ExifBytes.heif(imirAxis = 0)),
      "axis 0 exchanges top and bottom",
    )
    assertEquals(
      ImageOrientation.FLIP_HORIZONTAL,
      read(ExifBytes.heif(imirAxis = 1)),
      "axis 1 exchanges left and right",
    )
  }

  /**
   * `irot` and `imir` together, for all eight combinations.
   *
   * These are the cases a rotation-plus-flag table gets wrong: composing a mirror onto a rotation
   * is not the same as listing them side by side, and the top-bottom axis has no direct term in
   * [ImageOrientation] at all: it is a left-right mirror plus a half turn.
   */
  @Test
  fun composesIrotWithImir() {
    val expected = mapOf(
      // irot angle to imir axis
      (0 to 0) to ImageOrientation.FLIP_VERTICAL,
      (0 to 1) to ImageOrientation.FLIP_HORIZONTAL,
      (1 to 0) to ImageOrientation.TRANSPOSE,
      (1 to 1) to ImageOrientation.TRANSVERSE,
      (2 to 0) to ImageOrientation.FLIP_HORIZONTAL,
      (2 to 1) to ImageOrientation.FLIP_VERTICAL,
      (3 to 0) to ImageOrientation.TRANSVERSE,
      (3 to 1) to ImageOrientation.TRANSPOSE,
    )

    expected.forEach { (input, orientation) ->
      val (angle, axis) = input
      val heif = ExifBytes.heif(irotAngle = angle, imirAxis = axis)

      assertEquals(orientation, read(heif), "irot $angle + imir $axis")
    }
  }

  /**
   * The transforms are applied in the order the file lists them, and that order changes the answer.
   *
   * MIAF constrains a writer to `irot` before `imir`, and `ipco` holds them that way in practice,
   * so composing as the walk encounters them reproduces the association order without correlating
   * `ipma`. This pins the fact that the order is honoured rather than normalised away.
   */
  @Test
  fun appliesTransformPropertiesInFileOrder() {
    val forwards = ExifBytes.heif(
      irotAngle = 1,
      imirAxis = 1,
      transformOrder = listOf("irot", "imir"),
    )
    val backwards = ExifBytes.heif(
      irotAngle = 1,
      imirAxis = 1,
      transformOrder = listOf("imir", "irot"),
    )

    assertEquals(ImageOrientation.TRANSVERSE, read(forwards), "rotate, then mirror")
    assertEquals(ImageOrientation.TRANSPOSE, read(backwards), "mirror, then rotate")
  }

  // ---------------------------------------------------------------------------------------------
  // HEIF: the Exif item, and reconciling the two sources
  // ---------------------------------------------------------------------------------------------

  /**
   * The iOS 15 shape: the Photos editor writes the Exif `Orientation` tag and no transform boxes at
   * all (libvips#2551). A reader that only looks at `irot`/`imir` shows every edited iPhone photo
   * sideways, which is the failure that put the Exif fallback here in the first place.
   */
  @Test
  fun fallsBackToTheExifItemWhenThereAreNoTransformBoxes() {
    ImageOrientation.entries.forEach { expected ->
      val heif = ExifBytes.heif(exif = ExifBytes.exifBlock(expected.exifValue, ByteOrder.BIG))

      assertEquals(expected, read(heif), "exif ${expected.exifValue} with no irot or imir")
    }
  }

  /**
   * When the two sources disagree, the container's transform properties win.
   *
   * This is the documented precedence rule and it is a choice, not an accident: `irot`/`imir` are
   * normative for the container, so a decoder that already honoured them has produced pixels the
   * Exif tag would rotate a second time. The fixture makes the two disagree on purpose: a reader
   * with no rule at all would answer with whichever source it happened to read first.
   */
  @Test
  fun prefersTheContainerTransformWhenItDisagreesWithExif() {
    val exif = ExifBytes.exifBlock(ImageOrientation.ROTATE_90.exifValue, ByteOrder.BIG)

    assertEquals(
      ImageOrientation.ROTATE_270,
      read(ExifBytes.heif(irotAngle = 1, exif = exif)),
      "irot says 90 anti-clockwise, exif says 90 clockwise",
    )
    assertEquals(
      ImageOrientation.FLIP_HORIZONTAL,
      read(ExifBytes.heif(imirAxis = 1, exif = exif)),
      "imir says mirror left-right, exif says rotate 90 clockwise",
    )
  }

  /**
   * An `irot` of angle 0 is still an answer: the container is saying "upright", and the Exif tag
   * does not get to overrule it. This is the edge the precedence rule is easiest to get wrong on,
   * because "no rotation" and "no property" look the same to a reader that tracks only degrees.
   */
  @Test
  fun treatsAZeroAngleIrotAsAnAnswerRatherThanAnAbsence() {
    val exif = ExifBytes.exifBlock(ImageOrientation.ROTATE_180.exifValue, ByteOrder.LITTLE)

    assertEquals(ImageOrientation.NORMAL, read(ExifBytes.heif(irotAngle = 0, exif = exif)))
  }

  /**
   * The Exif item's `exif_tiff_header_offset` counts the bytes before the TIFF header, and writers
   * use both 0 and 6, the latter because they reused their JPEG code and emitted the identifier.
   */
  @Test
  fun readsAnExifItemWithAndWithoutTheIdentifierPrefix() {
    listOf(0, EXIF_IDENTIFIER.length).forEach { headerOffset ->
      val heif = ExifBytes.heif(
        exif = ExifBytes.exifBlock(ImageOrientation.TRANSPOSE.exifValue, ByteOrder.LITTLE),
        exifTiffHeaderOffset = headerOffset,
      )

      assertEquals(ImageOrientation.TRANSPOSE, read(heif), "header offset $headerOffset")
    }
  }

  /**
   * `iloc` construction method 1 puts the item's bytes in `idat` inside `meta`, and the offset is
   * then relative to that box rather than to the file. Reading it as a file offset lands in `ftyp`.
   */
  @Test
  fun resolvesAnExifItemStoredInIdat() {
    val heif = ExifBytes.heif(
      exif = ExifBytes.exifBlock(ImageOrientation.ROTATE_270.exifValue, ByteOrder.BIG),
      constructionMethod = 1,
    )

    assertEquals(ImageOrientation.ROTATE_270, read(heif))
  }

  /**
   * The field widths in `iloc` and `infe` are chosen per file by their version bytes, so the same
   * item is written four visibly different ways here and has to read back identically.
   */
  @Test
  fun readsEveryIlocAndInfeVersionCombination() {
    listOf(0, 1).forEach { ilocVersion ->
      listOf(2, 3).forEach { infeVersion ->
        val heif = ExifBytes.heif(
          exif = ExifBytes.exifBlock(ImageOrientation.FLIP_VERTICAL.exifValue, ByteOrder.LITTLE),
          ilocVersion = ilocVersion,
          infeVersion = infeVersion,
        )

        assertEquals(
          ImageOrientation.FLIP_VERTICAL,
          read(heif),
          "iloc v$ilocVersion, infe v$infeVersion",
        )
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // HEIF: box-header shapes the walk has to survive
  // ---------------------------------------------------------------------------------------------

  /**
   * A `size == 1` box carries its real size as a 64-bit `largesize` after the header. A walk that
   * does not know that steps eight bytes instead of sixteen and reads the size itself as the next
   * box, so `meta` is never found and the file reports upright.
   */
  @Test
  fun walksPastABoxWrittenWithASixtyFourBitSize() {
    val heif = ExifBytes.heif(irotAngle = 3, sixtyFourBitBoxes = true)

    assertEquals(ImageOrientation.ROTATE_90, read(heif))
  }

  /**
   * A `size == 0` box runs to the end of the file. A one-pass writer uses it for its last box
   * because it cannot know that box's length in advance, and here that box is `meta` itself, so a
   * walk that treats 0 as an empty box finds no children at all.
   */
  @Test
  fun walksIntoABoxWrittenWithAZeroSize() {
    val heif = ExifBytes.heif(irotAngle = 2, metaLast = true)

    assertEquals(ImageOrientation.ROTATE_180, read(heif))
  }

  /** AVIF is the same container, and declares itself only in the `ftyp` brands. */
  @Test
  fun readsAnAvifTheSameWayAsAHeif() {
    val avif = ExifBytes.heif(irotAngle = 1, imirAxis = 0, majorBrand = "avif")

    assertEquals(ImageOrientation.TRANSPOSE, read(avif))
  }

  // ---------------------------------------------------------------------------------------------
  // WebP, and the formats that carry nothing
  // ---------------------------------------------------------------------------------------------

  /**
   * WebP's `EXIF` chunk, reached across an odd-length chunk. RIFF pads chunks to an even boundary
   * without counting the pad byte, so a walk that ignores the padding lands one byte short of the
   * `EXIF` fourcc.
   */
  @Test
  fun readsAWebPExifChunk() {
    listOf(false, true).forEach { withIdentifier ->
      val webP = ExifBytes.webP(
        exif = ExifBytes.exifBlock(ImageOrientation.ROTATE_270.exifValue, ByteOrder.LITTLE),
        withIdentifier = withIdentifier,
      )

      assertEquals(ImageOrientation.ROTATE_270, read(webP), "identifier prefix: $withIdentifier")
    }
  }

  @Test
  fun returnsNormalForAWebPWithNoExifChunk() {
    assertEquals(ImageOrientation.NORMAL, read(ExifBytes.webP(exif = null)))
  }

  /**
   * GIF has nowhere to put an orientation, and while PNG's third edition defines an `eXIf` chunk,
   * nothing that reaches a photo picker writes one. Both are upright by definition here.
   */
  @Test
  fun returnsNormalForPngAndGif() {
    assertEquals(ImageOrientation.NORMAL, read(ImageHeaders.png(4032, 3024)), "png")
    assertEquals(ImageOrientation.NORMAL, read(ImageHeaders.gif(4032, 3024)), "gif")
  }

  // ---------------------------------------------------------------------------------------------
  // Hostile input
  // ---------------------------------------------------------------------------------------------

  /**
   * Every prefix of every fixture, which is what a cancelled download or a half-written cache
   * entry looks like. None may throw, and none may report anything but a real reading.
   */
  @Test
  fun neverThrowsOnATruncatedFile() {
    fixtures().forEach { (name, fixture) ->
      for (length in 0..fixture.size) {
        val prefix = fixture.copyOf(length)
        try {
          read(prefix)
        } catch (error: Throwable) {
          throw AssertionError("$name truncated to $length bytes threw $error", error)
        }
      }
    }
  }

  /**
   * A truncated file must never report an orientation it did not get to read. The full fixtures
   * below all declare [ImageOrientation.ROTATE_90]; a prefix may answer that or
   * [ImageOrientation.NORMAL], and nothing else.
   */
  @Test
  fun aTruncatedFileNeverInventsADifferentOrientation() {
    val rotated = listOf(
      "jpeg" to ExifBytes.jpeg(ExifBytes.exifBlock(6, ByteOrder.BIG)),
      "jpeg little-endian" to ExifBytes.jpeg(ExifBytes.exifBlock(6, ByteOrder.LITTLE)),
      "heif irot" to ExifBytes.heif(irotAngle = 3),
      "heif exif" to ExifBytes.heif(exif = ExifBytes.exifBlock(6, ByteOrder.BIG)),
      "webp" to ExifBytes.webP(ExifBytes.exifBlock(6, ByteOrder.LITTLE)),
    )

    rotated.forEach { (name, fixture) ->
      assertEquals(ImageOrientation.ROTATE_90, read(fixture), "$name, intact")
      for (length in 0 until fixture.size) {
        val actual = read(fixture.copyOf(length))
        if (actual != ImageOrientation.NORMAL && actual != ImageOrientation.ROTATE_90) {
          throw AssertionError("$name truncated to $length bytes invented $actual")
        }
      }
    }
  }

  /** Bytes that are not an image, or are an image header with nothing behind it. */
  @Test
  fun returnsNormalForGarbage() {
    val garbage = mapOf(
      "empty" to ByteArray(0),
      "one byte" to byteArrayOf(0x42),
      "zeroes" to ByteArray(512),
      "0xff run" to ByteArray(512) { 0xFF.toByte() },
      "counting" to ByteArray(512) { it.toByte() },
      "jpeg marker only" to byteArrayOf(0xFF.toByte(), 0xD8.toByte()),
      "riff only" to "RIFFxxxxWEBP".encodeToByteArray(),
      "ftyp only" to ImageHeaders.isoBaseMedia("heic", "mif1"),
      "text" to "this is not an image, it is a sentence about one".encodeToByteArray(),
    )

    garbage.forEach { (name, bytes) ->
      assertEquals(ImageOrientation.NORMAL, read(bytes), name)
    }
  }

  /**
   * A container that is intact and simply lying: every four-byte window in turn is overwritten
   * with a huge value, which is what a lengths-and-offsets field looks like after a fuzzer or a
   * bad transfer has been at it. A parser that trusts its own arithmetic dereferences one of these.
   *
   * Only "does not throw" is asserted. Some of these windows land on the Orientation tag itself,
   * and a file that now genuinely declares a different orientation is being read correctly.
   */
  @Test
  fun neverThrowsOnACorruptedLengthOrOffset() {
    val heif = ExifBytes.heif(exif = ExifBytes.exifBlock(6, ByteOrder.BIG))
    for (index in 0 until heif.size - 4) {
      val corrupted = heif.copyOf()
      corrupted[index] = 0xFF.toByte()
      corrupted[index + 1] = 0xFF.toByte()
      corrupted[index + 2] = 0xFF.toByte()
      corrupted[index + 3] = 0x00.toByte()
      try {
        read(corrupted)
      } catch (error: Throwable) {
        throw AssertionError("corrupting bytes $index..${index + 3} threw $error", error)
      }
    }
  }

  /** Every fixture shape, used by the truncation sweep. */
  private fun fixtures(): List<Pair<String, ByteArray>> {
    val exif = ExifBytes.exifBlock(ImageOrientation.TRANSVERSE.exifValue, ByteOrder.BIG)
    val exifLittle = ExifBytes.exifBlock(ImageOrientation.TRANSPOSE.exifValue, ByteOrder.LITTLE)
    return listOf(
      "jpeg without exif" to ExifBytes.jpeg(exif = null),
      "jpeg big-endian" to ExifBytes.jpeg(exif),
      "jpeg little-endian" to ExifBytes.jpeg(exifLittle),
      "jpeg with xmp decoy" to ExifBytes.jpeg(exif, withXmpDecoy = true),
      "jpeg with post-scan decoy" to ExifBytes.jpeg(exif = null, decoyAfterScan = exif),
      "jpeg with padded ifd" to ExifBytes.jpeg(
        ExifBytes.exifBlock(6, ByteOrder.BIG, ifdPadding = 16),
      ),
      "heif irot" to ExifBytes.heif(irotAngle = 1),
      "heif imir" to ExifBytes.heif(imirAxis = 0),
      "heif irot and imir" to ExifBytes.heif(irotAngle = 3, imirAxis = 1),
      "heif exif only" to ExifBytes.heif(exif = exif),
      "heif exif with identifier" to ExifBytes.heif(
        exif = exif,
        exifTiffHeaderOffset = EXIF_IDENTIFIER.length,
      ),
      "heif transforms and exif" to ExifBytes.heif(irotAngle = 2, imirAxis = 1, exif = exif),
      "heif idat" to ExifBytes.heif(exif = exif, constructionMethod = 1),
      "heif iloc v0" to ExifBytes.heif(exif = exif, ilocVersion = 0),
      "heif infe v3" to ExifBytes.heif(exif = exif, infeVersion = 3),
      "heif 64-bit box" to ExifBytes.heif(irotAngle = 1, sixtyFourBitBoxes = true),
      "heif meta last" to ExifBytes.heif(irotAngle = 1, metaLast = true),
      "avif" to ExifBytes.heif(irotAngle = 1, majorBrand = "avif"),
      "webp" to ExifBytes.webP(exif),
      "webp with identifier" to ExifBytes.webP(exif, withIdentifier = true),
      "webp without exif" to ExifBytes.webP(exif = null),
      "png" to ImageHeaders.png(4032, 3024),
      "gif" to ImageHeaders.gif(4032, 3024),
    )
  }
}
