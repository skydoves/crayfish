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
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import com.github.skydoves.crayfish.decode.CropSource
import kotlin.math.roundToInt

/**
 * A [CropSource] for an image that is already on screen.
 *
 * The shortest route from a Compose image to a crop. Whatever gave you the [Painter], a network
 * loader, a bundled resource, a vector, can be cropped without ever touching a file path or a byte
 * array:
 *
 * ```kotlin
 * val painter = rememberAsyncImagePainter(url)
 * val state = rememberCropState(rememberCropSource(painter, cacheKey = url))
 * ```
 *
 * The painter is drawn into an [ImageBitmap] once, keyed on the painter and the size, and the
 * result is a [CropSource.Image]. Which is to say the whole image is resident while the cropper is
 * open. For a picture a screen is already showing, that costs nothing extra. For a camera original
 * it gives up the guarantee the rest of this library is built on, and `CropSource.FilePath` or
 * `CropSource.Loader` are the sources that keep it.
 *
 * @param cacheKey what identifies this image, and what the crop state is saved under. Use something
 *   stable across process death, such as the URL the painter is loading, rather than an identity
 *   hash of the painter.
 * @param size the pixel size to rasterise at. Defaults to the painter's own intrinsic size, which
 *   for anything backed by a bitmap is that bitmap's true resolution, so nothing is lost. A painter
 *   with no intrinsic size, such as a plain colour, has no natural answer and needs this given.
 * @return the source, or `null` when [size] is unusable: no intrinsic size and none supplied, or a
 *   size with a zero or negative side.
 */
@Composable
public fun rememberCropSource(
  painter: Painter,
  cacheKey: String,
  size: IntSize? = null,
): CropSource? {
  val density = LocalDensity.current
  val layoutDirection = LocalLayoutDirection.current
  val resolved = size ?: painter.intrinsicSize.toIntSizeOrNull()

  return remember(painter, cacheKey, resolved, density, layoutDirection) {
    val target = resolved ?: return@remember null
    if (target.width <= 0 || target.height <= 0) return@remember null

    val bitmap = ImageBitmap(target.width, target.height)
    val canvasSize = Size(target.width.toFloat(), target.height.toFloat())
    CanvasDrawScope().draw(density, layoutDirection, Canvas(bitmap), canvasSize) {
      with(painter) { draw(canvasSize) }
    }
    CropSource.Image(bitmap, cacheKey)
  }
}

/** A [CropSource] for pixels you already hold. */
@Composable
public fun rememberCropSource(image: ImageBitmap, cacheKey: String): CropSource =
  remember(image, cacheKey) { CropSource.Image(image, cacheKey) }

/** `Size.Unspecified` and the infinite sizes a painter may report have no pixel grid. */
private fun Size.toIntSizeOrNull(): IntSize? {
  if (isUnspecified) return null
  if (!width.isFinite() || !height.isFinite()) return null
  val w = width.roundToInt()
  val h = height.roundToInt()
  return if (w <= 0 || h <= 0) null else IntSize(w, h)
}
