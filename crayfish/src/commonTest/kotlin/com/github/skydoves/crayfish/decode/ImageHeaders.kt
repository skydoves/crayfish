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

/**
 * Synthetic container headers, byte-for-byte to spec.
 *
 * These are built rather than checked in as binaries so that each field's offset is visible in the
 * test source: a fixture whose bytes nobody can read is a fixture nobody can debug.
 */
internal object ImageHeaders {

  fun png(width: Int, height: Int): ByteArray = buildBytes {
    bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    beU32(13) // IHDR payload length
    ascii("IHDR")
    beU32(width)
    beU32(height)
    bytes(0x08, 0x06, 0x00, 0x00, 0x00) // bit depth, colour type, compression, filter, interlace
  }

  /**
   * A JPEG carrying an APP0 segment before its frame header, which is the ordinary layout and the
   * reason the parser has to walk segments rather than read a fixed offset.
   */
  fun jpeg(width: Int, height: Int, startOfFrameMarker: Int = 0xC0): ByteArray = buildBytes {
    bytes(0xFF, 0xD8)
    // APP0 / JFIF
    bytes(0xFF, 0xE0)
    beU16(16)
    ascii("JFIF")
    bytes(0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00)
    // Start of frame
    bytes(0xFF, startOfFrameMarker)
    beU16(17)
    bytes(0x08) // sample precision
    beU16(height)
    beU16(width)
    bytes(0x03)
    bytes(0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01)
  }

  /** A JPEG whose frame header is preceded by a huge Exif segment, as camera output always is. */
  fun jpegWithLargeExif(width: Int, height: Int, exifPayloadSize: Int = 4096): ByteArray =
    buildBytes {
      bytes(0xFF, 0xD8)
      bytes(0xFF, 0xE1)
      beU16(exifPayloadSize + 2)
      repeat(exifPayloadSize) { bytes(0x00) }
      bytes(0xFF, 0xC2) // progressive frame
      beU16(17)
      bytes(0x08)
      beU16(height)
      beU16(width)
      bytes(0x03)
      bytes(0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01)
    }

  fun gif(width: Int, height: Int, version: String = "GIF89a"): ByteArray = buildBytes {
    ascii(version)
    leU16(width)
    leU16(height)
    bytes(0x00, 0x00, 0x00)
  }

  fun webPLossy(width: Int, height: Int): ByteArray = buildBytes {
    ascii("RIFF")
    leU32(0) // file size, not read by the parser
    ascii("WEBP")
    ascii("VP8 ")
    leU32(0) // chunk size
    bytes(0x00, 0x00, 0x00) // frame tag
    bytes(0x9D, 0x01, 0x2A) // sync code
    leU16(width)
    leU16(height)
  }

  fun webPLossless(width: Int, height: Int): ByteArray = buildBytes {
    ascii("RIFF")
    leU32(0)
    ascii("WEBP")
    ascii("VP8L")
    leU32(0)
    bytes(0x2F) // signature
    // 14 bits of (width - 1), then 14 bits of (height - 1).
    leU32(((width - 1) and 0x3FFF) or (((height - 1) and 0x3FFF) shl 14))
  }

  fun webPExtended(width: Int, height: Int): ByteArray = buildBytes {
    ascii("RIFF")
    leU32(0)
    ascii("WEBP")
    ascii("VP8X")
    leU32(10)
    bytes(0x00, 0x00, 0x00, 0x00) // flags
    leU24(width - 1)
    leU24(height - 1)
  }

  /** An `ftyp` box; [compatibleBrands] is where a `mif1`-major AVIF actually declares itself. */
  fun isoBaseMedia(majorBrand: String, vararg compatibleBrands: String): ByteArray {
    val boxSize = 16 + compatibleBrands.size * 4
    return buildBytes {
      beU32(boxSize)
      ascii("ftyp")
      ascii(majorBrand)
      beU32(0) // minor version
      compatibleBrands.forEach { ascii(it) }
    }
  }

  private fun buildBytes(block: ByteBuilder.() -> Unit): ByteArray =
    ByteBuilder().apply(block).build()

  internal class ByteBuilder {
    private val out = mutableListOf<Byte>()

    fun bytes(vararg values: Int) = values.forEach { out += it.toByte() }

    fun ascii(text: String) = text.forEach { out += it.code.toByte() }

    fun beU16(value: Int) = bytes(value ushr 8, value)

    fun beU32(value: Int) = bytes(value ushr 24, value ushr 16, value ushr 8, value)

    fun leU16(value: Int) = bytes(value, value ushr 8)

    fun leU24(value: Int) = bytes(value, value ushr 8, value ushr 16)

    fun leU32(value: Int) = bytes(value, value ushr 8, value ushr 16, value ushr 24)

    fun build(): ByteArray = out.toByteArray()
  }
}
