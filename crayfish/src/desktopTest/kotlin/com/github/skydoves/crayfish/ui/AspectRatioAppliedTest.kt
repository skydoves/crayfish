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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.skydoves.crayfish.decode.TestImages
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A requested aspect ratio is held by the crop rectangle, not merely by the next drag.
 *
 * The ratio used to be consulted in exactly two places, both inside the resize geometry. So
 * `rememberCropState(source, AspectRatio.Square)`, the first example in this library's own
 * documentation, opened on a rectangle that was square only if the viewport happened to be, and a
 * toolbar chip did nothing visible until a handle was dragged. Every unit test of the resize
 * geometry passed throughout, because the geometry was never the part that was wrong.
 *
 * The viewport here is deliberately **not** square (640x480). A square one makes the default
 * rectangle square by accident and every assertion below vacuous.
 */
@OptIn(ExperimentalTestApi::class)
class AspectRatioAppliedTest {

  @Test
  fun aRatioPassedAtConstructionIsHeldOnTheFirstFrame() = runComposeUiTest {
    val state = readyState(AspectRatio.Square)

    assertRatio(1f, state, "the ratio given to rememberCropState was never applied")
  }

  @Test
  fun theDefaultRectangleIsNotSquareOnItsOwn() = runComposeUiTest {
    val state = readyState(AspectRatio.Free)

    // The control. Without it, the test above would pass on a viewport that happened to be square.
    assertTrue(
      abs(state.cropRect.width / state.cropRect.height - 1f) > 0.05f,
      "the free rectangle is already square (${state.cropRect}), so the ratio tests prove nothing",
    )
  }

  @Test
  fun changingTheRatioReshapesTheRectangleImmediately() = runComposeUiTest {
    val state = readyState(AspectRatio.Free)

    listOf(
      AspectRatio.Square to 1f,
      AspectRatio.Portrait3x4 to 3f / 4f,
      AspectRatio.Landscape4x3 to 4f / 3f,
      AspectRatio.Widescreen16x9 to 16f / 9f,
      AspectRatio.Portrait9x16 to 9f / 16f,
    ).forEach { (ratio, expected) ->
      state.aspectRatio = ratio
      waitForIdle()
      assertRatio(expected, state, "selecting $ratio did not reshape the crop rectangle")
    }
  }

  /** The new rectangle stays where the old one was, rather than jumping back to the centre. */
  @Test
  fun reshapingKeepsTheRectangleWhereTheUserLeftIt() = runComposeUiTest {
    val state = readyState(AspectRatio.Free)
    state.normalizedCropRect =
      com.github.skydoves.crayfish.geometry.FloatRect(0.05f, 0.5f, 0.55f, 0.95f)
    waitForIdle()
    val before = state.cropRect
    val centerX = before.left + before.width / 2f
    val centerY = before.top + before.height / 2f

    state.aspectRatio = AspectRatio.Square
    waitForIdle()

    val after = state.cropRect
    assertRatio(1f, state, "the rectangle did not become square")
    assertTrue(
      abs((after.left + after.width / 2f) - centerX) < 2f,
      "the rectangle jumped horizontally: $before then $after",
    )
    assertTrue(
      abs((after.top + after.height / 2f) - centerY) < 2f,
      "the rectangle jumped vertically: $before then $after",
    )
  }

  /**
   * Reshaping preserves the rectangle's area rather than fitting inside it.
   *
   * The obvious rule, "the largest rectangle of the right ratio that fits inside the current one",
   * is monotonically shrinking, and the rectangle is stored as fractions of the viewport, so every
   * change of viewport shape distorts it and brings it back here to be shrunk again. Four device
   * rotations took a 480px square down to 35px with no way back. Area preservation is idempotent:
   * a rectangle that already has the ratio returns unchanged.
   */
  @Test
  fun reshapingPreservesTheRectanglesArea() = runComposeUiTest {
    val state = readyState(AspectRatio.Free)
    val before = state.cropRect
    val beforeArea = before.width * before.height

    state.aspectRatio = AspectRatio.Portrait9x16
    waitForIdle()

    val after = state.cropRect
    val afterArea = after.width * after.height
    val viewport = state.viewportSize

    // It never leaves the viewport.
    assertTrue(after.width <= viewport.width + 0.5f, "it left the viewport: $after")
    assertTrue(after.height <= viewport.height + 0.5f, "it left the viewport: $after")

    // And it is as large as that allows: either the area came through, or the viewport is what
    // stopped it. 9:16 inside a landscape viewport is the second case, and shrinking to fit is
    // correct there. What must not happen is shrinking for any other reason.
    val areaKept = abs(afterArea - beforeArea) / beforeArea < 0.02f
    val boundByViewport =
      after.width >= viewport.width - 0.5f || after.height >= viewport.height - 0.5f
    assertTrue(
      areaKept || boundByViewport,
      "the rectangle shrank from area $beforeArea to $afterArea without touching a viewport " +
        "edge, so nothing forced it: $before then $after in $viewport",
    )
  }

  /**
   * The frame does not decay when the viewport keeps changing shape.
   *
   * A device rotating back and forth, a desktop window being resized, split screen entered and
   * left. Each one re-derives the rectangle, and the measured failure was geometric: 480, 375, 250,
   * 195, 130, 101, 67, 52, 35 pixels across four rotations, ending smaller than the handles needed
   * to grab it and with no reset in the dialog to recover.
   */
  @Test
  fun theFrameDoesNotDecayAcrossRepeatedViewportChanges() = runComposeUiTest {
    var state: CropState? = null
    var portrait by mutableStateOf(true)
    setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        Box(if (portrait) Modifier.size(600.dp, 900.dp) else Modifier.size(900.dp, 600.dp)) {
          val cropState = rememberCropState(
            source = TestImages.source(TestImages.pngBytes(), key = "decay"),
            initialAspectRatio = AspectRatio.Square,
          )
          state = cropState
          Cropper(state = cropState, modifier = Modifier.fillMaxSize())
        }
      }
    }
    waitUntil(timeoutMillis = TIMEOUT) { state?.status is CropStatus.Ready }
    waitUntil(timeoutMillis = TIMEOUT) { state?.viewportSize?.isEmpty == false }
    waitForIdle()
    val cropState = assertNotNull(state)
    val opening = cropState.cropRect.width
    assertTrue(opening > 100f, "the fixture opened at ${opening}px, too small to show a decay")

    repeat(4) {
      portrait = false
      waitForIdle()
      portrait = true
      waitForIdle()
    }

    val ended = cropState.cropRect.width
    assertTrue(
      abs(ended - opening) < 1f,
      "the square frame went from ${opening}px to ${ended}px just by changing the viewport shape",
    )
    assertRatio(1f, cropState, "the ratio was lost while the viewport changed")
  }

  @Test
  fun resetLeavesAFixedRatioStillHeld() = runComposeUiTest {
    val state = readyState(AspectRatio.Square)
    state.rotateBy(30f)
    state.normalizedCropRect =
      com.github.skydoves.crayfish.geometry.FloatRect(0.2f, 0.3f, 0.9f, 0.5f)
    waitForIdle()

    state.reset()
    waitForIdle()

    assertRatio(1f, state, "reset went back to a rectangle that ignores the locked ratio")
  }

  /** Free means free: selecting it must not reshape anything. */
  @Test
  fun goingBackToFreeLeavesTheRectangleAlone() = runComposeUiTest {
    val state = readyState(AspectRatio.Square)
    val square = state.cropRect

    state.aspectRatio = AspectRatio.Free
    waitForIdle()

    // A tolerance, not equality: the rectangle is stored as fractions and read back as pixels, so
    // the round trip moves a 204.8 to a 204.79999. What must not happen is a *reshape*.
    val after = state.cropRect
    listOf(
      square.left to after.left,
      square.top to after.top,
      square.right to after.right,
      square.bottom to after.bottom,
    ).forEach { (before, now) ->
      assertTrue(
        abs(before - now) < 0.01f,
        "selecting Free moved the rectangle: $square then $after",
      )
    }
  }

  /** The image still covers the reshaped rectangle, which is the invariant every mutation owes. */
  @Test
  fun theImageStillCoversTheReshapedRectangle() = runComposeUiTest {
    val state = readyState(AspectRatio.Free)

    listOf(AspectRatio.Widescreen16x9, AspectRatio.Portrait9x16, AspectRatio.Square).forEach {
      state.aspectRatio = it
      waitForIdle()
      assertTrue(
        com.github.skydoves.crayfish.geometry.CropCoverage.covers(
          transform = state.transform,
          contentBounds = state.coordinateSpace.contentBounds,
          coverRegion = state.cropRect,
        ),
        "$it left a corner of the crop frame off the image",
      )
    }
  }

  // -----------------------------------------------------------------------------------------
  // Harness
  // -----------------------------------------------------------------------------------------

  private fun ComposeUiTest.readyState(aspectRatio: AspectRatio): RealCropState {
    var state: CropState? = null
    setContent {
      val cropState = rememberCropState(
        source = TestImages.source(TestImages.pngBytes(), key = "aspect"),
        initialAspectRatio = aspectRatio,
      )
      state = cropState
      Cropper(state = cropState, modifier = Modifier.fillMaxSize())
    }
    waitUntil(timeoutMillis = TIMEOUT) { state?.status is CropStatus.Ready }
    waitUntil(timeoutMillis = TIMEOUT) { state?.viewportSize?.isEmpty == false }
    waitForIdle()
    return assertNotNull(state) as RealCropState
  }

  private fun assertRatio(expected: Float, state: CropState, message: String) {
    val rect = state.cropRect
    assertTrue(rect.width > 1f && rect.height > 1f, "$message: the rectangle is degenerate: $rect")
    val actual = rect.width / rect.height
    assertTrue(
      abs(actual - expected) < 0.02f,
      // Everything the early returns in `conformCropRectToAspectRatio` key on, because this has
      // failed twice at a rate near one run in eight and the value alone did not say which of them
      // took. `aspectRatio` not Fixed would mean the ratio never reached the state; an empty
      // viewport would mean the conform ran before layout and nothing re-ran it.
      "$message: expected $expected but the rectangle $rect is $actual " +
        "[aspectRatio=${state.aspectRatio} viewport=${state.viewportSize} " +
        "status=${state.status} content=${state.coordinateSpace.contentBounds} " +
        "transform=${state.transform}]",
    )
  }

  private companion object {
    const val TIMEOUT = 10_000L
  }

  // -----------------------------------------------------------------------------------------
  // Who owns the ratio
  // -----------------------------------------------------------------------------------------

  /**
   * A ratio the caller sets survives the next recomposition.
   *
   * `rememberCropState` used to re-apply its parameter on every composition, and the setter reads
   * a snapshot state, so writing `state.aspectRatio = X` invalidated the composable that called
   * `rememberCropState`, which re-ran and wrote the parameter back. The chip appeared to work for
   * one frame and the lock was gone by the next.
   *
   * None of the tests above could catch it: they assert the *rectangle's shape* straight after the
   * set, and the one-shot reshape has already run by then. The rectangle stays square while the
   * lock is lost, so only reading `aspectRatio` back after a recomposition sees it.
   */
  @Test
  fun aRatioTheCallerSetsSurvivesARecomposition() = runComposeUiTest {
    var state: CropState? = null
    var tick by mutableStateOf(0)
    setContent {
      @Suppress("UNUSED_EXPRESSION")
      tick
      val cropState = rememberCropState(
        source = TestImages.source(TestImages.pngBytes(), key = "owner"),
      )
      state = cropState
      Cropper(state = cropState, modifier = Modifier.fillMaxSize())
    }
    waitUntil(timeoutMillis = TIMEOUT) { state?.status is CropStatus.Ready }
    waitForIdle()
    val cropState = assertNotNull(state)

    cropState.aspectRatio = AspectRatio.Square
    waitForIdle()
    assertEquals(
      AspectRatio.Square,
      cropState.aspectRatio,
      "the ratio was reverted on the next frame",
    )

    tick++
    waitForIdle()
    assertEquals(
      AspectRatio.Square,
      cropState.aspectRatio,
      "the ratio was reverted by an unrelated recomposition",
    )
    assertRatio(1f, cropState, "the rectangle no longer matches the ratio the caller set")
  }

  /** The parameter is an opening value, so it does not fight the caller on later compositions. */
  @Test
  fun theParameterOpensTheRatioAndThenStopsApplying() = runComposeUiTest {
    var state: CropState? = null
    var tick by mutableStateOf(0)
    setContent {
      @Suppress("UNUSED_EXPRESSION")
      tick
      val cropState = rememberCropState(
        source = TestImages.source(TestImages.pngBytes(), key = "opener"),
        initialAspectRatio = AspectRatio.Widescreen16x9,
      )
      state = cropState
      Cropper(state = cropState, modifier = Modifier.fillMaxSize())
    }
    waitUntil(timeoutMillis = TIMEOUT) { state?.status is CropStatus.Ready }
    waitForIdle()
    val cropState = assertNotNull(state)
    assertRatio(16f / 9f, cropState, "the opening ratio was never applied")

    cropState.aspectRatio = AspectRatio.Portrait9x16
    tick++
    waitForIdle()

    assertEquals(AspectRatio.Portrait9x16, cropState.aspectRatio)
    assertRatio(9f / 16f, cropState, "the parameter overwrote the caller on a later composition")
  }
}
