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
 * The ceiling a single decoded bitmap must stay under.
 *
 * Two independent limits, routinely confused for one:
 *
 * - **Bytes.** Android's hardware-accelerated canvas refuses to draw a bitmap past a device
 *   property that defaults to roughly 100MB, throwing `Canvas: trying to draw too large bitmap`.
 *   A 108MP phone photo is 412MiB decoded and a 200MP one is 768MiB, so both are unconditionally
 *   undrawable at full resolution.
 * - **Dimensions.** Separately, uploading a bitmap as a GPU texture fails past `GL_MAX_TEXTURE_SIZE`,
 *   which is still 4096 on the largest share of shipping devices and 2048 on some. This bites on
 *   perfectly ordinary photos: a 3120x4160 portrait is only 50MB but exceeds a 4096 cap.
 *
 * Both have to be checked, because neither implies the other.
 */
public data class DecodeBudget(
  /** The most bytes a decoded ARGB_8888 bitmap may occupy. */
  public val maxByteCount: Long,
  /** The largest either dimension of a decoded bitmap may be. */
  public val maxDimension: Int,
) {
  init {
    require(maxByteCount > 0) { "maxByteCount must be positive, was $maxByteCount" }
    require(maxDimension > 0) { "maxDimension must be positive, was $maxDimension" }
  }

  public companion object {
    /**
     * For anything that will be drawn on screen.
     *
     * 4096 is the most common `GL_MAX_TEXTURE_SIZE` across shipping devices, and 64MiB is exactly
     * what a 4096x4096 ARGB_8888 bitmap costs, so the two limits agree rather than one quietly
     * overriding the other. Both sit well under the ~100MB canvas ceiling, leaving room for the
     * rest of the app's bitmaps.
     */
    public val ForDisplay: DecodeBudget = DecodeBudget(
      maxByteCount = 64L * 1024 * 1024,
      maxDimension = 4096,
    )

    /**
     * For the pixels that will be encoded and handed back, which are never uploaded as a texture.
     *
     * The dimension cap is Skia's hard per-side limit (`MAXIMUM_BITMAP_SIZE`); past it no bitmap
     * can exist at all. The byte cap is generous because the buffer is transient (decoded,
     * encoded, released) but it is still a cap: a crop of a 200MP source is downsampled rather
     * than allowed to allocate 768MiB.
     */
    public val ForOutput: DecodeBudget = DecodeBudget(
      maxByteCount = 192L * 1024 * 1024,
      maxDimension = 32_766,
    )
  }
}
