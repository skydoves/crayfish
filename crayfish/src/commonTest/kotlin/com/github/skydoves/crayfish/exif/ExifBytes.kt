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

/**
 * Synthetic files carrying orientation metadata, built byte-for-byte to spec.
 *
 * These are built rather than checked in as binaries so that each field's offset is visible in the
 * test source: a fixture whose bytes nobody can read is a fixture nobody can debug. They are also
 * deliberately hostile in the places where a plausible-but-wrong parser would still pass: a decoy
 * XMP `APP1` before the Exif one, an odd-length WebP chunk before the `EXIF` chunk, an `ispe`
 * property before the transforms, and padding between the TIFF header and IFD0.
 *
 * What they are *not* is real camera output. Everything here was written from the specifications,
 * so they prove the parser agrees with the specs as read, not that it agrees with what an iPhone
 * actually writes.
 */
internal object ExifBytes {

  /** TIFF field type 3, SHORT: the type the Orientation tag is defined as. */
  const val TYPE_SHORT: Int = 3

  /** TIFF field type 4, LONG: what a malformed writer sometimes uses instead. */
  const val TYPE_LONG: Int = 4

  /** The item the transform properties and the image data belong to. */
  private const val IMAGE_ITEM_ID = 1

  /** The item carrying the Exif block. */
  private const val EXIF_ITEM_ID = 2

  /** Stand-in for coded image data, distinctive enough to locate unambiguously in the output. */
  private val IMAGE_DATA = ByteArray(24) { (0xA0 + it).toByte() }

  // ---------------------------------------------------------------------------------------------
  // The Exif / TIFF block, which every container below embeds unchanged.
  // ---------------------------------------------------------------------------------------------

  /**
   * A TIFF block carrying an IFD0 with an optional Orientation entry.
   *
   * @param orientation the Exif tag value to write, or `null` to leave the tag out entirely.
   * @param order the byte order the TIFF header declares, and which every field below is written
   *   in. Cameras ship both, and a parser that hardcodes one passes exactly half of these.
   * @param tagsBefore extra IFD0 entries written before the Orientation tag, ascending as TIFF
   *   requires, so the entry walk is exercised rather than a fixed first-entry offset.
   * @param tagsAfter extra IFD0 entries written after it.
   * @param ifdPadding bytes inserted between the TIFF header and IFD0, which pushes IFD0 past the
   *   offset a parser would guess at. The IFD0 pointer is TIFF-relative, so a parser that treats
   *   it as file-relative lands here instead.
   * @param orientationType the field type to declare for the Orientation entry.
   */
  fun exifBlock(
    orientation: Int?,
    order: ByteOrder,
    tagsBefore: List<Int> = emptyList(),
    tagsAfter: List<Int> = emptyList(),
    ifdPadding: Int = 0,
    orientationType: Int = TYPE_SHORT,
  ): ByteArray {
    val entries = buildList {
      tagsBefore.forEach { add(it to 1) }
      if (orientation != null) add(0x0112 to orientation)
      tagsAfter.forEach { add(it to 1) }
    }
    return buildBytes {
      ascii(if (order == ByteOrder.LITTLE) "II" else "MM")
      u16(42, order)
      u32(8 + ifdPadding, order) // Offset of IFD0, measured from the "II"/"MM" above.
      repeat(ifdPadding) { bytes(0xCC) }
      u16(entries.size, order)
      entries.forEach { (tag, value) ->
        val type = if (tag == 0x0112) orientationType else TYPE_SHORT
        u16(tag, order)
        u16(type, order)
        u32(1, order) // count
        if (type == TYPE_SHORT) {
          // A SHORT occupies the first two bytes of the four-byte value field, in the declared
          // order. The other two are undefined padding, filled with 0xFF here so that a parser
          // reading all four bytes gets a nonsense value rather than accidentally the right one.
          u16(value, order)
          bytes(0xFF, 0xFF)
        } else {
          u32(value, order)
        }
      }
      u32(0, order) // No IFD1.
    }
  }

  // ---------------------------------------------------------------------------------------------
  // JPEG: ITU-T T.81, Exif in APP1 per CIPA DC-008.
  // ---------------------------------------------------------------------------------------------

  /**
   * A JPEG with the ordinary camera layout: JFIF, then metadata, then the frame, then the scan.
   *
   * @param exif the TIFF block to wrap in an Exif `APP1`, or `null` for a JPEG with no Exif at all.
   * @param withXmpDecoy writes an XMP `APP1` before the Exif one. Real phone photos carry both, and
   *   a parser that takes the first `APP1` it sees reads the XMP payload as a TIFF header.
   * @param decoyAfterScan plants a complete Exif `APP1` immediately after the start-of-scan
   *   data. Deliberately illegal (a real encoder byte-stuffs 0xFF), but it is what a parser that
   *   walks past the start-of-scan marker would find, and it declares a different orientation.
   */
  fun jpeg(
    exif: ByteArray?,
    withXmpDecoy: Boolean = false,
    decoyAfterScan: ByteArray? = null,
  ): ByteArray = buildBytes {
    bytes(0xFF, 0xD8) // SOI

    // APP0 / JFIF.
    bytes(0xFF, 0xE0)
    beU16(16)
    ascii("JFIF")
    bytes(0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00)

    if (withXmpDecoy) {
      // The XMP namespace URL and its NUL terminator, which is what distinguishes this
      // APP1 from the Exif one that follows it.
      val xmp = "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta/>"
      bytes(0xFF, 0xE1)
      beU16(2 + xmp.length)
      ascii(xmp)
    }

    if (exif != null) raw(app1(exif))

    // SOF0.
    bytes(0xFF, 0xC0)
    beU16(17)
    bytes(0x08)
    beU16(3024)
    beU16(4032)
    bytes(0x03)
    bytes(0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01)

    // SOS, then entropy-coded data.
    bytes(0xFF, 0xDA)
    beU16(12)
    bytes(0x03, 0x01, 0x00, 0x02, 0x11, 0x03, 0x11, 0x00, 0x3F, 0x00)
    if (decoyAfterScan != null) raw(app1(decoyAfterScan))
    bytes(0x12, 0x34, 0x56, 0x78)
    bytes(0xFF, 0xD9) // EOI
  }

  /** An Exif `APP1` segment: marker, length, the six-byte identifier, then the TIFF block. */
  private fun app1(exif: ByteArray): ByteArray = buildBytes {
    bytes(0xFF, 0xE1)
    beU16(2 + EXIF_IDENTIFIER.length + exif.size)
    ascii(EXIF_IDENTIFIER)
    raw(exif)
  }

  // ---------------------------------------------------------------------------------------------
  // WebP: https://developers.google.com/speed/webp/docs/riff_container
  // ---------------------------------------------------------------------------------------------

  /**
   * An extended WebP whose `EXIF` chunk sits behind an odd-length `VP8 ` chunk.
   *
   * The odd length is the point: RIFF pads every chunk to an even boundary and does not count the
   * pad byte in the size field, so a parser that forgets it lands one byte short of the `EXIF`
   * fourcc and finds nothing.
   *
   * @param withIdentifier writes the six-byte `"Exif"` identifier before the TIFF header. The
   *   container spec says the chunk holds the Exif metadata itself, but writers that reused their
   *   JPEG code emit the identifier too, and both shapes are in circulation.
   */
  fun webP(exif: ByteArray?, withIdentifier: Boolean = false): ByteArray {
    val bitstream = ByteArray(11) { 0x2A }
    val chunks = buildBytes {
      ascii("VP8X")
      leU32(10)
      bytes(0x08, 0x00, 0x00, 0x00) // flags: EXIF present
      leU24(4031) // canvas width - 1
      leU24(3023) // canvas height - 1

      ascii("VP8 ")
      leU32(bitstream.size)
      raw(bitstream)
      bytes(0x00) // Pad to an even boundary; not counted by the size field above.

      if (exif != null) {
        ascii("EXIF")
        leU32(exif.size + if (withIdentifier) EXIF_IDENTIFIER.length else 0)
        if (withIdentifier) ascii(EXIF_IDENTIFIER)
        raw(exif)
      }
    }
    return buildBytes {
      ascii("RIFF")
      leU32(4 + chunks.size)
      ascii("WEBP")
      raw(chunks)
    }
  }

  // ---------------------------------------------------------------------------------------------
  // HEIF / AVIF: ISO/IEC 14496-12 boxes, ISO/IEC 23008-12 image properties.
  // ---------------------------------------------------------------------------------------------

  /**
   * A HEIF or AVIF file, optionally carrying transform property boxes, an Exif item, or both.
   *
   * The file is assembled twice. `iloc` stores absolute file offsets for the items, and those
   * offsets are only knowable once the boxes in front of them have been laid out, so the first
   * pass is written with zero offsets purely to measure the layout, and the second writes the real
   * ones. The offset fields are a fixed four bytes wide, so the two passes are identical in size.
   *
   * @param irotAngle the `irot` angle, 0..3, in 90-degree anti-clockwise units. `null`
   *   omits `irot` entirely.
   * @param imirAxis the `imir` axis: 0 exchanges top and bottom, 1 exchanges left and right.
   *   `null` omits `imir`.
   * @param exif the TIFF block to carry as an Exif item, or `null` for no Exif item.
   * @param exifTiffHeaderOffset the `exif_tiff_header_offset` the item declares: 0 when the
   *   payload starts at the TIFF header, 6 when it starts with the Exif identifier.
   * @param transformOrder the order the transform properties appear in inside `ipco`. Composition
   *   is not commutative, so this is a real degree of freedom and not decoration.
   * @param constructionMethod 0 places the item data in `mdat` at an absolute file offset; 1 places
   *   it in an `idat` box inside `meta`, at an offset relative to that box's payload.
   * @param ilocVersion `iloc` version 0 has no `construction_method` field at all; version 1 does.
   * @param infeVersion `infe` version 2 declares a 16-bit `item_ID`, version 3 a 32-bit one, which
   *   moves `item_type` by two bytes.
   * @param metaLast writes `mdat` before `meta` and gives `meta` a declared size of 0, the
   *   "runs to the end of the file" form a one-pass writer uses for its final box.
   * @param sixtyFourBitBoxes inserts a `free` box written in the `size == 1` 64-bit form, which the
   *   walk has to step over at the wider width to reach `meta` at all.
   */
  fun heif(
    irotAngle: Int? = null,
    imirAxis: Int? = null,
    exif: ByteArray? = null,
    exifTiffHeaderOffset: Int = 0,
    transformOrder: List<String> = listOf("irot", "imir"),
    constructionMethod: Int = 0,
    ilocVersion: Int = 1,
    infeVersion: Int = 2,
    majorBrand: String = "heic",
    metaLast: Boolean = false,
    sixtyFourBitBoxes: Boolean = false,
  ): ByteArray {
    val exifItem = exif?.let { exifItemPayload(it, exifTiffHeaderOffset) }
    val mediaData = IMAGE_DATA + (exifItem ?: ByteArray(0))
    val useIdat = constructionMethod == 1

    fun assemble(imageOffset: Int, exifOffset: Int): ByteArray {
      val metaPayload = buildBytes {
        bytes(0, 0, 0, 0) // meta is a FullBox: version and flags precede its children.
        raw(hdlrBox())
        raw(pitmBox())
        raw(iinfBox(withExif = exifItem != null, infeVersion = infeVersion))
        raw(
          ilocBox(
            version = ilocVersion,
            constructionMethod = constructionMethod,
            imageOffset = imageOffset,
            imageLength = IMAGE_DATA.size,
            exifOffset = exifOffset,
            exifLength = exifItem?.size,
          ),
        )
        raw(iprpBox(irotAngle, imirAxis, transformOrder))
        if (useIdat) raw(box("idat", mediaData))
      }
      val ftyp = ftypBox(majorBrand)
      val filler = if (sixtyFourBitBoxes) largeBox("free", ByteArray(4)) else ByteArray(0)
      val mdat = if (useIdat) ByteArray(0) else box("mdat", mediaData)

      return if (metaLast) {
        ftyp + filler + mdat + openEndedBox("meta", metaPayload)
      } else {
        ftyp + filler + box("meta", metaPayload) + mdat
      }
    }

    if (useIdat) {
      // `idat` offsets are relative to that box's own payload, so they need no measuring pass.
      return assemble(imageOffset = 0, exifOffset = IMAGE_DATA.size)
    }
    val measured = assemble(imageOffset = 0, exifOffset = 0)
    return assemble(
      imageOffset = measured.locate(IMAGE_DATA),
      exifOffset = exifItem?.let { measured.locate(it) } ?: 0,
    )
  }

  /**
   * The payload of an Exif item: ISO/IEC 23008-12 Annex A prefixes the Exif block with a 32-bit
   * count of the bytes that precede its TIFF header.
   */
  private fun exifItemPayload(exif: ByteArray, tiffHeaderOffset: Int): ByteArray = buildBytes {
    beU32(tiffHeaderOffset)
    if (tiffHeaderOffset == EXIF_IDENTIFIER.length) ascii(EXIF_IDENTIFIER)
    raw(exif)
  }

  private fun ftypBox(majorBrand: String): ByteArray = box(
    "ftyp",
    buildBytes {
      ascii(majorBrand)
      beU32(0) // minor version
      ascii("mif1")
      ascii(majorBrand)
    },
  )

  private fun hdlrBox(): ByteArray = fullBox(
    "hdlr",
    version = 0,
    payload = buildBytes {
      beU32(0) // pre_defined
      ascii("pict")
      repeat(12) { bytes(0) } // reserved
      bytes(0) // name, an empty NUL-terminated string
    },
  )

  private fun pitmBox(): ByteArray =
    fullBox("pitm", version = 0, payload = buildBytes { beU16(IMAGE_ITEM_ID) })

  /**
   * `iinf` with the image item first and the Exif item second, so the `infe` walk has to get past
   * an entry that is not the one it wants.
   */
  private fun iinfBox(withExif: Boolean, infeVersion: Int): ByteArray {
    val entries = buildList {
      add(infeBox(infeVersion, IMAGE_ITEM_ID, "hvc1"))
      if (withExif) add(infeBox(infeVersion, EXIF_ITEM_ID, "Exif"))
    }
    return fullBox(
      "iinf",
      version = 0,
      payload = buildBytes {
        beU16(entries.size)
        entries.forEach { raw(it) }
      },
    )
  }

  private fun infeBox(version: Int, itemId: Int, itemType: String): ByteArray = fullBox(
    "infe",
    version = version,
    payload = buildBytes {
      if (version >= 3) beU32(itemId) else beU16(itemId)
      beU16(0) // item_protection_index
      ascii(itemType)
      ascii(itemType) // item_name
      bytes(0)
    },
  )

  private fun ilocBox(
    version: Int,
    constructionMethod: Int,
    imageOffset: Int,
    imageLength: Int,
    exifOffset: Int,
    exifLength: Int?,
  ): ByteArray {
    val items = buildList {
      add(Triple(IMAGE_ITEM_ID, imageOffset, imageLength))
      if (exifLength != null) add(Triple(EXIF_ITEM_ID, exifOffset, exifLength))
    }
    return fullBox(
      "iloc",
      version = version,
      payload = buildBytes {
        bytes((4 shl 4) or 4) // offset_size = 4, length_size = 4
        bytes((0 shl 4) or 0) // base_offset_size = 0, index_size = 0
        beU16(items.size)
        items.forEach { (id, offset, length) ->
          beU16(id)
          if (version >= 1) beU16(constructionMethod) // 12 reserved bits, then a 4-bit method
          beU16(0) // data_reference_index
          // base_offset is zero bytes wide here, and therefore absent entirely.
          beU16(1) // extent_count
          beU32(offset)
          beU32(length)
        }
      },
    )
  }

  /**
   * `iprp` holding an `ipco` of properties and the `ipma` that associates them.
   *
   * `ispe` is written first so the property walk has to step over a box it does not recognise
   * before it reaches the transforms, and `ipma` follows `ipco` so the search for `ipco` has a
   * sibling to get past.
   */
  private fun iprpBox(irotAngle: Int?, imirAxis: Int?, transformOrder: List<String>): ByteArray {
    val properties = buildList {
      add(
        fullBox(
          "ispe",
          version = 0,
          payload = buildBytes {
            beU32(4032)
            beU32(3024)
          },
        ),
      )
      transformOrder.forEach { name ->
        when (name) {
          // ISO/IEC 23008-12 6.5.10: six reserved bits then a two-bit anti-clockwise angle.
          "irot" -> irotAngle?.let { add(box("irot", buildBytes { bytes(it and 0x03) })) }

          // ISO/IEC 23008-12 6.5.12: seven reserved bits then a one-bit axis.
          "imir" -> imirAxis?.let { add(box("imir", buildBytes { bytes(it and 0x01) })) }
        }
      }
    }
    val ipco = box("ipco", *properties.toTypedArray())
    val ipma = fullBox(
      "ipma",
      version = 0,
      payload = buildBytes {
        beU32(1) // entry_count
        beU16(IMAGE_ITEM_ID)
        bytes(properties.size) // association_count
        for (index in 1..properties.size) bytes(0x80 or index) // essential bit + property index
      },
    )
    return box("iprp", ipco, ipma)
  }

  // ---------------------------------------------------------------------------------------------
  // Box and byte plumbing.
  // ---------------------------------------------------------------------------------------------

  private fun box(type: String, vararg payload: ByteArray): ByteArray = buildBytes {
    beU32(8 + payload.sumOf { it.size })
    ascii(type)
    payload.forEach { raw(it) }
  }

  private fun fullBox(type: String, version: Int, payload: ByteArray): ByteArray =
    box(type, buildBytes { bytes(version, 0, 0, 0) }, payload)

  /** A box in the 64-bit form: `size == 1`, then the real size as a `largesize`. */
  private fun largeBox(type: String, payload: ByteArray): ByteArray = buildBytes {
    beU32(1)
    ascii(type)
    beU64((16 + payload.size).toLong())
    raw(payload)
  }

  /** A box with `size == 0`: it runs to the end of the file, and must be the last one. */
  private fun openEndedBox(type: String, payload: ByteArray): ByteArray = buildBytes {
    beU32(0)
    ascii(type)
    raw(payload)
  }

  /**
   * Where [slice] sits in this array.
   *
   * The measuring pass depends on the payload appearing exactly once, so a second occurrence is a
   * fixture bug rather than a test failure and is reported as one.
   */
  private fun ByteArray.locate(slice: ByteArray): Int {
    val hits = (0..size - slice.size).filter { start ->
      slice.indices.all { this[start + it] == slice[it] }
    }
    check(hits.size == 1) { "expected exactly one occurrence of the payload, found ${hits.size}" }
    return hits.single()
  }

  private fun buildBytes(block: ByteBuilder.() -> Unit): ByteArray =
    ByteBuilder().apply(block).build()

  private class ByteBuilder {
    private val out = mutableListOf<Byte>()

    fun bytes(vararg values: Int) = values.forEach { out += it.toByte() }

    fun raw(values: ByteArray) = values.forEach { out += it }

    fun ascii(text: String) = text.forEach { out += it.code.toByte() }

    fun beU16(value: Int) = bytes(value ushr 8, value)

    fun beU32(value: Int) = bytes(value ushr 24, value ushr 16, value ushr 8, value)

    fun beU64(value: Long) {
      for (shift in 56 downTo 0 step 8) out += (value ushr shift).toByte()
    }

    fun leU16(value: Int) = bytes(value, value ushr 8)

    fun leU24(value: Int) = bytes(value, value ushr 8, value ushr 16)

    fun leU32(value: Int) = bytes(value, value ushr 8, value ushr 16, value ushr 24)

    fun u16(value: Int, order: ByteOrder) =
      if (order == ByteOrder.LITTLE) leU16(value) else beU16(value)

    fun u32(value: Int, order: ByteOrder) =
      if (order == ByteOrder.LITTLE) leU32(value) else beU32(value)

    fun build(): ByteArray = out.toByteArray()
  }
}
