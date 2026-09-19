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
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.github.skydoves.crayfish.decode.TestImages
import com.github.skydoves.crayfish.geometry.CropCoverage
import com.github.skydoves.crayfish.geometry.FloatRect
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The operations a toolbar calls.
 *
 * `rotateBy`, `rotateToNearestQuarterTurn`, the two flips and `reset` are the whole public verb
 * surface of a cropper, and half their branches had never been executed.
 */
@OptIn(ExperimentalTestApi::class)
class CropStateOperationsTest {

  private fun ComposeUiTest.readyState(): CropState {
    var state: CropState? = null
    setContent {
      val cropState = rememberCropState(TestImages.source(TestImages.pngBytes()))
      state = cropState
      Cropper(state = cropState, modifier = Modifier.fillMaxSize())
    }
    waitUntil(timeoutMillis = TIMEOUT) { state?.status is CropStatus.Ready }
    return assertNotNull(state)
  }

  @Test
  fun rotatingAccumulatesAndWrapsAtAFullTurn() = runComposeUiTest {
    val state = readyState()

    state.rotateBy(90f)
    assertEquals(90f, state.transform.rotationDegrees, 0.01f)

    state.rotateBy(90f)
    assertEquals(180f, state.transform.rotationDegrees, 0.01f)

    state.rotateBy(200f)
    assertTrue(
      abs(state.transform.rotationDegrees) < 360f,
      "the rotation was not normalised: ${state.transform.rotationDegrees}",
    )
  }

  @Test
  fun snappingGoesToTheNearestQuarterTurn() = runComposeUiTest {
    val state = readyState()

    state.rotateBy(83f)
    state.rotateToNearestQuarterTurn()
    assertEquals(90f, state.transform.rotationDegrees, 0.01f)

    state.rotateBy(52f)
    state.rotateToNearestQuarterTurn()
    assertEquals(180f, state.transform.rotationDegrees, 0.01f)
  }

  @Test
  fun eachFlipTogglesIndependently() = runComposeUiTest {
    val state = readyState()

    state.toggleFlipHorizontal()
    assertTrue(state.transform.flipHorizontal)
    assertTrue(!state.transform.flipVertical, "the horizontal flip changed the vertical one")

    state.toggleFlipVertical()
    assertTrue(state.transform.flipHorizontal)
    assertTrue(state.transform.flipVertical)

    state.toggleFlipHorizontal()
    assertTrue(!state.transform.flipHorizontal)
    assertTrue(state.transform.flipVertical, "turning off one flip turned off the other")
  }

  @Test
  fun resetReturnsBothTheTransformAndTheRectangle() = runComposeUiTest {
    val state = readyState()
    val openingRect = state.cropRect

    state.rotateBy(45f)
    state.toggleFlipHorizontal()
    (state as RealCropState).normalizedCropRect = FloatRect(0.4f, 0.4f, 0.6f, 0.6f)
    assertTrue(state.cropRect != openingRect, "the fixture never changed, so reset proves nothing")

    state.reset()

    assertEquals(0f, state.transform.rotationDegrees, 0.01f)
    assertEquals(1f, state.transform.scale, 0.01f)
    assertTrue(!state.transform.flipHorizontal)
    assertEquals(openingRect, state.cropRect)
  }

  /** The image has to stay under the crop rectangle after every one of these. */
  @Test
  fun everyOperationLeavesTheCropRectangleCovered() = runComposeUiTest {
    val state = readyState()
    val space = { (state as RealCropState).coordinateSpace }

    listOf<() -> Unit>(
      { state.rotateBy(37f) },
      { state.rotateBy(-120f) },
      { state.toggleFlipHorizontal() },
      { state.toggleFlipVertical() },
      { state.rotateToNearestQuarterTurn() },
      { state.reset() },
    ).forEachIndexed { index, operation ->
      operation()
      waitForIdle()
      val covered = CropCoverage.covers(
        transform = state.transform,
        contentBounds = space().contentBounds,
        coverRegion = state.cropRect,
      )
      assertTrue(covered, "operation $index left a corner of the crop frame uncovered")
    }
  }

  private companion object {
    const val TIMEOUT = 10_000L
  }
}
