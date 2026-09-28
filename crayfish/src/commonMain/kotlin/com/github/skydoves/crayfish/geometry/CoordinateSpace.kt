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

import com.github.skydoves.crayfish.decode.ImageRegion
import com.github.skydoves.crayfish.decode.ImageSize
import kotlin.math.roundToInt

/**
 * The bridge between the two coordinate spaces a cropper lives in.
 *
 * - **Viewport space** is continuous, origin at the viewport's top-left, and is where gestures,
 *   the crop frame and everything else the user touches are measured.
 * - **Image space** is the image's own pixels, origin at its top-left, and is what a region decoder
 *   will accept.
 *
 * Turning an on-screen crop frame into a decodable [ImageRegion] means undoing a fit, a scale, a
 * rotation, a mirroring and a pan, in the right order. At a call site that is four or five
 * multiplications that look plausible whichever way round they are, so it lives here once.
 *
 * @property imageSize the image's pixel dimensions, after any EXIF orientation has been applied.
 *   This type knows nothing about orientation and will happily map into an upside-down image if
 *   handed one.
 * @property contentBounds where the untransformed image sits in the viewport, normally from
 *   [fitInside]. Its centre is the pivot, per [CropTransform].
 * @property transform the user's current zoom, pan, rotation and mirroring.
 */
public data class CoordinateSpace(
  public val imageSize: ImageSize,
  public val contentBounds: FloatRect,
  public val transform: CropTransform,
) {

  /**
   * The point every transform turns about.
   *
   * For content laid out by [fitInside] this is also the viewport's centre, which is why
   * [CropTransform.scaledAround] can be handed either and get the same answer.
   */
  public val pivot: FloatPoint get() = contentBounds.center

  /** Whether the image and its bounds are real enough for the mappings below to mean anything. */
  public val isValid: Boolean
    get() = imageSize.width > 0 && imageSize.height > 0 &&
      contentBounds.isFinite && !contentBounds.isEmpty

  /**
   * Maps a pixel of the image onto the viewport: fit it into [contentBounds], then transform it.
   *
   * @return [FloatPoint.Zero] when this space is degenerate, so a not-yet-measured layout produces
   *   no NaN.
   */
  public fun imageToViewport(point: FloatPoint): FloatPoint {
    if (!isValid) return FloatPoint.Zero
    val safe = point.sanitized()
    val local = FloatPoint(
      x = contentBounds.left + safe.x * contentBounds.width / imageSize.width,
      y = contentBounds.top + safe.y * contentBounds.height / imageSize.height,
    )
    return transform.mapPoint(local, pivot)
  }

  /**
   * Maps a point of the viewport back onto a pixel of the image: the inverse of [imageToViewport].
   *
   * The result is not clamped to the image: a crop frame dragged past the edge of a zoomed-out
   * image legitimately maps to negative pixels, and silently clamping here would hide that from
   * [CropCoverage], which is the thing that is supposed to notice.
   */
  public fun viewportToImage(point: FloatPoint): FloatPoint {
    if (!isValid) return FloatPoint.Zero
    val local = transform.unmapPoint(point.sanitized(), pivot)
    return FloatPoint(
      x = (local.x - contentBounds.left) * imageSize.width / contentBounds.width,
      y = (local.y - contentBounds.top) * imageSize.height / contentBounds.height,
    )
  }

  /**
   * The axis-aligned bounds, in image space, of a viewport rectangle's four mapped corners.
   *
   * At a rotation that is not a multiple of 90 degrees this is larger than the rectangle the user
   * drew, because an upright crop of a tilted image is not an upright region of the source. Getting
   * the tilted pixels themselves takes a rotate-then-crop on the decoded bitmap; this rectangle is
   * what has to be decoded to have them all in hand.
   */
  public fun viewportToImage(rect: FloatRect): FloatRect {
    if (!isValid || !rect.isFinite) return FloatRect.Zero
    return FloatRect.bounding(rect.corners().map { viewportToImage(it) })
  }

  /**
   * The one call this type exists for: an on-screen crop frame as a region a decoder will accept.
   *
   * Edges are rounded to the nearest pixel rather than expanded outwards, so a frame the user
   * dragged to a given size yields a region of that size instead of one that creeps a pixel wider
   * on each interaction. The result is then clipped to the image, because platform region decoders
   * disagree about out-of-bounds rectangles: some clamp, some throw, some return padded pixels.
   *
   * @return `null` when the frame lies entirely off the image, or when this space is degenerate.
   */
  public fun toImageRegion(viewportRect: FloatRect): ImageRegion? {
    if (!isValid || !viewportRect.isFinite || viewportRect.isEmpty) return null

    val bounds = viewportToImage(viewportRect)
    if (!bounds.isFinite) return null

    return ImageRegion(
      left = bounds.left.roundToInt(),
      top = bounds.top.roundToInt(),
      right = bounds.right.roundToInt(),
      bottom = bounds.bottom.roundToInt(),
    ).intersect(ImageRegion.of(imageSize))
  }

  public companion object {

    /**
     * A space for [imageSize] letterboxed inside a viewport of [viewportSize].
     *
     * Fitting rather than filling is what makes the content's centre coincide with the viewport's,
     * which is the assumption the pivot convention rests on.
     */
    public fun fitting(
      imageSize: ImageSize,
      viewportSize: FloatSize,
      transform: CropTransform = CropTransform.Identity,
    ): CoordinateSpace = CoordinateSpace(
      imageSize = imageSize,
      contentBounds = fitInside(imageSize.toFloatSize(), FloatRect.of(viewportSize)),
      transform = transform,
    )
  }
}

/**
 * [CoordinateSpace.viewportToImage] for a loop over every pixel of a crop.
 *
 * The same arithmetic in the same order, so each answer is bit for bit the point-based one, which
 * `ViewportToImageTest` holds it to. Only what does not depend on the point is hoisted: the
 * sanitised transform, the pivot and two trig calls. The point-based path paid those, plus five
 * `FloatPoint` allocations, on every call.
 */
internal class ViewportToImage(space: CoordinateSpace) {
  private val valid = space.isValid
  private val offsetX: Float
  private val offsetY: Float
  private val pivotX: Float
  private val pivotY: Float
  private val cos: Float
  private val sin: Float
  private val scaleX: Float
  private val scaleY: Float
  private val left = space.contentBounds.left
  private val top = space.contentBounds.top
  private val boundsWidth = space.contentBounds.width
  private val boundsHeight = space.contentBounds.height
  private val imageWidth = space.imageSize.width
  private val imageHeight = space.imageSize.height

  init {
    val transform = space.transform.sanitized()
    val pivot = space.pivot.sanitized()
    offsetX = transform.offset.x
    offsetY = transform.offset.y
    pivotX = pivot.x
    pivotY = pivot.y
    cos = cosDegrees(transform.rotationDegrees)
    sin = sinDegrees(transform.rotationDegrees)
    scaleX = transform.signedScaleX
    scaleY = transform.signedScaleY
  }

  fun x(viewportX: Float, viewportY: Float): Float {
    if (!valid) return 0f
    val dx = finite(viewportX) - offsetX - pivotX
    val dy = finite(viewportY) - offsetY - pivotY
    val unrotated = pivotX + dx * cos + dy * sin
    val local = pivotX + (unrotated - pivotX) / scaleX
    return (local - left) * imageWidth / boundsWidth
  }

  fun y(viewportX: Float, viewportY: Float): Float {
    if (!valid) return 0f
    val dx = finite(viewportX) - offsetX - pivotX
    val dy = finite(viewportY) - offsetY - pivotY
    val unrotated = pivotY - dx * sin + dy * cos
    val local = pivotY + (unrotated - pivotY) / scaleY
    return (local - top) * imageHeight / boundsHeight
  }

  private fun finite(value: Float): Float = if (value.isFinite()) value else 0f
}
