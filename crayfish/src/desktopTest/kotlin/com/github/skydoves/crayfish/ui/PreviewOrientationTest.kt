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

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import com.github.skydoves.crayfish.decode.DecodedRegion
import com.github.skydoves.crayfish.decode.ImageFormat
import com.github.skydoves.crayfish.decode.ImageRegion
import com.github.skydoves.crayfish.decode.ImageSize
import com.github.skydoves.crayfish.decode.RegionDecoder
import com.github.skydoves.crayfish.decode.platformImageOfArgbPixels
import com.github.skydoves.crayfish.exif.ImageOrientation
import com.github.skydoves.crayfish.geometry.CropTransform
import com.github.skydoves.crayfish.geometry.FloatRect
import com.github.skydoves.crayfish.geometry.FloatSize
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the preview draws for a photo whose Exif tag is not NORMAL.
 *
 * Reported from a Galaxy S23: a picked photo opened lying on its side, and cropping it came back
 * upright, so the preview and the result disagreed about which way up the photo was.
 *
 * `CropperExifWiringTest` already covers the tag reaching `CropStatus.Ready` and reaching the
 * cropped bytes. Neither touches the preview, and the preview is a second decode path: `TileStore`
 * asks the decoder for regions of its own and draws exactly what comes back. It was reading them
 * off the wrong axis and never turning them, and every one of those tests stayed green.
 *
 * The seam is that `CropState.imageSize` is the **oriented** size while `RegionDecoder` works in the
 * file's grid. For a quarter turned photo the two disagree about which axis is which, and a decoder
 * clips a region it cannot satisfy rather than refusing it, so the failure is silent.
 */
class PreviewOrientationTest {

  /** A 40x30 file that the Exif tag turns into a 30x40 photo, the shape of a portrait phone shot. */
  private val fileSize = ImageSize(40, 30)
  private val orientedSize = ImageOrientation.ROTATE_90.transformSize(fileSize)

  @Test
  fun theOrientedSizeIsNotTheFileSize() {
    // The control for everything below. On a NORMAL source the two spaces agree and every
    // assertion in this file holds for a reason that has nothing to do with the code under test.
    assertEquals(ImageSize(30, 40), orientedSize)
    assertTrue(orientedSize != fileSize)
  }

  @Test
  fun everyRegionThePreviewAsksForFitsInsideTheFile() = runTest {
    val decoder = RecordingDecoder(fileSize)
    val store = TileStore()
    store.open(decoder, orientedSize, ImageOrientation.ROTATE_90)

    store.request(request(), backgroundScope)
    store.awaitIdle()

    assertTrue(decoder.requested.isNotEmpty(), "the store never asked the decoder for anything")
    assertTrue(
      decoder.outsideTheFile().isEmpty(),
      "the preview asked for ${decoder.outsideTheFile()}, outside the file's own " +
        "${ImageRegion.of(fileSize)}",
    )
  }

  /**
   * The same store told the orientation is NORMAL, which is what this class did before the fix.
   *
   * Without this the test above could pass on a store that asks for nothing at all, and it pins
   * what the defect actually was rather than only that it is gone.
   */
  @Test
  fun callingItNormalIsWhatPutTheRegionOffTheImage() = runTest {
    val decoder = RecordingDecoder(fileSize)
    val store = TileStore()
    store.open(decoder, orientedSize, ImageOrientation.NORMAL)

    store.request(request(), backgroundScope)
    store.awaitIdle()

    assertTrue(
      decoder.outsideTheFile().isNotEmpty(),
      "asking for oriented regions of a turned photo without saying so used to read off the end " +
        "of the file, and now does not, so the test above proves nothing",
    )
  }

  /**
   * The pixels come back turned, not merely read from the right place.
   *
   * The oracle is independent of the implementation: the fixture's left half is red, and a
   * clockwise quarter turn puts a left half on top. Nothing here consults `applyTo`.
   */
  @Test
  fun theTilePixelsArriveTheRightWayUp() = runTest {
    val decoder = RecordingDecoder(fileSize)
    val store = TileStore()
    store.open(decoder, orientedSize, ImageOrientation.ROTATE_90)

    store.request(request(), backgroundScope)
    store.awaitIdle()

    val base = assertNotNull(store.baseLayer, "the base layer never decoded")
    assertEquals(
      ImageRegion.of(orientedSize),
      base.region,
      "the base tile reports a region the crop rectangle cannot be compared against",
    )

    val bitmap: ImageBitmap = assertNotNull(base.imageBitmap, "the base tile has no pixels")
    assertEquals(orientedSize.width, bitmap.width, "the tile is still on the file's grid")
    assertEquals(orientedSize.height, bitmap.height, "the tile is still on the file's grid")

    val map = bitmap.toPixelMap()
    assertEquals(
      RED,
      map[0, 0].toArgb(),
      "the top of the turned photo is not the file's left half",
    )
    assertEquals(
      BLUE,
      map[0, bitmap.height - 1].toArgb(),
      "the bottom of the turned photo is not the file's right half",
    )
  }

  private fun request() = TileRequest(
    imageSize = orientedSize,
    viewportSize = FloatSize(300f, 400f),
    transform = CropTransform.Identity,
    cropRect = FloatRect(30f, 40f, 270f, 360f),
  )
}

/**
 * Records what it was asked for and answers with the fixture: left half red, right half blue.
 *
 * Returns exactly the region it was given, at full resolution, so the shape of what comes back is
 * the shape that was read rather than something this class chose.
 */
private class RecordingDecoder(override val imageSize: ImageSize) : RegionDecoder {

  val requested = mutableListOf<ImageRegion>()

  override val format: ImageFormat = ImageFormat.JPEG
  override val appliedOrientation: ImageOrientation = ImageOrientation.NORMAL

  fun outsideTheFile(): List<ImageRegion> {
    val bounds = ImageRegion.of(imageSize)
    return requested.filterNot {
      it.left >= bounds.left && it.top >= bounds.top &&
        it.right <= bounds.right && it.bottom <= bounds.bottom
    }
  }

  override suspend fun decodeRegion(region: ImageRegion, sampleSize: Int): DecodedRegion? {
    requested += region
    val clipped = region.intersect(ImageRegion.of(imageSize)) ?: return null
    val width = clipped.width
    val height = clipped.height
    if (width <= 0 || height <= 0) return null
    val pixels = IntArray(width * height) { index ->
      if ((clipped.left + index % width) < imageSize.width / 2) RED else BLUE
    }
    val image = platformImageOfArgbPixels(pixels, ImageSize(width, height)) ?: return null
    return DecodedRegion(image = image, region = clipped, sampleSize = 1)
  }

  override fun close(): Unit = Unit
}

private const val RED = 0xFFFF0000.toInt()
private const val BLUE = 0xFF0000FF.toInt()
