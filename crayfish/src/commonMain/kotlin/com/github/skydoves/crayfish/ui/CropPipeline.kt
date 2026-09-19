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

import com.github.skydoves.crayfish.decode.DecodeBudget
import com.github.skydoves.crayfish.decode.DecodedRegion
import com.github.skydoves.crayfish.decode.ImageFormat
import com.github.skydoves.crayfish.decode.ImageRegion
import com.github.skydoves.crayfish.decode.ImageSize
import com.github.skydoves.crayfish.decode.PlatformImage
import com.github.skydoves.crayfish.decode.RegionDecoder
import com.github.skydoves.crayfish.decode.SampleSize
import com.github.skydoves.crayfish.decode.platformImageOfArgbPixels
import com.github.skydoves.crayfish.decode.reorientPixels
import com.github.skydoves.crayfish.encode.EncodeOptions
import com.github.skydoves.crayfish.encode.encodeImage
import com.github.skydoves.crayfish.exif.ImageOrientation
import com.github.skydoves.crayfish.exif.toOrientedRegion
import com.github.skydoves.crayfish.exif.toRawRegion
import com.github.skydoves.crayfish.geometry.CropTransform
import com.github.skydoves.crayfish.geometry.FloatPoint
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/**
 * Turns the crop rectangle on screen into encoded bytes.
 *
 * Kept out of [RealCropState] because it is the one part of the cropper with no UI in it at all:
 * given a source, a region and some options it is a decode followed by an encode, which is what
 * makes it testable without standing up a composition.
 *
 * Common code, not an expect/actual seam: the platform differences are already absorbed one layer
 * down by `createRegionDecoder` and `encodeImage`, and adding a second seam here would mean four
 * copies of the same orchestration to keep in step.
 *
 * ## What this does, and what it deliberately does not
 *
 * ## The viewer's transform
 *
 * [CropTransform][com.github.skydoves.crayfish.geometry.CropTransform] is honoured in two separate
 * places, and it helps to keep them apart.
 *
 * `toImageRegion` inverts the whole transform (pan, zoom, rotation *and* mirroring) to decide
 * **which** pixels to decode, so the selection is right at every angle and under either flip. That
 * alone is not enough: the decoded rectangle is axis aligned in the *image* while the frame is axis
 * aligned in the *viewport*, and under a rotation those are different grids. So
 * `frameThroughViewerTransform` then walks the frame's own grid, asks
 * `CoordinateSpace.viewportToImage` where each point lands, and samples there. One map handles a
 * quarter turn, a free angle and either flip.
 *
 * Two consequences worth stating. A rotated crop **is** resampled, so at a free angle the output is
 * interpolated rather than a permutation of source pixels; at a quarter turn the samples land on
 * pixel centres and it reduces to a permutation. And [CropResult.Success.region] stays an axis
 * aligned rectangle of the source, which under a free angle is the *bounding box* of the tilted
 * frame rather than the frame: it describes where the bytes came from, not their shape.
 *
 * The source's own Exif orientation is applied before any of this, by `reorient`, so the two never
 * compose in the wrong order.
 */
private suspend fun cropPixels(state: RealCropState, budget: DecodeBudget): CropPixels {
  // The state already knows why a source it failed to open failed; repeating that here as a
  // generic SourceUnreadable would throw away a cause the caller could have shown.
  (state.status as? CropStatus.Failed)?.let {
    return CropPixels.Refused(CropResult.Failure(it.reason, it.cause))
  }
  val decoder = state.decoder
    ?: return CropPixels.Refused(CropResult.Failure(CropResult.Failure.Reason.SourceUnreadable))

  // The frame on screen, in the image's oriented coordinates. `toImageRegion` inverts the
  // transform, rounds to whole pixels and clips to the image, so a frame dragged off the edge
  // arrives legal and a frame dragged entirely off the image arrives as null.
  val orientedRegion = state.coordinateSpace.toImageRegion(state.cropRect)
    ?: return CropPixels.Refused(CropResult.Failure(CropResult.Failure.Reason.EmptyRegion))

  // Down onto the file's own grid, which is the only space the decoder accepts. The state's
  // rectangles live in oriented space; the decoder reads the raw one.
  val outstanding = state.outstandingOrientation(decoder)
  val rawSize = decoder.imageSize
  val rawRegion = outstanding.toRawRegion(orientedRegion, rawSize)
    .intersect(ImageRegion.of(rawSize))
    ?: return CropPixels.Refused(CropResult.Failure(CropResult.Failure.Reason.EmptyRegion))

  // A rotated or mirrored crop is turned by reading the decoded pixels into one array and writing
  // a second one, and both are alive at the same time. So the budget is split between them rather
  // than handed to each in full. Without this the framed buffer was bounded per axis and not at
  // all by bytes: a rotated crop of a 108MP source asked for a single 166MB allocation on a device
  // whose entire heap cap was 192MB, and the OutOfMemoryError escaped to the caller, which is
  // exactly the failure this library exists to prevent.
  val bakesTransform = state.transform.sanitized().baked
  val decodeBudget = if (bakesTransform) budget.halved() else budget

  // The budget applies even though the caller wants every pixel of the region. Passing the
  // region's own size as the target says "as much detail as there is", and SampleSize still
  // halves until the allocation is legal. There is no safe "give me everything": a 200MP source
  // is 768MiB decoded, and ForOutput caps that at 192MiB.
  val sampleSize = SampleSize.forDecode(
    sourceSize = rawRegion.size,
    targetSize = rawRegion.size,
    budget = decodeBudget,
  )

  // A null here is an ordinary outcome, including the platform running out of memory. But a
  // decoder cancelled mid-flight also reports null, so the job is consulted before the failure
  // is named.
  val decoded = decoder.decodeRegion(rawRegion, sampleSize)
    ?: return CropPixels.Refused(
      if (currentCoroutineContext().isActive) {
        CropResult.Failure(CropResult.Failure.Reason.DecodeFailed)
      } else {
        CropResult.Cancelled
      },
    )

  // Bring the pixels upright. Done after the decode, because only the decoded region has to be
  // turned rather than the whole source, and the region is the smaller thing by construction.
  // A failure here is reported rather than silently encoding a sideways image: an Exif-rotated
  // photo that comes out rotated is the bug this whole layer exists to prevent.
  val reoriented = if (outstanding == ImageOrientation.NORMAL) {
    null
  } else {
    reorientPixels(decoded, outstanding)
      ?: return CropPixels.Refused(
        CropResult.Failure(
          reason = CropResult.Failure.Reason.DecodeFailed,
          cause = IllegalStateException(
            "the source declares Exif orientation ${outstanding.name}, and the pixels could not " +
              "be brought upright: the platform refused either the read or the allocation",
          ),
        ),
      ).also { decoded.close() }
  }
  val uprightPixels = reoriented ?: decoded.image

  // The viewer's rotation and flips, baked into the pixels.
  //
  // `toImageRegion` already inverted the whole transform to choose *which* pixels to decode, so at
  // a quarter turn those pixels are the right ones lying on their side, and at a free angle they
  // are the axis aligned bounding box of the tilted frame, a superset. This turns them.
  val framed = frameThroughViewerTransform(state, uprightPixels, orientedRegion, sampleSize)
  if (framed == null && state.transform.baked) {
    reoriented?.close()
    decoded.close()
    return CropPixels.Refused(
      CropResult.Failure(
        reason = CropResult.Failure.Reason.DecodeFailed,
        cause = IllegalStateException(
          "the crop is rotated or mirrored and the pixels could not be turned: the platform " +
            "refused either the read or the allocation",
        ),
      ),
    )
  }
  val upright = framed ?: uprightPixels

  return CropPixels.Ready(
    image = upright,
    // What actually came back, never what was asked for. `decoded.sampleSize` may be coarser than
    // the request, because a decoder that met an OutOfMemoryError retries rather than failing, so
    // deriving this from the requested value is the bug that field exists to prevent. Read from
    // the *upright* pixels: a quarter turn swaps width and height, and reporting the pre-rotation
    // size would describe pixels that do not exist.
    size = ImageSize(width = upright.width, height = upright.height),
    // Back up into oriented space. The decoder reports raw coordinates, clipped to what it could
    // really read; the published region promises the space the caller's rectangles were in.
    region = outstanding.toOrientedRegion(decoded.region, rawSize),
    sourceFormat = decoder.format,
    decoded = decoded,
    reoriented = reoriented,
    framed = framed,
  )
}

/**
 * Turns the crop rectangle on screen into encoded bytes.
 *
 * The decode and the geometry are [cropPixels]; this is the encode and nothing else.
 */
internal suspend fun cropToBytes(
  state: RealCropState,
  options: EncodeOptions,
  budget: DecodeBudget = DecodeBudget.ForOutput,
): CropResult {
  val pixels = when (val outcome = cropPixels(state, budget)) {
    is CropPixels.Refused -> return outcome.result
    is CropPixels.Ready -> outcome
  }

  // Cut before the alpha check, not after: the mask is what puts the transparency there, and
  // checking first would ask whether the *unmasked* pixels need an alpha channel.
  val masked = if (options.format.supportsAlpha) {
    maskToShape(pixels.image, state.shape, state.cornerRadiusInOutputPixels(pixels.size))
  } else {
    // A circle written to a JPEG would come back on a background nobody chose. Documented on
    // `CropState.shape`, and the reason this is a skip rather than a refusal.
    null
  }
  val encodable = masked ?: pixels.image

  return try {
    if (wouldSilentlyFlattenAlpha(pixels.sourceFormat, options, encodable)) {
      return CropResult.Failure(
        reason = CropResult.Failure.Reason.EncodeUnsupported,
        cause = IllegalStateException(
          "the crop contains translucent pixels and ${options.format} has no alpha channel; " +
            "encoding it would flatten them onto an unspecified colour (black on Android, " +
            "whatever lies under the transparency on Skia), so the format is refused instead",
        ),
      )
    }

    // An exception is not part of `encodeImage`'s contract, but Android's Bitmap.compress has
    // thrown IllegalStateException on a bitmap freed under it, and a crash out of a function
    // whose whole point is to return failures as values would be a poor trade.
    val bytes = try {
      encodeImage(encodable, options)
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      return CropResult.Failure(CropResult.Failure.Reason.EncodeFailed, error)
    }

    // Cancellation is checked *after* the encode returns, never before. `encodeImage` bottoms out
    // in one blocking platform call (Skia's encodeToData, Android's Bitmap.compress) with no
    // suspension point inside it, so a cancelled job cannot interrupt the work, only decline the
    // result.
    //
    // This check also has to come before `bytes` is read, because both encoders report a
    // cancelled caller as a null. Reading the null first would report EncodeUnsupported for a
    // format the platform writes perfectly well.
    if (!currentCoroutineContext().isActive) return CropResult.Cancelled

    // Null is the expect declaration's documented "this platform cannot write that format":
    // lossless WebP on every Skia target, and on Android below API 30.
    if (bytes == null) return CropResult.Failure(CropResult.Failure.Reason.EncodeUnsupported)

    CropResult.Success(bytes = bytes, size = pixels.size, region = pixels.region)
  } finally {
    masked?.close()
    pixels.close()
  }
}

/**
 * Turns the crop rectangle on screen into an [ImageBitmap], with no encode step at all.
 *
 * This is the shorter path, not merely a convenience wrapper. Going through bytes to reach
 * something a `Image(bitmap = ...)` can draw means encoding a PNG and decoding it straight back,
 * which costs a second full copy of the crop and, for a lossy format, quality that cannot be
 * recovered. Nothing here touches [EncodeOptions], and alpha survives, because there is no format
 * to flatten it into.
 *
 * Reach for [cropToBytes] when the crop is going somewhere outside the process: an upload, a file,
 * a content Uri. Reach for this when it is going into the UI.
 */
internal suspend fun cropToImage(
  state: RealCropState,
  budget: DecodeBudget = DecodeBudget.ForOutput,
): CropImage {
  val pixels = when (val outcome = cropPixels(state, budget)) {
    is CropPixels.Refused -> return outcome.result.asCropImage()
    is CropPixels.Ready -> outcome
  }

  // An `ImageBitmap` always has an alpha channel, so there is no format to ask about here. Hoisted
  // out of the `try` because the `finally` has to know whether a mask was cut to release the right
  // buffers.
  val masked = maskToShape(
    image = pixels.image,
    shape = state.shape,
    cornerRadiusPx = state.cornerRadiusInOutputPixels(pixels.size),
  )
  val handedOut = masked ?: pixels.image

  return try {
    // The conversion copies on Skia and wraps on Android, and either way the result has to outlive
    // the buffers closed below, which is why it happens before the `finally` rather than after.
    val bitmap = handedOut.toImageBitmap()
      ?: return CropImage.Failure(
        reason = CropResult.Failure.Reason.DecodeFailed,
        cause = IllegalStateException(
          "the cropped pixels could not be handed to Compose: the platform refused either the " +
            "read or the allocation",
        ),
      )
    if (!currentCoroutineContext().isActive) return CropImage.Cancelled
    CropImage.Success(image = bitmap, size = pixels.size, region = pixels.region)
  } finally {
    // Whichever buffer the returned ImageBitmap wraps is the one kept; the rest go. When a mask was
    // cut, that is the mask's own buffer, and every decode buffer is released.
    if (masked != null) pixels.close() else pixels.closeAllExcept(handedOut)
  }
}

/**
 * [CropShape.RoundedRectangle]'s radius in the output's own pixels.
 *
 * Two conversions, and both are needed. `Dp` to screen pixels is the density, and screen pixels to
 * output pixels is however much the crop was scaled on its way out: a frame 400px wide on screen
 * that yields a 2000px crop has to round its corners five times as hard, or the result is a
 * rectangle with a barely visible nick in each corner.
 */
private fun RealCropState.cornerRadiusInOutputPixels(output: ImageSize): Float {
  val shape = shape as? CropShape.RoundedRectangle ?: return 0f
  val frameWidth = cropRect.width
  if (frameWidth <= 0f || output.width <= 0) return shape.cornerRadius.value * density
  return shape.cornerRadius.value * density * (output.width / frameWidth)
}

/** Maps the failure half of a [CropResult] across, so the two paths refuse for the same reasons. */
private fun CropResult.asCropImage(): CropImage = when (this) {
  is CropResult.Failure -> CropImage.Failure(reason, cause)

  CropResult.Cancelled -> CropImage.Cancelled

  // `cropPixels` never reaches a Success: it stops at the pixels, and turning them into an answer
  // is this layer's job. An exhaustive `when` is still cheaper than an assumption.
  is CropResult.Success -> CropImage.Failure(CropResult.Failure.Reason.DecodeFailed)
}

/**
 * The cropped pixels and the buffers that back them.
 *
 * Three allocations can be alive at once, each a full crop's worth: what the decoder returned, the
 * copy turned upright by the Exif tag, and the copy turned again by the viewer's own rotation. They
 * are owned together because they have to be released together, newest first, and a caller that
 * took only the image would leak the other two.
 */
private sealed interface CropPixels {

  /** The crop could not be produced, and [result] says why in the caller's own vocabulary. */
  class Refused(val result: CropResult) : CropPixels

  class Ready(
    val image: PlatformImage,
    val size: ImageSize,
    val region: ImageRegion,
    /** The container the *source* was in, which is what decides whether alpha can be encoded. */
    val sourceFormat: ImageFormat,
    private val decoded: DecodedRegion,
    private val reoriented: PlatformImage?,
    private val framed: PlatformImage?,
  ) : CropPixels {

    /**
     * Newest first, so a buffer is never held while a larger one is still alive. Closing only on
     * the success path would leak tens of megabytes per press of a button users press twice.
     */
    fun close() = closeAllExcept(null)

    /**
     * Releases every buffer except [kept], whose ownership passes to the caller.
     *
     * For [cropToImage], which hands the pixels themselves back. `PlatformImage.toImageBitmap`
     * **wraps** the platform bitmap on both Android and Skia rather than copying it, so closing the
     * buffer after handing it over recycles the very bitmap the caller is about to draw. On Android
     * that is `Canvas: trying to use a recycled bitmap` on the next frame; on Skia it is a SIGSEGV,
     * because a freed Skia bitmap does not object to being wrapped, only to being drawn.
     *
     * Transferring rather than copying, because the alternative is a second full copy of every crop
     * to avoid a close this function can simply not perform. What keeps it honest is that [kept] is
     * always one of these three, so nothing is leaked and nothing is closed twice.
     */
    fun closeAllExcept(kept: PlatformImage?) {
      if (framed !== kept) framed?.close()
      if (reoriented !== kept) reoriented?.close()
      // `DecodedRegion.close` closes its own image, so the identity compared here is that image.
      if (decoded.image !== kept) decoded.close()
    }
  }
}

/** Whether this transform changes which way the pixels face, rather than only which ones. */
private val CropTransform.baked: Boolean
  get() = rotationDegrees != 0f || flipHorizontal || flipVertical

/**
 * The pixels the crop frame encloses, turned upright.
 *
 * Pan and zoom need nothing here: they change which pixels the frame is over, and `toImageRegion`
 * has already accounted for them. Rotation and mirroring do, because the decoded rectangle is axis
 * aligned in the *image* while the frame is axis aligned in the *viewport*, and under a rotation
 * those are different grids.
 *
 * The map is one line, and it is the same one the preview draws through:
 * [CoordinateSpace.viewportToImage] inverts the whole transform, so walking the frame's own grid
 * and asking where each point lands in the image handles a quarter turn, a free angle and either
 * flip with no special case for any of them. At a quarter turn the samples land on pixel centres
 * and the bilinear weights collapse to one and zero, so it is a permutation rather than a blur.
 *
 * @return the turned pixels, or `null` both when there is nothing to turn and when the pixels could
 *   not be read. The caller tells those apart by asking the transform, because only one of them is
 *   a failure.
 */
private fun frameThroughViewerTransform(
  state: RealCropState,
  image: PlatformImage,
  region: ImageRegion,
  sampleSize: Int,
): PlatformImage? {
  val transform = state.transform.sanitized()
  if (!transform.baked) return null

  val space = state.coordinateSpace
  val crop = state.cropRect
  if (crop.isEmpty || !crop.isFinite || !space.isValid) return null

  val source = image.readArgbPixels() ?: return null
  val sourceWidth = image.width
  val sourceHeight = image.height
  if (sourceWidth <= 0 || sourceHeight <= 0) return null

  // `fitInside` preserves the aspect ratio, so one scalar carries both axes: image pixels per
  // viewport unit, undone by the zoom, then by whatever the decoder subsampled.
  val perDecoded = space.imageSize.width / space.contentBounds.width / transform.scale / sampleSize
  val width = (crop.width * perDecoded).roundToInt().coerceIn(1, MAX_FRAMED_DIMENSION)
  val height = (crop.height * perDecoded).roundToInt().coerceIn(1, MAX_FRAMED_DIMENSION)

  val out = IntArray(width * height)
  for (y in 0 until height) {
    val viewportY = crop.top + (y + 0.5f) * crop.height / height
    for (x in 0 until width) {
      val viewportX = crop.left + (x + 0.5f) * crop.width / width
      val inImage = space.viewportToImage(FloatPoint(viewportX, viewportY))
      // Into the decoded buffer's own grid: minus the region's origin, divided by the sample size,
      // and shifted half a pixel because a sample names a centre and an index names a corner.
      val sampleX = (inImage.x - region.left) / sampleSize - 0.5f
      val sampleY = (inImage.y - region.top) / sampleSize - 0.5f
      out[y * width + x] = sampleBilinear(source, sourceWidth, sourceHeight, sampleX, sampleY)
    }
  }
  return platformImageOfArgbPixels(out, ImageSize(width, height))
}

/**
 * [pixels] at ([x], [y]), interpolated, with the edges extended rather than wrapped.
 *
 * Clamping matters at the border: a frame dragged to the edge of the image samples fractionally
 * outside it, and wrapping would paint the opposite edge of the photo into that seam.
 */
private fun sampleBilinear(pixels: IntArray, width: Int, height: Int, x: Float, y: Float): Int {
  val clampedX = x.coerceIn(0f, (width - 1).toFloat())
  val clampedY = y.coerceIn(0f, (height - 1).toFloat())
  val x0 = clampedX.toInt()
  val y0 = clampedY.toInt()
  val x1 = (x0 + 1).coerceAtMost(width - 1)
  val y1 = (y0 + 1).coerceAtMost(height - 1)
  val fx = clampedX - x0
  val fy = clampedY - y0

  val topLeft = pixels[y0 * width + x0]
  val topRight = pixels[y0 * width + x1]
  val bottomLeft = pixels[y1 * width + x0]
  val bottomRight = pixels[y1 * width + x1]

  var result = 0
  for (shift in intArrayOf(24, 16, 8, 0)) {
    val a = (topLeft ushr shift) and 0xFF
    val b = (topRight ushr shift) and 0xFF
    val c = (bottomLeft ushr shift) and 0xFF
    val d = (bottomRight ushr shift) and 0xFF
    val top = a + (b - a) * fx
    val bottom = c + (d - c) * fx
    val value = (top + (bottom - top) * fy).roundToInt().coerceIn(0, 255)
    result = result or (value shl shift)
  }
  return result
}

/** Skia's own per dimension ceiling, so turning cannot produce a bitmap nothing will draw. */
private const val MAX_FRAMED_DIMENSION = 32766

/**
 * Half the bytes, the same dimensions: what each of two simultaneous buffers may have.
 *
 * A clamp on the framed buffer's own byte count was written alongside this and then removed. It
 * never fired: once the decode is budgeted at half, the framed size follows the decoded size down,
 * because it is derived from it through `perDecoded`. Turning the clamp off changed no measurement
 * on either the device or the desktop, and shipping a bound nothing reaches is shipping a claim
 * nobody has tested. If a geometry is ever found where the framed buffer outgrows the decoded one,
 * that clamp is the fix, and it belongs here.
 */
private fun DecodeBudget.halved(): DecodeBudget =
  copy(maxByteCount = (maxByteCount / 2).coerceAtLeast(1L))

/**
 * The orientation still to be applied to whatever this decoder returns.
 *
 * `RegionDecoder.appliedOrientation` is what the platform has already baked in, and on the web that
 * is not always [ImageOrientation.NORMAL]: a browser applies the Exif tag during decode and the
 * request to suppress it is not honoured everywhere. Applying the tag again on top of that
 * double-rotates the crop. So the decoder is the authority, and a decoder that has already oriented
 * its pixels leaves nothing outstanding.
 *
 * `Cropper` performs the same subtraction when it opens the source, which makes this idempotent
 * rather than redundant: a state restored or assembled some other way may not have been through
 * that path.
 *
 * A non-NORMAL result is applied rather than ignored because the alternative is pixels still on
 * the file's raw grid, which means one of two silent lies: a `region` in oriented space that does
 * not describe them, or a `region` in raw space, which is not what [CropResult.Success] documents.
 * A crop quietly a quarter turn out, or for the mirrored values back-to-front, is exactly the Exif
 * failure this library was built to end. So the orientation is spent on the decoded pixels in
 * `reorient`, and only a platform that will not hand those pixels over produces a failure.
 * `CropperExifWiringTest` covers the chain end to end on a real Exif-tagged JPEG; every piece was
 * already unit-tested in isolation and all of those stayed green with `sourceOrientation` stubbed
 * to NORMAL, so the seams are the only place this bug can now live.
 */
private fun RealCropState.outstandingOrientation(decoder: RegionDecoder): ImageOrientation =
  if (decoder.appliedOrientation == ImageOrientation.NORMAL) {
    orientation
  } else {
    ImageOrientation.NORMAL
  }

/**
 * Whether encoding [image] as [options] would drop transparency the caller cannot get back.
 *
 * **The rule: this pipeline never flattens alpha silently.** A format with no alpha channel is
 * refused for pixels that actually carry some, rather than written out over an unspecified
 * background (black on Android, whatever RGB sits under the transparency on Skia) which is the
 * black fringe that turns up around a cropped logo and cannot be undone afterwards.
 *
 * Refusing is chosen over compositing onto a caller-named background only because the caller has
 * nowhere to name one: `EncodeOptions` carries format, quality and effort, and `CropState.crop`
 * takes nothing else. Adding that field is a public API change and belongs with the API's owner.
 *
 * Alpha here is the source's own. `CropShape` belongs to the overlay, not to [CropState], so it
 * never reaches this function: a circular mask dims the preview and does not cut a hole in the
 * output.
 *
 * The check is ordered so the expensive part almost never runs: a format with an alpha channel has
 * nothing to lose; nor has a source container that cannot carry alpha at all, which is JPEG and so
 * most of what a cropper is pointed at, and that costs one enum comparison. Only then are the
 * pixels read, allocating one `IntArray` the size of the crop, on the PNG-or-WebP-to-JPEG path
 * alone.
 *
 * Pixels that cannot be read are treated as unproven and refused, which keeps the rule absolute.
 * In practice that means an Android hardware bitmap, and the decoders here ask for software memory
 * precisely so one never arrives.
 */
private fun wouldSilentlyFlattenAlpha(
  sourceFormat: ImageFormat,
  options: EncodeOptions,
  image: PlatformImage,
): Boolean {
  if (options.format.supportsAlpha) return false
  if (!sourceFormat.mayCarryAlpha) return false
  val pixels = image.readArgbPixels() ?: return true
  return pixels.any { (it ushr ALPHA_SHIFT) != OPAQUE_ALPHA }
}

/**
 * Whether this container has an alpha channel to begin with.
 *
 * Only about the format, never about a particular file: an opaque PNG is still a PNG. It exists to
 * keep the pixel scan above off the path a camera photo takes, and nothing more, so a format that
 * merely might carry alpha counts as one that does.
 */
private val ImageFormat.mayCarryAlpha: Boolean
  get() = when (this) {
    ImageFormat.PNG,
    ImageFormat.WEBP,
    ImageFormat.GIF,
    ImageFormat.HEIF,
    ImageFormat.AVIF,
    // Already decoded pixels, so alpha is whatever the caller handed over.
    ImageFormat.RAW,
    -> true

    ImageFormat.JPEG -> false
  }

private const val ALPHA_SHIFT = 24
private const val OPAQUE_ALPHA = 0xFF
