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
 * Which end of a multi-byte field comes first.
 *
 * Exif does not pick one: the TIFF header names the order the rest of the block uses, and cameras
 * ship both. A reader that hardcodes either one is correct for roughly half the files it is given,
 * which is worse than being wrong for all of them because the failure looks random.
 */
internal enum class ByteOrder { BIG, LITTLE }

/**
 * Bounds-checked readers, every one of which returns `null` (or `-1`, for the single-byte case)
 * rather than throwing.
 *
 * A photo picker hands this parser truncated downloads, zero-byte placeholders and files whose
 * extension lies, and none of those may reach the caller as an exception. Returning a sentinel at
 * every read is what makes the parsers above total without a single `try`.
 */
internal fun ByteArray.u8(index: Int): Int =
  if (index in indices) this[index].toInt() and 0xFF else -1

internal fun ByteArray.beU16(index: Int): Int? {
  if (index < 0 || index + 1 >= size) return null
  return (u8(index) shl 8) or u8(index + 1)
}

internal fun ByteArray.leU16(index: Int): Int? {
  if (index < 0 || index + 1 >= size) return null
  return u8(index) or (u8(index + 1) shl 8)
}

/**
 * Reads a 32-bit unsigned field as a [Long].
 *
 * The width matters: an ISOBMFF box may legally declare a size above [Int.MAX_VALUE], and reading
 * it into an `Int` wraps it negative, which then reads as "this box ends before it began" and walks
 * the parser off into the payload.
 */
internal fun ByteArray.beU32(index: Int): Long? = uIntBe(index, byteCount = 4)

internal fun ByteArray.leU32(index: Int): Long? {
  if (index < 0 || index + 3 >= size) return null
  return u8(index).toLong() or (u8(index + 1).toLong() shl 8) or
    (u8(index + 2).toLong() shl 16) or (u8(index + 3).toLong() shl 24)
}

/**
 * Reads a big-endian unsigned integer [byteCount] bytes wide.
 *
 * `iloc` stores its offsets and lengths at a width the box header chooses per file (0, 4 or 8
 * bytes), so the width cannot be baked into the call site. A zero width is not an error there: it
 * means the field is absent and its value is zero.
 *
 * @return the value, or `null` if it runs past the end of the array or does not fit in a
 *   non-negative [Long].
 */
internal fun ByteArray.uIntBe(index: Int, byteCount: Int): Long? {
  if (byteCount == 0) return 0L
  if (byteCount !in 1..8 || index < 0 || index.toLong() + byteCount > size) return null
  var value = 0L
  for (i in 0 until byteCount) {
    value = (value shl 8) or (this[index + i].toLong() and 0xFF)
  }
  return if (value < 0L) null else value
}

internal fun ByteArray.u16(index: Int, order: ByteOrder): Int? =
  if (order == ByteOrder.BIG) beU16(index) else leU16(index)

internal fun ByteArray.u32(index: Int, order: ByteOrder): Long? =
  if (order == ByteOrder.BIG) beU32(index) else leU32(index)

internal fun ByteArray.asciiEquals(offset: Int, text: String): Boolean {
  if (offset < 0 || offset.toLong() + text.length > size) return false
  for (i in text.indices) {
    if (this[offset + i].toInt() != text[i].code) return false
  }
  return true
}

internal fun ByteArray.ascii(offset: Int, length: Int): String? {
  if (offset < 0 || length < 0 || offset.toLong() + length > size) return null
  val chars = CharArray(length) { (this[offset + it].toInt() and 0xFF).toChar() }
  return chars.concatToString()
}

/**
 * Narrows a value read from a file to an index into this array, or `null` when it cannot be one.
 *
 * Every offset in these containers is attacker-controlled in the only sense that matters here: it
 * comes from a file the user picked, not from us.
 */
internal fun Long.toIndexOrNull(size: Int): Int? = if (this in 0L..size.toLong()) toInt() else null
