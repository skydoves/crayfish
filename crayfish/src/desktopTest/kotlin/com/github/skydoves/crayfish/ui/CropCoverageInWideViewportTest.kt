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
import com.github.skydoves.crayfish.decode.TestImages
import com.github.skydoves.crayfish.geometry.CropCoverage
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The image has to cover the crop frame in a viewport shaped nothing like it.
 *
 * Found in a screenshot of the running web demo, not in a test: a 4:3 photograph in a wide, short
 * browser window sat letterboxed in the middle while the crop frame - which is stored as fractions
 * of the *viewport* - stretched across the empty gutters on both sides. The frame no longer said
 * what pressing Crop would select.
 *
 * Every existing coverage assertion missed it because their fixtures are 4:3 images in 4:3
 * viewports, where the content fills the viewport and coverage holds no matter what the code does.
 * The viewports here deliberately disagree with the image on both axes and by a wide margin.
 */
@OptIn(ExperimentalTestApi::class)
class CropCoverageInWideViewportTest {

  @Test
  fun aTallImageInAWideViewportStillCoversTheCropFrame() = runComposeUiTest {
    assertCovered(widthDp = 1248, heightDp = 330, "the shape of the published web screenshot")
  }

  @Test
  fun aWideViewportOfEveryAwkwardShapeStillCoversTheCropFrame() = runComposeUiTest {
    listOf(
      1248 to 330,
      1600 to 200,
      300 to 900,
      200 to 1600,
      1000 to 1000,
    ).forEach { (width, height) ->
      assertCovered(width, height, "a ${width}x$height viewport")
    }
  }

  /** The same, after the operations a toolbar offers, which each re-derive the transform. */
  @Test
  fun coverageSurvivesEveryToolbarActionInAWideViewport() = runComposeUiTest {
    val state = readyState(1248, 330)

    listOf<Pair<String, () -> Unit>>(
      "rotate 90" to { state.rotateBy(90f) },
      "rotate 37" to { state.rotateBy(37f) },
      "flip horizontal" to { state.toggleFlipHorizontal() },
      "flip vertical" to { state.toggleFlipVertical() },
      "snap" to { state.rotateToNearestQuarterTurn() },
      "reset" to { state.reset() },
      "1:1" to { state.aspectRatio = AspectRatio.Square },
      "16:9" to { state.aspectRatio = AspectRatio.Widescreen16x9 },
      "9:16" to { state.aspectRatio = AspectRatio.Portrait9x16 },
    ).forEach { (name, action) ->
      action()
      waitForIdle()
      assertCovers(state, "$name left the crop frame off the image")
    }
  }

  // -----------------------------------------------------------------------------------------
  // Harness
  // -----------------------------------------------------------------------------------------

  private fun ComposeUiTest.assertCovered(widthDp: Int, heightDp: Int, what: String) {
    val state = readyState(widthDp, heightDp)
    assertCovers(state, "$what leaves the crop frame off the image")
  }

  private fun assertCovers(state: RealCropState, message: String) {
    val bounds = state.coordinateSpace.contentBounds
    val rect = state.cropRect
    assertTrue(
      CropCoverage.covers(
        transform = state.transform,
        contentBounds = bounds,
        coverRegion = rect,
        // A tenth of a pixel: the rectangle round-trips through normalised fractions, and a
        // rounding error is not an uncovered corner.
        tolerance = 0.1f,
      ),
      "$message\n  crop frame  = $rect\n  image sits at = $bounds (transform ${state.transform})",
    )
  }

  private fun ComposeUiTest.readyState(widthDp: Int, heightDp: Int): RealCropState {
    var state: CropState? = null
    setContent {
      // Density 1 so the dp above are pixels and the numbers in a failure are readable.
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        Box(Modifier.size(widthDp.dp, heightDp.dp)) {
          val cropState = rememberCropState(
            TestImages.source(TestImages.pngBytes(), key = "$widthDp-$heightDp"),
          )
          state = cropState
          Cropper(state = cropState, modifier = Modifier.fillMaxSize())
        }
      }
    }
    waitUntil(timeoutMillis = TIMEOUT) { state?.status is CropStatus.Ready }
    waitUntil(timeoutMillis = TIMEOUT) { state?.viewportSize?.isEmpty == false }
    waitForIdle()
    return assertNotNull(state) as RealCropState
  }

  private companion object {
    const val TIMEOUT = 10_000L
  }
}
