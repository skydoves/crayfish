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

/** What an image's container header says about it, read without decoding a single pixel. */
public data class ImageProbeResult(
  public val format: ImageFormat,
  /**
   * The image's pixel size, or `null` when the header does not carry it cheaply.
   *
   * Only HEIF and AVIF return `null`: their dimensions live in an `ispe` property box that is
   * reachable only by correlating `ipma` associations with the primary item, and getting that
   * wrong silently reports a thumbnail's size instead of the image's. Every platform decoder can
   * answer the question authoritatively and cheaply, so the ambiguity is left to them rather than
   * guessed at here.
   */
  public val size: ImageSize?,
)

/**
 * Reads an image's format, and where it is cheap to do so its size, from the leading bytes of the
 * encoded file.
 *
 * This exists so a caller can choose a decode strategy before committing to a decode: knowing a
 * source is a 12000x9000 JPEG is what makes it possible to pick a sample size that keeps the
 * decoded bitmap away from the platform's rendering ceiling.
 *
 * Every parser here is bounds-checked and total: a truncated or malformed file yields `null`, never
 * an exception, because "the user picked a broken file" is an ordinary event on a photo picker.
 */
public object ImageProbe {

  /**
   * The largest prefix any parser below needs. Callers streaming from disk can read just this much.
   */
  public const val HEADER_BYTE_COUNT: Int = 32

  /**
   * Reads [bytes] as an image header.
   *
   * @return what the header says, or `null` if it matches no supported container.
   */
  public fun probe(bytes: ByteArray): ImageProbeResult? = probePng(bytes)
    ?: probeJpeg(bytes)
    ?: probeGif(bytes)
    ?: probeWebP(bytes)
    ?: probeIsoBaseMedia(bytes)

  // ---------------------------------------------------------------------------------------------
  // PNG: https://www.w3.org/TR/png-3/
  // ---------------------------------------------------------------------------------------------

  private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(),
    0x50,
    0x4E,
    0x47,
    0x0D,
    0x0A,
    0x1A,
    0x0A,
  )

  private fun probePng(bytes: ByteArray): ImageProbeResult? {
    if (!bytes.startsWith(PNG_SIGNATURE)) return null
    // IHDR is required by the spec to be the first chunk, so width and height sit at a fixed
    // offset: 8 signature + 4 length + 4 type.
    if (!bytes.asciiEquals(offset = 12, text = "IHDR")) return null
    val width = bytes.beU32(16) ?: return null
    val height = bytes.beU32(20) ?: return null
    return ImageProbeResult(ImageFormat.PNG, validSizeOrNull(width, height))
  }

  // ---------------------------------------------------------------------------------------------
  // JPEG: ITU-T T.81
  // ---------------------------------------------------------------------------------------------

  private fun probeJpeg(bytes: ByteArray): ImageProbeResult? {
    if (bytes.u8(0) != 0xFF || bytes.u8(1) != 0xD8) return null

    // Walk the marker segments looking for a start-of-frame, which is the only one that carries
    // the image's dimensions. Everything before it (JFIF, Exif, quantisation tables, comments) is
    // variable-length, so there is no fixed offset to jump to.
    var index = 2
    while (index + 3 < bytes.size) {
      if (bytes.u8(index) != 0xFF) return ImageProbeResult(ImageFormat.JPEG, null)

      val marker = bytes.u8(index + 1)
      when {
        // Fill bytes: any number of 0xFF may precede a marker.
        marker == 0xFF -> {
          index++
          continue
        }

        // Standalone markers carry no length field.
        marker == 0x01 || marker in 0xD0..0xD9 -> {
          index += 2
          continue
        }
      }

      val segmentLength = bytes.beU16(index + 2) ?: return ImageProbeResult(ImageFormat.JPEG, null)
      if (segmentLength < 2) return ImageProbeResult(ImageFormat.JPEG, null)

      if (marker.isStartOfFrame()) {
        // SOF payload: precision(1) height(2) width(2) ...
        val height = bytes.beU16(index + 5) ?: return ImageProbeResult(ImageFormat.JPEG, null)
        val width = bytes.beU16(index + 7) ?: return ImageProbeResult(ImageFormat.JPEG, null)
        return ImageProbeResult(ImageFormat.JPEG, validSizeOrNull(width, height))
      }

      index += 2 + segmentLength
    }
    // A JPEG with no reachable SOF is still a JPEG; the platform decoder can say more.
    return ImageProbeResult(ImageFormat.JPEG, null)
  }

  /**
   * Whether [marker] starts a frame.
   *
   * 0xC4, 0xC8 and 0xCC sit inside the same range but are the Huffman-table, JPEG-extension and
   * arithmetic-coding-conditioning markers, and have an entirely different payload.
   */
  private fun Int.isStartOfFrame(): Boolean =
    this in 0xC0..0xCF && this != 0xC4 && this != 0xC8 && this != 0xCC

  // ---------------------------------------------------------------------------------------------
  // GIF: https://www.w3.org/Graphics/GIF/spec-gif89a.txt
  // ---------------------------------------------------------------------------------------------

  private fun probeGif(bytes: ByteArray): ImageProbeResult? {
    if (!bytes.asciiEquals(offset = 0, text = "GIF87a") &&
      !bytes.asciiEquals(offset = 0, text = "GIF89a")
    ) {
      return null
    }
    val width = bytes.leU16(6) ?: return null
    val height = bytes.leU16(8) ?: return null
    return ImageProbeResult(ImageFormat.GIF, validSizeOrNull(width, height))
  }

  // ---------------------------------------------------------------------------------------------
  // WebP: https://developers.google.com/speed/webp/docs/riff_container
  // ---------------------------------------------------------------------------------------------

  private fun probeWebP(bytes: ByteArray): ImageProbeResult? {
    if (!bytes.asciiEquals(offset = 0, text = "RIFF")) return null
    if (!bytes.asciiEquals(offset = 8, text = "WEBP")) return null

    // WebP has three incompatible ways of stating its size, one per bitstream flavour.
    val size = when {
      bytes.asciiEquals(offset = 12, text = "VP8 ") -> lossyWebPSize(bytes)
      bytes.asciiEquals(offset = 12, text = "VP8L") -> losslessWebPSize(bytes)
      bytes.asciiEquals(offset = 12, text = "VP8X") -> extendedWebPSize(bytes)
      else -> null
    }
    return ImageProbeResult(ImageFormat.WEBP, size)
  }

  /** Simple lossy: a VP8 key-frame header, whose dimensions are 14-bit fields after the sync code. */
  private fun lossyWebPSize(bytes: ByteArray): ImageSize? {
    // 20..22 frame tag, 23..25 sync code 0x9D 0x01 0x2A, 26..27 width, 28..29 height.
    if (bytes.u8(23) != 0x9D || bytes.u8(24) != 0x01 || bytes.u8(25) != 0x2A) return null
    val width = bytes.leU16(26)?.and(0x3FFF) ?: return null
    val height = bytes.leU16(28)?.and(0x3FFF) ?: return null
    return validSizeOrNull(width, height)
  }

  /** Simple lossless: 14 bits of width-1 then 14 bits of height-1, packed little-endian. */
  private fun losslessWebPSize(bytes: ByteArray): ImageSize? {
    if (bytes.u8(20) != 0x2F) return null
    val packed = bytes.leU32(21) ?: return null
    val width = (packed and 0x3FFF) + 1
    val height = ((packed ushr 14) and 0x3FFF) + 1
    return validSizeOrNull(width, height)
  }

  /** Extended: an explicit canvas size, stored as 24-bit minus-one values. */
  private fun extendedWebPSize(bytes: ByteArray): ImageSize? {
    val width = bytes.leU24(24)?.plus(1) ?: return null
    val height = bytes.leU24(27)?.plus(1) ?: return null
    return validSizeOrNull(width, height)
  }

  // ---------------------------------------------------------------------------------------------
  // HEIF / AVIF: ISO/IEC 14496-12 `ftyp`
  // ---------------------------------------------------------------------------------------------

  private val HEIF_BRANDS = setOf(
    "heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs", "mif1", "msf1",
  )
  private val AVIF_BRANDS = setOf("avif", "avis")

  private fun probeIsoBaseMedia(bytes: ByteArray): ImageProbeResult? {
    if (!bytes.asciiEquals(offset = 4, text = "ftyp")) return null

    val majorBrand = bytes.ascii(offset = 8, length = 4) ?: return null
    val brands = mutableListOf(majorBrand)

    // Compatible brands follow the 4-byte minor version and run to the end of the ftyp box. A file
    // whose major brand is a generic one (`mif1`) is routinely an AVIF that only says so here.
    val boxSize = bytes.beU32(0) ?: return null
    var index = 16
    while (index + 4 <= minOf(boxSize, bytes.size)) {
      bytes.ascii(offset = index, length = 4)?.let(brands::add)
      index += 4
    }

    val format = when {
      brands.any { it in AVIF_BRANDS } -> ImageFormat.AVIF
      brands.any { it in HEIF_BRANDS } -> ImageFormat.HEIF
      else -> return null
    }
    // Size is deliberately not parsed here; see ImageProbeResult.size.
    return ImageProbeResult(format, size = null)
  }

  // ---------------------------------------------------------------------------------------------
  // Bounds-checked readers. Every one returns null rather than throwing, so a truncated file walks
  // back out of the parser instead of crashing the picker that fed it in.
  // ---------------------------------------------------------------------------------------------

  private fun ByteArray.u8(index: Int): Int =
    if (index in indices) this[index].toInt() and 0xFF else -1

  private fun ByteArray.beU16(index: Int): Int? {
    if (index < 0 || index + 1 >= size) return null
    return (u8(index) shl 8) or u8(index + 1)
  }

  private fun ByteArray.beU32(index: Int): Int? {
    if (index < 0 || index + 3 >= size) return null
    // Widened through Long so a header claiming >2GB does not wrap to a negative Int.
    val value = (u8(index).toLong() shl 24) or (u8(index + 1).toLong() shl 16) or
      (u8(index + 2).toLong() shl 8) or u8(index + 3).toLong()
    return if (value > Int.MAX_VALUE) null else value.toInt()
  }

  private fun ByteArray.leU16(index: Int): Int? {
    if (index < 0 || index + 1 >= size) return null
    return u8(index) or (u8(index + 1) shl 8)
  }

  private fun ByteArray.leU24(index: Int): Int? {
    if (index < 0 || index + 2 >= size) return null
    return u8(index) or (u8(index + 1) shl 8) or (u8(index + 2) shl 16)
  }

  private fun ByteArray.leU32(index: Int): Int? {
    if (index < 0 || index + 3 >= size) return null
    val value = u8(index).toLong() or (u8(index + 1).toLong() shl 8) or
      (u8(index + 2).toLong() shl 16) or (u8(index + 3).toLong() shl 24)
    return if (value > Int.MAX_VALUE) null else value.toInt()
  }

  private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    for (i in prefix.indices) {
      if (this[i] != prefix[i]) return false
    }
    return true
  }

  private fun ByteArray.asciiEquals(offset: Int, text: String): Boolean {
    if (offset < 0 || offset + text.length > size) return false
    for (i in text.indices) {
      if (this[offset + i].toInt() != text[i].code) return false
    }
    return true
  }

  private fun ByteArray.ascii(offset: Int, length: Int): String? {
    if (offset < 0 || offset + length > size) return null
    val chars = CharArray(length) { (this[offset + it].toInt() and 0xFF).toChar() }
    return chars.concatToString()
  }

  /**
   * A header that claims a zero or negative dimension is describing nothing decodable, and a caller
   * that trusts it divides by zero while computing a sample size.
   */
  private fun validSizeOrNull(width: Int, height: Int): ImageSize? =
    if (width > 0 && height > 0) ImageSize(width, height) else null
}
