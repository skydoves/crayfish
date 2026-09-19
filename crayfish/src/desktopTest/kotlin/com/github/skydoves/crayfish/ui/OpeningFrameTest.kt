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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.skydoves.crayfish.decode.CropSource
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Where the crop frame sits when the cropper opens.
 *
 * Reported from a Galaxy S23 and reproduced on it: a 3840x2160 photo on a 1080x2340 screen fits to
 * 607 pixels of height, and the opening frame spanned 10% to 90% of the **viewport**, which is 1872
 * pixels. The frame therefore enclosed mostly letterbox, and the coverage invariant resolved that
 * the only way it could, by zooming the image to `scale = 3.08` to meet it. Measured before the fix:
 *
 * ```
 * contentBounds = y 866..1474      cropRect = y 234..2106
 * transform     = scale 3.0816462  region   = 26% of the image's width
 * ```
 *
 * Three symptoms, one cause: the photo opened blown up and panned, every gesture snapped the scale
 * back as coverage re-clamped it, and the crop returned a narrow vertical slice of a landscape
 * photo rather than the frame's contents.
 *
 * These viewports are deliberately nothing like the image's shape. A fixture whose image and
 * viewport share an aspect ratio cannot show this at all, which is why the desktop suite missed it:
 * the test window is 1024x768 and the fixtures are 4:3.
 */
@OptIn(ExperimentalTestApi::class)
class OpeningFrameTest {

  @Test
  fun aLandscapePhotoOnAPortraitPhoneOpensUnzoomed() = runComposeUiTest {
    val state = readyState(viewportW = 1080, viewportH = 2340, imageW = 3840, imageH = 2160)

    assertTrue(
      state.transform.scale in 0.99f..1.01f,
      "the cropper opened at ${state.transform.scale}x; the frame is enclosing letterbox and " +
        "coverage is zooming the image to meet it",
    )
  }

  @Test
  fun aPortraitPhotoOnALandscapeWindowOpensUnzoomed() = runComposeUiTest {
    val state = readyState(viewportW = 2340, viewportH = 1080, imageW = 2160, imageH = 3840)

    assertTrue(state.transform.scale in 0.99f..1.01f, "opened at ${state.transform.scale}x")
  }

  /** The frame opens inside the photo, never over the bars beside it. */
  @Test
  fun theOpeningFrameIsInsideTheImage() = runComposeUiTest {
    listOf(
      intArrayOf(1080, 2340, 3840, 2160),
      intArrayOf(2340, 1080, 2160, 3840),
      intArrayOf(1080, 2340, 2000, 2000),
    ).forEach { (vw, vh, iw, ih) ->
      val state = readyState(vw, vh, iw, ih)
      val bounds = state.coordinateSpace.contentBounds
      val crop = state.cropRect

      assertTrue(
        crop.left >= bounds.left - 1f && crop.right <= bounds.right + 1f &&
          crop.top >= bounds.top - 1f && crop.bottom <= bounds.bottom + 1f,
        "on a ${vw}x$vh viewport with a ${iw}x$ih image the frame $crop is outside the image $bounds",
      )
    }
  }

  /**
   * The frame encloses most of the photo, rather than a sliver of it.
   *
   * The control for the assertions above: a frame collapsed to nothing would also be "inside the
   * image" and would also need no zoom.
   */
  @Test
  fun theOpeningFrameSelectsMostOfThePhoto() = runComposeUiTest {
    val state = readyState(viewportW = 1080, viewportH = 2340, imageW = 3840, imageH = 2160)

    val region = assertNotNull(state.coordinateSpace.toImageRegion(state.cropRect))
    val fraction = region.width.toFloat() / state.imageSize.width

    assertTrue(
      fraction > 0.7f,
      "the opening frame selects only ${(fraction * 100).toInt()}% of the photo's width",
    )
  }

  private fun ComposeUiTest.readyState(
    viewportW: Int,
    viewportH: Int,
    imageW: Int,
    imageH: Int,
  ): CropState {
    var state: CropState? = null
    setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        Box(Modifier.size(viewportW.dp, viewportH.dp)) {
          val s = rememberCropState(photo(imageW, imageH))
          state = s
          Cropper(state = s, modifier = Modifier.fillMaxSize())
        }
      }
    }
    waitUntil(timeoutMillis = 10_000) { state?.status is CropStatus.Ready }
    waitUntil(timeoutMillis = 10_000) { state?.viewportSize?.isEmpty == false }
    waitForIdle()
    return assertNotNull(state)
  }

  private fun photo(w: Int, h: Int): CropSource {
    val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    graphics.color = java.awt.Color(0x20, 0x80, 0xC0)
    graphics.fillRect(0, 0, w, h)
    graphics.dispose()
    val out = ByteArrayOutputStream()
    ImageIO.write(image, "png", out)
    return CropSource.Bytes(out.toByteArray(), "$w-$h")
  }
}
