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

import com.github.skydoves.crayfish.decode.ImageFormat
import com.github.skydoves.crayfish.decode.ImageProbe

/**
 * Reads the orientation an encoded image declares about itself.
 *
 * This is pure common Kotlin with no platform calls, because there is no platform to call.
 * `androidx.exifinterface` is Android-only, and Skia's `Image.makeFromEncoded` ignores the tag
 * outright. So an image that is upright on Android arrives on its side everywhere else unless
 * something in common code reads the tag and [ImageOrientation.applyTo] puts the pixels where
 * they belong.
 *
 * Every parser below is total and bounds-checked. Truncated downloads, zero-byte placeholders and
 * files whose extension lies are ordinary traffic on a photo picker, and none of them may surface
 * as an exception.
 */
public object ExifReader {

  /**
   * Reads [bytes] and returns the orientation it declares.
   *
   * @return the declared orientation, or [ImageOrientation.NORMAL] when the bytes carry none, are
   *   truncated, are of a format that cannot carry one, or are not an image at all.
   *
   *   **Unreadable means [ImageOrientation.NORMAL], always.** Any other default rotates images that
   *   were already correct, and a wrongly rotated photo is a far worse failure than a rotated one
   *   left alone: the first breaks files that worked, the second leaves a pre-existing problem
   *   where it was. [ImageOrientation.fromExifValue] already encodes the same choice for tag values
   *   outside 1..8.
   */
  public fun readOrientation(bytes: ByteArray): ImageOrientation =
    when (ImageProbe.probe(bytes)?.format) {
      ImageFormat.JPEG -> readJpegOrientation(bytes)

      ImageFormat.HEIF, ImageFormat.AVIF -> readHeifOrientation(bytes)

      ImageFormat.WEBP -> readWebPOrientation(bytes)

      // Already decoded pixels. There is no header here, and whatever decoded them has
      // applied the tag already; reading one would turn the image a second time.
      ImageFormat.RAW -> ImageOrientation.NORMAL

      // PNG and GIF carry no orientation worth reading. GIF has no metadata block for one at all,
      // and while PNG's third edition does define an `eXIf` chunk, no camera writes one and no
      // platform decoder here honours one, so looking for it would add a parser with no file to
      // read. Deliberately omitted rather than overlooked.
      ImageFormat.PNG, ImageFormat.GIF -> ImageOrientation.NORMAL

      // Not a container this library recognises, which includes every truncated or corrupt file
      // whose signature never arrived.
      null -> ImageOrientation.NORMAL
    }

  // ---------------------------------------------------------------------------------------------
  // JPEG: ITU-T T.81, with the Exif block in `APP1` per CIPA DC-008.
  // ---------------------------------------------------------------------------------------------

  /**
   * Walks the marker segments to the `APP1` segment whose payload begins with the Exif identifier.
   *
   * The walk is unavoidable. Segments are variable-length and a camera may write JFIF, ICC profiles
   * and an XMP `APP1` before the Exif one, so there is no fixed offset to jump to. The XMP segment
   * in particular means "the first `APP1`" is the wrong segment on a large share of real files.
   * Only the payload's identifier settles it.
   */
  private fun readJpegOrientation(bytes: ByteArray): ImageOrientation {
    var index = 2 // Past the SOI.
    while (index + 3 < bytes.size) {
      if (bytes.u8(index) != 0xFF) return ImageOrientation.NORMAL

      val marker = bytes.u8(index + 1)
      when {
        // Any number of 0xFF fill bytes may precede a marker.
        marker == 0xFF -> {
          index++
          continue
        }

        // Standalone markers carry no length field to step over.
        marker == 0x01 || marker in 0xD0..0xD9 -> {
          index += 2
          continue
        }

        // Start of scan: entropy-coded image data follows, and 0xFF inside it is not a marker.
        // Exif always precedes the first scan, so there is nothing left to find.
        marker == 0xDA -> return ImageOrientation.NORMAL
      }

      val segmentLength = bytes.beU16(index + 2) ?: return ImageOrientation.NORMAL
      if (segmentLength < 2) return ImageOrientation.NORMAL

      val payload = index + 4
      if (marker == 0xE1 && bytes.asciiEquals(payload, EXIF_IDENTIFIER)) {
        readTiffOrientation(bytes, payload + EXIF_IDENTIFIER.length)?.let { return it }
      }
      index += 2 + segmentLength
    }
    return ImageOrientation.NORMAL
  }

  // ---------------------------------------------------------------------------------------------
  // WebP: https://developers.google.com/speed/webp/docs/riff_container
  // ---------------------------------------------------------------------------------------------

  /**
   * Walks the RIFF chunks for the `EXIF` chunk.
   *
   * Only an extended (`VP8X`) WebP may carry one, but the chunk walk finds it without having to
   * check the flag bits first: a simple WebP has no `EXIF` chunk to find, and a file whose
   * flag bits and chunks disagree is better served by trusting the chunk that is actually
   * there.
   */
  private fun readWebPOrientation(bytes: ByteArray): ImageOrientation {
    var index = 12 // Past "RIFF", the file size, and "WEBP".
    while (index + 8 <= bytes.size) {
      val chunkType = bytes.ascii(index, 4) ?: return ImageOrientation.NORMAL
      val chunkSize = bytes.leU32(index + 4) ?: return ImageOrientation.NORMAL
      val payload = index + 8

      if (chunkType == "EXIF") {
        // The container spec says the chunk holds the Exif metadata itself, so the TIFF header is
        // usually the first byte of the payload. Writers that copied their JPEG code emit the
        // six-byte identifier first, and both shapes are in circulation.
        val tiffHeader = if (bytes.asciiEquals(payload, EXIF_IDENTIFIER)) {
          payload + EXIF_IDENTIFIER.length
        } else {
          payload
        }
        readTiffOrientation(bytes, tiffHeader)?.let { return it }
      }

      // Chunks are padded to an even length, and the pad byte is not counted by the size field.
      val next = payload + chunkSize + (chunkSize and 1L)
      if (next > bytes.size) return ImageOrientation.NORMAL
      index = next.toInt()
    }
    return ImageOrientation.NORMAL
  }
}
