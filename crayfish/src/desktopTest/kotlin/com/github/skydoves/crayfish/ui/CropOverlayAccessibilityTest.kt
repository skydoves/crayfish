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
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.skydoves.crayfish.geometry.FloatPoint
import com.github.skydoves.crayfish.geometry.FloatSize
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The drag-free path through the crop rectangle.
 *
 * [WCAG 2.2 SC 2.5.7](https://www.w3.org/WAI/WCAG22/Understanding/dragging-movements.html) is a
 * Level AA criterion and it is not satisfied by *having* actions; it is satisfied by actions that
 * reach the same crops a drag reaches, and that stop where a drag stops. So these tests check the
 * arithmetic and the limits, not merely that a label exists.
 */
@OptIn(ExperimentalTestApi::class)
class CropOverlayAccessibilityTest {

  @Test
  fun `every move action shifts the rectangle one step in its own direction`() {
    val expected = mapOf(
      CropAccessibilityAction.MoveLeft to FloatPoint(-STEP_PX, 0f),
      CropAccessibilityAction.MoveRight to FloatPoint(STEP_PX, 0f),
      CropAccessibilityAction.MoveUp to FloatPoint(0f, -STEP_PX),
      CropAccessibilityAction.MoveDown to FloatPoint(0f, STEP_PX),
    )
    expected.forEach { (action, shift) ->
      runComposeUiTest {
        val state = cropStateForTest()
        showCropOverlay(state)
        perform(action)
        assertRect(OPENING_CROP.translate(shift), state.cropRect, "after $action")
      }
    }
  }

  @Test
  fun `every edge action moves that one edge by one step`() {
    val expected = mapOf(
      CropAccessibilityAction.GrowLeft to OPENING_CROP.copy(left = 40f - STEP_PX),
      CropAccessibilityAction.ShrinkLeft to OPENING_CROP.copy(left = 40f + STEP_PX),
      CropAccessibilityAction.GrowTop to OPENING_CROP.copy(top = 40f - STEP_PX),
      CropAccessibilityAction.ShrinkTop to OPENING_CROP.copy(top = 40f + STEP_PX),
      CropAccessibilityAction.GrowRight to OPENING_CROP.copy(right = 360f + STEP_PX),
      CropAccessibilityAction.ShrinkRight to OPENING_CROP.copy(right = 360f - STEP_PX),
      CropAccessibilityAction.GrowBottom to OPENING_CROP.copy(bottom = 360f + STEP_PX),
      CropAccessibilityAction.ShrinkBottom to OPENING_CROP.copy(bottom = 360f - STEP_PX),
    )
    expected.forEach { (action, rect) ->
      runComposeUiTest {
        val state = cropStateForTest()
        showCropOverlay(state)
        perform(action)
        assertRect(rect, state.cropRect, "after $action")
      }
    }
  }

  @Test
  fun `the reset action puts the rectangle back where it opened`() = runComposeUiTest {
    val state = cropStateForTest()
    showCropOverlay(state)

    perform(CropAccessibilityAction.MoveLeft, times = 2)
    perform(CropAccessibilityAction.ShrinkBottom)
    assertNotEquals(OPENING_CROP, state.cropRect, "the setup never moved the rectangle")

    perform(CropAccessibilityAction.Reset)
    assertRect(OPENING_CROP, state.cropRect, "after Reset")
  }

  /**
   * A state description that never changes is the accessibility equivalent of a status light that
   * is always green: it is announced, it is wrong, and nobody notices.
   */
  @Test
  fun `the state description exists and follows the rectangle`() = runComposeUiTest {
    val state = cropStateForTest()
    showCropOverlay(state)

    val opening = assertNotNull(stateDescription(), "no stateDescription on the crop node")
    assertTrue(opening.isNotBlank(), "the stateDescription was blank")
    assertTrue("80%" in opening, "expected the opening 80% x 80% rectangle, was \"$opening\"")
    assertTrue("10%" in opening, "expected the opening 10% offsets, was \"$opening\"")

    perform(CropAccessibilityAction.ShrinkLeft, times = 4)
    val after = assertNotNull(stateDescription(), "the stateDescription disappeared")
    assertNotEquals(opening, after, "the stateDescription did not follow the rectangle")
    // 40 + 64 = 104 of 400 across, and 256 of 400 wide.
    assertTrue("64%" in after, "expected a 64%-wide rectangle, was \"$after\"")
    assertTrue("26%" in after, "expected a 26% left offset, was \"$after\"")
  }

  @Test
  fun `arrow keys move the rectangle`() {
    val expected = mapOf(
      Key.DirectionLeft to FloatPoint(-STEP_PX, 0f),
      Key.DirectionRight to FloatPoint(STEP_PX, 0f),
      Key.DirectionUp to FloatPoint(0f, -STEP_PX),
      Key.DirectionDown to FloatPoint(0f, STEP_PX),
    )
    expected.forEach { (key, shift) ->
      runComposeUiTest {
        val state = cropStateForTest()
        showCropOverlay(state)
        cropNode().requestFocus()
        cropNode().performKeyInput { pressKey(key) }
        waitForIdle()
        assertRect(OPENING_CROP.translate(shift), state.cropRect, "after $key")
      }
    }
  }

  @Test
  fun `shift with an arrow grows that edge and control with an arrow pulls it back`() {
    val expected = listOf(
      Triple(Key.ShiftLeft, Key.DirectionLeft, OPENING_CROP.copy(left = 40f - STEP_PX)),
      Triple(Key.ShiftLeft, Key.DirectionUp, OPENING_CROP.copy(top = 40f - STEP_PX)),
      Triple(Key.ShiftLeft, Key.DirectionRight, OPENING_CROP.copy(right = 360f + STEP_PX)),
      Triple(Key.ShiftLeft, Key.DirectionDown, OPENING_CROP.copy(bottom = 360f + STEP_PX)),
      Triple(Key.CtrlLeft, Key.DirectionLeft, OPENING_CROP.copy(left = 40f + STEP_PX)),
      Triple(Key.CtrlLeft, Key.DirectionUp, OPENING_CROP.copy(top = 40f + STEP_PX)),
      Triple(Key.CtrlLeft, Key.DirectionRight, OPENING_CROP.copy(right = 360f - STEP_PX)),
      Triple(Key.CtrlLeft, Key.DirectionDown, OPENING_CROP.copy(bottom = 360f - STEP_PX)),
    )
    expected.forEach { (modifier, arrow, rect) ->
      runComposeUiTest {
        val state = cropStateForTest()
        showCropOverlay(state)
        cropNode().requestFocus()
        cropNode().performKeyInput { withKeyDown(modifier) { pressKey(arrow) } }
        waitForIdle()
        assertRect(rect, state.cropRect, "after $modifier + $arrow")
      }
    }
  }

  @Test
  fun `shrinking stops at the minimum crop size`() = runComposeUiTest {
    val state = cropStateForTest()
    showCropOverlay(state)

    // Far more steps than the 320px rectangle has room for: 16 would reach the floor exactly.
    perform(CropAccessibilityAction.ShrinkLeft, times = 30)
    assertEquals(MINIMUM_PX, state.cropRect.width, 0.01f, "width below the minimum crop size")
    assertEquals(360f, state.cropRect.right, 0.01f, "the anchored edge moved")

    perform(CropAccessibilityAction.ShrinkTop, times = 30)
    assertEquals(MINIMUM_PX, state.cropRect.height, 0.01f, "height below the minimum crop size")
    assertEquals(360f, state.cropRect.bottom, 0.01f, "the anchored edge moved")
  }

  @Test
  fun `moving and growing stop at the viewport bounds`() = runComposeUiTest {
    val state = cropStateForTest()
    showCropOverlay(state)

    perform(CropAccessibilityAction.MoveLeft, times = 10)
    assertEquals(0f, state.cropRect.left, 0.01f, "the rectangle left the viewport")
    assertEquals(320f, state.cropRect.width, 0.01f, "a move changed the size")

    perform(CropAccessibilityAction.MoveDown, times = 10)
    assertEquals(VIEWPORT_PX.toFloat(), state.cropRect.bottom, 0.01f, "the rectangle fell out")

    perform(CropAccessibilityAction.GrowRight, times = 10)
    assertEquals(VIEWPORT_PX.toFloat(), state.cropRect.right, 0.01f, "the right edge overran")
    perform(CropAccessibilityAction.GrowTop, times = 10)
    assertEquals(0f, state.cropRect.top, 0.01f, "the top edge overran")
  }

  /**
   * A locked ratio is the case where a second copy of the resize arithmetic would show up first:
   * clamping width and height independently is the obvious implementation and it silently unlocks
   * the ratio at every boundary.
   */
  @Test
  fun `a locked aspect ratio survives every resize action`() {
    listOf(1f, 1.5f, 0.75f).forEach { ratio ->
      runComposeUiTest {
        val state = cropStateForTest(AspectRatio.Fixed(ratio))
        showCropOverlay(state)

        listOf(
          CropAccessibilityAction.GrowLeft,
          CropAccessibilityAction.GrowTop,
          CropAccessibilityAction.ShrinkRight,
          CropAccessibilityAction.ShrinkBottom,
          CropAccessibilityAction.GrowBottom,
          CropAccessibilityAction.GrowRight,
        ).forEach { action ->
          perform(action)
          val rect = state.cropRect
          assertEquals(
            ratio,
            rect.width / rect.height,
            0.001f,
            "$action unlocked the $ratio ratio: $rect",
          )
        }

        // And at the boundary, where the naive implementation gives up on the ratio.
        perform(CropAccessibilityAction.GrowLeft, times = 40)
        val stretched = state.cropRect
        assertEquals(
          ratio,
          stretched.width / stretched.height,
          0.001f,
          "the $ratio ratio broke against the viewport edge: $stretched",
        )
        assertTrue(
          stretched.left >= -0.01f && stretched.right <= VIEWPORT_PX + 0.01f &&
            stretched.top >= -0.01f && stretched.bottom <= VIEWPORT_PX + 0.01f,
          "the ratio was kept by leaving the viewport: $stretched",
        )
      }
    }
  }

  @Test
  fun `a move action keeps the size it was given`() = runComposeUiTest {
    val state = cropStateForTest(AspectRatio.Fixed(1f))
    showCropOverlay(state)

    val before = state.cropRect
    perform(CropAccessibilityAction.MoveRight, times = 2)
    val after = state.cropRect
    assertTrue(
      abs(before.width - after.width) < 0.01f && abs(before.height - after.height) < 0.01f,
      "a move resized the rectangle: $before -> $after",
    )
  }

  /**
   * The pointer surface must not be offered alongside the actions.
   *
   * A screen reader that finds a full-screen "crop overlay" node it can focus but only drag is
   * worse than one that finds nothing there: the user cannot tell it apart from the node that does
   * work. `clearAndSetSemantics {}` is what keeps it out of the tree's vocabulary.
   */
  @Test
  fun `the drawing and pointer surface announces nothing`() = runComposeUiTest {
    val state = cropStateForTest()
    showCropOverlay(state)

    val accessible = onNodeWithContentDescription(
      label = CropAccessibility.Default.contentDescription,
      useUnmergedTree = true,
    ).fetchSemanticsNode()
    val parent = assertNotNull(accessible.parent, "the overlay root vanished")
    val others = parent.children.filter { it.id != accessible.id }
    assertTrue(others.isNotEmpty(), "the chrome node was not in the tree at all")
    others.forEach { node ->
      assertTrue(
        node.config.none(),
        "the chrome offers semantics of its own: ${node.config.joinToString { it.key.name }}",
      )
    }
  }

  /**
   * The accessible node sits on top of the whole overlay, so it must not swallow the drag.
   *
   * It carries `focusable()`, which is a focus target and a semantics node but deliberately not a
   * pointer input node. If that ever changes, the gesture layer stops receiving anything and the
   * accessibility work would have broken the very interaction it exists to supplement.
   */
  @Test
  fun `the accessible node does not intercept pointer input`() = runComposeUiTest {
    val state = cropStateForTest()
    var pointersSeen = 0

    setContent {
      CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
        Box(
          Modifier
            .size(VIEWPORT_PX.dp)
            .testTag(SCENE_TAG)
            .onSizeChanged {
              state.viewportSize = FloatSize(it.width.toFloat(), it.height.toFloat())
            },
        ) {
          // Stands in for the gesture layer, underneath the overlay exactly as it will be.
          Box(
            Modifier.matchParentSize().pointerInput(Unit) {
              awaitPointerEventScope {
                while (true) {
                  val event = awaitPointerEvent()
                  pointersSeen += event.changes.count { change -> change.pressed }
                }
              }
            },
          )
          CropOverlay(state = state)
        }
      }
    }
    waitForIdle()

    onNodeWithTag(SCENE_TAG).performTouchInput {
      down(Offset(200f, 200f))
      moveTo(Offset(240f, 220f))
      up()
    }
    waitForIdle()

    assertTrue(pointersSeen > 0, "the accessible node swallowed the drag the gestures need")
  }

  private fun ComposeUiTest.stateDescription(): String? =
    cropNode().fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
}
