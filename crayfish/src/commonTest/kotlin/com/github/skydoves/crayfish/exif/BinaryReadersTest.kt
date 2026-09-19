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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bounds-checked readers every parser in this package is built out of.
 *
 * They are tested directly rather than only through the containers above them because a container
 * fixture can only reach the offsets that container happens to produce, and the values that break a
 * hand-rolled reader - a negative offset, a read that ends one byte past the array, a byte whose
 * top bit is set - are mostly not among them. A reader that is wrong here is wrong in a way that
 * reads as "that camera writes strange files".
 */
class BinaryReadersTest {

  /**
   * Every byte distinct, ascending, and with the top bit set in the second half. Anything
   * palindromic or repeated would let a reader with its endianness reversed pass.
   */
  private val bytes = byteArrayOf(
    0x01,
    0x23,
    0x45,
    0x67,
    0x89.toByte(),
    0xAB.toByte(),
    0xCD.toByte(),
    0xEF.toByte(),
  )

  // ---------------------------------------------------------------------------------------------
  // u8: the single-byte read every other reader is built on
  // ---------------------------------------------------------------------------------------------

  /**
   * Kotlin's `Byte` is signed, so `0x89` arrives as -119 and every reader above this one would
   * inherit the sign. The mask is the whole point of the function.
   */
  @Test
  fun readsAByteWhoseTopBitIsSetAsAnUnsignedValue() {
    assertEquals(0x01, bytes.u8(0))
    assertEquals(0x89, bytes.u8(4))
    assertEquals(0xEF, bytes.u8(7))
  }

  /**
   * -1 rather than an exception, and -1 rather than 0: callers distinguish "the file does not have
   * that byte" from "that byte is zero", and a zero here is a version number, an `irot` angle or a
   * box type that the parser would then act on.
   */
  @Test
  fun reportsMinusOneForAnIndexOutsideTheArray() {
    assertEquals(-1, bytes.u8(-1))
    assertEquals(-1, bytes.u8(bytes.size))
    assertEquals(-1, bytes.u8(Int.MIN_VALUE))
    assertEquals(-1, bytes.u8(Int.MAX_VALUE))
    assertEquals(-1, ByteArray(0).u8(0))
  }

  // ---------------------------------------------------------------------------------------------
  // Both byte orders, at every width
  // ---------------------------------------------------------------------------------------------

  /**
   * The TIFF header names its own byte order and cameras ship both, so each width has to be right
   * in both directions. A reader that hardcodes one is correct for about half the files it is
   * handed, which is worse than being wrong for all of them: the failure looks random.
   */
  @Test
  fun readsEveryWidthInBothByteOrders() {
    assertEquals(291, bytes.beU16(0), "beU16 0x0123")
    assertEquals(8961, bytes.leU16(0), "leU16 0x2301")
    assertEquals(19088743L, bytes.beU32(0), "beU32 0x01234567")
    assertEquals(1732584193L, bytes.leU32(0), "leU32 0x67452301")

    assertEquals(291, bytes.u16(0, ByteOrder.BIG))
    assertEquals(8961, bytes.u16(0, ByteOrder.LITTLE))
    assertEquals(19088743L, bytes.u32(0, ByteOrder.BIG))
    assertEquals(1732584193L, bytes.u32(0, ByteOrder.LITTLE))
  }

  /**
   * The same reads over the half of the array whose leading byte has its top bit set.
   *
   * `beU32` at offset 4 is 0x89ABCDEF, which is larger than [Int.MAX_VALUE]: read into an `Int` it
   * is -1985229329, and an ISOBMFF box size read that way says the box ends before it began.
   */
  @Test
  fun readsValuesThatDoNotFitASignedIntOfTheSameWidth() {
    assertEquals(35243, bytes.u16(4, ByteOrder.BIG), "0x89AB")
    assertEquals(43913, bytes.u16(4, ByteOrder.LITTLE), "0xAB89")
    assertEquals(2309737967L, bytes.u32(4, ByteOrder.BIG), "0x89ABCDEF")
    assertEquals(4023233417L, bytes.u32(4, ByteOrder.LITTLE), "0xEFCDAB89")
    assertTrue(bytes.beU32(4)!! > Int.MAX_VALUE, "the value a 32-bit box size may legally reach")
  }

  // ---------------------------------------------------------------------------------------------
  // Offsets a file is free to name and this array does not have
  // ---------------------------------------------------------------------------------------------

  /**
   * Every offset these parsers use is arithmetic on a number that came out of the file, so a
   * negative one is a subtraction away at every call site. Each reader owns its own check; none of
   * them may lean on the caller having done it.
   */
  @Test
  fun everyReaderRefusesANegativeOffset() {
    assertNull(bytes.beU16(-1), "beU16")
    assertNull(bytes.leU16(-1), "leU16")
    assertNull(bytes.beU32(-1), "beU32")
    assertNull(bytes.leU32(-1), "leU32")
    assertNull(bytes.uIntBe(-1, byteCount = 4), "uIntBe")
    assertNull(bytes.u16(-1, ByteOrder.BIG), "u16 BIG")
    assertNull(bytes.u16(-1, ByteOrder.LITTLE), "u16 LITTLE")
    assertNull(bytes.u32(-1, ByteOrder.BIG), "u32 BIG")
    assertNull(bytes.u32(-1, ByteOrder.LITTLE), "u32 LITTLE")
    assertNull(bytes.ascii(-1, length = 2), "ascii")
    assertFalse(bytes.asciiEquals(-1, "II"), "asciiEquals")
    assertNull(bytes.uIntBe(Int.MIN_VALUE, byteCount = 1), "uIntBe at the far end")
  }

  /**
   * The last offset that works and the first that does not, for each width.
   *
   * Off-by-one is the entire failure mode of a bounds check, so the boundary is asserted from both
   * sides rather than sampled well inside the array.
   */
  @Test
  fun everyReaderRefusesAReadThatEndsPastTheArray() {
    val last = bytes.size - 1

    assertEquals(52719, bytes.beU16(last - 1), "beU16 at the last legal offset")
    assertNull(bytes.beU16(last), "beU16 one byte short")
    assertNull(bytes.leU16(last), "leU16 one byte short")
    assertNull(bytes.beU16(bytes.size), "beU16 at the end")

    assertEquals(2309737967L, bytes.beU32(last - 3), "beU32 at the last legal offset")
    assertNull(bytes.beU32(last - 2), "beU32 one byte short")
    assertNull(bytes.leU32(last - 2), "leU32 one byte short")

    assertEquals(81985529216486895L, bytes.uIntBe(0, byteCount = 8), "uIntBe filling the array")
    assertNull(bytes.uIntBe(1, byteCount = 8), "uIntBe one byte short")

    val text = "WEBP".encodeToByteArray()
    assertEquals("BP", text.ascii(2, length = 2), "ascii at the last legal offset")
    assertNull(text.ascii(3, length = 2), "ascii one byte short")
    assertTrue(text.asciiEquals(2, "BP"), "asciiEquals at the last legal offset")
    assertFalse(text.asciiEquals(3, "PP"), "asciiEquals one byte short")

    assertNull(ByteArray(0).beU16(0), "an empty array has no 16-bit value at 0")
    assertNull(ByteArray(0).beU32(0), "an empty array has no 32-bit value at 0")
  }

  // ---------------------------------------------------------------------------------------------
  // uIntBe: the variable-width read `iloc` needs
  // ---------------------------------------------------------------------------------------------

  /**
   * `iloc` chooses each of its field widths per file from {0, 4, 8}, and a width of 0 is not an
   * error there: it means the field is absent and its value is zero. Returning `null` instead would
   * abandon every file that omits `base_offset`, which is most of them.
   */
  @Test
  fun treatsAZeroWidthFieldAsAbsentAndWorthZero() {
    assertEquals(0L, bytes.uIntBe(0, byteCount = 0))
    // Absent means absent even where the array is not: no bounds check applies to no bytes.
    assertEquals(0L, bytes.uIntBe(bytes.size, byteCount = 0))
    assertEquals(0L, ByteArray(0).uIntBe(0, byteCount = 0))
  }

  /** Widths this reader cannot represent, which a nibble in a file is free to ask for. */
  @Test
  fun refusesAWidthItCannotRepresent() {
    assertNull(bytes.uIntBe(0, byteCount = 9), "wider than a Long")
    assertNull(bytes.uIntBe(0, byteCount = -1), "negative")
    assertNull(bytes.uIntBe(0, byteCount = Int.MAX_VALUE), "absurd")
    assertEquals(1L, bytes.uIntBe(0, byteCount = 1), "the narrowest legal width still works")
  }

  /**
   * A 64-bit `largesize` with its top bit set does not fit in a non-negative [Long], and returning
   * the wrapped value would hand the box walk a negative length - a box that ends before it starts,
   * and a walk that then runs backwards through the file.
   */
  @Test
  fun refusesAnEightByteValueWhoseTopBitIsSet() {
    val allOnes = ByteArray(8) { 0xFF.toByte() }
    val topBitOnly = ByteArray(8).also { it[0] = 0x80.toByte() }

    assertNull(allOnes.uIntBe(0, byteCount = 8), "0xFFFFFFFFFFFFFFFF")
    assertNull(topBitOnly.uIntBe(0, byteCount = 8), "0x8000000000000000")
    // Seven bytes of the same value fit, so it is the width that decides, not the bytes.
    assertEquals(72057594037927935L, allOnes.uIntBe(1, byteCount = 7), "0x00FFFFFFFFFFFFFF")
  }

  // ---------------------------------------------------------------------------------------------
  // The text readers
  // ---------------------------------------------------------------------------------------------

  @Test
  fun comparesAsciiWithoutReadingPastTheArray() {
    val riff = "RIFFsize".encodeToByteArray()

    assertTrue(riff.asciiEquals(0, "RIFF"))
    assertFalse(riff.asciiEquals(0, "WEBP"), "same length, different bytes")
    assertFalse(riff.asciiEquals(4, "sizeAndMore"), "a needle longer than what is left")
    assertTrue(riff.asciiEquals(0, ""), "an empty needle matches anywhere inside the array")
  }

  /** A length a file asked for, which may be nonsense in either direction. */
  @Test
  fun refusesAnAsciiReadOfNegativeLength() {
    assertNull(bytes.ascii(0, length = -1))
    assertNull(bytes.ascii(0, length = Int.MIN_VALUE))
    assertEquals("", bytes.ascii(0, length = 0), "zero characters is a legal, empty answer")
  }

  // ---------------------------------------------------------------------------------------------
  // toIndexOrNull: narrowing a value read from a file into an index
  // ---------------------------------------------------------------------------------------------

  /**
   * `size` itself is allowed through, because an empty extent at the very end of the file is a
   * legal thing for a container to describe and every consumer treats the result as an exclusive
   * end. Everything on either side of `0..size` is not an index into this array at all.
   */
  @Test
  fun narrowsOnlyValuesThatAreRealIndices() {
    assertEquals(0, 0L.toIndexOrNull(8))
    assertEquals(8, 8L.toIndexOrNull(8), "the end of the array is a legal exclusive bound")
    assertNull(9L.toIndexOrNull(8), "one past the end")
    assertNull((-1L).toIndexOrNull(8), "negative")
    assertNull(Long.MIN_VALUE.toIndexOrNull(8))
    assertNull(4294967295L.toIndexOrNull(8), "a 32-bit size field at its maximum")
    assertEquals(0, 0L.toIndexOrNull(0), "an empty array still has index 0 as its bound")
  }
}
