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
 * A real, decodable PNG, built here rather than checked in as a binary.
 *
 * `ImageHeaders` in commonTest stops at the header, which is all a parser test needs; a decoder
 * test needs pixels ImageIO will actually produce, and needs to know what they are. The image is
 * a grid of solid blocks so that every assertion can name the colour it expects from the
 * coordinate it is reading, and so that interior pixels survive subsampling unchanged: an
 * averaging filter over identical pixels returns that pixel.
 *
 * The deflate stream is built from *stored* blocks, which are ordinary deflate and cost five
 * bytes per 64KB. That is what lets this file emit a spec-legal PNG with no compressor.
 */
internal object PngFixture {

  const val WIDTH: Int = 128
  const val HEIGHT: Int = 96

  /** The side of each solid block; assertions read pixels well inside one. */
  const val BLOCK: Int = 32

  private const val COLUMNS = WIDTH / BLOCK

  private val BLOCK_COLORS = intArrayOf(
    0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00,
    0xFF00FF, 0x00FFFF, 0x808080, 0xFFFFFF,
    0x000000, 0x804000, 0x008040, 0x400080,
  )

  /** The colour the fixture holds at ([x], [y]), as `0xRRGGBB`. */
  fun colorAt(x: Int, y: Int): Int = BLOCK_COLORS[(y / BLOCK) * COLUMNS + (x / BLOCK)]

  /** A [WIDTH]x[HEIGHT] PNG of twelve solid [BLOCK]-square blocks, each a different colour. */
  fun image(): ByteArray = encode(WIDTH, HEIGHT, ::colorAt)

  private const val MAX_STORED_BLOCK = 0xFFFF
  private const val BYTES_PER_RGB_PIXEL = 3

  private fun encode(width: Int, height: Int, rgb: (Int, Int) -> Int): ByteArray {
    // Scanlines, each prefixed by its filter type. Filter 0 (none) keeps the fixture readable.
    val raw = ByteArray(height * (1 + width * BYTES_PER_RGB_PIXEL))
    var index = 0
    for (y in 0 until height) {
      raw[index++] = 0
      for (x in 0 until width) {
        val color = rgb(x, y)
        raw[index++] = (color shr 16).toByte()
        raw[index++] = (color shr 8).toByte()
        raw[index++] = color.toByte()
      }
    }

    val header = ByteSink().apply {
      beU32(width)
      beU32(height)
      // bit depth 8, colour type 2 (truecolour), deflate, adaptive filtering, no interlace.
      bytes(8, 2, 0, 0, 0)
    }

    return ByteSink().apply {
      bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
      chunk("IHDR", header.toByteArray())
      chunk("IDAT", zlibStored(raw))
      chunk("IEND", ByteArray(0))
    }.toByteArray()
  }

  private fun zlibStored(raw: ByteArray): ByteArray = ByteSink().apply {
    bytes(0x78, 0x01) // zlib: deflate, 32KB window, fastest
    var offset = 0
    do {
      val length = minOf(MAX_STORED_BLOCK, raw.size - offset)
      val isLast = offset + length >= raw.size
      // BFINAL in bit 0, BTYPE 00 (stored) in bits 1-2; the rest of the byte is padding.
      bytes(if (isLast) 1 else 0)
      leU16(length)
      leU16(length.inv() and 0xFFFF)
      bytes(raw.copyOfRange(offset, offset + length))
      offset += length
    } while (offset < raw.size)
    beU32(adler32(raw))
  }.toByteArray()

  private val CRC_TABLE = IntArray(256) { entry ->
    var value = entry
    repeat(8) {
      value = if (value and 1 != 0) 0xEDB88320.toInt() xor (value ushr 1) else value ushr 1
    }
    value
  }

  private fun crc32(data: ByteArray): Int {
    var crc = -1
    for (byte in data) {
      crc = CRC_TABLE[(crc xor byte.toInt()) and 0xFF] xor (crc ushr 8)
    }
    return crc.inv()
  }

  private fun adler32(data: ByteArray): Int {
    var low = 1
    var high = 0
    for (byte in data) {
      low = (low + (byte.toInt() and 0xFF)) % ADLER_MODULUS
      high = (high + low) % ADLER_MODULUS
    }
    return (high shl 16) or low
  }

  private const val ADLER_MODULUS = 65_521

  private class ByteSink {
    private val out = ArrayList<Byte>()

    fun bytes(vararg values: Int) {
      values.forEach { out.add(it.toByte()) }
    }

    fun bytes(values: ByteArray) {
      values.forEach { out.add(it) }
    }

    fun beU32(value: Int) = bytes(value ushr 24, value ushr 16, value ushr 8, value)

    fun leU16(value: Int) = bytes(value, value ushr 8)

    /** A PNG chunk: length, type, payload, then a CRC over the type and payload together. */
    fun chunk(type: String, payload: ByteArray) {
      beU32(payload.size)
      val body = ByteArray(4 + payload.size)
      type.forEachIndexed { at, character -> body[at] = character.code.toByte() }
      payload.copyInto(body, 4)
      bytes(body)
      beU32(crc32(body))
    }

    fun toByteArray(): ByteArray = out.toByteArray()
  }
}
