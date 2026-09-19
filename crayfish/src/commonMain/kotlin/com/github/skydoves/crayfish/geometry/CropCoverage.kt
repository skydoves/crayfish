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
package com.github.skydoves.crayfish.geometry

/**
 * What [CropCoverage.correct] had to change to bring the crop frame back inside the image.
 *
 * The two flags are not decoration: a UI layer animates a translation and a zoom differently, and
 * the difference between "the image slid" and "the image grew" is the difference between a
 * correction the user does not notice and one they do.
 */
public data class CoverageCorrection(
  public val transform: CropTransform,
  /** Whether [CropTransform.offset] had to move. */
  public val translated: Boolean,
  /** Whether [CropTransform.scale] had to grow, which only happens when translation cannot win. */
  public val scaled: Boolean,
)

/**
 * Keeps the crop frame fully covered by the rotated image.
 *
 * A port of uCrop's `CropImageView.isImageWrapCropBounds()` and the `WrapCropBounds` correction
 * that follows it. Three steps, in this order:
 *
 * 1. **Test in the un-rotated frame.** Un-rotate both the image's corners and the crop rectangle by
 *    `-angle` and do an axis-aligned containment test. Rotation was the only thing tilting the
 *    image, so a hard polygon-containment problem becomes a trivial one. See [UnrotatedFrame].
 * 2. **Try translation first.** Most corrections after a small rotation or an over-pan are a few
 *    pixels of slide, and sliding is invisible. Reaching for the scale first is what makes a
 *    cropper's image pulse in size on every nudge of the rotation wheel.
 * 3. **Only then grow the scale, and only by the deficit**, which is uCrop's formula verbatim:
 *    `deltaScale = max(rotatedCropRect.width / imageSides[0], rotatedCropRect.height /`
 *    `imageSides[1])`. That is the smallest factor that lets step 2 succeed, so step 2 is re-run
 *    to place it.
 *
 * What uCrop does not have, and is added here, is [RotationSnap]: its rotate wheel is purely
 * proportional, so nothing pulls a nearly-upright image to upright.
 */
public object CropCoverage {

  /**
   * Slack, in viewport units, that [correct] leaves beyond the crop frame.
   *
   * A correction is computed in the un-rotated frame and applied in the viewport frame, so it makes
   * two round trips through a rotation. Each costs a few float ulps, on the order of a thousandth
   * of a pixel at realistic viewport sizes, and without slack a correction can land a hair short
   * and leave [covers] reporting `false` immediately after [correct] ran. A twentieth of a pixel is
   * an order of magnitude more than that error and is below anything a display can resolve.
   */
  public const val CORRECTION_SLACK: Float = 0.05f

  /**
   * Whether [coverRegion] is entirely covered by [contentBounds] under [transform].
   *
   * @param tolerance how far outside the image an edge of [coverRegion] may stray and still count
   *   as covered. Zero, the default, is the honest question; a caller polling this every frame
   *   against a value it did not compute itself may want a fraction of a pixel of give.
   * @return `true` when there is nothing to cover, since a degenerate [coverRegion] asks for
   *   nothing, and `false` when [contentBounds] is degenerate and so covers nothing.
   */
  public fun covers(
    transform: CropTransform,
    contentBounds: FloatRect,
    coverRegion: FloatRect,
    tolerance: Float = 0f,
  ): Boolean {
    if (!isUsable(coverRegion)) return true
    if (!isUsable(contentBounds)) return false

    val frame = unrotatedFrame(transform.sanitized(), contentBounds, coverRegion)
    val give = if (tolerance.isFinite() && tolerance > 0f) tolerance else 0f
    return frame.image.inflate(give).contains(frame.cover)
  }

  /**
   * [transform], adjusted as little as possible so that [coverRegion] is covered.
   *
   * @param contentBounds the image's untransformed rectangle in viewport coordinates, as produced
   *   by [fitInside]. Its centre is the pivot everything turns about.
   * @param coverRegion the crop frame, in viewport coordinates.
   * @return the unchanged transform with both flags `false` when nothing needed doing, when either
   *   rectangle is degenerate, or when the arithmetic would not be finite.
   */
  public fun correct(
    transform: CropTransform,
    contentBounds: FloatRect,
    coverRegion: FloatRect,
  ): CoverageCorrection {
    val current = transform.sanitized()
    val unchanged = CoverageCorrection(current, translated = false, scaled = false)
    if (!isUsable(contentBounds) || !isUsable(coverRegion)) return unchanged

    // Step 1. Already covered, so leave the user's transform exactly as they left it.
    if (covers(current, contentBounds, coverRegion)) return unchanged

    // Step 2. Translation first. `coerceOffset` returns an alignment rather than a cover when the
    // image is too small for one, so the result is re-tested rather than assumed.
    val target = coverRegion.inflate(CORRECTION_SLACK)
    val translated = current.copy(
      offset = CropBounds.coerceOffset(current, contentBounds, target),
    )
    if (covers(translated, contentBounds, coverRegion)) {
      return CoverageCorrection(translated, translated = true, scaled = false)
    }

    // Step 3. Translation cannot cover it, so the image is genuinely too small. Grow it by the
    // deficit and no more, then place it with step 2's clamp.
    val frame = unrotatedFrame(current, contentBounds, target)
    if (frame.image.isEmpty || !frame.image.isFinite) return unchanged

    val deltaScale = maxOf(
      frame.cover.width / frame.image.width,
      frame.cover.height / frame.image.height,
    )
    if (!deltaScale.isFinite()) {
      // Arithmetic that has stopped making sense. Keep the translation rather than scaling by a
      // factor that means nothing.
      return CoverageCorrection(translated, translated = true, scaled = false)
    }

    // Never shrink: reaching this point means some axis is short, and `deltaScale` is the largest
    // of the per-axis ratios, so clamping at 1 only matters when float noise at the boundary makes
    // both axes look adequate, in which case the re-placement below is simply step 2 again.
    val grown = current.copy(scale = current.scale * maxOf(deltaScale, 1f))
    val placed = grown.copy(offset = CropBounds.coerceOffset(grown, contentBounds, target))
    return CoverageCorrection(
      transform = placed,
      translated = placed.offset != current.offset,
      scaled = placed.scale != current.scale,
    )
  }

  private fun isUsable(rect: FloatRect): Boolean = rect.isFinite && !rect.isEmpty
}
