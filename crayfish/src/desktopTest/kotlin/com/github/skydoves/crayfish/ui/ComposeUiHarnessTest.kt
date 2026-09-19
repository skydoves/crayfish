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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Proves the Compose UI test harness works before there is any cropper UI to point it at.
 *
 * The cropper's interaction surface is entirely gestural (pinch, pan, rotate, drag a handle), so
 * the tests that will matter most all run through `performTouchInput`. Standing that up now, on a
 * composable whose behaviour is trivially known, means that when a gesture test later goes red it
 * is the cropper that is wrong and not the apparatus.
 */
@OptIn(ExperimentalTestApi::class)
class ComposeUiHarnessTest {

  @Test
  fun rendersAndFindsANodeByTestTag() = runComposeUiTest {
    setContent {
      Box(Modifier.size(200.dp).testTag("surface"))
    }

    onNodeWithTag("surface").assertIsDisplayed()
  }

  /**
   * A multi-touch pinch reaches a `pointerInput` block. This is the one that matters: the whole
   * gesture layer is built on two-finger input, and a harness that silently delivers nothing would
   * make every future gesture test pass for the wrong reason.
   */
  @Test
  fun deliversAMultiTouchPinchToAPointerInputBlock() = runComposeUiTest {
    var observedPointerCount = 0

    setContent {
      Box(
        Modifier
          .size(300.dp)
          .testTag("surface")
          .pointerInput(Unit) {
            awaitPointerEventScope {
              while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.count { it.pressed }
                if (pressed > observedPointerCount) observedPointerCount = pressed
              }
            }
          },
      )
    }

    onNodeWithTag("surface").performTouchInput {
      pinch(
        start0 = Offset(centerX - 20f, centerY),
        end0 = Offset(centerX - 120f, centerY),
        start1 = Offset(centerX + 20f, centerY),
        end1 = Offset(centerX + 120f, centerY),
      )
    }
    waitForIdle()

    assertTrue(
      observedPointerCount >= 2,
      "the harness delivered at most $observedPointerCount simultaneous pointers; " +
        "every gesture test depends on two arriving",
    )
  }
}
