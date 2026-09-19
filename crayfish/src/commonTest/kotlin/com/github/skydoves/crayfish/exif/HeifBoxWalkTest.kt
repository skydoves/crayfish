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

import com.github.skydoves.crayfish.decode.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** `size`(4) + `type`(4). Every ISOBMFF box starts with these eight bytes. */
private const val BOX_HEADER_BYTES = 8

/** The item the transform properties belong to. */
private const val IMAGE_ITEM_ID = 1

/** The item carrying the Exif block. */
private const val EXIF_ITEM_ID = 2

/**
 * ISOBMFF box headers that say something impossible, and the walk that has to survive them.
 *
 * [ExifReaderTest] covers HEIFs whose boxes are well-formed. A box header is four bytes of
 * attacker-supplied length followed by four bytes of type, and a walk driven by that length is one
 * bad number away from reading a payload as structure or from never advancing at all. The fixtures
 * here each break exactly one field, and most of them carry a real `irot` behind the break so that
 * a walk which recovers where it should have stopped has something wrong to find.
 */
class HeifBoxWalkTest {

  private fun read(bytes: ByteArray): ImageOrientation = ExifReader.readOrientation(bytes)

  // ---------------------------------------------------------------------------------------------
  // Box sizes that cannot be true
  // ---------------------------------------------------------------------------------------------

  /**
   * A box cannot be smaller than the eight bytes of its own header, and a walk that steps by the
   * declared size anyway lands inside the next box's header and reads its type as a length.
   *
   * Sizes 0 and 1 are excluded deliberately: they are the two escape values (runs to end of file,
   * and a 64-bit size follows), so 2..7 is exactly the range that is impossible rather than
   * special.
   */
  @Test
  fun stopsAtABoxWhoseDeclaredSizeIsSmallerThanItsOwnHeader() {
    val rotated = metaWithProperties(irot(1))

    assertEquals(
      ImageOrientation.ROTATE_270,
      read(ftyp() + box("free") + rotated),
      "the positive control: the same file behind a well-formed empty box",
    )

    for (declaredSize in 2..7) {
      assertEquals(
        ImageOrientation.NORMAL,
        read(ftyp() + box("free", declaredSize = declaredSize) + rotated),
        "a box declaring size $declaredSize",
      )
    }
  }

  /**
   * The 64-bit form of the same lie. `size == 1` means the real size is a `largesize` after the
   * header, so anything below the sixteen bytes that header now occupies is impossible.
   *
   * A `largesize` of 0 is the one that matters most: the walk's next index is the box's end, so a
   * box that claims to occupy nothing puts the walk back exactly where it started and it runs
   * forever. This test returning at all is the assertion; removing the guard here together with
   * the two no-forward-progress checks behind it hangs the run rather than failing it.
   */
  @Test
  fun refusesASixtyFourBitBoxThatWouldNotAdvanceTheWalk() {
    val rotated = metaWithProperties(irot(1))
    val payload = ByteArray(4)

    assertEquals(
      ImageOrientation.ROTATE_270,
      read(ftyp() + largeBox("free", payload) + rotated),
      "the positive control: the same file behind a well-formed 64-bit box",
    )

    listOf(0L, 1L, 8L, 15L).forEach { largeSize ->
      assertEquals(
        ImageOrientation.NORMAL,
        read(ftyp() + largeBox("free", payload, declaredLargeSize = largeSize) + rotated),
        "a 64-bit box declaring largesize $largeSize",
      )
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Transform properties that are not all there
  // ---------------------------------------------------------------------------------------------

  /**
   * An `irot` truncated to its header declares no angle, and "no angle" is not angle 0.
   *
   * The angle is a two-bit field read out of one byte, and the byte reader returns -1 when that
   * byte is not in the file. Masking -1 gives 3, which is a 270-degree anti-clockwise turn: a file
   * that was cut short would come out rotated a quarter turn, which is the single most visible way
   * this library can be wrong.
   */
  @Test
  fun readsAnIrotTruncatedToItsHeaderAsNoRotationAtAll() {
    assertEquals(
      ImageOrientation.ROTATE_270,
      read(ftyp() + metaWithProperties(irot(1))),
      "the positive control: one byte of payload and the same file is a quarter turn",
    )
    assertEquals(ImageOrientation.NORMAL, read(ftyp() + metaWithProperties(box("irot"))))
  }

  /**
   * The same cut, one property along. Masking the reader's -1 with the one-bit axis field gives 1,
   * a left-right mirror, so a truncated file would come out back-to-front.
   */
  @Test
  fun readsAnImirTruncatedToItsHeaderAsNoMirrorAtAll() {
    assertEquals(
      ImageOrientation.FLIP_HORIZONTAL,
      read(ftyp() + metaWithProperties(imir(1))),
      "the positive control: one byte of payload and the same file is mirrored",
    )
    assertEquals(ImageOrientation.NORMAL, read(ftyp() + metaWithProperties(box("imir"))))
  }

  /**
   * Mirrors compose; they do not latch a flag.
   *
   * HEIF states its transforms as a sequence of property boxes applied in order, and nothing stops
   * a writer emitting two. Two left-right mirrors are the identity, and a left-right followed by a
   * top-bottom is a half turn with no mirror left over. A reader that sets a boolean instead of
   * toggling it answers `FLIP_HORIZONTAL` and `FLIP_VERTICAL` to these two.
   */
  @Test
  fun composesRepeatedMirrorsRatherThanLatchingAFlag() {
    assertEquals(
      ImageOrientation.NORMAL,
      read(ftyp() + metaWithProperties(imir(1), imir(1))),
      "two left-right mirrors cancel",
    )
    assertEquals(
      ImageOrientation.ROTATE_180,
      read(ftyp() + metaWithProperties(imir(1), imir(0))),
      "left-right then top-bottom is a half turn",
    )
    assertEquals(
      ImageOrientation.ROTATE_270,
      read(ftyp() + metaWithProperties(imir(1), imir(1), irot(1))),
      "a rotation after two cancelling mirrors turns the way an unmirrored one does",
    )
  }

  // ---------------------------------------------------------------------------------------------
  // The Exif item: `iinf` names it, `iloc` says where it is
  // ---------------------------------------------------------------------------------------------

  /** An `iinf` cut off before its version byte names no items, Exif or otherwise. */
  @Test
  fun readsAnIinfTruncatedToItsHeaderAsNoExifItem() {
    assertEquals(ImageOrientation.NORMAL, read(ftyp() + box("meta", metaPrefix(), box("iinf"))))
  }

  /**
   * An `infe` entry cut off after its version and flags.
   *
   * The version decides how wide `item_ID` is and therefore where `item_type` sits, so both
   * versions that can name an Exif item are cut at the same place and both have to come back
   * empty-handed rather than reading whatever follows the box.
   */
  @Test
  fun readsAnInfeTruncatedToItsVersionAsNoExifItem() {
    listOf(2, 3).forEach { version ->
      val file = ftyp() + box(
        "meta",
        metaPrefix(),
        fullBox(
          "iinf",
          0,
          writeBytes { beU16(1) },
          box("infe", writeBytes { bytes(version, 0, 0, 0) }),
        ),
      )

      assertEquals(ImageOrientation.NORMAL, read(file), "infe version $version")
    }
  }

  /**
   * `iloc` cut at each of its fields in turn.
   *
   * It is the one box here whose field widths are chosen per file, so there is no fixed layout to
   * bounds-check once at the top: the walk reads field by field and each read is its own chance to
   * run off the end. The byte counts below are the offsets of those fields, and the `iinf` in front
   * of them is complete, so every one of these files genuinely promises an Exif item it cannot
   * deliver.
   */
  @Test
  fun readsAnIlocTruncatedAtAnyFieldAsNoExifItem() {
    val truncations = mapOf(
      0 to "before the version byte",
      4 to "before the offset and length widths",
      5 to "before the base-offset and index widths",
      6 to "before item_count",
      8 to "before the first item_ID",
      10 to "before construction_method",
      14 to "before extent_count",
    )

    truncations.forEach { (payloadBytes, where) ->
      val file = ftyp() + box(
        "meta",
        metaPrefix(),
        iinfNamingTheExifItem(),
        ilocTruncatedTo(payloadBytes),
      )

      assertEquals(ImageOrientation.NORMAL, read(file), "iloc cut $where")
    }
  }

  /**
   * Construction method 1 measures the extent offset from this `meta`'s `idat` payload. With no
   * `idat` there is no base to measure from, and falling back to 0 would silently reinterpret the
   * offset as a file offset.
   *
   * The fixture makes that fallback visible: the bytes at the file offset the extent names really
   * are an Exif block, and it declares a quarter turn. A reader that guesses answers `ROTATE_90`
   * with total confidence.
   */
  @Test
  fun refusesAnExifItemWhoseIdatBaseIsMissing() {
    val item = exifItemPayload(ImageOrientation.ROTATE_90)
    val fileOffset = ftyp().size + BOX_HEADER_BYTES

    val withIdat = ftyp() + box(
      "meta",
      metaPrefix(),
      iinfNamingTheExifItem(),
      iloc(constructionMethod = 1, extentOffset = 0, extentLength = item.size),
      box("idat", item),
    )
    assertEquals(
      ImageOrientation.ROTATE_90,
      read(withIdat),
      "the positive control: the same item, resolved against a present idat",
    )

    val withoutIdat = ftyp() + box("mdat", item) + box(
      "meta",
      metaPrefix(),
      iinfNamingTheExifItem(),
      iloc(constructionMethod = 1, extentOffset = fileOffset, extentLength = item.size),
    )
    assertEquals(ImageOrientation.NORMAL, read(withoutIdat))
  }

  /**
   * An extent starting at the end of the file is a legal thing to describe - an empty extent is
   * allowed, and the index narrower lets `size` itself through as an exclusive bound - but there is
   * no `exif_tiff_header_offset` to read there, and the parser must say so rather than read four
   * bytes from wherever it lands.
   */
  @Test
  fun refusesAnExifItemWhoseExtentStartsAtTheEndOfTheFile() {
    // Two passes: the extent offset is the file's own length, which is only knowable once the file
    // is laid out. The offset field is four bytes wide whatever it holds, so the two are the same
    // size and the measured length is the real one.
    fun assemble(extentOffset: Int): ByteArray = ftyp() + box(
      "meta",
      metaPrefix(),
      iinfNamingTheExifItem(),
      iloc(constructionMethod = 0, extentOffset = extentOffset, extentLength = 0),
    )

    val measured = assemble(0)

    assertEquals(ImageOrientation.NORMAL, read(assemble(measured.size)))
  }

  // ---------------------------------------------------------------------------------------------
  // Box plumbing. Everything is written longhand so each fixture's break is visible in the source.
  // ---------------------------------------------------------------------------------------------

  /**
   * A box.
   *
   * @param declaredSize what the size field says, which for these fixtures is free to disagree with
   *   how many bytes the box actually occupies.
   */
  private fun box(type: String, vararg payload: ByteArray, declaredSize: Int? = null): ByteArray =
    writeBytes {
      beU32(declaredSize ?: (BOX_HEADER_BYTES + payload.sumOf { it.size }))
      ascii(type)
      payload.forEach { raw(it) }
    }

  /** A FullBox: a box whose payload begins with a version byte and three flag bytes. */
  private fun fullBox(type: String, version: Int, vararg payload: ByteArray): ByteArray =
    box(type, writeBytes { bytes(version, 0, 0, 0) }, *payload)

  /**
   * A box in the 64-bit form: `size == 1`, then the real size as a `largesize` after the header.
   *
   * @param declaredLargeSize what that `largesize` says. A well-formed one counts the sixteen bytes
   *   of header it follows.
   */
  private fun largeBox(
    type: String,
    payload: ByteArray,
    declaredLargeSize: Long? = null,
  ): ByteArray = writeBytes {
    beU32(1)
    ascii(type)
    beU64(declaredLargeSize ?: (16L + payload.size))
    raw(payload)
  }

  /** `ftyp` declaring HEIF, which is all the format probe needs to route these files here. */
  private fun ftyp(): ByteArray = box(
    "ftyp",
    writeBytes {
      ascii("heic")
      beU32(0) // minor version
      ascii("mif1")
      ascii("heic")
    },
  )

  /** `meta` is a FullBox, so a version byte and three flag bytes precede its first child. */
  private fun metaPrefix(): ByteArray = byteArrayOf(0, 0, 0, 0)

  /** `meta` -> `iprp` -> `ipco` holding [properties], written as the file's last box. */
  private fun metaWithProperties(vararg properties: ByteArray): ByteArray =
    box("meta", metaPrefix(), box("iprp", box("ipco", *properties)))

  /** ISO/IEC 23008-12 6.5.10: six reserved bits, then a two-bit anti-clockwise angle. */
  private fun irot(angle: Int): ByteArray = box("irot", byteArrayOf((angle and 0x03).toByte()))

  /** ISO/IEC 23008-12 6.5.12: seven reserved bits, then a one-bit axis. */
  private fun imir(axis: Int): ByteArray = box("imir", byteArrayOf((axis and 0x01).toByte()))

  /** An `iinf` with the image item first, so the `infe` walk has to get past an entry it wants. */
  private fun iinfNamingTheExifItem(): ByteArray = fullBox(
    "iinf",
    version = 0,
    writeBytes { beU16(2) },
    infe(IMAGE_ITEM_ID, "hvc1"),
    infe(EXIF_ITEM_ID, "Exif"),
  )

  private fun infe(itemId: Int, itemType: String): ByteArray = fullBox(
    "infe",
    version = 2,
    writeBytes {
      beU16(itemId)
      beU16(0) // item_protection_index
      ascii(itemType)
      ascii(itemType) // item_name
      bytes(0)
    },
  )

  /**
   * An `iloc` whose field widths are all zero must not spin.
   *
   * Every field width in an `iloc` is chosen by the file, and zero is legal: it means the field is
   * absent and its value is zero. Set `index_size`, `offset_size` and `length_size` all to zero and
   * the extent loop advances its cursor by nothing, so it runs the declared `extent_count` (up to
   * 65535) for each of up to 65535 items, re-reading the same bytes. Measured before the guard: a
   * 65KB file took 2.4 seconds and a 131KB one 4.8 seconds on a desktop JVM, against 19ms for a
   * well-formed file.
   *
   * That is not merely slow. `Cropper` calls `ExifReader.readOrientation` from its `LaunchedEffect`
   * without moving to another dispatcher for a `CropSource.Bytes`, so the work lands on the
   * composition thread: on Android, picking a crafted file is an ANR. 131072 bytes is the real
   * budget, because that is `SourceHeader.DEFAULT_HEADER_BYTES`.
   *
   * Measured on this fixture with the guard removed: **273,760,255 iterations of the extent loop in
   * 793ms**, against 17ms with it. The threshold sits between the two rather than at a round
   * number, because a second would have let the defect through.
   */
  @Test
  fun anIlocWithNoFieldWidthsDoesNotSpin() {
    val hostile = ftyp() + box(
      "meta",
      metaPrefix(),
      iinfNamingTheExifItem(),
      box("iloc", zeroWidthIlocPayload()),
    ) + ByteArray(128 * 1024) { 0x41 }

    val started = TimeSource.Monotonic.markNow()
    val orientation = read(hostile)
    val elapsed = started.elapsedNow()

    assertEquals(
      ImageOrientation.NORMAL,
      orientation,
      "a file that locates nothing is not oriented",
    )
    assertTrue(
      elapsed < 250.milliseconds,
      "reading a ${hostile.size} byte file took $elapsed; the extent walk is not advancing",
    )
  }

  /**
   * `item_count` and `extent_count` both at their maximum, with widths that make each extent free.
   *
   * Written by hand rather than through [ilocPayload] because that one hardcodes usable widths,
   * which is the whole point of this fixture.
   */
  private fun zeroWidthIlocPayload(): ByteArray = writeBytes {
    bytes(1, 0, 0, 0) // version 1, then flags
    bytes((0 shl 4) or 0) // offset_size = 0, length_size = 0
    bytes((0 shl 4) or 0) // base_offset_size = 0, index_size = 0
    beU16(0xFFFF) // item_count
    // Deliberately NOT the Exif item. A matching id resolves on its first extent and returns, so
    // the loop runs once and the fixture proves nothing: measured, that version reached exactly one
    // iteration. A non-matching id sends every extent through the `continue` instead.
    beU16(EXIF_ITEM_ID + 1) // item_ID
    beU16(0) // construction_method
    beU16(0) // data_reference_index
    beU16(0xFFFF) // extent_count, every one of them zero bytes wide
  }

  /** A complete `iloc` naming one extent of the Exif item. */
  private fun iloc(constructionMethod: Int, extentOffset: Int, extentLength: Int): ByteArray =
    box("iloc", ilocPayload(constructionMethod, extentOffset, extentLength))

  /** The same payload, cut to its first [payloadBytes] bytes. */
  private fun ilocTruncatedTo(payloadBytes: Int): ByteArray = box(
    "iloc",
    ilocPayload(constructionMethod = 0, extentOffset = 0, extentLength = 0)
      .copyOf(payloadBytes),
  )

  private fun ilocPayload(
    constructionMethod: Int,
    extentOffset: Int,
    extentLength: Int,
  ): ByteArray = writeBytes {
    bytes(1, 0, 0, 0) // version 1, then flags
    bytes((4 shl 4) or 4) // offset_size = 4, length_size = 4
    bytes((0 shl 4) or 0) // base_offset_size = 0, index_size = 0
    beU16(1) // item_count
    beU16(EXIF_ITEM_ID) // item_ID
    beU16(constructionMethod) // twelve reserved bits, then a four-bit method
    beU16(0) // data_reference_index
    // base_offset is zero bytes wide here, and therefore absent entirely.
    beU16(1) // extent_count
    beU32(extentOffset)
    beU32(extentLength)
  }

  /**
   * An Exif item's payload: ISO/IEC 23008-12 Annex A's 32-bit count of the bytes preceding the
   * TIFF header, then the TIFF block itself.
   */
  private fun exifItemPayload(orientation: ImageOrientation): ByteArray = writeBytes {
    beU32(0)
    raw(ExifBytes.exifBlock(orientation.exifValue, ByteOrder.BIG))
  }
}
