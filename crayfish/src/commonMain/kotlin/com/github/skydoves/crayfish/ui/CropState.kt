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
package com.github.skydoves.crayfish.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.github.skydoves.crayfish.decode.CropSource
import com.github.skydoves.crayfish.decode.DecodeBudget
import com.github.skydoves.crayfish.decode.ImageSize
import com.github.skydoves.crayfish.decode.RegionDecoder
import com.github.skydoves.crayfish.encode.EncodeOptions
import com.github.skydoves.crayfish.encode.EncodedFormat
import com.github.skydoves.crayfish.exif.ImageOrientation
import com.github.skydoves.crayfish.geometry.CoordinateSpace
import com.github.skydoves.crayfish.geometry.CropBounds
import com.github.skydoves.crayfish.geometry.CropCoverage
import com.github.skydoves.crayfish.geometry.CropTransform
import com.github.skydoves.crayfish.geometry.FloatPoint
import com.github.skydoves.crayfish.geometry.FloatRect
import com.github.skydoves.crayfish.geometry.FloatSize
import com.github.skydoves.crayfish.geometry.RotationSnap
import kotlin.math.sqrt

/**
 * Everything a cropper needs to remember, hoisted out of the composable that draws it.
 *
 * An interface rather than an open class, per the Compose API guidelines: the only way to obtain
 * one is [rememberCropState], which keeps the implementation free to change without breaking a
 * subclass nobody should have written.
 *
 * **State survives configuration change and process death.** That is not a refinement. Android 16
 * ignores `screenOrientation` on any display 600dp or wider, so the portrait lock that every
 * existing cropper leans on to keep its geometry simple is gone: a tablet rotates, the activity is
 * recreated, and an unsaved crop rectangle is a lost edit. What is saved is the transform, the crop
 * rectangle as fractions of the viewport, and the aspect ratio, never pixels. A decoded bitmap in
 * saved state is a `TransactionTooLargeException` waiting for a large enough photo.
 */
@Stable
public sealed interface CropState {

  /** The image being cropped. */
  public val source: CropSource

  /** How far the source has got to being usable. */
  public val status: CropStatus

  /**
   * The source's size **after** its Exif orientation is applied, or [ImageSize.Zero] until the
   * source is open. Every coordinate in this API lives in this space.
   */
  public val imageSize: ImageSize

  /** The pan, zoom, rotation and flips currently applied to the image. */
  public val transform: CropTransform

  /** The crop rectangle, in viewport coordinates. */
  public val cropRect: FloatRect

  /** The size of the area the cropper was laid out in. */
  public val viewportSize: FloatSize

  /** The proportions the crop rectangle is held to. */
  public var aspectRatio: AspectRatio

  /** Whether a gesture is in flight, which is what [CropGridMode.OnTouch] keys off. */
  public val isInteracting: Boolean

  /** Converts between viewport and image coordinates under the current [transform]. */
  public val coordinateSpace: CoordinateSpace

  /**
   * This crop written down, so it can be stored and opened again later.
   *
   * Eleven numbers and no pixels. See [CropRecipe] for what that buys and what it leaves out.
   */
  public val recipe: CropRecipe

  /**
   * The mask the crop wears, in the viewfinder **and** in the output.
   *
   * [CropOverlay] draws it and the crop cuts it out, so a circular avatar is one assignment rather
   * than two values that can disagree. The cut only happens where the result can hold the
   * transparency it needs: always for [cropToImage], and for [crop] only when the encoded format
   * has an alpha channel, since a circle written to a JPEG would come back on a background nobody
   * chose. [CropShape.Custom] is drawn but not cut; evaluating a caller's path per pixel is not
   * something this layer can do.
   *
   * Not saved across process death, unlike [aspectRatio]: [CropShape.Custom] carries a lambda, and
   * a configuration the caller re-supplies on the next composition anyway.
   */
  public var shape: CropShape

  /**
   * Decodes and encodes the cropped region.
   *
   * Suspends; the work runs off the caller's thread. Cancelling the calling scope cancels the
   * decode between steps, though not the encode itself, which is a blocking platform call with no
   * suspension point, so a cancelled job is detected after it returns rather than during.
   *
   * @param budget the ceiling on what this crop is allowed to allocate. The default caps it at
   *   192MiB, which is what makes a 200 megapixel source croppable at all. Lower it when the result
   *   is going somewhere small, such as an avatar: spending 192MiB to produce a 200 pixel thumbnail
   *   is the same waste in the other direction. A crop that is rotated or mirrored is decoded
   *   against **half** of it, because turning the pixels writes a second buffer while the first is
   *   still alive and the ceiling covers the pair. Such a crop therefore comes back smaller than
   *   the same crop untransformed; raise the budget if the extra resolution matters more than the
   *   peak.
   */

  public suspend fun crop(
    options: EncodeOptions = EncodeOptions(EncodedFormat.JPEG),
    budget: DecodeBudget = DecodeBudget.ForOutput,
  ): CropResult

  /**
   * Crops to an [androidx.compose.ui.graphics.ImageBitmap] instead of to encoded bytes.
   *
   * The one to use when the crop is going straight back into the UI, which for a Compose app is
   * most of the time:
   *
   * ```kotlin
   * when (val result = state.cropToImage()) {
   *   is CropImage.Success -> Image(bitmap = result.image, contentDescription = null)
   *   is CropImage.Cancelled -> Unit
   *   is CropImage.Failure -> showError(result.reason)
   * }
   * ```
   *
   * Shorter than [crop] rather than a wrapper around it. Going through bytes to reach something
   * drawable means encoding and immediately decoding again, which costs a second full copy of the
   * crop and, in a lossy format, quality that does not come back. There are no [EncodeOptions]
   * here because nothing is encoded, and alpha survives for the same reason.
   *
   * @param budget the same ceiling [crop] takes.
   */
  public suspend fun cropToImage(budget: DecodeBudget = DecodeBudget.ForOutput): CropImage

  /**
   * Turns the image by [degrees], clockwise, about the content centre.
   *
   * The crop rectangle stays where it is and the photo is zoomed the least amount that still fills
   * it, which is what a straighten control needs: a slider that shrank the frame on every degree
   * would never give back what it took on the way out.
   */
  public fun rotateBy(degrees: Float)

  /**
   * Turns the image **to** [degrees] rather than by them.
   *
   * What a straighten slider drives, because a slider reports where it is and not how far it has
   * moved. Feeding [rotateBy] the difference between frames accumulates the error instead.
   */
  public fun rotateTo(degrees: Float)

  /** Snaps the rotation to the nearest quarter turn. */
  public fun rotateToNearestQuarterTurn()

  public fun toggleFlipHorizontal()

  public fun toggleFlipVertical()

  /** Returns the transform and the crop rectangle to their opening state. */
  public fun reset()
}

/**
 * Creates a [CropState] for [source] that survives recomposition, configuration change and process
 * death.
 *
 * @param source what to crop. Changing it starts again with a new image.
 * @param initialAspectRatio the proportions to open on. Only the initial value: the returned
 *   [CropState] owns its [CropState.aspectRatio] afterwards, so a toolbar can set it and the
 *   choice sticks. It is saved with the rest of the state, so the user's ratio and not this
 *   parameter is what comes back after process death.
 */
@Composable
public fun rememberCropState(
  source: CropSource,
  initialAspectRatio: AspectRatio = AspectRatio.Free,
  initialRecipe: CropRecipe? = null,
): CropState {
  val state = rememberSaveable(source.cacheKey, saver = RealCropState.Saver(source)) {
    RealCropState(
      source = source,
      // The recipe's own ratio wins when there is one: it is part of the crop being restored, and
      // a caller passing both means the stored crop, opened with a default they wrote months ago.
      initialAspectRatio = initialRecipe?.aspectRatio ?: initialAspectRatio,
      restored = initialRecipe?.let {
        RestoredCropState(it.transform, it.normalizedCropRect)
      },
    )
  }
  return state
}

@Stable
internal class RealCropState(
  override val source: CropSource,
  initialAspectRatio: AspectRatio,
  restored: RestoredCropState? = null,
) : CropState {

  override var status: CropStatus by mutableStateOf(CropStatus.Loading)
    internal set

  override var transform: CropTransform by mutableStateOf(
    restored?.transform ?: CropTransform.Identity,
  )
    internal set

  override var viewportSize: FloatSize by mutableStateOf(FloatSize.Zero)
    internal set

  private var currentAspectRatio: AspectRatio by mutableStateOf(initialAspectRatio)

  /**
   * Setting this reshapes the crop rectangle, rather than waiting for the next drag.
   *
   * Consulted only inside the resize geometry, as it once was,
   * `rememberCropState(source, AspectRatio.Square)` opens on the default rectangle, square only
   * if the viewport happens to be, and a toolbar chip appears to do nothing until a handle is
   * dragged. Both are the same bug: a ratio nobody has applied yet.
   */
  override val recipe: CropRecipe
    get() = CropRecipe(
      transform = transform,
      normalizedCropRect = normalizedCropRect,
      aspectRatio = aspectRatio,
    )

  override var shape: CropShape by mutableStateOf(CropShape.Rectangle)

  /**
   * The display density, so the pipeline can turn a `Dp` corner radius into output pixels.
   *
   * Written by `Cropper`, which is the composable that has `LocalDensity`. Defaulted to 1 so a
   * state driven headlessly still produces a rounded rectangle, just one measured in pixels.
   */
  internal var density: Float = 1f

  override var aspectRatio: AspectRatio
    get() = currentAspectRatio
    set(value) {
      if (currentAspectRatio == value) return
      currentAspectRatio = value
      conformCropRectToAspectRatio()
    }

  override var isInteracting: Boolean by mutableStateOf(false)
    internal set

  /**
   * The crop rectangle as fractions of the viewport.
   *
   * Stored normalised because the viewport it was measured in may never exist again: the device
   * rotates, the window is resized, the app is restored on a different screen. A rectangle in
   * pixels would be restored into the wrong place; fractions restore into the same *relative*
   * place, which is what the user meant.
   */
  internal var normalizedCropRect: FloatRect by mutableStateOf(
    restored?.normalizedCropRect ?: DEFAULT_NORMALIZED_CROP,
  )

  override val imageSize: ImageSize
    get() = (status as? CropStatus.Ready)?.imageSize ?: ImageSize.Zero

  internal val orientation: ImageOrientation
    get() = (status as? CropStatus.Ready)?.orientation ?: ImageOrientation.NORMAL

  override val cropRect: FloatRect
    get() = FloatRect(
      left = viewportSize.width * normalizedCropRect.left,
      top = viewportSize.height * normalizedCropRect.top,
      right = viewportSize.width * normalizedCropRect.right,
      bottom = viewportSize.height * normalizedCropRect.bottom,
    )

  override val coordinateSpace: CoordinateSpace
    get() = CoordinateSpace.fitting(
      imageSize = imageSize,
      viewportSize = viewportSize,
      transform = transform,
    )

  /**
   * The open decoder, owned by this state and closed when the state leaves composition.
   *
   * A region decoder holds a file handle and, on some platforms, a native buffer sized by the
   * source. Leaking one per recomposition is how a cropper that looks fine in a demo runs a device
   * out of memory in a gallery.
   */
  internal var decoder: RegionDecoder? = null

  internal fun closeDecoder() {
    decoder?.close()
    decoder = null
  }

  override suspend fun crop(options: EncodeOptions, budget: DecodeBudget): CropResult =
    cropToBytes(state = this, options = options, budget = budget)

  override suspend fun cropToImage(budget: DecodeBudget): CropImage =
    cropToImage(state = this, budget = budget)

  override fun rotateBy(degrees: Float) {
    rotateTo(transform.rotationDegrees + degrees)
  }

  override fun rotateTo(degrees: Float) {
    transform = transform.copy(rotationDegrees = RotationSnap.normalize(degrees))
    coverFrameAfterTurning()
  }

  override fun rotateToNearestQuarterTurn() {
    transform = transform.copy(
      rotationDegrees = RotationSnap.snap(transform.rotationDegrees, thresholdDegrees = 180f),
    )
    coverFrameAfterTurning()
  }

  /**
   * Keeps the crop rectangle where it is and zooms the photo the least amount that still fills it.
   *
   * ## Why turning is the one case that does not shrink the frame
   *
   * Everywhere else the frame yields: it is the thing the user is dragging, and moving it is what
   * they asked for. A straighten control is the opposite. The user is holding a slider, the frame is
   * the composition they have already chosen, and shrinking it a little on every degree is a
   * ratchet: drag to +3 and the frame is smaller, drag back to 0 and it does not come back. A
   * minute of fiddling and their crop has quietly shrunk to nothing.
   *
   * ## Why it is recomputed rather than accumulated
   *
   * The scale is derived from the angle each time, by bisection on [CropCoverage.covers], never
   * multiplied into the one already there. That is what makes it reversible: returning the slider to
   * zero returns the photo to the fit it opened at, to the precision of the bisection, rather than
   * to wherever twenty small multiplications happened to land.
   */
  private fun coverFrameAfterTurning() {
    val space = coordinateSpace
    val frame = cropRect
    if (viewportSize.isEmpty || imageSize.width <= 0 || !space.isValid || frame.isEmpty) {
      constrainImageToViewport()
      constrainFrameToImage()
      return
    }

    val fitted = transform.copy(scale = 1f, offset = FloatPoint.Zero)
    if (CropCoverage.covers(fitted, space.contentBounds, frame)) {
      // Already covered at the fit, which a small tilt usually is: the frame opens inset a tenth of
      // the photo on every side, and that margin is what a few degrees rotates into.
      transform = fitted
      settleAfterTurning(space, frame)
      return
    }

    // The smallest scale in (1, MAXIMUM_COVER_SCALE] that covers the frame. Monotonic in the scale,
    // because a larger photo about the same centre covers everything a smaller one did, so a
    // bisection finds it rather than a search.
    var low = 1f
    var high = MAXIMUM_COVER_SCALE
    repeat(BISECTION_STEPS) {
      val mid = (low + high) / 2f
      if (CropCoverage.covers(fitted.copy(scale = mid), space.contentBounds, frame)) {
        high = mid
      } else {
        low = mid
      }
    }
    transform = fitted.copy(scale = high).sanitized()
    settleAfterTurning(space, frame)
  }

  /**
   * Clamps the transform, and lets the frame yield **only** if the zoom could not cover it.
   *
   * Calling `constrainFrameToImage` unconditionally here undoes the work above: the zoom is chosen
   * to fit this exact frame, and then the frame is shrunk anyway, so the photo ends up enlarged more
   * than the smaller frame needed. Measured as a test failure at 20 degrees, where 1.117x was
   * chosen and 1.083x would have done.
   */
  private fun settleAfterTurning(space: CoordinateSpace, frame: FloatRect) {
    constrainImageToViewport()
    if (!CropCoverage.covers(transform, space.contentBounds, frame)) constrainFrameToImage()
  }

  override fun toggleFlipHorizontal() {
    transform = transform.copy(flipHorizontal = !transform.flipHorizontal)
  }

  override fun toggleFlipVertical() {
    transform = transform.copy(flipVertical = !transform.flipVertical)
  }

  override fun reset() {
    transform = CropTransform.Identity
    normalizedCropRect = DEFAULT_NORMALIZED_CROP
    // Back over the image, not over the viewport. See `openOnTheImage`.
    opened = false
    openOnTheImage()
    // The default rectangle is a fraction of the viewport and so is square only by accident.
    // Resetting under a fixed ratio has to end with that ratio still held.
    conformCropRectToAspectRatio()
    // And then coverage, unconditionally. `conformCropRectToAspectRatio` ends here too, but only
    // for a fixed ratio - under `AspectRatio.Free` it returns before doing anything, which left
    // reset as the one mutation that could uncover the frame and not put it back.
    constrainImageToViewport()
    constrainFrameToImage()
  }

  /**
   * Whether the opening rectangle has been placed over the image yet.
   *
   * Restored state already has a rectangle the user chose, so it is never re-placed.
   */
  private var opened: Boolean = restored != null

  /**
   * The whole opening pass, as one call, to be made only from the composition thread.
   *
   * ## Why it is one function and why the thread matters
   *
   * These four steps are a read-modify-write of the crop rectangle, and they used to be four
   * separate calls made from `Cropper`'s `LaunchedEffect`. That effect suspends on `sourceOrientation`
   * before it reaches them, and a suspended effect resumes wherever its dispatcher leaves it, which
   * under the Compose test harness is an IO worker rather than the composition thread. Measured:
   * layout on `AWT-EventQueue-0`, the same effect resuming on `DefaultDispatcher-worker-1`.
   *
   * So they ran concurrently with `onSizeChanged` doing the same four on the UI thread, and the two
   * interleaved: one thread conformed the rectangle to the aspect ratio, the other reopened it to
   * the default, and whichever wrote last won. The symptom was a ratio that "was never applied",
   * about one run in eight, with every precondition for applying it satisfied.
   *
   * Called from layout and from a `SideEffect`, both of which are the composition thread, and from
   * nowhere else. Idempotent, so running it after every composition costs a few float comparisons.
   */
  internal fun openAndConform() {
    openOnTheImage()
    conformCropRectToAspectRatio()
    constrainImageToViewport()
    constrainFrameToImage()
  }

  /**
   * Puts the opening crop rectangle inside the image rather than inside the viewport.
   *
   * [DEFAULT_NORMALIZED_CROP] is a fraction of the *viewport*, because that is the space the
   * rectangle is stored in. As an opening value that is wrong whenever the image does not fill the
   * viewport, which on a phone is almost always: a 3840x2160 photo on a 1080x2340 screen fits to
   * 607 pixels of height, and a frame spanning 10% to 90% of the viewport is 1872 pixels tall. The
   * frame then encloses mostly letterbox, and the coverage invariant resolves that the only way it
   * can, by zooming the image 3x to meet it. Measured on a Galaxy S23: the cropper opened at
   * `scale = 3.08` with 26% of the photo's width selected, the image jumped under every gesture as
   * coverage re-clamped it, and the crop returned a narrow vertical slice of a landscape photo.
   *
   * So the opening rectangle is the same inset applied to the image's own fitted bounds. Coverage
   * then already holds at `scale = 1`, and what the frame encloses is what the crop returns.
   */
  internal fun openOnTheImage() {
    if (opened || viewportSize.isEmpty || imageSize.width <= 0) return
    val bounds = coordinateSpace.contentBounds
    if (bounds.isEmpty || !bounds.isFinite) return
    opened = true

    val inset = DEFAULT_CROP_INSET
    val left = bounds.left + bounds.width * inset
    val top = bounds.top + bounds.height * inset
    val right = bounds.right - bounds.width * inset
    val bottom = bounds.bottom - bounds.height * inset

    normalizedCropRect = FloatRect(
      left = left / viewportSize.width,
      top = top / viewportSize.height,
      right = right / viewportSize.width,
      bottom = bottom / viewportSize.height,
    )
  }

  /**
   * Reshapes the crop rectangle to satisfy a fixed [aspectRatio], keeping where the user put it.
   *
   * The largest rectangle of the right proportions that fits *inside* the current one, sharing its
   * centre. Shrinking rather than re-centring on the viewport is the less surprising of the two: a
   * user who has framed a face and then picks 1:1 expects the square to be around that face.
   *
   * A [AspectRatio.Free] does nothing, and so does a viewport that has not been measured: the
   * rectangle is stored as fractions of a viewport, so there is no pixel geometry to reshape yet.
   * `Cropper` calls this again the moment it has a size, which is what makes an aspect ratio passed
   * to `rememberCropState` take effect on the first frame.
   */
  internal fun conformCropRectToAspectRatio() {
    val ratio = (aspectRatio as? AspectRatio.Fixed)?.ratio ?: return
    val viewport = viewportSize
    if (viewport.isEmpty) return
    val current = cropRect
    if (current.isEmpty || !current.isFinite) return

    // Area preserving, not "the largest rectangle that fits inside the current one".
    //
    // The rectangle is stored as fractions of the viewport, so any change of viewport *shape*
    // distorts it and brings it back here. Fitting inside would then remove the excess, and the
    // removal is permanent: rotating a device four times took a 480px square down to 35px, and
    // there is no way back short of reopening the image. Preserving the area is idempotent
    // instead, because a rectangle that already has the ratio comes back unchanged
    // (w = h * r gives sqrt(w * h * r) = w), so repeating it costs nothing.
    val area = current.width * current.height
    val width = sqrt(area * ratio)
    val height = width / ratio
    if (width <= 0f || height <= 0f || !width.isFinite() || !height.isFinite()) return

    val centerX = current.left + current.width / 2f
    val centerY = current.top + current.height / 2f
    // Preserving the area can ask for a rectangle wider or taller than the viewport, so it is
    // scaled down as a whole here rather than per axis, which would break the ratio again.
    val fit = minOf(1f, viewport.width / width, viewport.height / height)
    val fittedWidth = width * fit
    val fittedHeight = height * fit

    val next = FloatRect(
      left = centerX - fittedWidth / 2f,
      top = centerY - fittedHeight / 2f,
      right = centerX + fittedWidth / 2f,
      bottom = centerY + fittedHeight / 2f,
    ).nudgedInside(FloatRect.of(viewport))
    if (next == current) return

    normalizedCropRect = FloatRect(
      left = next.left / viewport.width,
      top = next.top / viewport.height,
      right = next.right / viewport.width,
      bottom = next.bottom / viewport.height,
    )
    constrainImageToViewport()
    constrainFrameToImage()
  }

  /**
   * The rectangle the crop frame is allowed to occupy: the image as it is currently drawn.
   *
   * The invariant is "the frame is inside the image", and the frame is what yields to it. It used
   * to be the other way round, with the image moved and zoomed until it covered the frame, and that
   * is what a Galaxy S23 reported as the photo jumping under every gesture: a frame larger than the
   * drawn image cannot be covered without zooming, so the image was dragged along behind the
   * selector and snapped back on release.
   *
   * With no rotation this is exactly the drawn image. Under one, an axis aligned frame cannot reach
   * the corners of a tilted photo, so the answer is the largest centred rectangle the photo still
   * covers, found by bisection on [CropCoverage.covers] rather than by a closed form: the predicate
   * is the one the rest of the geometry already agrees with, and twenty halvings settle it to well
   * under a pixel.
   */
  internal val frameBounds: FloatRect
    get() {
      val space = coordinateSpace
      if (viewportSize.isEmpty || imageSize.width <= 0 || !space.isValid) {
        return FloatRect.of(viewportSize)
      }
      val drawn = transform.mapRect(space.contentBounds, space.pivot)
      if (drawn.isEmpty || !drawn.isFinite) return FloatRect.of(viewportSize)
      if (CropCoverage.covers(transform, space.contentBounds, drawn)) return drawn

      val centerX = drawn.left + drawn.width / 2f
      val centerY = drawn.top + drawn.height / 2f
      var low = 0f
      var high = 1f
      repeat(BISECTION_STEPS) {
        val mid = (low + high) / 2f
        val candidate = FloatRect(
          left = centerX - drawn.width * mid / 2f,
          top = centerY - drawn.height * mid / 2f,
          right = centerX + drawn.width * mid / 2f,
          bottom = centerY + drawn.height * mid / 2f,
        )
        if (CropCoverage.covers(transform, space.contentBounds, candidate)) {
          low = mid
        } else {
          high =
            mid
        }
      }
      return FloatRect(
        left = centerX - drawn.width * low / 2f,
        top = centerY - drawn.height * low / 2f,
        right = centerX + drawn.width * low / 2f,
        bottom = centerY + drawn.height * low / 2f,
      )
    }

  /**
   * Keeps the photo itself sensible: fitted or larger, and never panned off the screen.
   *
   * This is the only thing that still moves the image, and it is the constraint every photo viewer
   * has rather than anything to do with the crop frame. Without it, inverting the invariant left
   * the photo free to be flung out of the viewport and pinched away to nothing, because the frame
   * had been the only thing holding it.
   *
   * Zooming out stops at the fit, which is [CropTransform.scale] of one by construction:
   * `contentBounds` is already the fitted rectangle. Panning is clamped per axis, so an axis where
   * the photo is larger than the viewport slides until its edge meets the edge, and an axis where
   * it is smaller stays centred. That is `coerceOffset` with the viewport as the region to cover,
   * rather than the crop frame.
   */
  internal fun constrainImageToViewport() {
    if (viewportSize.isEmpty || imageSize.width <= 0) return
    val space = coordinateSpace
    if (!space.isValid) return

    val clampedScale = transform.scale.coerceAtLeast(1f)
    val scaled = if (clampedScale ==
      transform.scale
    ) {
      transform
    } else {
      transform.copy(scale = clampedScale)
    }
    val placed = scaled.copy(
      offset = CropBounds.coerceOffset(
        transform = scaled,
        contentBounds = space.contentBounds,
        coverRegion = FloatRect.of(viewportSize),
      ),
    )
    if (placed != transform) transform = placed
  }

  /**
   * Brings the crop frame back inside the image, shrinking it only as far as it has to.
   *
   * Replaces the old `recoverCoverage`, which solved the same invariant from the other side by
   * moving the image. Nothing here touches [transform], so the photo stays exactly where the
   * viewer put it.
   */
  internal fun constrainFrameToImage() {
    if (viewportSize.isEmpty || imageSize.width <= 0) return
    val bounds = frameBounds
    if (bounds.isEmpty || !bounds.isFinite) return
    val current = cropRect
    if (current.isEmpty || !current.isFinite) return

    // Shrink first, keeping the proportions so a locked aspect ratio survives, then slide inside.
    val fit = minOf(1f, bounds.width / current.width, bounds.height / current.height)
    val width = current.width * fit
    val height = current.height * fit
    val centerX = current.left + current.width / 2f
    val centerY = current.top + current.height / 2f
    val next = FloatRect(
      left = centerX - width / 2f,
      top = centerY - height / 2f,
      right = centerX + width / 2f,
      bottom = centerY + height / 2f,
    ).nudgedInside(bounds)

    if (next == current || next.isEmpty) return
    normalizedCropRect = FloatRect(
      left = next.left / viewportSize.width,
      top = next.top / viewportSize.height,
      right = next.right / viewportSize.width,
      bottom = next.bottom / viewportSize.height,
    )
  }

  internal companion object {
    /** Opens on a centred rectangle covering most of the viewport, the way every cropper does. */
    val DEFAULT_NORMALIZED_CROP: FloatRect = FloatRect(0.1f, 0.1f, 0.9f, 0.9f)

    /** How much of the image the opening rectangle leaves outside itself, per edge. */
    const val DEFAULT_CROP_INSET: Float = 0.1f

    /** Halvings used to find the largest frame a tilted photo still covers. */
    private const val BISECTION_STEPS: Int = 20

    /**
     * The ceiling on the zoom a turn may introduce to keep the frame covered.
     *
     * A frame that reaches the corners of a photo turned 45 degrees needs about 1.42x, and a
     * long thin frame more. Past this the photo is enlarged more than the crop is worth, and
     * `constrainFrameToImage` shrinking the frame is the better of the two bad answers.
     */
    private const val MAXIMUM_COVER_SCALE: Float = 4f

    /**
     * Process death and [CropRecipe] store the same eleven numbers, so they share one format.
     *
     * Two encoders for one thing is two things to keep in step, and the one that drifts is always
     * the one nobody looks at. This way the saved-state tests exercise the recipe's format too.
     */
    fun Saver(source: CropSource): Saver<RealCropState, List<Float>> = Saver(
      save = { state -> CropRecipe.encodeTo(state.recipe) },
      restore = { saved ->
        CropRecipe.decodeFrom(saved)?.let { recipe ->
          RealCropState(
            source = source,
            initialAspectRatio = recipe.aspectRatio,
            restored = RestoredCropState(recipe.transform, recipe.normalizedCropRect),
          )
        }
      },
    )
  }
}

/** What survives process death: geometry only, never pixels. */
internal class RestoredCropState(val transform: CropTransform, val normalizedCropRect: FloatRect)
