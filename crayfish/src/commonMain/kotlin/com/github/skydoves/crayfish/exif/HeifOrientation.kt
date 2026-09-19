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

/** `size`(4) + `type`(4). Every ISOBMFF box starts with these eight bytes. */
private const val BOX_HEADER_BYTES = 8

/** The 64-bit `largesize` that follows the header when `size == 1`. */
private const val LARGE_SIZE_BYTES = 8

/** `version`(1) + `flags`(3), present on a FullBox and absent on a plain Box. */
private const val FULL_BOX_PREFIX = 4

/** The `item_type` an Exif metadata item declares in its `infe` entry. */
private const val EXIF_ITEM_TYPE = "Exif"

/** A box's payload: the half-open range `[start, end)` of indices it occupies in the file. */
internal data class BoxSpan(val start: Int, val end: Int)

/**
 * Reads the orientation a HEIF or AVIF file declares.
 *
 * ### Why there are two answers to reconcile
 *
 * HEIF can state its orientation in two places that are free to disagree:
 *
 *  * the container's own transformative properties, `irot` (rotation) and `imir` (mirror), living
 *    in `meta` -> `iprp` -> `ipco`; and
 *  * an ordinary Exif block, carried as an item and located through `iinf` and `iloc`.
 *
 * Writers do not agree on which to use. iOS 15's Photos editor writes the Exif `Orientation` tag
 * and no transform boxes at all (libvips#2551, <https://github.com/libvips/libvips/issues/2551>),
 * so a reader that only looks at `irot`/`imir` shows every edited iPhone photo sideways. Other
 * writers do the exact opposite and rotate with `irot` while leaving Exif at 1. Reading a single
 * source is wrong for roughly half the HEIFs in circulation, in one direction or the other.
 *
 * ### The precedence rule, and that it is a choice
 *
 * **When any transformative property box is present it wins outright; Exif is consulted only when
 * there is none.** `irot`/`imir` are normative *for the container*: a HEIF decoder is required to
 * apply them, so a platform decoder that already honours them has produced pixels that the Exif
 * tag would rotate a second time. A file carrying both and meaning different things is already
 * malformed, and picking the normative one is the defensible half of the coin.
 *
 * @return the declared orientation, or [ImageOrientation.NORMAL] when nothing readable says
 *   otherwise.
 */
internal fun readHeifOrientation(bytes: ByteArray): ImageOrientation {
  val meta = findBox(bytes, "meta", BoxSpan(0, bytes.size)) ?: return ImageOrientation.NORMAL

  // `meta` is a FullBox: a version byte and three flag bytes sit between its header and its first
  // child. `iprp`, `ipco` and `idat` are plain boxes with no such word, while `iinf`, `infe` and
  // `iloc` have one. Skipping the word for a box that lacks it - or failing to skip it for one
  // that has it - starts the walk four bytes into a child's header, where the type field is read
  // as a size and the whole subtree silently disappears.
  val children = BoxSpan(meta.start + FULL_BOX_PREFIX, meta.end)
  if (children.start > children.end) return ImageOrientation.NORMAL

  return readTransformProperties(bytes, children)
    ?: readExifItemOrientation(bytes, children)
    ?: ImageOrientation.NORMAL
}

/**
 * Composes `irot` and `imir` from `meta` -> `iprp` -> `ipco`.
 *
 * @return the composed orientation, or `null` when neither box is present - which is the signal
 *   that [readHeifOrientation] should fall back to Exif.
 */
private fun readTransformProperties(bytes: ByteArray, metaChildren: BoxSpan): ImageOrientation? {
  val iprp = findBox(bytes, "iprp", metaChildren) ?: return null
  val ipco = findBox(bytes, "ipco", iprp) ?: return null

  val transform = Transform()
  var present = false

  forEachBox(bytes, ipco) { type, payload ->
    when (type) {
      "irot" -> {
        // ISO/IEC 23008-12 6.5.10 ImageRotation: six reserved bits, then a two-bit `angle`, where
        // `angle * 90` is the rotation ANTI-CLOCKWISE. ImageOrientation.rotationDegrees is
        // clockwise, so the value must be negated rather than copied:
        //
        //     clockwise = (360 - 90 * angle) % 360
        //
        // Omitting that leaves angles 0 and 2 correct and swaps 1 with 3, so half the fixtures
        // still pass and every quarter-turn photo ships inverted. The four-angle test holds it.
        val angle = bytes.u8(payload.start)
        if (angle >= 0) {
          val counterClockwise = (angle and 0x03) * 90
          transform.rotate(clockwise = (360 - counterClockwise) % 360)
          present = true
        }
      }

      "imir" -> {
        // ISO/IEC 23008-12 6.5.12 ImageMirror: seven reserved bits, then a one-bit axis.
        //
        // The first edition's wording, "a vertical (axis = 0) or horizontal (axis = 1) axis for
        // the mirroring operation", names the mirror LINE, and read that way axis = 0 would be a
        // left-right flip. The 2022 edition replaced it with an unambiguous sentence: 0 exchanges
        // the top and bottom parts, 1 exchanges left and right. libheif and libavif both
        // implement the 2022 reading, and a file written to the older wording is
        // indistinguishable from one written to the newer, so following the decoders is the only
        // choice that agrees with what users see.
        val axis = bytes.u8(payload.start)
        if (axis >= 0) {
          if (axis and 0x01 == 0) transform.mirrorTopBottom() else transform.mirrorLeftRight()
          present = true
        }
      }
    }
    true
  }

  return if (present) transform.toOrientation() else null
}

/**
 * The transform accumulated so far, in [ImageOrientation]'s convention: rotate clockwise by
 * [rotationDegrees], then mirror horizontally if [mirrored].
 *
 * HEIF states its transforms as independent property boxes applied in the order they are
 * associated, so they must be composed rather than looked up in a table. MIAF (ISO/IEC 23000-22)
 * constrains a conforming writer to associate them in the order `clap`, `irot`, `imir`, and
 * `ipco` holds them in that order in practice, so walking `ipco` and composing as boxes arrive
 * reproduces the association order without correlating `ipma`.
 */
private class Transform {
  var rotationDegrees: Int = 0
  var mirrored: Boolean = false

  /** Applies a clockwise rotation after everything accumulated so far. */
  fun rotate(clockwise: Int) {
    // A mirror already in the stack flips the sense of every rotation that follows it: reflecting
    // and then turning one way is the same as turning the other way and then reflecting.
    rotationDegrees = if (mirrored) {
      (rotationDegrees - clockwise + 360) % 360
    } else {
      (rotationDegrees + clockwise) % 360
    }
  }

  /** Applies a left-right exchange after everything accumulated so far. */
  fun mirrorLeftRight() {
    mirrored = !mirrored
  }

  /**
   * Applies a top-bottom exchange, which [ImageOrientation] has no direct term for: a vertical
   * flip is a horizontal flip composed with a half turn, and that holds whichever way round the
   * existing mirror flag sits.
   */
  fun mirrorTopBottom() {
    rotationDegrees = (rotationDegrees + 180) % 360
    mirrored = !mirrored
  }

  /**
   * Resolves the accumulated rotation and mirror to one of the eight orientations, by reading
   * [ImageOrientation]'s own fields rather than repeating a table that could drift from them.
   */
  fun toOrientation(): ImageOrientation = ImageOrientation.entries.firstOrNull {
    it.rotationDegrees == rotationDegrees && it.isMirrored == mirrored
  } ?: ImageOrientation.NORMAL
}

// -----------------------------------------------------------------------------------------------
// The Exif item: `iinf` names it, `iloc` says where its bytes are, and they are nowhere near
// each other in the file.
// -----------------------------------------------------------------------------------------------

private fun readExifItemOrientation(bytes: ByteArray, metaChildren: BoxSpan): ImageOrientation? {
  val itemId = findExifItemId(bytes, metaChildren) ?: return null
  val extent = findItemExtent(bytes, metaChildren, itemId) ?: return null
  return readExifDataBlock(bytes, extent)
}

/** Walks `iinf` for the `infe` entry whose `item_type` is `Exif`, and returns its `item_ID`. */
private fun findExifItemId(bytes: ByteArray, metaChildren: BoxSpan): Long? {
  val iinf = findBox(bytes, "iinf", metaChildren) ?: return null
  val version = bytes.u8(iinf.start)
  if (version < 0) return null

  // FullBox prefix, then `entry_count`: 16 bits at version 0 and 32 bits above it. The entries
  // themselves are ordinary boxes and carry their own sizes, so only their starting point depends
  // on the version.
  val entriesStart = iinf.start + FULL_BOX_PREFIX + if (version == 0) 2 else 4
  var itemId: Long? = null
  forEachBox(bytes, BoxSpan(entriesStart, iinf.end)) { type, payload ->
    if (type == "infe") itemId = exifItemIdOrNull(bytes, payload)
    itemId == null
  }
  return itemId
}

/**
 * Reads one `infe` entry, returning its `item_ID` only if it describes an Exif item.
 *
 * `infe` is itself a FullBox, and its version decides how wide `item_ID` is, which in turn moves
 * `item_type`. Versions 0 and 1 predate `item_type` entirely and cannot name an Exif item.
 */
private fun exifItemIdOrNull(bytes: ByteArray, payload: BoxSpan): Long? {
  val fields = payload.start + FULL_BOX_PREFIX
  return when (bytes.u8(payload.start)) {
    // item_ID(2) item_protection_index(2) item_type(4)
    2 -> bytes.beU16(fields)?.toLong()
      ?.takeIf { bytes.asciiEquals(fields + 4, EXIF_ITEM_TYPE) }

    // item_ID(4) item_protection_index(2) item_type(4)
    3 -> bytes.beU32(fields)
      ?.takeIf { bytes.asciiEquals(fields + 6, EXIF_ITEM_TYPE) }

    else -> null
  }
}

/**
 * Walks `iloc` for [itemId] and returns where that item's bytes actually live.
 *
 * `iloc` is the one box here whose field widths are chosen per file rather than fixed by the spec:
 * `offset_size`, `length_size`, `base_offset_size` and `index_size` are four nibbles in its first
 * two payload bytes, and every one of them may be 0, 4 or 8. There is no fixed layout to jump to,
 * so the walk steps field by field and reads each at the width the header declared.
 *
 * @return the extent, or `null` when the item is absent, uses a construction method that cannot be
 *   resolved from these bytes alone, or points outside the file.
 */
private fun findItemExtent(bytes: ByteArray, metaChildren: BoxSpan, itemId: Long): BoxSpan? {
  val iloc = findBox(bytes, "iloc", metaChildren) ?: return null
  val version = bytes.u8(iloc.start)
  if (version < 0) return null

  var cursor = iloc.start + FULL_BOX_PREFIX
  val sizeNibbles = bytes.u8(cursor)
  val indexNibbles = bytes.u8(cursor + 1)
  if (sizeNibbles < 0 || indexNibbles < 0) return null
  val offsetSize = sizeNibbles ushr 4
  val lengthSize = sizeNibbles and 0x0F
  val baseOffsetSize = indexNibbles ushr 4
  val indexSize = if (version == 1 || version == 2) indexNibbles and 0x0F else 0
  cursor += 2

  // `item_ID` and `item_count` are both 16-bit up to version 1 and both 32-bit at version 2.
  val idBytes = if (version < 2) 2 else 4
  val itemCount = bytes.uIntBe(cursor, idBytes) ?: return null
  cursor += idBytes

  var remaining = itemCount
  while (remaining > 0) {
    remaining--
    val id = bytes.uIntBe(cursor, idBytes) ?: return null
    cursor += idBytes

    var constructionMethod = 0
    if (version == 1 || version == 2) {
      constructionMethod = (bytes.beU16(cursor) ?: return null) and 0x0F
      cursor += 2
    }
    cursor += 2 // data_reference_index, always 0 for a self-contained file.

    val baseOffset = bytes.uIntBe(cursor, baseOffsetSize) ?: return null
    cursor += baseOffsetSize
    val extentCount = bytes.beU16(cursor) ?: return null
    cursor += 2

    // Every field width in an `iloc` is chosen by the file, and zero is legal: it means the field
    // is absent and its value is zero. All three at once makes the loop below advance the cursor by
    // nothing, so it runs its declared `extent_count` (up to 65535) per item, for up to 65535
    // items, reading the same bytes each time. A 131KB file took 4.8 seconds on a desktop JVM, and
    // this parser runs on the composition thread, so on Android that is an ANR from opening a
    // picked file. An entry that locates nothing cannot be the Exif item anyway.
    if (indexSize + offsetSize + lengthSize == 0) return null

    for (extent in 0 until extentCount) {
      cursor += indexSize
      val extentOffset = bytes.uIntBe(cursor, offsetSize) ?: return null
      cursor += offsetSize
      val extentLength = bytes.uIntBe(cursor, lengthSize) ?: return null
      cursor += lengthSize
      if (id != itemId || extent != 0) continue

      // construction_method 0 measures the offset from the start of the file; 1 measures it from
      // the start of this `meta`'s `idat` payload. 2 points at another item and is left alone: an
      // Exif block stored that way is vanishingly rare, and resolving it means recursing through
      // `iloc` with a cycle to guard against.
      val base = when (constructionMethod) {
        0 -> 0L
        1 -> (findBox(bytes, "idat", metaChildren) ?: return null).start.toLong()
        else -> return null
      }
      val start = (base + baseOffset + extentOffset).toIndexOrNull(bytes.size) ?: return null
      val end = (start + extentLength).toIndexOrNull(bytes.size) ?: return null
      return BoxSpan(start, end)
    }
  }
  return null
}

/**
 * Reads the Exif payload an item carries.
 *
 * ISO/IEC 23008-12 Annex A wraps it in a 32-bit `exif_tiff_header_offset` counting the bytes that
 * precede the TIFF header. Writers use 0 (payload starts at the TIFF header) and 6 (it starts with
 * `"Exif"` plus two NUL bytes) about equally often, and some set the field to 0 while still
 * writing the identifier. Both readings are tried, the declared one first, so a well-formed file
 * is never second-guessed.
 */
private fun readExifDataBlock(bytes: ByteArray, extent: BoxSpan): ImageOrientation? {
  val headerOffset = bytes.beU32(extent.start) ?: return null
  val payload = extent.start + 4

  val declared = if (headerOffset <= extent.end - payload) payload + headerOffset.toInt() else -1
  val afterIdentifier =
    if (bytes.asciiEquals(payload, EXIF_IDENTIFIER)) payload + EXIF_IDENTIFIER.length else -1

  return readTiffOrientation(bytes, declared) ?: readTiffOrientation(bytes, afterIdentifier)
}

// -----------------------------------------------------------------------------------------------
// The box walk itself.
// -----------------------------------------------------------------------------------------------

/**
 * Walks the boxes laid out in [span], calling [action] with each one's type and payload.
 *
 * The walk stops as soon as [action] returns `false`, or the moment a box says something that
 * cannot be true - a size smaller than its own header, or an end past its parent's. Stopping is
 * the right response to both: a box whose size is wrong makes every byte after it meaningless,
 * and guessing a recovery point is how a parser starts reading a payload as structure.
 */
private fun forEachBox(
  bytes: ByteArray,
  span: BoxSpan,
  action: (type: String, payload: BoxSpan) -> Boolean,
) {
  val limit = minOf(span.end, bytes.size)
  var index = maxOf(span.start, 0)

  while (index + BOX_HEADER_BYTES <= limit) {
    val declaredSize = bytes.beU32(index) ?: return
    val type = bytes.ascii(index + 4, 4) ?: return

    var payloadStart = index + BOX_HEADER_BYTES
    val boxEnd = when (declaredSize) {
      // `size == 0`: the box runs to the end of the file. A one-pass writer uses it for the last
      // box because it cannot know that box's length until it has finished writing it.
      0L -> limit

      // `size == 1`: the real size is a 64-bit `largesize` after the header. Nothing this parser
      // looks at needs 2 GB, but a file is free to say so and the header still has to be stepped
      // over at the wider width or every box after it is read at the wrong offset.
      1L -> {
        payloadStart = index + BOX_HEADER_BYTES + LARGE_SIZE_BYTES
        val largeSize = bytes.uIntBe(index + BOX_HEADER_BYTES, LARGE_SIZE_BYTES) ?: return
        if (largeSize < payloadStart - index) return
        (index + largeSize).toIndexOrNull(limit) ?: return
      }

      else -> {
        if (declaredSize < BOX_HEADER_BYTES) return
        (index + declaredSize).toIndexOrNull(limit) ?: return
      }
    }

    if (payloadStart > boxEnd) return
    if (!action(type, BoxSpan(payloadStart, boxEnd))) return
    // The one line that makes this loop terminate, for every input, whatever the size checks
    // above do. Those are early exits that keep a malformed box from reaching `action`; this is
    // the structural guarantee. Removing both of them leaves the walk terminating and the suite
    // green - removing this one makes a `size == 1` box declaring a zero `largesize` spin for
    // ever, and a hang is the one failure a test suite reports as "still running".
    if (boxEnd <= index) return
    index = boxEnd
  }
}

/** The payload of the first box of [type] directly inside [span], or `null` if there is none. */
private fun findBox(bytes: ByteArray, type: String, span: BoxSpan): BoxSpan? {
  var found: BoxSpan? = null
  forEachBox(bytes, span) { boxType, payload ->
    if (boxType == type) found = payload
    found == null
  }
  return found
}
