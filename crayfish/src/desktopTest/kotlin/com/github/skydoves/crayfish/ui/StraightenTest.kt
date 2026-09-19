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

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.github.skydoves.crayfish.decode.TestImages
import com.github.skydoves.crayfish.geometry.CropCoverage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Straightening: the frame stays, the photo turns under it and grows enough to keep filling it.
 *
 * The rule everywhere else in this class is the opposite, and deliberately so. The frame yields
 * when the user is dragging it, because moving it is what they asked for. A straighten slider is
 * the one control where the frame is the thing being preserved: it is the composition they already
 * chose, and taking a slice off it on every degree is a ratchet they cannot undo by sliding back.
 */
@OptIn(ExperimentalTestApi::class)
class StraightenTest {

  /**
   * The defect this exists to prevent, stated as a test rather than as a comment.
   *
   * Out to five degrees and back to zero, one degree at a time. The old behaviour shrank the frame
   * on each step and never grew it again, so the round trip cost the user their crop.
   */
  @Test
  fun slidingOutAndBackLeavesTheCropWhereItStarted() = runComposeUiTest {
    val state = readyState()
    val before = state.cropRect

    (1..5).forEach { state.rotateTo(it.toFloat()) }
    (4 downTo 0).forEach { state.rotateTo(it.toFloat()) }

    val after = state.cropRect
    assertEquals(before.left, after.left, 0.5f, "the frame drifted left over a slider round trip")
    assertEquals(before.top, after.top, 0.5f)
    assertEquals(before.right, after.right, 0.5f)
    assertEquals(before.bottom, after.bottom, 0.5f)
    assertEquals(
      1f,
      state.transform.scale,
      absoluteTolerance = 1e-3f,
      message = "back at zero degrees the photo is still zoomed to ${state.transform.scale}",
    )
  }

  @Test
  fun aTiltedPhotoStillFillsTheFrame() = runComposeUiTest {
    val state = readyState()

    listOf(3f, 7f, 15f, 30f, 45f, -12f).forEach { angle ->
      state.rotateTo(angle)
      assertTrue(
        CropCoverage.covers(state.transform, state.coordinateSpace.contentBounds, state.cropRect),
        "at $angle degrees the frame reaches past the photo, so the crop would have empty corners",
      )
    }
  }

  /**
   * It grows only as much as it has to, and not at all when it does not have to.
   *
   * A small tilt needs no zoom: the frame opens inset a tenth of the photo on every side, so there
   * is margin to rotate into before the corners reach anything. The first version of this test
   * assumed 10 degrees must enlarge the photo and was simply wrong about the geometry.
   *
   * The property that does hold at every angle is minimality, and it is checked directly: at the
   * chosen scale the frame is covered, and a hair below it is not.
   */
  @Test
  fun turningZoomsTheLeastItCanGetAwayWith() = runComposeUiTest {
    val state = readyState()

    listOf(5f, 20f, 45f).forEach { angle ->
      state.rotateTo(angle)
      val scale = state.transform.scale
      val bounds = state.coordinateSpace.contentBounds

      assertTrue(
        CropCoverage.covers(state.transform, bounds, state.cropRect),
        "at $angle degrees the chosen scale $scale does not cover the frame",
      )
      if (scale > 1.001f) {
        assertTrue(
          !CropCoverage.covers(
            state.transform.copy(scale = scale * 0.97f),
            bounds,
            state.cropRect,
          ),
          "at $angle degrees ${scale}x was more zoom than the frame needed",
        )
      }
    }

    state.rotateTo(45f)
    assertTrue(
      state.transform.scale > 1f,
      "a 45 degree tilt needed no enlargement at all, which no frame this size can manage",
    )
    assertTrue(
      state.transform.scale < 2f,
      "45 degrees enlarged the photo to ${state.transform.scale}x, which is far too much",
    )
  }

  @Test
  fun rotateByIsRotateToWithTheDifferenceAlreadyAdded() = runComposeUiTest {
    val byDelta = readyState()
    byDelta.rotateBy(12f)
    byDelta.rotateBy(-4f)

    val absolute = readyState()
    absolute.rotateTo(8f)

    assertEquals(absolute.transform.rotationDegrees, byDelta.transform.rotationDegrees, 1e-3f)
    assertEquals(absolute.transform.scale, byDelta.transform.scale, 1e-3f)
  }

  /** A quarter turn is still a quarter turn, and a square-ish frame needs no zoom for one. */
  @Test
  fun aQuarterTurnIsUnaffected() = runComposeUiTest {
    val state = readyState()
    state.rotateTo(90f)

    assertEquals(90f, state.transform.rotationDegrees, absoluteTolerance = 1e-3f)
    assertTrue(
      CropCoverage.covers(state.transform, state.coordinateSpace.contentBounds, state.cropRect),
    )
  }

  @Test
  fun theAngleSurvivesARecipeRoundTrip() = runComposeUiTest {
    val state = readyState()
    state.rotateTo(6.5f)
    val recipe = assertNotNull(CropRecipe.decodeFromString(state.recipe.encodeToString()))

    assertTrue(abs(recipe.transform.rotationDegrees - 6.5f) < 1e-3f)
  }

  private fun androidx.compose.ui.test.ComposeUiTest.readyState(): RealCropState {
    var held: CropState? = null
    setContent {
      val state = rememberCropState(TestImages.source(TestImages.pngBytes(), key = "straighten"))
      held = state
      Cropper(state = state, modifier = Modifier.fillMaxSize())
    }
    waitUntil(timeoutMillis = 10_000) { held?.status is CropStatus.Ready }
    waitUntil(timeoutMillis = 10_000) { held?.viewportSize?.isEmpty == false }
    waitForIdle()
    return assertNotNull(held) as RealCropState
  }
}
