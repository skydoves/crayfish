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

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.github.skydoves.crayfish.decode.CropSource
import com.github.skydoves.crayfish.decode.openRegionDecoder
import com.github.skydoves.crayfish.decode.readHeaderBytes
import com.github.skydoves.crayfish.decode.resolved
import com.github.skydoves.crayfish.exif.ExifReader
import com.github.skydoves.crayfish.exif.ImageOrientation
import com.github.skydoves.crayfish.geometry.FloatSize

/**
 * An image with a crop rectangle over it.
 *
 * The primitive layer: it owns the gestures, the preview and the overlay, and nothing else. There
 * is no toolbar, no confirm button and no dialog, because those are the parts every app wants to
 * style itself. [rememberImageCropper] builds the one-line version on top of this for callers who
 * do not.
 *
 * @param state hoisted from [rememberCropState], so a caller can drive the crop from a view model
 *   or a toolbar without this composable owning the truth.
 * @param overlay drawn over the image. Defaults to the standard scrim, frame, grid and handles.
 */
@Composable
public fun Cropper(
  state: CropState,
  modifier: Modifier = Modifier,
  style: CropStyle = CropStyle.Default,
  gestures: CropGestures = CropGestures.Default,
  overlay: @Composable (CropState) -> Unit = { CropOverlay(it, style = style) },
) {
  // `CropState` is sealed and `RealCropState` is its only implementation, so this cannot fail.
  // It used to be a checked cast with a runtime error, which meant a caller who wrote a fake for a
  // preview or a screenshot test got an exception instead of a compile error.
  val real = state as RealCropState

  // Opening the source is the state's job, not the caller's: it is what turns a CropSource into an
  // imageSize, and every rectangle in the API is expressed in that size's space.
  LaunchedEffect(real.source.cacheKey) {
    real.status = CropStatus.Loading
    real.closeDecoder()
    // Resolved once. A `CropSource.Loader` fetches encoded bytes, and the decoder and the Exif
    // header both need them, so leaving each to resolve on its own would fetch a network image
    // twice.
    val resolved = real.source.resolved()
    val decoder = resolved?.let { openRegionDecoder(it) }
    if (resolved == null || decoder == null) {
      real.status = CropStatus.Failed(CropResult.Failure.Reason.SourceUnreadable)
      return@LaunchedEffect
    }
    real.decoder = decoder
    val declared = decoder.imageSize
    // The decoder reports the file's own grid. The orientation it has already applied, if any, is
    // subtracted here so that `imageSize` means the same thing on every platform. The browser is
    // the one that sometimes applies the tag during decode and says so.
    val orientation = sourceOrientation(resolved)
    val outstanding = if (decoder.appliedOrientation == ImageOrientation.NORMAL) {
      orientation
    } else {
      ImageOrientation.NORMAL
    }
    real.status = CropStatus.Ready(
      imageSize = outstanding.transformSize(declared),
      orientation = outstanding,
    )

    // And nothing else. Setting `status` is one snapshot write, which is safe from any thread;
    // the opening pass is a read-modify-write of the crop rectangle and is not, so it happens in
    // the `SideEffect` below instead. This effect suspends on `sourceOrientation` and resumes
    // wherever its dispatcher leaves it, which is not the composition thread. See
    // `RealCropState.openAndConform`.
  }

  // The opening pass, on the composition thread, after every composition that leaves the source
  // ready. Idempotent, and both of its inputs (the status above and the viewport below) are
  // snapshot state, so arriving in either order ends with it having run against both.
  if (real.status is CropStatus.Ready) {
    SideEffect { real.openAndConform() }
  }

  // A region decoder holds a file handle and, on some platforms, a native buffer sized by the
  // source. Leaving composition without closing it is a leak measured in tens of megabytes.
  DisposableEffect(real) {
    onDispose { real.closeDecoder() }
  }

  // The one number the crop pipeline cannot get for itself: a `Dp` corner radius has to become
  // output pixels, and only a composable can see the density.
  real.density = LocalDensity.current.density

  Box(
    modifier = modifier
      .onSizeChanged { size ->
        val next = FloatSize(size.width.toFloat(), size.height.toFloat())
        if (next != real.viewportSize) {
          real.viewportSize = next
          // On every size change rather than only the first: the crop rectangle is stored as
          // fractions of the viewport, so a window that changes shape changes the rectangle's
          // proportions with it and a fixed ratio has to be re-applied. Layout is the composition
          // thread, which is what makes calling it here safe.
          real.openAndConform()
        }
      }
      // One finger belongs to the crop rectangle, two to the image, and by default the image has
      // nothing for them to do. When [gestures] does give the image something, the two-finger case
      // is claimed on the event the second pointer appears, before slop, because a pinch is
      // unambiguous from its first event and an ancestor pager would otherwise win the race.
      .cropGestures(real, style, gestures),
  ) {
    CropPreview(real, Modifier)
    overlay(real)
  }
}

/**
 * Reads the Exif orientation declared in the source's header.
 *
 * Bounded: only the leading bytes are read, because the tag lives near the front and reading the
 * whole file to find it would be the allocation this library exists to avoid, 1.8MB of it on the
 * 108MP fixture, before a single pixel is decoded.
 *
 * An unreadable header means [ImageOrientation.NORMAL]. That is the only safe default: rotating an
 * image that was already upright is a worse failure than leaving a rotated one alone.
 */
private suspend fun sourceOrientation(source: CropSource): ImageOrientation {
  // Pixels that arrived already decoded have had the tag applied by whatever decoded them, and
  // there is no header left to read one out of. Reading it would turn the image a second time.
  if (source is CropSource.Image) return ImageOrientation.NORMAL
  val header = source.readHeaderBytes() ?: return ImageOrientation.NORMAL
  return ExifReader.readOrientation(header)
}
