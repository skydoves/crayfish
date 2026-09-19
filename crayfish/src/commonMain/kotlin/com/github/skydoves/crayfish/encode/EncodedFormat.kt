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
package com.github.skydoves.crayfish.encode

/**
 * A container to write cropped pixels into.
 *
 * Lossy and lossless WebP are separate entries because their quality parameters mean different
 * things; see [EncodeOptions]. Treating them as one setting is a recurring source of confusion.
 */
public enum class EncodedFormat {
  JPEG,
  PNG,
  WEBP_LOSSY,
  WEBP_LOSSLESS,
  ;

  /** Whether this format can represent transparent pixels. */
  public val supportsAlpha: Boolean
    get() = when (this) {
      PNG, WEBP_LOSSY, WEBP_LOSSLESS -> true
      JPEG -> false
    }
}
