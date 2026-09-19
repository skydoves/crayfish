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
 * The transformed content and the region it must cover, both viewed from a frame rotated by
 * `-rotationDegrees` so that the content is axis-aligned again.
 *
 * This is uCrop's `isImageWrapCropBounds()` trick, and it is the reason [CropBounds] and
 * [CropCoverage] are rectangle arithmetic instead of a polygon-containment routine. Un-rotating
 * both by the same angle makes the content axis-aligned again, since rotation was the only step
 * that tilted it, so the question collapses to comparing four numbers against four numbers.
 *
 * @property image the transformed content, exactly, because un-rotating undoes the only
 *   non-axis-aligned step. Scale, mirroring and translation all survive into it.
 * @property cover the axis-aligned bounding box of the region that must stay covered, which is now
 *   the tilted one. Taking its bounding box is a *conservative* simplification: satisfying it
 *   always covers the region, and can ask for slightly more than strictly necessary away from a
 *   right angle. That is the right way to err: over-coverage costs a slightly larger image, while
 *   under-coverage produces transparent corners in the exported crop.
 */
internal class UnrotatedFrame(
  val image: FloatRect,
  val cover: FloatRect,
  val cos: Float,
  val sin: Float,
)

/**
 * Builds the [UnrotatedFrame] for [transform] applied to [contentBounds], against [coverRegion].
 *
 * Both rectangles are un-rotated about the content's centre, which is the pivot [CropTransform]
 * transforms about. Any common centre would do for a containment test; using the pivot keeps the
 * numbers small and means the translation recovered by [toViewportOffset] needs no further
 * correction.
 */
internal fun unrotatedFrame(
  transform: CropTransform,
  contentBounds: FloatRect,
  coverRegion: FloatRect,
): UnrotatedFrame {
  val pivot = contentBounds.center
  val cos = cosDegrees(transform.rotationDegrees)
  val sin = sinDegrees(transform.rotationDegrees)

  return UnrotatedFrame(
    image = FloatRect.bounding(
      transform.mapCorners(contentBounds, pivot).map { unrotateAbout(it, pivot, cos, sin) },
    ),
    cover = FloatRect.bounding(
      coverRegion.corners().map { unrotateAbout(it, pivot, cos, sin) },
    ),
    cos = cos,
    sin = sin,
  )
}

/**
 * Converts a translation measured in the un-rotated frame back into a change to
 * [CropTransform.offset].
 *
 * [CropTransform.offset] is applied outside the rotation, so un-rotating a transformed corner
 * leaves `R⁻¹ · offset` in the result. Shifting the un-rotated rectangle by `d` therefore means
 * adding `R · d` to the offset.
 */
internal fun UnrotatedFrame.toViewportOffset(
  currentOffset: FloatPoint,
  unrotatedTranslation: FloatPoint,
): FloatPoint = currentOffset + rotateAbout(unrotatedTranslation, FloatPoint.Zero, cos, sin)
