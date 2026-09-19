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

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.util.fastForEach
import com.github.skydoves.crayfish.geometry.FloatPoint
import com.github.skydoves.crayfish.geometry.FloatRect
import com.github.skydoves.crayfish.geometry.RotationSnap
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Every touch the cropper understands: pinch, two-finger pan and twist, double tap, and the
 * single-finger drags that resize and move the crop rectangle.
 *
 * ## Why the two are one modifier
 *
 * Splitting them into two `pointerInput` blocks would mean two pointer-input nodes competing for
 * the same stream, and whichever one claimed first would decide, non-deterministically, whether a
 * finger that starts on a handle and is joined by a second finger becomes a resize or a pinch. One
 * detector with one state machine makes that transition a written rule instead of a race.
 *
 * ## The division of labour between fingers
 *
 * One finger belongs to the crop rectangle; two belong to the image. This is not the only possible
 * split, but it is the only one under which every gesture is unambiguous from its first event,
 * which is what lets the two-finger case be claimed immediately (see [detectCropGestures]).
 */
internal fun Modifier.cropGestures(
  state: RealCropState,
  style: CropStyle,
  gestures: CropGestures = CropGestures.Default,
): Modifier = this then CropGestureElement(state, style, gestures)

/**
 * A `data class` for its generated [equals] and [hashCode], which [ModifierNodeElement] declares
 * abstract for a reason: without them every recomposition compares unequal, and Compose responds by
 * calling `update`, which here would tear down a gesture in flight.
 */
private data class CropGestureElement(
  val state: RealCropState,
  val style: CropStyle,
  val gestures: CropGestures,
) : ModifierNodeElement<CropGestureNode>() {

  override fun create(): CropGestureNode = CropGestureNode(state, style, gestures)

  override fun update(node: CropGestureNode) {
    node.update(state, style, gestures)
  }

  override fun InspectorInfo.inspectableProperties() {
    name = "cropGestures"
    properties["state"] = state
    properties["style"] = style
    properties["gestures"] = gestures
  }
}

/**
 * The node behind [Modifier.cropGestures].
 *
 * A [DelegatingNode] over a [SuspendingPointerInputModifierNode] rather than `composed {}`, which
 * is deprecated for precisely this and allocates a fresh modifier on every recomposition, and
 * rather than `Modifier.pointerInput`, which cannot hold the fling job across gestures.
 *
 * **Nothing large lives here.** A [Modifier.Node] outlives recomposition by design and is released
 * only when the cropper leaves the tree, so a bitmap, a decoder or a tile buffer captured in this
 * class would be held for the whole life of the cropper. The fields below are a reference to the
 * hoisted state, a small style value and one [Job].
 */
internal class CropGestureNode(state: RealCropState, style: CropStyle, gestures: CropGestures) :
  DelegatingNode() {

  internal var state: RealCropState = state
    private set

  internal var style: CropStyle = style
    private set

  internal var gestures: CropGestures = gestures
    private set

  private var flingJob: Job? = null

  private val pointerInput: SuspendingPointerInputModifierNode = delegate(
    SuspendingPointerInputModifierNode { detectCropGestures(this@CropGestureNode) },
  )

  /**
   * Adopts a new state or style.
   *
   * The handler reads both through this node on every event, so most changes need nothing more than
   * the assignment. The exception is a change to the geometry a gesture already started against: a
   * different state object, or a touch radius that would now hit a different handle. Those restart
   * the detector, because finishing the drag under the old numbers is the wrong answer.
   */
  internal fun update(state: RealCropState, style: CropStyle, gestures: CropGestures) {
    val restart = this.state !== state ||
      this.style.handleTouchRadius != style.handleTouchRadius ||
      this.style.minimumCropSize != style.minimumCropSize ||
      // A gesture in flight was claimed under the old set. Turning zoom off mid-pinch and
      // letting the same gesture run on would leave the fingers driving nothing while still
      // holding the event stream away from the ancestor that could have used it.
      this.gestures != gestures
    this.state = state
    this.style = style
    this.gestures = gestures
    if (restart) pointerInput.resetPointerInputHandler()
  }

  /** Stops a fling in flight, which is what a new finger on the screen means. */
  internal fun cancelFling() {
    flingJob?.cancel()
    flingJob = null
  }

  internal fun startFling(velocity: Velocity) {
    cancelFling()
    flingJob = coroutineScope.launch {
      animateCropFling(velocity) { delta -> applyFlingStep(delta) }
    }
  }

  /** @return whether the image actually moved; `false` ends the fling against its bounds. */
  private fun applyFlingStep(delta: FloatPoint): Boolean {
    val before = state.transform.offset
    state.transform = state.transform.copy(offset = before + delta).sanitized()
    state.constrainImageToViewport()
    state.constrainFrameToImage()
    return state.transform.offset != before
  }

  override fun onDetach() {
    cancelFling()
    // The detector's own `finally` covers the ordinary cancellation, but the order in which a
    // delegate and its delegator are detached is not something to depend on, and a state left
    // interacting leaves the overlay's OnTouch grid drawn over a cropper nobody is touching.
    state.isInteracting = false
  }
}

/**
 * The state machine.
 *
 * Reads [node] rather than capturing a state and a style, so that a recomposition carrying new
 * values does not have to restart a gesture to be seen.
 */
private suspend fun PointerInputScope.detectCropGestures(node: CropGestureNode) {
  // A double tap is two gestures by definition, so what it needs to remember has to outlive the
  // block below.
  var previousTapUpMillis = 0L
  var previousTapPosition = Offset.Zero

  awaitEachGesture {
    val state = node.state
    val style = node.style
    val gestures = node.gestures
    val touchRadius = style.handleTouchRadius.toPx()
    val minimumSize = style.minimumCropSize.toPx()
    val touchSlop = viewConfiguration.touchSlop
    val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
    val longPressTimeout = viewConfiguration.longPressTimeoutMillis

    val down = awaitFirstDown(requireUnconsumed = false)
    node.cancelFling()
    state.isInteracting = true

    // One tracker per gesture, on the centroid rather than on any single finger: with two fingers
    // down the centroid is what the image is following, and a tracker fed one finger's path would
    // read a pinch as a fling.
    val velocityTracker = VelocityTracker()
    var lastSampleMillis = Long.MIN_VALUE

    // The two frozen values a crop drag is recomputed from. See `resolveCropRect`.
    var handle = hitTestCropHandle(state.cropRect, down.position.toFloatPoint(), touchRadius)
    var dragOrigin = state.cropRect
    var dragStart = down.position

    var travelled = false
    var claimed = false
    var rawRotation = state.transform.rotationDegrees
    var lastUpMillis = down.uptimeMillis

    try {
      while (true) {
        val event = awaitPointerEvent()
        val pressed = event.changes.count { it.pressed }
        if (pressed == 0) {
          lastUpMillis = event.changes.maxOf { it.uptimeMillis }
          break
        }

        if (pressed >= 2 && !claimed && gestures.movesImage) {
          // Claim the gesture the moment a second pointer arrives, ahead of any slop. A pinch is
          // unambiguous, so there is nothing to gain by waiting, and waiting is what produces the
          // pager-versus-zoom conflict: the ancestor's drag detector reaches its own slop first,
          // takes the gesture, and the pinch never starts at all.
          //
          // Only when there is something to claim it for. With a fixed image a pinch means
          // nothing here, and consuming it anyway would take the gesture away from an ancestor
          // that could have used it while doing nothing with it.
          claimed = true
          // A crop-rect drag never continues into a two-finger gesture.
          handle = null
        }

        if (claimed) {
          // Every change, every frame, not only the ones that moved. An ancestor still waiting for
          // its own slop reads an unconsumed change as permission to start.
          event.changes.fastForEach { if (!it.isConsumed) it.consume() }
        }

        if (pressed >= 2 && gestures.movesImage) {
          val sampleMillis = event.changes.first().uptimeMillis
          val currentCentroid = event.calculateCentroid(useCurrent = true)
          // Skip a sample whose timestamp has not advanced. A repeated timestamp is one of the two
          // ways the least-squares fit produces NaN, since it divides by the time span, and never
          // handing it one is cheaper than explaining the result afterwards.
          if (currentCentroid.isSpecified && sampleMillis > lastSampleMillis) {
            lastSampleMillis = sampleMillis
            velocityTracker.addPosition(sampleMillis, currentCentroid)
          }
          val centroid = event.calculateCentroid(useCurrent = false)
          if (centroid.isSpecified) {
            rawRotation = applyImageTransform(
              state = state,
              gestures = gestures,
              centroid = centroid.toFloatPoint(),
              pan = event.calculatePan().toFloatPoint(),
              zoom = event.calculateZoom(),
              rotation = event.calculateRotation(),
              rawRotationDegrees = rawRotation,
            )
          }
        } else if (pressed == 1) {
          // One finger is the crop rectangle's. The image deliberately does not move under a single
          // pointer: that is what leaves every part of the screen free to mean "adjust the frame".
          val position = event.changes.first().position
          if (!travelled && (position - down.position).getDistance() > touchSlop) {
            travelled = true
            // Re-freeze once, here rather than at the down, so the drag does not open with a jump
            // of one slop. Everything after this recomputes from these two values and nothing else.
            dragStart = position
            dragOrigin = state.cropRect
          }
          if (travelled && handle != null) {
            event.changes.fastForEach { if (!it.isConsumed) it.consume() }
            applyCropDrag(
              state = state,
              handle = handle,
              origin = dragOrigin,
              totalDelta = (position - dragStart).toFloatPoint(),
              minimumSize = minimumSize,
            )
          }
        }
      }

      val wasTap = !travelled && !claimed && lastUpMillis - down.uptimeMillis <= longPressTimeout
      if (!wasTap) {
        previousTapUpMillis = 0L
      } else if (
        gestures.zoom &&
        previousTapUpMillis != 0L &&
        down.uptimeMillis - previousTapUpMillis <= doubleTapTimeout &&
        (down.position - previousTapPosition).getDistance() <= touchSlop * 2f
      ) {
        previousTapUpMillis = 0L
        applyDoubleTapZoom(state, down.position.toFloatPoint())
      } else {
        previousTapUpMillis = lastUpMillis
        previousTapPosition = down.position
      }

      // A fling is panning carried on after the fingers leave, so it is the pan flag that gates
      // it, not the claim.
      if (claimed && gestures.pan) {
        // The bounded overload, and then `finiteVelocity` inside the fling: the clamp handles a
        // velocity that is merely enormous, the guard handles one that is NaN.
        node.startFling(velocityTracker.calculateVelocity(FLING_LIMIT))
      }
    } finally {
      state.isInteracting = false
    }
  }
}

/**
 * Applies one frame of a two-finger gesture and returns the **unsnapped** running rotation.
 *
 * The raw angle is carried in a local rather than read back from the transform because the stored
 * angle is snapped: reading it back would re-snap an already-snapped value every frame, the magnet
 * would never let go, and the image could not be turned through the snap window at all.
 */
private fun applyImageTransform(
  state: RealCropState,
  gestures: CropGestures,
  centroid: FloatPoint,
  pan: FloatPoint,
  zoom: Float,
  rotation: Float,
  rawRotationDegrees: Float,
): Float {
  val safeZoom = if (gestures.zoom && zoom.isFinite() && zoom > 0f) zoom else 1f
  val safeRotation = if (gestures.rotate && rotation.isFinite()) rotation else 0f
  val safePan = if (gestures.pan) pan.sanitized() else FloatPoint.Zero
  val current = state.transform
  val nextRaw = rawRotationDegrees + safeRotation
  val nextScale = (current.scale * safeZoom).coerceIn(MINIMUM_SCALE, MAXIMUM_SCALE)

  // `scaledAround` is the centre-pivot anchor, derived once in CropTransform and exact because the
  // centroid is taken relative to that pivot. Re-deriving it here against raw viewport coordinates
  // is the error its KDoc names: wrong by (1 - ratio) times the pivot, accumulating over a gesture
  // into the image swimming out from under the fingers.
  val anchored = current.scaledAround(nextScale, centroid.sanitized(), state.coordinateSpace.pivot)
  state.transform = anchored.copy(
    offset = anchored.offset + safePan,
    rotationDegrees = RotationSnap.snap(nextRaw),
  ).sanitized()
  // Any of the three can uncover a corner of the crop rectangle.
  state.constrainImageToViewport()
  state.constrainFrameToImage()
  return nextRaw
}

/** Resizes or moves the crop rectangle and writes it back as fractions of the viewport. */
private fun applyCropDrag(
  state: RealCropState,
  handle: CropHandle,
  origin: FloatRect,
  totalDelta: FloatPoint,
  minimumSize: Float,
) {
  val viewport = state.viewportSize
  if (viewport.isEmpty) return

  val resolved = resolveCropRect(
    origin = origin,
    handle = handle,
    delta = totalDelta,
    bounds = state.frameBounds,
    minimumSize = minimumSize,
    aspectRatio = state.aspectRatio,
  )
  // Normalised, never pixels: `cropRect` is derived from this and not the other way round, and a
  // rectangle stored in pixels does not survive the window it was measured in.
  state.normalizedCropRect = FloatRect(
    left = resolved.left / viewport.width,
    top = resolved.top / viewport.height,
    right = resolved.right / viewport.width,
    bottom = resolved.bottom / viewport.height,
  )
  // Moving or shrinking the rectangle uncovers the image exactly as panning it does.
  state.constrainImageToViewport()
  state.constrainFrameToImage()
}

/**
 * Toggles between the fitted image and [DOUBLE_TAP_SCALE], anchored under the finger.
 *
 * `1f` is the fit rather than an arbitrary floor: `contentBounds` is already the fitted rectangle,
 * so zooming back out lands exactly where the image opened.
 */
private fun applyDoubleTapZoom(state: RealCropState, centroid: FloatPoint) {
  val current = state.transform
  val target = if (current.scale < DOUBLE_TAP_SCALE) DOUBLE_TAP_SCALE else 1f
  state.transform = current
    .scaledAround(target, centroid.sanitized(), state.coordinateSpace.pivot)
    .sanitized()
  state.constrainImageToViewport()
  state.constrainFrameToImage()
}

private fun Offset.toFloatPoint(): FloatPoint = FloatPoint(x, y)

/** The fit. `contentBounds` is already the fitted rectangle, so one is as far out as it goes. */
private const val MINIMUM_SCALE = 1f
private const val MAXIMUM_SCALE = 10f

/** One double tap's worth of zoom: the factor, not the destination. */
private const val DOUBLE_TAP_SCALE = 2f

private val FLING_LIMIT = Velocity(MAXIMUM_FLING_VELOCITY, MAXIMUM_FLING_VELOCITY)
