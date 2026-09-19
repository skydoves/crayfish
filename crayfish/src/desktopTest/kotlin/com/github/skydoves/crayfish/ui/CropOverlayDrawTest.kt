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

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.skydoves.crayfish.geometry.FloatRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the overlay actually puts on the screen.
 *
 * **These are pixel assertions on a captured frame, not semantics assertions.** The guides and the
 * cut-out have no semantics (deliberately, since the whole chrome is silenced for assistive
 * technology) and inventing a test-only flag on the drawing path would mean the test agrees with
 * a hook rather than with the picture. Reading the frame back is the only check here that fails
 * when the drawing is wrong. The scene is rendered at density 1 into a 400x400 box over a plain
 * blue background, and every colour used by the overlay is chosen to be distinguishable from it.
 */
@OptIn(ExperimentalTestApi::class)
class CropOverlayDrawTest {

  @Test
  fun `the grid mode decides whether the composition guides are drawn`() {
    // The flag is flipped *after* the overlay is composed, so this also proves the draw lambda
    // re-runs on a state change that never passes through composition.
    fun guideCount(mode: CropGridMode, interacting: Boolean): Int {
      var count = 0
      runComposeUiTest {
        val state = cropStateForTest()
        showCropOverlay(state, style = guideStyle(mode))
        if (interacting) {
          state.isInteracting = true
          waitForIdle()
        }
        count = redPixelsAcrossTheMiddle(scenePixels())
      }
      return count
    }

    assertTrue(
      guideCount(CropGridMode.Always, interacting = false) >= 2,
      "CropGridMode.Always drew no guides",
    )
    assertEquals(
      0,
      guideCount(CropGridMode.Never, interacting = true),
      "CropGridMode.Never drew guides during an interaction",
    )
    assertEquals(
      0,
      guideCount(CropGridMode.OnTouch, interacting = false),
      "CropGridMode.OnTouch drew guides while nothing was being dragged",
    )
    assertTrue(
      guideCount(CropGridMode.OnTouch, interacting = true) >= 2,
      "CropGridMode.OnTouch drew no guides during an interaction",
    )
  }

  /**
   * The cut-out removes the scrim and nothing else.
   *
   * This is the test that watches `CompositingStrategy.Offscreen`. `BlendMode.Clear` applies to
   * whatever surface the drawing lands on, so without an offscreen layer to contain it the punch
   * takes the background with it: invisible on an opaque window, catastrophic on a translucent
   * one. Here the background is a flat blue drawn by an ancestor: if it is still blue inside the
   * crop rectangle, the clear stayed inside the overlay's own layer.
   */
  @Test
  fun `the cut-out clears the scrim without clearing what is behind the overlay`() =
    runComposeUiTest {
      val state = cropStateForTest()
      showCropOverlay(state, background = Color.Blue)
      val pixels = scenePixels()

      val inside = pixels[100, 100]
      assertTrue(
        inside.blue > 0.5f && inside.red < 0.2f && inside.green < 0.2f,
        "the background behind the crop rectangle was cleared away too, leaving $inside",
      )

      // And the scrim really is there outside it, or the assertion above proves nothing.
      val outside = pixels[10, 10]
      assertTrue(
        outside.blue < inside.blue,
        "no scrim outside the crop rectangle: $outside next to $inside",
      )
    }

  @Test
  fun `every crop shape cuts the scrim in its own outline`() {
    // A circle leaves the corners of the bounding box dimmed; a rectangle does not. Sampling one
    // pixel just inside a corner is enough to tell the four shapes apart.
    fun cornerIsClear(shape: CropShape): Boolean {
      var clear = false
      runComposeUiTest {
        val state = cropStateForTest()
        showCropOverlay(state, shape = shape)
        val corner = scenePixels()[52, 52]
        clear = corner.blue > 0.5f && corner.red < 0.2f
      }
      return clear
    }

    assertTrue(cornerIsClear(CropShape.Rectangle), "a rectangle left its own corner dimmed")
    assertTrue(!cornerIsClear(CropShape.Circle), "an ellipse cut out its bounding box's corner")
    assertTrue(
      !cornerIsClear(CropShape.RoundedRectangle(cornerRadius = 80.dp)),
      "an 80px corner radius did not round the corner",
    )
    assertTrue(
      !cornerIsClear(CropShape.Custom { width, height -> centredSquarePath(width, height) }),
      "a custom path was ignored in favour of the bounding rectangle",
    )
  }

  /**
   * The chrome is identical in either reading direction.
   *
   * This is the claim the overlay's KDoc makes, so it is worth a gate rather than a paragraph: the
   * crop rectangle selects pixels measured from the left edge, and a mirrored frame would advertise
   * a region the crop does not take. Comparing whole frames also catches the subtle version of the
   * bug (a `RoundRect` built from direction-aware corners, say) rather than only a flipped
   * rectangle.
   */
  @Test
  fun `the chrome does not mirror in a right-to-left layout`() {
    fun frame(direction: LayoutDirection): IntArray {
      var pixels = IntArray(0)
      runComposeUiTest {
        val state = cropStateForTest()
        // Deliberately off-centre: a rectangle centred in the viewport is its own mirror image,
        // and a frame comparison against one would pass however badly the chrome flipped.
        state.normalizedCropRect = FloatRect(0.05f, 0.1f, 0.5f, 0.85f)
        showCropOverlay(state, style = guideStyle(CropGridMode.Always), direction = direction)
        pixels = scenePixels().toArgbArray()
      }
      return pixels
    }

    val ltr = frame(LayoutDirection.Ltr)
    val rtl = frame(LayoutDirection.Rtl)
    assertTrue(ltr.isNotEmpty(), "nothing was captured")
    val differing = ltr.indices.count { ltr[it] != rtl[it] }
    assertEquals(0, differing, "$differing pixels changed when the layout direction flipped")
  }

  /**
   * The touch radius is the gesture layer's number, and it must not reach the drawing.
   *
   * One shipped cropper hit-tests at its drawn size and is unusable; the opposite mistake (drawing
   * at the 24dp hit size) is a 24dp slab of handle over the picture. Quadrupling the radius must
   * change nothing on screen.
   */
  @Test
  fun `the handle touch radius never reaches the drawing`() {
    fun frame(touchRadius: Dp): IntArray {
      var pixels = IntArray(0)
      runComposeUiTest {
        val state = cropStateForTest()
        showCropOverlay(state, style = CropStyle.Default.copy(handleTouchRadius = touchRadius))
        pixels = scenePixels().toArgbArray()
      }
      return pixels
    }

    val narrow = frame(24.dp)
    val wide = frame(96.dp)
    val differing = narrow.indices.count { narrow[it] != wide[it] }
    assertEquals(0, differing, "$differing pixels moved with handleTouchRadius")
  }

  private fun ComposeUiTest.scenePixels(): PixelMap =
    onNodeWithTag(SCENE_TAG).captureToImage().toPixelMap()

  /**
   * The frame as packed ARGB.
   *
   * `Color.value` is a `ULong` whose colour lives in the *high* bits, so `value.toInt()` keeps the
   * colour-space tag and throws the colour away: every pixel compares equal and the frame
   * comparisons above become vacuous. This cost two mutation runs to notice.
   */
  private fun PixelMap.toArgbArray(): IntArray = IntArray(VIEWPORT_PX * VIEWPORT_PX) { index ->
    this[index % VIEWPORT_PX, index / VIEWPORT_PX].toArgb()
  }

  /**
   * How many red pixels lie on the horizontal centre line, between the crop rectangle's edges.
   *
   * The centre row misses the rule-of-thirds horizontals (they are at 146 and 253) and crosses
   * both verticals, and the sampled span starts well inside the frame and the edge handles, so
   * every red pixel it can find came from a guide.
   */
  private fun redPixelsAcrossTheMiddle(pixels: PixelMap): Int =
    (60 until 340).count { x -> pixels[x, VIEWPORT_PX / 2].red > 0.25f }

  private fun guideStyle(mode: CropGridMode): CropStyle = CropStyle.Default.copy(
    gridMode = mode,
    // Red against a blue background and a black scrim: nothing else in the overlay is red.
    gridColor = Color.Red,
  )

  private fun centredSquarePath(width: Float, height: Float): Path = Path().apply {
    val inset = minOf(width, height) / 4f
    addRect(Rect(inset, inset, width - inset, height - inset))
  }
}
