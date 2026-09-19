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
 * Clamps panning so the content keeps covering the region it is supposed to cover.
 *
 * The region to clamp against is the content's **fitted rectangle inside the viewport**, never the
 * viewport itself. A container-relative clamp assumes the content exactly fills its container,
 * true only when the image's aspect ratio matches the viewport's. Once an image is letterboxed, or
 * the crop frame is deliberately smaller than the viewport (the normal case for a fixed output
 * ratio), such a clamp either lets the image slide off an edge or refuses perfectly legal pans.
 *
 * Per axis: content **at least as large** as the region it must cover has a legal interval of
 * offsets, and the answer is the nearest point in it; content that is **smaller** cannot cover the
 * region at any offset, so alignment takes over.
 *
 * Writing only the first branch is the common bug, and its symptom is specific: zoom out past fit
 * and the image sticks to whichever corner the clamp last pushed it towards instead of settling
 * back to the middle.
 */
public object CropBounds {

  /** Centred on both axes, which is what content too small to fill the crop frame wants. */
  public val CenterAlignment: FloatPoint = FloatPoint(0.5f, 0.5f)

  /**
   * The offset [transform] should carry so that [contentBounds], once transformed, covers
   * [coverRegion].
   *
   * Works at any rotation: the comparison happens in the frame of [unrotatedFrame], where the
   * transformed content is axis-aligned again, and the resulting translation is rotated back into
   * viewport space at the end.
   *
   * @param contentBounds the content's untransformed rectangle in viewport coordinates, i.e. what
   *   [fitInside] produced. Its centre is the pivot, per [CropTransform].
   * @param coverRegion the crop frame, in viewport coordinates, which must stay covered.
   * @param alignmentBias where to place content too small to cover, as a fraction of the slack on
   *   each axis: `0f` flush to the left/top edge, `1f` to the right/bottom, [CenterAlignment]
   *   in the middle.
   * @return the coerced offset, or [CropTransform.offset] unchanged when either rectangle is
   *   degenerate and there is nothing meaningful to clamp against.
   */
  public fun coerceOffset(
    transform: CropTransform,
    contentBounds: FloatRect,
    coverRegion: FloatRect,
    alignmentBias: FloatPoint = CenterAlignment,
  ): FloatPoint {
    val safeTransform = transform.sanitized()
    if (!isUsable(contentBounds) || !isUsable(coverRegion)) return safeTransform.offset

    val frame = unrotatedFrame(safeTransform, contentBounds, coverRegion)
    val bias = alignmentBias.sanitized()
    val translation = FloatPoint(
      x = axisCorrection(
        imageMin = frame.image.left,
        imageMax = frame.image.right,
        coverMin = frame.cover.left,
        coverMax = frame.cover.right,
        bias = bias.x,
      ),
      y = axisCorrection(
        imageMin = frame.image.top,
        imageMax = frame.image.bottom,
        coverMin = frame.cover.top,
        coverMax = frame.cover.bottom,
        bias = bias.y,
      ),
    )
    return frame.toViewportOffset(safeTransform.offset, translation)
  }

  /**
   * How far the content must move along one axis, in the un-rotated frame.
   *
   * @return zero when the axis is already covered. That case must not perturb the offset at all:
   *   a clamp that nudges by an ulp every frame is a clamp that drifts.
   */
  private fun axisCorrection(
    imageMin: Float,
    imageMax: Float,
    coverMin: Float,
    coverMax: Float,
    bias: Float,
  ): Float {
    val imageSpan = imageMax - imageMin
    val coverSpan = coverMax - coverMin

    return if (imageSpan >= coverSpan) {
      // Larger than the region: both edges can be satisfied at once, so any translation in
      // [coverMax - imageMax, coverMin - imageMin] is legal. Take the one nearest to standing
      // still. min/max rather than the interval as written, because at imageSpan == coverSpan the
      // two ends can invert by an ulp and `coerceIn` throws on an empty range.
      val lower = coverMax - imageMax
      val upper = coverMin - imageMin
      0f.coerceIn(minOf(lower, upper), maxOf(lower, upper))
    } else {
      // Smaller than the region: no translation covers it, so clamping has nothing to say. Align
      // within the slack instead. Skipping this branch is what leaves zoomed-out content pinned to
      // a corner.
      val target = coverMin + (coverSpan - imageSpan) * bias.coerceIn(0f, 1f)
      target - imageMin
    }
  }

  private fun isUsable(rect: FloatRect): Boolean = rect.isFinite && !rect.isEmpty
}
