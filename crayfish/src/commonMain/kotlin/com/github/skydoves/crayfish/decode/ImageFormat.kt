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
 * An encoded image container that Crayfish can read.
 *
 * Membership here says only that the format was recognised from its header bytes; whether a given
 * platform can decode it is a separate question answered by the decoder for that target. [HEIF] in
 * particular is readable on Android 28+ and Apple, and on no desktop JDK.
 */
public enum class ImageFormat {
  JPEG,
  PNG,
  WEBP,
  GIF,
  HEIF,
  AVIF,

  /**
   * Not a container at all: pixels that were already decoded when they arrived.
   *
   * What a [CropSource.Image] reports. There is no file to parse, no header to read and no
   * Exif tag to apply, and a region of it is a copy rather than a decode.
   */
  RAW,
  ;

  /**
   * Whether a region of this format can be decoded without materialising the whole image.
   *
   * This is the property that keeps a 200MP source off the 100MB rendering wall, so it gates the
   * tiled path. [GIF] is excluded because no platform exposes region decoding for it.
   */
  public val supportsRegionDecoding: Boolean
    get() = when (this) {
      JPEG, PNG, WEBP, HEIF, RAW -> true
      GIF, AVIF -> false
    }
}
