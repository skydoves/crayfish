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

/** Exif `Orientation`, TIFF 6.0 tag 274. */
private const val TAG_ORIENTATION = 0x0112

/** TIFF field type 3, a 16-bit unsigned integer: the only type Orientation is defined as. */
private const val TYPE_SHORT = 3

/** Tag(2) + type(2) + count(4) + value-or-offset(4). */
private const val ENTRY_BYTES = 12

/** The `42` that follows the byte-order marker and confirms the block really is TIFF. */
private const val TIFF_MAGIC = 42

/**
 * The six bytes that introduce an Exif payload inside a JPEG `APP1` or a WebP `EXIF` chunk:
 * `"Exif"` followed by two NUL pad bytes. The TIFF header begins immediately after them.
 */
internal const val EXIF_IDENTIFIER: String = "Exif\u0000\u0000"

/**
 * Reads the Exif `Orientation` tag out of the TIFF block whose header begins at
 * [tiffHeaderStart].
 *
 * **Every offset inside a TIFF block is measured from the start of the TIFF header, not from the
 * start of the file.** That header sits at file offset 12 in a plain JPEG, and wherever `iloc` put
 * the Exif item in a HEIF. Treating the two as interchangeable does not fail loudly: it lands
 * inside some other structure, reads twelve bytes that happen to parse, and reports a confident
 * wrong orientation. [tiffHeaderStart] is threaded through every read below for that reason.
 *
 * @return the orientation the block declares, or `null` when the block is absent, malformed,
 *   truncated, or simply carries no Orientation tag. `null` is distinct from
 *   [ImageOrientation.NORMAL] on purpose: the HEIF precedence rule has to know whether Exif said
 *   "upright" or said nothing at all.
 */
internal fun readTiffOrientation(bytes: ByteArray, tiffHeaderStart: Int): ImageOrientation? {
  if (tiffHeaderStart < 0) return null

  val order = when {
    bytes.asciiEquals(tiffHeaderStart, "II") -> ByteOrder.LITTLE
    bytes.asciiEquals(tiffHeaderStart, "MM") -> ByteOrder.BIG
    else -> return null
  }
  if (bytes.u16(tiffHeaderStart + 2, order) != TIFF_MAGIC) return null

  // IFD0's offset is TIFF-relative, so it is added to the header's position rather than used raw.
  // It is bounded against the remaining bytes first, so the sum cannot overflow into a negative
  // index that would then read from the wrong end of the array.
  val ifdOffset = bytes.u32(tiffHeaderStart + 4, order) ?: return null
  if (ifdOffset > bytes.size - tiffHeaderStart) return null
  val ifd = tiffHeaderStart + ifdOffset.toInt()

  val entryCount = bytes.u16(ifd, order) ?: return null
  for (i in 0 until entryCount) {
    val entry = ifd + 2 + i * ENTRY_BYTES
    if (entry < 0 || entry + ENTRY_BYTES > bytes.size) return null
    if (bytes.u16(entry, order) != TAG_ORIENTATION) continue

    // A tag that is not a single SHORT is malformed. Reading it anyway would mean guessing at a
    // width, and a wrong guess rotates an image that was already upright.
    if (bytes.u16(entry + 2, order) != TYPE_SHORT) return null
    if (bytes.u32(entry + 4, order) != 1L) return null

    // A single SHORT fits inside the 4-byte value field, occupying its first two bytes in the
    // declared order. The remaining two are undefined padding and must not be read.
    val value = bytes.u16(entry + 8, order) ?: return null
    return ImageOrientation.fromExifValue(value)
  }
  return null
}
