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

import com.github.skydoves.crayfish.decode.ImageRegion
import com.github.skydoves.crayfish.decode.ImageSize

/**
 * Where [region], measured in **oriented** space, lies on the file's own **raw** grid.
 *
 * A source carrying an Exif orientation has two coordinate spaces, and they are not the same shape:
 *
 * - **Oriented space** is the image as the user sees it. It is `CropStatus.Ready.imageSize` and
 *   every rectangle in the public API, including the crop rectangle, and a quarter turn has already
 *   swapped its width and height.
 * - **Raw space** is the file's own grid. It is what a `RegionDecoder` reads, and the only space a
 *   region it will accept can be expressed in.
 *
 * So a crop needs both directions, not one: the user's rectangle comes *down* here to be decoded,
 * and the rectangle the decoder reports goes back *up* through [toOrientedRegion] to be published.
 * Mapping down and reporting the undown rectangle is how a cropper returns a `region` that does not
 * describe the pixels attached to it.
 *
 * This is a pure coordinate change written against [ImageOrientation.applyTo]'s convention, rotate
 * clockwise then mirror, so the rectangle and the pixels cannot drift apart. Nothing is clipped: a
 * rectangle that overhangs one grid overhangs the other by as much, and clipping belongs to the
 * caller, which knows whether an overhang is a crop frame dragged to the edge or a bug.
 *
 * Everything in this file is `internal`. It is general enough to be public, but publishing it would
 * change the binary-compatibility dumps, which is the library owner's call and not this layer's.
 *
 * @param region a rectangle in the space of `transformSize(rawSize)`.
 * @param rawSize the decoder's own reported size, the file's grid before this orientation is
 *   applied. Both directions take the raw size on purpose: a caller holding a decoder has exactly
 *   that value, and a pair of functions where one of them silently wanted the oriented size instead
 *   would be wrong half the time, on quarter turns only, which is the hardest kind of wrong to see.
 */
internal fun ImageOrientation.toRawRegion(region: ImageRegion, rawSize: ImageSize): ImageRegion =
  // The oriented image is what the inverse map consumes, so it is *its* size the arithmetic
  // reflects about: transformSize(rawSize), never rawSize. On a quarter turn the two differ, and
  // the wrong one throws the rectangle off the image for any crop away from the centre.
  inverse.mapRegion(region, transformSize(rawSize))

/**
 * Where [region], measured on the file's raw grid, lands once this orientation is applied.
 *
 * The return leg of [toRawRegion]: a decoder reports the rectangle it really read, clipped and in
 * raw coordinates, while `CropResult.Success.region` promises oriented ones.
 */
internal fun ImageOrientation.toOrientedRegion(
  region: ImageRegion,
  rawSize: ImageSize,
): ImageRegion = mapRegion(region, rawSize)

/**
 * The orientation that undoes this one.
 *
 * Short because of what the eight values really are. Four of them ([ImageOrientation.isMirrored])
 * are **reflections**, the two flips plus transpose and transverse, and a reflection is its own
 * inverse however much rotation is folded into it. Of the rest only the quarter turns move, and
 * they exchange.
 *
 * "Negate the angle" gets [ImageOrientation.TRANSPOSE] and [ImageOrientation.TRANSVERSE] exactly
 * backwards, producing a crop that is upright and back-to-front: the Exif bug that survives review
 * because a suite of rotation tests cannot see it.
 */
internal val ImageOrientation.inverse: ImageOrientation
  get() = when (this) {
    ImageOrientation.ROTATE_90 -> ImageOrientation.ROTATE_270

    ImageOrientation.ROTATE_270 -> ImageOrientation.ROTATE_90

    // Every mirrored value is a reflection, so its own inverse; NORMAL and ROTATE_180 likewise.
    else -> this
  }

/**
 * [region] of an image of [sourceSize], put through this orientation's forward map.
 *
 * Written on rectangle **edges** rather than pixel indices. [ImageOrientation.applyTo] works in
 * indices and so is full of `size - 1 - index`; an edge at `i` is the same point as the left side
 * of pixel `i`, which turns each of those into `size - edge` and removes the off-by-one that
 * rectangle conversions attract. The two still agree exactly: a pixel centred at `i + 0.5` maps to
 * `size - i - 0.5`, the centre of pixel `size - 1 - i`.
 */
private fun ImageOrientation.mapRegion(region: ImageRegion, sourceSize: ImageSize): ImageRegion {
  val width = sourceSize.width
  val height = sourceSize.height

  // Rotate clockwise, in the order applyTo fixes. A quarter turn feeds the source's *height* into
  // the new horizontal axis, which is why the 90 and 270 cases are not mirror images.
  val rotated = when (rotationDegrees) {
    90 -> ImageRegion(
      left = height - region.bottom,
      top = region.left,
      right = height - region.top,
      bottom = region.right,
    )

    180 -> ImageRegion(
      left = width - region.right,
      top = height - region.bottom,
      right = width - region.left,
      bottom = height - region.top,
    )

    270 -> ImageRegion(
      left = region.top,
      top = width - region.right,
      right = region.bottom,
      bottom = width - region.left,
    )

    else -> region
  }
  if (!isMirrored) return rotated

  // Then mirror horizontally in the *rotated* frame, so the width reflected about is the target's,
  // which a quarter turn has already swapped. Mirroring first instead turns TRANSPOSE into
  // TRANSVERSE, which is why applyTo pins the order and this follows it rather than re-deriving it.
  val targetWidth = transformSize(sourceSize).width
  return ImageRegion(
    left = targetWidth - rotated.right,
    top = rotated.top,
    right = targetWidth - rotated.left,
    bottom = rotated.bottom,
  )
}
