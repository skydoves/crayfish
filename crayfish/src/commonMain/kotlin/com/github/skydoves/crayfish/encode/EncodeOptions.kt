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
 * How to write the cropped pixels out.
 *
 * @param format the container to write.
 * @param lossyQuality fidelity for [EncodedFormat.JPEG] and [EncodedFormat.WEBP_LOSSY], 0..100.
 *   Ignored by the lossless formats, where it would be meaningless rather than merely unused.
 * @param losslessEffort how hard [EncodedFormat.WEBP_LOSSLESS] should work to compress, 0..100.
 *   This is encoder *effort*, not fidelity: the output is identical either way, only the file size
 *   and the time taken change. It shares a slot with quality in most platform APIs, which is
 *   exactly why it is named separately here.
 */
public data class EncodeOptions(
  public val format: EncodedFormat,
  public val lossyQuality: Int = 90,
  public val losslessEffort: Int = 80,
) {
  init {
    require(lossyQuality in 0..100) { "lossyQuality must be 0..100, was $lossyQuality" }
    require(losslessEffort in 0..100) { "losslessEffort must be 0..100, was $losslessEffort" }
  }

  /** The single integer a platform encoder wants, chosen by what [format] actually does with it. */
  public val platformQuality: Int
    get() = when (format) {
      EncodedFormat.JPEG, EncodedFormat.WEBP_LOSSY -> lossyQuality

      EncodedFormat.WEBP_LOSSLESS -> losslessEffort

      // PNG ignores it entirely; sending 100 keeps platform defaults from varying.
      EncodedFormat.PNG -> 100
    }
}
