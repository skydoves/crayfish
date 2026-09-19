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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.skydoves.crayfish.decode.CropSource
import com.github.skydoves.crayfish.decode.ImageSize
import com.github.skydoves.crayfish.exif.ImageOrientation
import com.github.skydoves.crayfish.geometry.CropCoverage
import com.github.skydoves.crayfish.geometry.CropTransform
import com.github.skydoves.crayfish.geometry.FloatPoint
import com.github.skydoves.crayfish.geometry.FloatRect
import com.github.skydoves.crayfish.geometry.FloatSize
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gesture layer, driven through a real composition by real touch events.
 *
 * Everything here runs against [Modifier.cropGestures] mounted on a bare [Box] rather than on
 * `Cropper`, so that a failure is a gesture failure and not a decoder one. The harness pins
 * `LocalDensity` to 1, which makes every dimension in [CropStyle] a round number of pixels and
 * every touch coordinate in these tests directly comparable with the rectangles being asserted on.
 */
@OptIn(ExperimentalTestApi::class)
class CropGestureTest {

  // ---------------------------------------------------------------------------------------------
  // 1. Pinch
  // ---------------------------------------------------------------------------------------------

  @Test
  fun aPinchZoomsAndLeavesTheImagePointUnderTheCentroidWhereItWas() = runComposeUiTest {
    val state = cropState()
    mount(state)

    // Deliberately NOT the middle of the viewport. The content's centre is the pivot everything
    // turns about, so a pinch centred there is satisfied by any pivot convention at all and would
    // hold just as well with the centroid compensation deleted outright.
    val centroid = FloatPoint(VIEWPORT / 2f + 60f, VIEWPORT / 2f + 40f)
    val scaleBefore = state.transform.scale
    val imagePointBefore = state.coordinateSpace.viewportToImage(centroid)

    onNodeWithTag(TAG).performTouchInput {
      val anchor = Offset(centroid.x, centroid.y)
      pinch(
        start0 = anchor + Offset(-40f, 0f),
        end0 = anchor + Offset(-120f, 0f),
        start1 = anchor + Offset(40f, 0f),
        end1 = anchor + Offset(120f, 0f),
      )
    }
    waitForIdle()

    assertTrue(
      state.transform.scale > scaleBefore * 2f,
      "a pinch from 80px apart to 240px apart should have zoomed well past 2x, " +
        "but the scale went $scaleBefore -> ${state.transform.scale}",
    )
    // The anchor is exact in the algebra; the tolerance is for float error accumulated over the
    // ~24 events the injector generates. Two image pixels is 0.8 viewport pixels at this fit.
    val imagePointAfter = state.coordinateSpace.viewportToImage(centroid)
    assertTrue(
      imagePointAfter.isCloseTo(imagePointBefore, tolerance = 2f),
      "the image point under the pinch centroid moved from $imagePointBefore to $imagePointAfter",
    )
  }

  @Test
  fun aTwoFingerTwistRotatesTheImage() = runComposeUiTest {
    val state = cropState()
    mount(state)

    // Both pointers swing through a quarter of a right angle about the middle, staying diametric so
    // the centroid does not move and the twist is the only thing the detector can see.
    onNodeWithTag(TAG).performTouchInput {
      val middle = Offset(centerX, centerY)
      val arm = 80f
      val swept = 45f
      pinch(
        start0 = middle + Offset(-arm, 0f),
        end0 = middle + rotated(-arm, 0f, swept),
        start1 = middle + Offset(arm, 0f),
        end1 = middle + rotated(arm, 0f, swept),
      )
    }
    waitForIdle()

    assertEquals(
      45f,
      state.transform.rotationDegrees,
      absoluteTolerance = 5f,
      message = "a 45-degree twist should turn the image by as much",
    )
  }

  // ---------------------------------------------------------------------------------------------
  // 2. Two fingers move the image; one finger never does
  // ---------------------------------------------------------------------------------------------

  @Test
  fun twoFingersPanTheImageAndOneFingerInsideTheFrameDoesNot() = runComposeUiTest {
    // Zoomed in, so that both halves have room to move without the coverage correction (which is
    // entitled to move the image whenever the crop rectangle is dragged off it) having anything
    // to say about either result.
    val state = cropState(transform = CropTransform(scale = 2f))
    mount(state)

    // One finger first, from a state nothing has disturbed.
    val transformBefore = state.transform
    val cropBefore = state.cropRect
    onNodeWithTag(TAG).performTouchInput {
      dragBy(Offset(centerX, centerY), Offset(30f, 30f), touchSlop)
    }
    waitForIdle()

    assertEquals(
      transformBefore,
      state.transform,
      "one finger inside the frame must never move the image",
    )
    assertTrue(
      state.cropRect != cropBefore,
      "the same drag should have moved the crop rectangle instead, but it stayed at $cropBefore",
    )

    // And now two.
    val offsetBefore = state.transform.offset
    onNodeWithTag(TAG).performTouchInput {
      down(0, Offset(centerX - 40f, centerY))
      down(1, Offset(centerX + 40f, centerY))
      repeat(PAN_STEPS) {
        updatePointerBy(0, Offset(PAN_STEP, 0f))
        updatePointerBy(1, Offset(PAN_STEP, 0f))
        move()
      }
      up(0)
      up(1)
    }
    // Read before a frame is allowed to run. A two-finger pan ends in a fling, and this assertion
    // is about the pan itself; the coast that follows has its own test.
    assertEquals(
      offsetBefore.x + PAN_STEPS * PAN_STEP,
      state.transform.offset.x,
      absoluteTolerance = 1f,
      message = "a two-finger pan of ${PAN_STEPS * PAN_STEP}px should move the offset by as much",
    )
    waitForIdle()
  }

  // ---------------------------------------------------------------------------------------------
  // 3. Crop-rectangle dragging
  // ---------------------------------------------------------------------------------------------

  @Test
  fun aCornerDragResizesTheRectangleAndADragInsideItMovesIt() = runComposeUiTest {
    val state = cropState()
    mount(state)

    val origin = state.cropRect
    onNodeWithTag(TAG).performTouchInput {
      dragBy(Offset(origin.right, origin.bottom), Offset(-80f, -60f), touchSlop)
    }
    waitForIdle()

    val resized = state.cropRect
    assertRect(FloatRect(origin.left, origin.top, origin.right - 80f, origin.bottom - 60f), resized)

    onNodeWithTag(TAG).performTouchInput {
      dragBy(Offset(resized.center.x, resized.center.y), Offset(20f, 30f), touchSlop)
    }
    waitForIdle()

    assertRect(resized.translate(FloatPoint(20f, 30f)), state.cropRect)
  }

  // ---------------------------------------------------------------------------------------------
  // 4. A locked ratio survives everything
  // ---------------------------------------------------------------------------------------------

  @Test
  fun aLockedAspectRatioSurvivesASequenceOfDragsAndAPinch() = runComposeUiTest {
    val ratio = 9f / 16f
    val state = cropState(aspectRatio = AspectRatio.Fixed(ratio))
    mount(state)

    fun assertRatio(stage: String) {
      val rect = state.cropRect
      assertEquals(
        ratio,
        rect.width / rect.height,
        absoluteTolerance = RATIO_TOLERANCE,
        message = "after $stage the rectangle was $rect, a ratio of ${rect.width / rect.height}",
      )
    }

    val start = state.cropRect
    onNodeWithTag(TAG).performTouchInput {
      dragBy(Offset(start.right, start.bottom), Offset(-60f, -40f), touchSlop)
    }
    waitForIdle()
    assertRatio("a bottom-right corner drag")

    val afterCorner = state.cropRect
    onNodeWithTag(TAG).performTouchInput {
      dragBy(Offset(afterCorner.left, afterCorner.center.y), Offset(40f, 0f), touchSlop)
    }
    waitForIdle()
    assertRatio("a left edge drag")

    onNodeWithTag(TAG).performTouchInput {
      pinch(
        start0 = Offset(centerX - 30f, centerY),
        end0 = Offset(centerX - 110f, centerY),
        start1 = Offset(centerX + 30f, centerY),
        end1 = Offset(centerX + 110f, centerY),
      )
    }
    waitForIdle()
    assertRatio("a pinch")

    val afterPinch = state.cropRect
    onNodeWithTag(TAG).performTouchInput {
      dragBy(Offset(afterPinch.center.x, afterPinch.center.y), Offset(15f, 25f), touchSlop)
    }
    waitForIdle()
    assertRatio("a move of the whole rectangle")
  }

  // ---------------------------------------------------------------------------------------------
  // 5. The minimum size
  // ---------------------------------------------------------------------------------------------

  @Test
  fun anAggressiveInwardDragStopsAtTheMinimumCropSize() = runComposeUiTest {
    val state = cropState()
    mount(state)

    val origin = state.cropRect
    val minimum = CropStyle.Default.minimumCropSize.value // density is pinned to 1
    onNodeWithTag(TAG).performTouchInput {
      dragBy(Offset(origin.right, origin.bottom), Offset(-300f, -300f), touchSlop)
    }
    waitForIdle()

    assertRect(
      FloatRect(origin.left, origin.top, origin.left + minimum, origin.top + minimum),
      state.cropRect,
    )
  }

  // ---------------------------------------------------------------------------------------------
  // 6. isInteracting
  // ---------------------------------------------------------------------------------------------

  @Test
  fun isInteractingIsTrueForTheDurationOfAGestureAndFalseAfterIt() = runComposeUiTest {
    val state = cropState()
    mount(state)

    assertFalse(state.isInteracting, "nothing is touching the cropper yet")

    onNodeWithTag(TAG).performTouchInput {
      down(Offset(centerX, centerY))
      moveBy(Offset(30f, 30f))
    }
    waitForIdle()
    assertTrue(state.isInteracting, "a finger is down and moving")

    onNodeWithTag(TAG).performTouchInput { up() }
    waitForIdle()
    assertFalse(state.isInteracting, "the finger has lifted")
  }

  // ---------------------------------------------------------------------------------------------
  // 7. Fling
  // ---------------------------------------------------------------------------------------------

  @Test
  fun aFlingCoastsTheImageAndNeverPutsANaNInTheTransform() = runComposeUiTest {
    val state = cropState(transform = CropTransform(scale = 3f))
    mount(state)

    onNodeWithTag(TAG).performTouchInput {
      down(0, Offset(centerX - 40f, centerY))
      down(1, Offset(centerX + 40f, centerY))
      repeat(10) {
        updatePointerBy(0, Offset(-14f, 0f))
        updatePointerBy(1, Offset(-14f, 0f))
        move()
      }
      up(0)
      up(1)
    }
    val offsetAtLift = state.transform.offset
    waitForIdle()

    val transform = state.transform
    assertTrue(
      transform.isValid,
      "the fling left the transform unusable: $transform",
    )
    assertTrue(
      transform.offset.isFinite && transform.scale.isFinite() &&
        transform.rotationDegrees.isFinite(),
      "the fling put a non-finite value in $transform",
    )
    // A fling that does not coast is a fling that never ran, and then the NaN assertion above is
    // vacuous. The image must still be moving left after the fingers have gone.
    assertTrue(
      transform.offset.x < offsetAtLift.x - 1f,
      "the image did not coast after lift-off: ${offsetAtLift.x} -> ${transform.offset.x}",
    )
  }

  // ---------------------------------------------------------------------------------------------
  // 8. Recompute, never accumulate
  // ---------------------------------------------------------------------------------------------

  @Test
  fun aDragPastTheMinimumAndBackReopensTheRectangleToWhereTheFingerIs() = runComposeUiTest {
    val state = cropState()
    mount(state)

    val origin = state.cropRect
    // Drive the corner far past `minimumCropSize`, where the clamp is holding the rectangle, and
    // then back out. Because every frame is recomputed from the rectangle the drag started on, the
    // answer depends only on where the finger ended. A detector that applied each frame's delta to
    // the rectangle already on screen would have spent the overshoot against the clamp, and would
    // reopen to the much larger rectangle asserted against below.
    onNodeWithTag(TAG).performTouchInput {
      val breaker = slopBreaker(touchSlop)
      val corner = Offset(origin.right, origin.bottom)
      down(corner)
      moveTo(corner + breaker)
      moveTo(corner + breaker + Offset(-300f, -300f))
      moveTo(corner + breaker + Offset(-80f, -80f))
      up()
    }
    waitForIdle()

    assertRect(
      FloatRect(origin.left, origin.top, origin.right - 80f, origin.bottom - 80f),
      state.cropRect,
    )
  }

  // ---------------------------------------------------------------------------------------------
  // 9. The second-pointer claim
  // ---------------------------------------------------------------------------------------------

  @Test
  fun aSecondPointerClaimsTheGestureFromAnAncestorThatAlsoWantsToDrag() = runComposeUiTest {
    val state = cropState(transform = CropTransform(scale = 2f))
    // A narrow, central rectangle so the two fingers below land on no handle at all: the only
    // thing that can stop the ancestor is the claim itself.
    state.normalizedCropRect = FloatRect(0.35f, 0.35f, 0.65f, 0.65f)
    var ancestorDragged = false

    setContent {
      touchSlop = LocalViewConfiguration.current.touchSlop
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        // Stands in for a pager or a scroller: an ancestor whose own drag detector is waiting for
        // touch slop while the pinch is getting under way.
        Box(
          Modifier
            .size(VIEWPORT.dp)
            .pointerInput(Unit) {
              detectHorizontalDragGestures { _, _ -> ancestorDragged = true }
            },
        ) {
          GestureSurface(state)
        }
      }
    }
    waitForIdle()

    onNodeWithTag(TAG).performTouchInput {
      down(0, Offset(centerX - 40f, 20f))
      down(1, Offset(centerX + 40f, 20f))
      repeat(8) {
        updatePointerBy(0, Offset(-20f, 0f))
        updatePointerBy(1, Offset(-20f, 0f))
        move()
      }
      up(0)
      up(1)
    }
    waitForIdle()

    assertTrue(
      state.transform.offset.x < -50f,
      "the cropper should have panned the image, but the offset is ${state.transform.offset}",
    )
    assertFalse(
      ancestorDragged,
      "the ancestor took the gesture: a two-finger pan must be claimed the moment the second " +
        "pointer arrives, before any ancestor can reach its own touch slop",
    )
  }

  // ---------------------------------------------------------------------------------------------
  // 10. Double tap
  // ---------------------------------------------------------------------------------------------

  @Test
  fun aDoubleTapZoomsInAndTheNextOneZoomsBackOut() = runComposeUiTest {
    val state = cropState()
    mount(state)

    val scaleBefore = state.transform.scale
    onNodeWithTag(TAG).performTouchInput { doubleTapAt(Offset(centerX, centerY)) }
    waitForIdle()
    val zoomedIn = state.transform.scale
    assertTrue(
      zoomedIn > scaleBefore * 1.5f,
      "a double tap should have zoomed in from $scaleBefore, but the scale is $zoomedIn",
    )

    onNodeWithTag(TAG).performTouchInput { doubleTapAt(Offset(centerX, centerY)) }
    waitForIdle()
    assertTrue(
      state.transform.scale < zoomedIn,
      "a second double tap should have zoomed back out from $zoomedIn, " +
        "but the scale is ${state.transform.scale}",
    )
  }

  // ---------------------------------------------------------------------------------------------
  // 11. What a single finger does NOT do
  // ---------------------------------------------------------------------------------------------

  /**
   * A drag that starts on nothing changes nothing.
   *
   * The scrim outside the crop rectangle is not dead space by accident: a finger that lands there
   * must not slide the rectangle, and, because one finger never moves the image, must not pan
   * either. Together those are what leave the scrim free to mean "cancel" to a host that wants it.
   */
  @Test
  fun aDragThatStartsOnNoHandleLeavesTheRectangleAndTheImageAlone() = runComposeUiTest {
    val state = cropState()
    mount(state)

    val cropBefore = state.cropRect
    val transformBefore = state.transform
    // The bottom-left scrim: 42px from the nearest corner and off the end of both nearby edges,
    // so the hit test returns nothing at all rather than `Inside`.
    onNodeWithTag(TAG).performTouchInput {
      dragBy(Offset(10f, 390f), Offset(60f, -60f), touchSlop)
    }
    waitForIdle()

    assertEquals(cropBefore, state.cropRect, "a drag on the scrim must not move the rectangle")
    assertEquals(transformBefore, state.transform, "one finger must never move the image")
  }

  /**
   * A move smaller than touch slop is not a drag yet.
   *
   * Everything downstream is recomputed from the rectangle frozen at the moment slop is crossed,
   * so acting before that would open every drag with a jump of one slop in whatever direction the
   * finger happened to wobble.
   */
  @Test
  fun aMoveInsideTheTouchSlopDoesNotStartADrag() = runComposeUiTest {
    val state = cropState()
    mount(state)

    val before = state.cropRect
    onNodeWithTag(TAG).performTouchInput {
      down(Offset(before.center.x, before.center.y))
      moveBy(Offset(touchSlop / 3f, 0f))
      moveBy(Offset(0f, touchSlop / 3f))
      up()
    }
    waitForIdle()

    assertEquals(before, state.cropRect, "a wobble of a third of touch slop is not a drag")
  }

  // ---------------------------------------------------------------------------------------------
  // 12. What is not a double tap
  // ---------------------------------------------------------------------------------------------

  /**
   * A long press is not a tap, and a tap that follows one starts counting again.
   *
   * Without the timeout, resting a finger on the image and lifting it would arm the double tap,
   * and the next ordinary tap would zoom. A cropper that zooms when you did not ask is worse than
   * one that never zooms.
   */
  @Test
  fun aLongPressIsNotATapAndDoesNotArmADoubleTap() = runComposeUiTest {
    val state = cropState()
    mount(state)

    val scaleBefore = state.transform.scale
    onNodeWithTag(TAG).performTouchInput {
      down(Offset(centerX, centerY))
      advanceEventTime(longPressTimeout + 100L)
      up()
      advanceEventTime(40)
      down(Offset(centerX, centerY))
      up()
    }
    waitForIdle()

    assertEquals(
      scaleBefore,
      state.transform.scale,
      "a long press followed by a tap is not a double tap, so nothing should have zoomed",
    )
  }

  /** Two taps further apart in time than the double-tap timeout are two taps. */
  @Test
  fun aSecondTapAfterTheDoubleTapTimeoutIsNotADoubleTap() = runComposeUiTest {
    val state = cropState()
    mount(state)

    val scaleBefore = state.transform.scale
    onNodeWithTag(TAG).performTouchInput {
      down(Offset(centerX, centerY))
      up()
      advanceEventTime(doubleTapTimeout + 100L)
      down(Offset(centerX, centerY))
      up()
    }
    waitForIdle()

    assertEquals(scaleBefore, state.transform.scale, "too slow to be a double tap")
  }

  /** And two taps further apart in space than twice touch slop are two taps. */
  @Test
  fun aSecondTapTooFarFromTheFirstIsNotADoubleTap() = runComposeUiTest {
    val state = cropState()
    mount(state)

    val scaleBefore = state.transform.scale
    onNodeWithTag(TAG).performTouchInput {
      down(Offset(centerX - 100f, centerY))
      up()
      advanceEventTime(40)
      down(Offset(centerX + 100f, centerY))
      up()
    }
    waitForIdle()

    assertEquals(scaleBefore, state.transform.scale, "200px apart is not a double tap")
  }

  // ---------------------------------------------------------------------------------------------
  // 13. Pinch limits and coverage recovery
  // ---------------------------------------------------------------------------------------------

  /**
   * Zooming out is a request, not a result: the image stops shrinking at the fit.
   *
   * It used to stop where the crop frame stopped being covered, which meant a small frame let the
   * photo shrink well below the fit and a large one dragged the photo around to keep up. The frame
   * is now what yields, so the only thing bounding the zoom is the photo's own fit.
   *
   * The centroid is deliberately off the viewport's centre. A centroid *at* the centre coincides
   * with the transform pivot, which makes every pivot convention agree and the assertion vacuous.
   */
  @Test
  fun aPinchZoomedOutStopsAtTheFit() = runComposeUiTest {
    val state = cropState(transform = CropTransform(scale = 4f))
    mount(state)

    // Both starts inside the node: a pointer whose *down* lands outside is never routed here, and
    // the pinch silently becomes a one-finger gesture.
    val anchor = Offset(VIEWPORT / 2f + 70f, VIEWPORT / 2f - 50f)
    onNodeWithTag(TAG).performTouchInput {
      pinch(
        start0 = anchor + Offset(-120f, 0f),
        end0 = anchor + Offset(-10f, 0f),
        start1 = anchor + Offset(120f, 0f),
        end1 = anchor + Offset(10f, 0f),
      )
    }
    waitForIdle()

    assertTrue(state.transform.scale < 4f, "the pinch did not zoom out at all")
    // The fit, which `contentBounds` makes a scale of one by construction.
    assertEquals(
      1f,
      state.transform.scale,
      absoluteTolerance = 0.01f,
      message = "zooming out must stop at the fit",
    )
    assertTrue(
      CropCoverage.covers(
        state.transform,
        state.coordinateSpace.contentBounds,
        state.cropRect,
        tolerance = CropCoverage.CORRECTION_SLACK,
      ),
      "the crop frame is no longer covered: ${state.transform}",
    )
  }

  /**
   * Zooming out stops at the fit, whatever the crop frame is doing.
   *
   * This used to read the other way: the crop frame was what stopped the zoom, and below its demand
   * there was a separate floor of 0.1. That model let the photo shrink to a tenth of the viewport
   * whenever the frame was small enough, which is not something a photo viewer does, and it was the
   * same rule that dragged the image around behind the selector on a real phone.
   *
   * `contentBounds` is already the fitted rectangle, so the fit is a scale of one by construction.
   */
  @Test
  fun zoomingOutStopsAtTheFitWhateverTheCropFrameIsDoing() = runComposeUiTest {
    val state = cropState()
    state.normalizedCropRect = FloatRect(0.49f, 0.49f, 0.51f, 0.51f)
    mount(state)

    val anchor = Offset(VIEWPORT / 2f + 60f, VIEWPORT / 2f + 45f)
    repeat(2) {
      onNodeWithTag(TAG).performTouchInput {
        pinch(
          start0 = anchor + Offset(-130f, 0f),
          end0 = anchor + Offset(-5f, 0f),
          start1 = anchor + Offset(130f, 0f),
          end1 = anchor + Offset(5f, 0f),
        )
      }
      waitForIdle()
    }

    assertEquals(
      // The fit, stated here rather than read from a copy of the production constant: this test
      // held its own `MINIMUM_SCALE = 0.1f` and went on passing for a while after the real one
      // changed, which is the whole hazard of duplicating a value into a test.
      1f,
      state.transform.scale,
      absoluteTolerance = 1e-4f,
      message = "zooming out must stop at the fit, even with an 8px crop frame",
    )
  }

  /** The ceiling, from the other direction: however many pinches, the scale stops at 10. */
  @Test
  fun theScaleStopsAtItsCeilingHoweverHardThePinch() = runComposeUiTest {
    val state = cropState()
    mount(state)

    val anchor = Offset(VIEWPORT / 2f - 55f, VIEWPORT / 2f + 65f)
    repeat(3) {
      onNodeWithTag(TAG).performTouchInput {
        pinch(
          start0 = anchor + Offset(-10f, 0f),
          end0 = anchor + Offset(-160f, 0f),
          start1 = anchor + Offset(10f, 0f),
          end1 = anchor + Offset(160f, 0f),
        )
      }
      waitForIdle()
    }

    assertEquals(
      MAXIMUM_SCALE,
      state.transform.scale,
      absoluteTolerance = 1e-3f,
      message = "three 16x pinches must still stop at the ceiling",
    )
  }

  /**
   * Two samples carrying the same timestamp are the documented way the velocity fit divides by
   * zero, and the NaN it returns rides straight into the transform, where everything derived from
   * it is NaN for the rest of the session.
   */
  @Test
  fun aPinchWhoseSamplesShareATimestampNeverPutsANaNInTheTransform() = runComposeUiTest {
    val state = cropState()
    mount(state)

    onNodeWithTag(TAG).performTouchInput {
      down(0, Offset(centerX - 40f, centerY))
      down(1, Offset(centerX + 40f, centerY))
      repeat(6) {
        updatePointerBy(0, Offset(-12f, 0f))
        updatePointerBy(1, Offset(12f, 0f))
        // Zero, so the clock does not move between samples.
        move(delayMillis = 0)
      }
      up(0)
      up(1)
    }
    waitForIdle()

    val transform = state.transform
    assertTrue(transform.isValid, "the pinch left the transform unusable: $transform")
    assertTrue(
      transform.offset.isFinite && transform.scale.isFinite() &&
        transform.rotationDegrees.isFinite(),
      "a repeated timestamp put a non-finite value in $transform",
    )
    // Positive control: a pinch that did nothing would satisfy every assertion above.
    assertTrue(transform.scale > 1.5f, "the pinch never zoomed, so nothing was being guarded")
  }

  // ---------------------------------------------------------------------------------------------
  // 14. Recomposition: what restarts the detector and what must not
  // ---------------------------------------------------------------------------------------------

  /**
   * A recomposition carrying a new colour must not tear down a gesture in flight.
   *
   * This is the whole reason `CropGestureNode.update` compares the two geometry values rather than
   * restarting on every change. A restart cancels the detector's coroutine, and the restarted one
   * opens on `awaitFirstDown`, which a finger that is *already* down never satisfies. The drag
   * would simply stop halfway, and the rectangle would freeze where it was when the recomposition
   * happened.
   */
  @Test
  fun aStyleChangeThatTouchesNoGeometryDoesNotInterruptADragInFlight() = runComposeUiTest {
    val state = mutableStateOf(cropState())
    val style = mutableStateOf(CropStyle.Default)
    mountMutable(state, style)

    val origin = state.value.cropRect
    val corner = Offset(origin.right, origin.bottom)
    val breaker = slopBreaker(touchSlop)
    onNodeWithTag(TAG).performTouchInput {
      down(corner)
      moveTo(corner + breaker)
      moveTo(corner + breaker + Offset(-40f, -30f))
    }
    waitForIdle()

    style.value = CropStyle.Default.copy(frameColor = Color.Red, gridMode = CropGridMode.Always)
    waitForIdle()

    onNodeWithTag(TAG).performTouchInput {
      moveTo(corner + breaker + Offset(-80f, -60f))
      up()
    }
    waitForIdle()

    assertRect(
      FloatRect(origin.left, origin.top, origin.right - 80f, origin.bottom - 60f),
      state.value.cropRect,
    )
  }

  /**
   * A touch radius raised by a recomposition is in force on the next gesture.
   *
   * A host that grows its targets, for an accessibility preference or a switch to a TV remote,
   * gets the new radius without remounting the cropper. The same touch is asserted twice, once
   * under each radius, so this cannot pass by the drag failing for some unrelated reason.
   */
  @Test
  fun aTouchRadiusRaisedByRecompositionTakesEffectOnTheNextGesture() = runComposeUiTest {
    val state = mutableStateOf(cropState())
    val style = mutableStateOf(CropStyle.Default)
    mountMutable(state, style)

    val origin = state.value.cropRect
    // 42px from the bottom-right corner and off the end of both edges beside it: outside the
    // 24dp target, inside an 80dp one.
    val outside = Offset(origin.right + 30f, origin.bottom + 30f)

    onNodeWithTag(TAG).performTouchInput { dragBy(outside, Offset(-30f, -20f), touchSlop) }
    waitForIdle()
    assertEquals(origin, state.value.cropRect, "at a 24dp radius that touch grabs nothing")

    style.value = CropStyle.Default.copy(handleTouchRadius = 80.dp)
    waitForIdle()

    onNodeWithTag(TAG).performTouchInput { dragBy(outside, Offset(-30f, -20f), touchSlop) }
    waitForIdle()

    assertRect(
      FloatRect(origin.left, origin.top, origin.right - 30f, origin.bottom - 20f),
      state.value.cropRect,
    )
  }

  /** And so is a minimum crop size raised by one. */
  @Test
  fun aMinimumCropSizeRaisedByRecompositionTakesEffect() = runComposeUiTest {
    val state = mutableStateOf(cropState())
    val style = mutableStateOf(CropStyle.Default)
    mountMutable(state, style)

    // Same touch radius, different floor: the detector restarts for this too, because a drag
    // finished under the old floor would stop somewhere the caller has said is too small.
    style.value = CropStyle.Default.copy(minimumCropSize = 160.dp)
    waitForIdle()

    val origin = state.value.cropRect
    onNodeWithTag(TAG).performTouchInput {
      dragBy(Offset(origin.right, origin.bottom), Offset(-300f, -300f), touchSlop)
    }
    waitForIdle()

    assertRect(
      FloatRect(origin.left, origin.top, origin.left + 160f, origin.top + 160f),
      state.value.cropRect,
    )
  }

  /** A different state object is a different cropper; the one that left must not be written to. */
  @Test
  fun aNewStateObjectTakesOverAndTheOldOneIsLeftAlone() = runComposeUiTest {
    val first = cropState()
    val second = cropState()
    val state = mutableStateOf(first)
    val style = mutableStateOf(CropStyle.Default)
    mountMutable(state, style)

    val firstCropBefore = first.normalizedCropRect
    second.viewportSize = FloatSize(VIEWPORT, VIEWPORT)
    state.value = second
    waitForIdle()

    val origin = second.cropRect
    onNodeWithTag(TAG).performTouchInput {
      dragBy(Offset(origin.right, origin.bottom), Offset(-60f, -40f), touchSlop)
    }
    waitForIdle()

    assertRect(
      FloatRect(origin.left, origin.top, origin.right - 60f, origin.bottom - 40f),
      second.cropRect,
    )
    assertEquals(
      firstCropBefore,
      first.normalizedCropRect,
      "the state that left the tree was still being written to",
    )
  }

  // ---------------------------------------------------------------------------------------------
  // 15. A fling that has nowhere to go
  // ---------------------------------------------------------------------------------------------

  /**
   * A throw at an image that cannot move leaves it where it was, and does not creep.
   *
   * The crop frame here is the whole viewport, so every frame of the fling is undone by the
   * coverage correction. The failure this guards against is not a jump but a drift: a correction
   * that gives back slightly less than the fling took moves the image by a fraction of a pixel per
   * frame, and a fling is well over a hundred frames long. A pixel of total travel against 140px
   * of throw is two orders of magnitude of headroom, and an uncorrected fling lands 348px away.
   */
  @Test
  fun aThrowAtAnImageWithNoRoomToMoveLeavesItWhereItWas() = runComposeUiTest {
    val state = cropState()
    // The crop frame is the fitted image exactly, so there is no legal offset but zero.
    state.normalizedCropRect = FloatRect(0f, 0f, 1f, 1f)
    mount(state)

    onNodeWithTag(TAG).performTouchInput {
      down(0, Offset(centerX - 40f, centerY))
      down(1, Offset(centerX + 40f, centerY))
      repeat(10) {
        updatePointerBy(0, Offset(-14f, 0f))
        updatePointerBy(1, Offset(-14f, 0f))
        move()
      }
      up(0)
      up(1)
    }
    waitForIdle()

    val transform = state.transform
    assertTrue(transform.isValid, "the fling left the transform unusable: $transform")
    assertTrue(
      abs(transform.offset.x) <= 1f && abs(transform.offset.y) <= 1f,
      "a 140px throw at an image with nowhere to go left it at ${transform.offset}",
    )
  }

  // ---------------------------------------------------------------------------------------------
  // 16. Sharing the pointer stream
  // ---------------------------------------------------------------------------------------------

  /**
   * A crop drag still works when something nearer the leaf has already consumed the event.
   *
   * Consumption is a claim, not a filter: the change still arrives. A detector that re-checked
   * `isConsumed` before reading the position, which is the shape of the usual mistake, would drop
   * every frame of the drag and the rectangle would not move at all.
   */
  @Test
  fun aCropDragStillWorksWhenAnInnerModifierHasAlreadyConsumedTheEvent() = runComposeUiTest {
    val state = cropState()
    mountUnderAnInnerConsumer(state)

    val origin = state.cropRect
    onNodeWithTag(TAG).performTouchInput {
      dragBy(Offset(origin.right, origin.bottom), Offset(-70f, -50f), touchSlop)
    }
    waitForIdle()

    assertRect(
      FloatRect(origin.left, origin.top, origin.right - 70f, origin.bottom - 50f),
      state.cropRect,
    )
  }

  /**
   * And so does a pinch, which is the half that has to consume for itself.
   *
   * A claimed gesture re-consumes every change on every frame so an ancestor still waiting for its
   * own slop cannot read one as permission to start. When something inner got there first there is
   * nothing left to consume, and the zoom must arrive all the same.
   */
  @Test
  fun aPinchStillZoomsWhenAnInnerModifierHasAlreadyConsumedTheEvent() = runComposeUiTest {
    val state = cropState()
    mountUnderAnInnerConsumer(state)

    val anchor = Offset(VIEWPORT / 2f - 30f, VIEWPORT / 2f + 40f)
    onNodeWithTag(TAG).performTouchInput {
      pinch(
        start0 = anchor + Offset(-30f, 0f),
        end0 = anchor + Offset(-120f, 0f),
        start1 = anchor + Offset(30f, 0f),
        end1 = anchor + Offset(120f, 0f),
      )
    }
    waitForIdle()

    assertTrue(
      state.transform.scale > 2f,
      "a 4x pinch under an inner consumer left the scale at ${state.transform.scale}",
    )
  }

  /**
   * A pinch with one finger held still.
   *
   * Real pinches are rarely symmetric: a thumb anchors and a finger moves. In that case neither
   * pointer's own path is what the gesture is about, since the still one has travelled nothing at
   * all and the moving one has travelled the whole zoom. The factor has to come out of the gap
   * between them.
   */
  @Test
  fun aPinchAnchoredOnAStillFingerStillZooms() = runComposeUiTest {
    val state = cropState()
    mount(state)

    onNodeWithTag(TAG).performTouchInput {
      down(0, Offset(centerX - 60f, centerY + 30f))
      down(1, Offset(centerX + 20f, centerY + 30f))
      repeat(8) {
        updatePointerBy(1, Offset(20f, 0f))
        move()
      }
      up(0)
      up(1)
    }
    waitForIdle()

    assertEquals(
      3f,
      state.transform.scale,
      absoluteTolerance = 0.2f,
      message = "80px apart to 240px apart is a factor of three, whichever finger did the moving",
    )
  }

  // ---------------------------------------------------------------------------------------------
  // 17. One finger becomes two
  // ---------------------------------------------------------------------------------------------

  /**
   * A second finger arriving mid-drag hands the gesture to the image, and the crop rectangle stops
   * exactly where it was.
   *
   * This is the transition the whole single-detector design exists to make deterministic. Two
   * competing `pointerInput` blocks would race for the claim, and which one won would decide,
   * differently on different frames, whether this gesture stayed a resize or became a pinch.
   */
  @Test
  fun aSecondFingerHandsACropDragOverToTheImage() = runComposeUiTest {
    val state = cropState(transform = CropTransform(scale = 2f))
    mount(state)

    val origin = state.cropRect
    val corner = Offset(origin.right, origin.bottom)
    val breaker = slopBreaker(touchSlop)
    onNodeWithTag(TAG).performTouchInput {
      down(0, corner)
      moveTo(0, corner + breaker)
      moveTo(0, corner + breaker + Offset(-50f, -40f))
      down(1, Offset(centerX - 80f, centerY - 80f))
      repeat(8) {
        updatePointerBy(0, Offset(10f, 0f))
        updatePointerBy(1, Offset(-10f, 0f))
        move()
      }
      up(0)
      up(1)
    }
    waitForIdle()

    assertRect(
      FloatRect(origin.left, origin.top, origin.right - 50f, origin.bottom - 40f),
      state.cropRect,
    )
    assertTrue(
      state.transform.scale > 2f,
      "the two fingers should have zoomed the image, but the scale is ${state.transform.scale}",
    )
  }

  // ---------------------------------------------------------------------------------------------
  // Harness
  // ---------------------------------------------------------------------------------------------

  /** The platform's touch slop, read out of the composition rather than assumed. */
  private var touchSlop = 0f

  /** Both read out of the composition for the same reason. */
  private var longPressTimeout = 0L
  private var doubleTapTimeout = 0L

  private fun ComposeUiTest.mount(state: RealCropState, style: CropStyle = CropStyle.Default) {
    setContent {
      readViewConfiguration()
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        GestureSurface(state, style)
      }
    }
    waitForIdle()
    assertViewport(state)
  }

  /**
   * The same surface, mounted against values a test can change from outside the composition.
   *
   * Changing either makes `CropGestureElement` compare unequal, which is what makes Compose call
   * `CropGestureNode.update`, the path that decides whether a gesture in flight survives.
   */
  private fun ComposeUiTest.mountMutable(
    state: MutableState<RealCropState>,
    style: MutableState<CropStyle>,
  ) {
    setContent {
      readViewConfiguration()
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        GestureSurface(state.value, style.value)
      }
    }
    waitForIdle()
    assertViewport(state.value)
  }

  /**
   * The same surface with a pointer-input modifier *after* `cropGestures`.
   *
   * Later in the chain is nearer the leaf, and the Main pass runs leaf first, so everything this
   * node consumes is already consumed by the time the cropper sees it.
   */
  private fun ComposeUiTest.mountUnderAnInnerConsumer(state: RealCropState) {
    setContent {
      readViewConfiguration()
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
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
            .cropGestures(state, CropStyle.Default, CropGestures.All)
            .pointerInput(Unit) {
              awaitPointerEventScope {
                while (true) {
                  awaitPointerEvent().changes.forEach { it.consume() }
                }
              }
            },
        )
      }
    }
    waitForIdle()
    assertViewport(state)
  }

  @Composable
  private fun readViewConfiguration() {
    val configuration = LocalViewConfiguration.current
    touchSlop = configuration.touchSlop
    longPressTimeout = configuration.longPressTimeoutMillis
    doubleTapTimeout = configuration.doubleTapTimeoutMillis
  }

  private fun assertViewport(state: RealCropState) {
    assertEquals(
      FloatSize(VIEWPORT, VIEWPORT),
      state.viewportSize,
      "the harness did not lay out at one pixel per dp; every coordinate below assumes it does",
    )
  }
}

private const val TAG = "cropper"
private const val VIEWPORT = 400f
private const val PAN_STEPS = 5
private const val PAN_STEP = -20f

/** The scale bounds `applyImageTransform` clamps into, restated here as an independent oracle. */
private const val MAXIMUM_SCALE = 10f

/** Generous for a ratio that is derived rather than accumulated, tight enough to catch a drift. */
private const val RATIO_TOLERANCE = 1e-3f

/** Sub-pixel: the only slack is the round trip through the normalised rectangle. */
private const val RECT_TOLERANCE = 0.05f

/**
 * Mounts with every image gesture live.
 *
 * The production default is [CropGestures.Default], under which the image does not move at all,
 * and that default is what `CropGestureSetTest` covers. The tests in this file are about what each
 * gesture does once it is enabled, so they say so here rather than inheriting it.
 */
@Composable
private fun GestureSurface(
  state: RealCropState,
  style: CropStyle = CropStyle.Default,
  gestures: CropGestures = CropGestures.All,
) {
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
      .cropGestures(state, style, gestures),
  )
}

private fun cropState(
  imageSize: ImageSize = ImageSize(1_000, 1_000),
  aspectRatio: AspectRatio = AspectRatio.Free,
  transform: CropTransform = CropTransform.Identity,
): RealCropState {
  val state = RealCropState(
    source = CropSource.Bytes(ByteArray(0), "crop-gesture-test"),
    initialAspectRatio = aspectRatio,
  )
  state.status = CropStatus.Ready(imageSize, ImageOrientation.NORMAL)
  state.transform = transform
  return state
}

/**
 * A move large enough to spend the touch slop and nothing else.
 *
 * The detector re-freezes the rectangle it recomputes from at the instant slop is crossed, so a
 * drag written as a single `moveTo` would cross slop at its destination and count as no drag at
 * all. Spending the slop on a separate event first makes the delta that follows exact.
 */
private fun slopBreaker(touchSlop: Float): Offset = Offset(0f, -(touchSlop * 2f + 20f))

private fun rotated(x: Float, y: Float, degrees: Float): Offset {
  val radians = degrees * PI.toFloat() / 180f
  return Offset(x * cos(radians) - y * sin(radians), x * sin(radians) + y * cos(radians))
}

private fun TouchInjectionScope.dragBy(start: Offset, delta: Offset, touchSlop: Float) {
  val breaker = slopBreaker(touchSlop)
  down(start)
  moveTo(start + breaker)
  moveTo(start + breaker + delta)
  up()
}

private fun TouchInjectionScope.doubleTapAt(position: Offset) {
  down(position)
  up()
  advanceEventTime(40)
  down(position)
  up()
}

private fun assertRect(expected: FloatRect, actual: FloatRect) {
  assertEquals(expected.left, actual.left, RECT_TOLERANCE, "left of $actual, expected $expected")
  assertEquals(expected.top, actual.top, RECT_TOLERANCE, "top of $actual, expected $expected")
  assertEquals(expected.right, actual.right, RECT_TOLERANCE, "right of $actual, expected $expected")
  assertEquals(
    expected.bottom,
    actual.bottom,
    RECT_TOLERANCE,
    "bottom of $actual, expected $expected",
  )
}
