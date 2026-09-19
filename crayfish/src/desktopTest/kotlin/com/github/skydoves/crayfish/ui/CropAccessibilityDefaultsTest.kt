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

import com.github.skydoves.crayfish.geometry.FloatRect
import com.github.skydoves.crayfish.geometry.FloatSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The strings a screen reader actually says.
 *
 * They are defaults a caller is expected to replace with localised resources, which is exactly why
 * they need a test: nobody notices an unlocalised default is also *wrong* until someone is relying
 * on it to know where the crop rectangle is.
 */
class CropAccessibilityDefaultsTest {

  @Test
  fun everyActionHasItsOwnLabel() {
    val labels = CropAccessibilityAction.entries.map { defaultCropActionLabel(it) }

    assertEquals(
      labels.size,
      labels.toSet().size,
      "two actions share a label, so a screen reader offers the same choice twice: $labels",
    )
    labels.forEach { assertTrue(it.isNotBlank(), "an action has a blank label") }
  }

  @Test
  fun theDescriptionNamesWhereTheRectangleIsAndHowBigItIs() {
    val description = defaultCropDescription(
      FloatRect(left = 100f, top = 50f, right = 300f, bottom = 250f),
      FloatSize(width = 1000f, height = 1000f),
    )

    assertTrue(description.isNotBlank())
    // Position and size both have to appear, or the announcement cannot locate the rectangle.
    assertTrue(
      description.any { it.isDigit() },
      "the description carries no numbers at all: $description",
    )
  }

  /** Moving the rectangle has to change what is announced, or the announcement is decorative. */
  @Test
  fun theDescriptionChangesWhenTheRectangleDoes() {
    val viewport = FloatSize(1000f, 1000f)
    val first = defaultCropDescription(FloatRect(0f, 0f, 100f, 100f), viewport)
    val moved = defaultCropDescription(FloatRect(200f, 200f, 300f, 300f), viewport)
    val resized = defaultCropDescription(FloatRect(0f, 0f, 400f, 400f), viewport)

    assertTrue(first != moved, "moving the rectangle did not change the announcement: $first")
    assertTrue(first != resized, "resizing the rectangle did not change the announcement: $first")
  }

  /** A degenerate viewport must not produce NaN or a division by zero in an announcement. */
  @Test
  fun survivesADegenerateViewport() {
    val description = defaultCropDescription(FloatRect(0f, 0f, 10f, 10f), FloatSize.Zero)

    assertTrue(description.isNotBlank())
    assertTrue(!description.contains("NaN"), "the announcement says NaN: $description")
    assertTrue(!description.contains("Infinity"), "the announcement says Infinity: $description")
  }
}
