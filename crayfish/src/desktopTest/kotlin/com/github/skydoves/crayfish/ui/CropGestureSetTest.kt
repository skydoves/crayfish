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

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.skydoves.crayfish.decode.CropSource
import com.github.skydoves.crayfish.decode.ImageSize
import com.github.skydoves.crayfish.exif.ImageOrientation
import com.github.skydoves.crayfish.geometry.CropTransform
import com.github.skydoves.crayfish.geometry.FloatSize
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Which gestures reach the image, and which do not.
 *
 * `CropGestureTest` mounts with [CropGestures.All] and asks what each gesture does. This file asks
 * the prior question: whether it runs at all. The two are separate because the answer here is a
 * default that ships, and a default is not covered by a suite that overrides it everywhere.
 *
 * Every negative below is paired with the same gesture under a set that enables it. A test that can
 * only report "nothing moved" cannot tell a working switch apart from a touch injection that missed
 * the node.
 */
@OptIn(ExperimentalTestApi::class)
class CropGestureSetTest {

  // ---------------------------------------------------------------------------------------------
  // The default: the image is furniture
  // ---------------------------------------------------------------------------------------------

  /**
   * The reported bug. Pinching and turning two fingers rotated the photo under the frame, and
   * nothing in the demo's UI had asked for a rotation.
   */
  @Test
  fun aTwistDoesNotTurnTheImageByDefault() = runComposeUiTest {
    val state = cropState()
    mount(state, CropGestures.Default)

    onNodeWithTag(TAG).performTouchInput { twist(degrees = 45f) }
    waitForIdle()

    assertEquals(
      0f,
      state.transform.rotationDegrees,
      absoluteTolerance = 0f,
      message = "a twist turned the image by ${state.transform.rotationDegrees} degrees",
    )
  }

  @Test
  fun aPinchDoesNotScaleTheImageByDefault() = runComposeUiTest {
    val state = cropState()
    mount(state, CropGestures.Default)

    onNodeWithTag(TAG).performTouchInput { spread() }
    waitForIdle()

    assertEquals(CropTransform.Identity, state.transform, "a pinch moved a fixed image")
  }

  @Test
  fun twoFingersDoNotPanTheImageByDefault() = runComposeUiTest {
    // Opens zoomed in, so there is somewhere to pan to. At the fit there is not, and the assertion
    // below would hold for the wrong reason.
    val state = cropState(transform = CropTransform(scale = 3f))
    mount(state, CropGestures.Default)

    onNodeWithTag(TAG).performTouchInput { twoFingerPan() }
    waitForIdle()

    assertEquals(Offset.Zero.x, state.transform.offset.x, absoluteTolerance = 0.01f)
    assertEquals(Offset.Zero.y, state.transform.offset.y, absoluteTolerance = 0.01f)
  }

  @Test
  fun aDoubleTapDoesNotZoomByDefault() = runComposeUiTest {
    val state = cropState()
    mount(state, CropGestures.Default)

    onNodeWithTag(TAG).performTouchInput { doubleTapAtTheMiddle() }
    waitForIdle()

    assertEquals(1f, state.transform.scale, absoluteTolerance = 0f, message = "a double tap zoomed")
  }

  // ---------------------------------------------------------------------------------------------
  // The positive controls: the same gestures, enabled
  // ---------------------------------------------------------------------------------------------

  @Test
  fun aTwistTurnsTheImageWhenRotationIsEnabled() = runComposeUiTest {
    val state = cropState()
    mount(state, CropGestures.All)

    onNodeWithTag(TAG).performTouchInput { twist(degrees = 45f) }
    waitForIdle()

    assertEquals(45f, state.transform.rotationDegrees, absoluteTolerance = 5f)
  }

  @Test
  fun aPinchScalesTheImageWhenZoomIsEnabled() = runComposeUiTest {
    val state = cropState()
    mount(state, CropGestures.Zoomable)

    onNodeWithTag(TAG).performTouchInput { spread() }
    waitForIdle()

    assertTrue(
      state.transform.scale > 1.2f,
      "zoom was enabled and the pinch left the scale at ${state.transform.scale}",
    )
  }

  /**
   * Paired with [aDoubleTapDoesNotZoomByDefault] and using the same injection, because a tap helper
   * whose two taps fall outside the double tap timeout would satisfy that test while proving
   * nothing about the flag.
   */
  @Test
  fun aDoubleTapZoomsWhenZoomIsEnabled() = runComposeUiTest {
    val state = cropState()
    mount(state, CropGestures.Zoomable)

    onNodeWithTag(TAG).performTouchInput { doubleTapAtTheMiddle() }
    waitForIdle()

    assertTrue(
      state.transform.scale > 1.5f,
      "zoom was enabled and the double tap left the scale at ${state.transform.scale}",
    )
  }

  @Test
  fun twoFingersPanTheImageWhenPanIsEnabled() = runComposeUiTest {
    val state = cropState(transform = CropTransform(scale = 3f))
    mount(state, CropGestures.Zoomable)

    onNodeWithTag(TAG).performTouchInput { twoFingerPan() }
    waitForIdle()

    assertNotEquals(0f, state.transform.offset.x, "pan was enabled and the image did not move")
  }

  // ---------------------------------------------------------------------------------------------
  // The flags are independent
  // ---------------------------------------------------------------------------------------------

  /**
   * [CropGestures.Zoomable] is the interesting middle: a pinch is almost never perfectly parallel,
   * so a set that enables zoom and not rotation has to drop the twist component out of the very
   * same event it is scaling from.
   */
  @Test
  fun aZoomableSetScalesUnderATwistAndStillDoesNotTurn() = runComposeUiTest {
    val state = cropState()
    mount(state, CropGestures.Zoomable)

    onNodeWithTag(TAG).performTouchInput { spreadAndTwist() }
    waitForIdle()

    assertTrue(state.transform.scale > 1.2f, "the spread half of the gesture was dropped too")
    assertEquals(
      0f,
      state.transform.rotationDegrees,
      absoluteTolerance = 0f,
      message = "the twist leaked through a set with rotate = false",
    )
  }

  // ---------------------------------------------------------------------------------------------
  // What a fixed image must not cost
  // ---------------------------------------------------------------------------------------------

  /** Fixing the image is a change to the image, not to the thing the screen is for. */
  @Test
  fun theCropRectangleStillMovesWhileTheImageIsFixed() = runComposeUiTest {
    val state = cropState()
    mount(state, CropGestures.Default)
    val origin = state.cropRect

    val slop = touchSlop
    onNodeWithTag(TAG).performTouchInput {
      down(Offset(origin.right, origin.bottom))
      moveTo(Offset(origin.right + slop + 1f, origin.bottom))
      moveTo(Offset(origin.right - 60f, origin.bottom - 60f))
      up()
    }
    waitForIdle()

    assertTrue(
      state.cropRect.right < origin.right - 40f,
      "the corner handle did not resize the rectangle: ${state.cropRect} from $origin",
    )
    assertEquals(CropTransform.Identity, state.transform, "the image moved during a crop drag")
  }

  /**
   * A gesture nobody uses belongs to whoever else wants it.
   *
   * With gestures live the cropper claims a second pointer immediately, ahead of an ancestor's
   * touch slop, and that claim is right because the pinch is about to do something. Keeping it
   * while the image is fixed would leave a cropper inside a pager swallowing every two finger
   * gesture and spending it on nothing.
   */
  @Test
  fun aFixedImageLeavesATwoFingerGestureToAnAncestor() = runComposeUiTest {
    val state = cropState()
    var ancestorDragged = false

    setContent {
      touchSlop = LocalViewConfiguration.current.touchSlop
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        Box(
          Modifier
            .size(VIEWPORT.dp)
            .pointerInput(Unit) {
              detectHorizontalDragGestures { _, _ -> ancestorDragged = true }
            },
        ) {
          Surface(state, CropGestures.Default)
        }
      }
    }
    waitForIdle()

    onNodeWithTag(TAG).performTouchInput { twoFingerPan() }
    waitForIdle()

    assertTrue(
      ancestorDragged,
      "the cropper consumed a two-finger gesture it had nothing to do with",
    )
    assertEquals(CropTransform.Identity, state.transform)
  }

  // ---------------------------------------------------------------------------------------------
  // Harness
  // ---------------------------------------------------------------------------------------------

  private var touchSlop = 0f

  private fun ComposeUiTest.mount(state: RealCropState, gestures: CropGestures) {
    setContent {
      touchSlop = LocalViewConfiguration.current.touchSlop
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        Surface(state, gestures)
      }
    }
    waitForIdle()
    assertEquals(
      FloatSize(VIEWPORT, VIEWPORT),
      state.viewportSize,
      "the harness did not lay out at one pixel per dp",
    )
    assertFalse(state.viewportSize.isEmpty)
  }
}

@androidx.compose.runtime.Composable
private fun Surface(state: RealCropState, gestures: CropGestures) {
  Box(
    Modifier
      .size(VIEWPORT.dp)
      .testTag(TAG)
      .onSizeChanged { size ->
        val next = FloatSize(size.width.toFloat(), size.height.toFloat())
        if (next != state.viewportSize) {
          state.viewportSize = next
          state.constrainFrameToImage()
        }
      }
      .cropGestures(state, CropStyle.Default, gestures),
  )
}

private fun cropState(transform: CropTransform = CropTransform.Identity): RealCropState {
  val state = RealCropState(
    source = CropSource.Bytes(ByteArray(0), "crop-gesture-set-test"),
    initialAspectRatio = AspectRatio.Free,
  )
  state.status = CropStatus.Ready(ImageSize(1_000, 1_000), ImageOrientation.NORMAL)
  state.transform = transform
  return state
}

/** Two diametric fingers swung about the middle: the centroid holds still, so only the angle moves. */
private fun androidx.compose.ui.test.TouchInjectionScope.twist(degrees: Float) {
  val middle = Offset(centerX, centerY)
  val arm = 80f
  pinch(
    start0 = middle + Offset(-arm, 0f),
    end0 = middle + rotated(-arm, 0f, degrees),
    start1 = middle + Offset(arm, 0f),
    end1 = middle + rotated(arm, 0f, degrees),
  )
}

/** Two fingers moving apart about the middle: the centroid holds still, so only the scale moves. */
private fun androidx.compose.ui.test.TouchInjectionScope.spread() {
  val middle = Offset(centerX, centerY)
  pinch(
    start0 = middle + Offset(-30f, 0f),
    end0 = middle + Offset(-120f, 0f),
    start1 = middle + Offset(30f, 0f),
    end1 = middle + Offset(120f, 0f),
  )
}

/** Both at once, which is what a real pinch is. */
private fun androidx.compose.ui.test.TouchInjectionScope.spreadAndTwist() {
  val middle = Offset(centerX, centerY)
  pinch(
    start0 = middle + Offset(-30f, 0f),
    end0 = middle + rotated(-120f, 0f, 40f),
    start1 = middle + Offset(30f, 0f),
    end1 = middle + rotated(120f, 0f, 40f),
  )
}

/** Two fingers travelling together: the separation holds, so only the centroid moves. */
private fun androidx.compose.ui.test.TouchInjectionScope.twoFingerPan() {
  down(0, Offset(centerX - 40f, centerY))
  down(1, Offset(centerX + 40f, centerY))
  repeat(8) {
    updatePointerBy(0, Offset(-20f, 0f))
    updatePointerBy(1, Offset(-20f, 0f))
    move()
  }
  up(0)
  up(1)
}

private fun androidx.compose.ui.test.TouchInjectionScope.doubleTapAtTheMiddle() {
  val middle = Offset(centerX, centerY)
  down(middle)
  up()
  advanceEventTime(50)
  down(middle)
  up()
}

private fun rotated(x: Float, y: Float, degrees: Float): Offset {
  val radians = degrees * PI.toFloat() / 180f
  return Offset(x * cos(radians) - y * sin(radians), x * sin(radians) + y * cos(radians))
}

private const val TAG = "cropper"
private const val VIEWPORT = 400f
