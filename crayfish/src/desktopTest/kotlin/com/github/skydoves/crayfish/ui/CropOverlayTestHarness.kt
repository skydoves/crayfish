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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.skydoves.crayfish.decode.CropSource
import com.github.skydoves.crayfish.geometry.FloatRect
import com.github.skydoves.crayfish.geometry.FloatSize
import kotlin.test.assertEquals

/**
 * A crop overlay in a viewport of known size, at a density of exactly one.
 *
 * Density is pinned rather than inherited so that every figure in these tests is both a `dp` and a
 * pixel: the step is 16 of each, the minimum crop 56 of each, and an assertion that reads
 * `left == 24f` needs no arithmetic to check by eye. It is also the difference between a test that
 * fails on a HiDPI CI machine and one that does not.
 *
 * The viewport is published the way `Cropper` publishes it (from `onSizeChanged`) rather than
 * assigned, so the rectangle under test is measured against a real layout.
 */
internal const val VIEWPORT_PX: Int = 400

/** The step and the minimum crop size, in pixels, at the pinned density. */
internal const val STEP_PX: Float = 16f
internal const val MINIMUM_PX: Float = 56f

/** `RealCropState.DEFAULT_NORMALIZED_CROP` is 0.1..0.9, so a 400px viewport opens here. */
internal val OPENING_CROP: FloatRect = FloatRect(40f, 40f, 360f, 360f)

internal const val SCENE_TAG: String = "scene"

internal fun cropStateForTest(aspectRatio: AspectRatio = AspectRatio.Free): RealCropState =
  RealCropState(
    source = CropSource.Bytes(ByteArray(0), cacheKey = "overlay-test"),
    initialAspectRatio = aspectRatio,
  )

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.showCropOverlay(
  state: RealCropState,
  style: CropStyle = CropStyle.Default,
  shape: CropShape = CropShape.Rectangle,
  accessibility: CropAccessibility = CropAccessibility.Default,
  background: Color = Color.Blue,
  direction: LayoutDirection = LayoutDirection.Ltr,
) {
  setContent {
    CompositionLocalProvider(
      LocalDensity provides Density(density = 1f, fontScale = 1f),
      LocalLayoutDirection provides direction,
    ) {
      Box(
        Modifier
          .size(VIEWPORT_PX.dp)
          .testTag(SCENE_TAG)
          .background(background)
          .onSizeChanged {
            state.viewportSize = FloatSize(it.width.toFloat(), it.height.toFloat())
          },
      ) {
        CropOverlay(
          state = state,
          style = style,
          modifier = Modifier,
          shape = shape,
          accessibility = accessibility,
        )
      }
    }
  }
  waitForIdle()
}

/** The overlay's accessible node, found by its name, which is the thing a user hears first. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.cropNode(
  accessibility: CropAccessibility = CropAccessibility.Default,
): SemanticsNodeInteraction = onNodeWithContentDescription(accessibility.contentDescription)

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.perform(
  action: CropAccessibilityAction,
  accessibility: CropAccessibility = CropAccessibility.Default,
  times: Int = 1,
) {
  repeat(times) {
    cropNode(accessibility).performCustomAccessibilityActionWithLabel(accessibility.label(action))
    waitForIdle()
  }
}

internal fun assertRect(expected: FloatRect, actual: FloatRect, message: String) {
  val tolerance = 0.01f
  assertEquals(expected.left, actual.left, tolerance, "$message: left")
  assertEquals(expected.top, actual.top, tolerance, "$message: top")
  assertEquals(expected.right, actual.right, tolerance, "$message: right")
  assertEquals(expected.bottom, actual.bottom, tolerance, "$message: bottom")
}
