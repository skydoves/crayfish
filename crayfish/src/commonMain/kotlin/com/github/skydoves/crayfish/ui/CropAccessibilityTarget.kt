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

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.github.skydoves.crayfish.geometry.FloatPoint

/**
 * The node a screen reader, a keyboard and a D-pad talk to.
 *
 * Deliberately a sibling of the chrome rather than a wrapper around it, and deliberately empty.
 * The state description has to change when the rectangle does, and a semantics configuration is
 * not re-read because something was read in the draw phase: only a recomposition (or an explicit
 * invalidation Compose does not expose here) rebuilds it. So this one composable does read
 * gesture-driven state in composition, which is the thing the overlay otherwise never does. It
 * costs one childless, drawing-free layout node per frame of a drag; putting the same read one
 * level up would instead rebuild the chrome's modifier chain each frame and throw away the cached
 * paths in `Modifier.drawWithCache`.
 *
 * It draws nothing and adds no pointer input, so it does not intercept the drag underneath it.
 */
@Composable
internal fun CropAccessibilityTarget(
  state: RealCropState,
  style: CropStyle,
  accessibility: CropAccessibility,
  modifier: Modifier = Modifier,
) {
  val density = LocalDensity.current
  val step = with(density) { accessibility.step.toPx() }
  val minimum = with(density) { style.minimumCropSize.toPx() }

  // The one composition-phase read of gesture-driven state in the whole overlay. See the KDoc.
  val description = accessibility.describe(state.cropRect, state.viewportSize)

  // The actions themselves do not depend on where the rectangle is, since they read it when
  // invoked, so a drag rebuilds one string rather than thirteen action objects.
  val actions = remember(state, accessibility, step, minimum) {
    CropAccessibilityAction.entries.map { action ->
      CustomAccessibilityAction(accessibility.label(action)) {
        state.performCropAction(action, step, minimum)
        true
      }
    }
  }

  Box(
    modifier
      .onKeyEvent { event -> state.onCropKeyEvent(event, step, minimum) }
      .focusable()
      .semantics {
        contentDescription = accessibility.contentDescription
        stateDescription = description
        customActions = actions
      },
  )
}

/**
 * Runs [action] through the same mutation a drag uses.
 *
 * The mapping is the whole of it: an action is a [CropHandle] plus a one-step delta. Nothing here
 * clamps, because [nudgeCropRect] already does and a second copy of those rules is how the
 * keyboard path and the drag path start to disagree.
 */
internal fun RealCropState.performCropAction(
  action: CropAccessibilityAction,
  step: Float,
  minimum: Float,
) {
  when (action) {
    CropAccessibilityAction.MoveLeft -> nudge(CropHandle.Inside, -step, 0f, minimum)
    CropAccessibilityAction.MoveUp -> nudge(CropHandle.Inside, 0f, -step, minimum)
    CropAccessibilityAction.MoveRight -> nudge(CropHandle.Inside, step, 0f, minimum)
    CropAccessibilityAction.MoveDown -> nudge(CropHandle.Inside, 0f, step, minimum)
    CropAccessibilityAction.GrowLeft -> nudge(CropHandle.Left, -step, 0f, minimum)
    CropAccessibilityAction.ShrinkLeft -> nudge(CropHandle.Left, step, 0f, minimum)
    CropAccessibilityAction.GrowTop -> nudge(CropHandle.Top, 0f, -step, minimum)
    CropAccessibilityAction.ShrinkTop -> nudge(CropHandle.Top, 0f, step, minimum)
    CropAccessibilityAction.GrowRight -> nudge(CropHandle.Right, step, 0f, minimum)
    CropAccessibilityAction.ShrinkRight -> nudge(CropHandle.Right, -step, 0f, minimum)
    CropAccessibilityAction.GrowBottom -> nudge(CropHandle.Bottom, 0f, step, minimum)
    CropAccessibilityAction.ShrinkBottom -> nudge(CropHandle.Bottom, 0f, -step, minimum)
    CropAccessibilityAction.Reset -> reset()
  }
}

private fun RealCropState.nudge(handle: CropHandle, dx: Float, dy: Float, minimum: Float) {
  nudgeCropRect(handle, FloatPoint(dx, dy), minimum)
}

/**
 * Arrow keys move the rectangle; an arrow with a modifier resizes it.
 *
 * Desktop, ChromeOS and TV all reach this composable without a finger, and a D-pad has no modifier
 * key at all, which is why the twelve [CropAccessibilityAction]s exist as well: bare arrows give
 * a D-pad the four moves, and the actions menu gives it the eight resizes.
 *
 * Shift grows the edge the arrow points at, Ctrl or Alt pulls it back in. Shift wins if both are
 * held, because "bigger" is the one a user is more likely to have meant by leaning on modifiers.
 *
 * @return true when the key was one of ours, which also stops the arrow from moving focus off the
 *   cropper: arrow keys have to mean "move the rectangle" while it is focused.
 */
internal fun RealCropState.onCropKeyEvent(event: KeyEvent, step: Float, minimum: Float): Boolean {
  if (event.type != KeyEventType.KeyDown) return false
  val grow = event.isShiftPressed
  val shrink = !grow && (event.isCtrlPressed || event.isAltPressed)
  val action = when (event.key) {
    Key.DirectionLeft -> when {
      grow -> CropAccessibilityAction.GrowLeft
      shrink -> CropAccessibilityAction.ShrinkLeft
      else -> CropAccessibilityAction.MoveLeft
    }

    Key.DirectionRight -> when {
      grow -> CropAccessibilityAction.GrowRight
      shrink -> CropAccessibilityAction.ShrinkRight
      else -> CropAccessibilityAction.MoveRight
    }

    Key.DirectionUp -> when {
      grow -> CropAccessibilityAction.GrowTop
      shrink -> CropAccessibilityAction.ShrinkTop
      else -> CropAccessibilityAction.MoveUp
    }

    Key.DirectionDown -> when {
      grow -> CropAccessibilityAction.GrowBottom
      shrink -> CropAccessibilityAction.ShrinkBottom
      else -> CropAccessibilityAction.MoveDown
    }

    else -> return false
  }
  performCropAction(action, step, minimum)
  return true
}
