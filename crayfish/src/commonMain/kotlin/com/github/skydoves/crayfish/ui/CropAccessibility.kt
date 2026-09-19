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

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.skydoves.crayfish.geometry.FloatRect
import com.github.skydoves.crayfish.geometry.FloatSize
import kotlin.math.roundToInt

/**
 * A single step the crop rectangle can be moved or resized by without a drag.
 *
 * **This enum is the library's answer to [WCAG 2.2 SC 2.5.7 Dragging
 * Movements](https://www.w3.org/WAI/WCAG22/Understanding/dragging-movements.html)**, which is a
 * Level AA success criterion: any function operated by dragging must also be operable by a single
 * pointer without dragging. A crop rectangle whose only affordance is a handle you drag therefore
 * *fails* AA outright, not "could be better".
 *
 * Twelve of these move one edge or the whole rectangle by [CropAccessibility.step]; [Reset] puts
 * it back. Together they are a complete, drag-free way to reach any crop the drag can reach, which
 * is the bar the criterion actually sets. They are surfaced as `CustomAccessibilityAction`s, which
 * TalkBack and VoiceOver present in their actions menu, and they are the same twelve operations
 * the arrow keys perform. See `CropOverlay`.
 */
public enum class CropAccessibilityAction {
  MoveLeft,
  MoveUp,
  MoveRight,
  MoveDown,
  GrowLeft,
  ShrinkLeft,
  GrowTop,
  ShrinkTop,
  GrowRight,
  ShrinkRight,
  GrowBottom,
  ShrinkBottom,
  Reset,
}

/**
 * How the crop overlay presents itself to assistive technology, and how far one step moves.
 *
 * Every string is a parameter because a hard-coded English label is an accessibility bug in every
 * other locale, and the caller is the only one who can reach a string resource. [label] is a
 * function rather than thirteen fields so that localising is one lambda:
 * `CropAccessibility(label = { stringResource(it.stringRes) })`.
 *
 * `@Immutable` for the same reason [CropStyle] is: an unstable parameter here would recompose the
 * overlay on every frame of a drag.
 */
@Immutable
public data class CropAccessibility(
  /**
   * How far one action or one arrow-key press moves an edge.
   *
   * Large enough to be worth pressing and small enough to be precise. Holding an arrow key repeats
   * it, so this is a floor on precision, not a ceiling on travel.
   */
  public val step: Dp = 16.dp,

  /** The rectangle's name. Without one the node has no accessible name, which is WCAG 4.1.2. */
  public val contentDescription: String = "Crop area",

  /** What an assistive technology announces for each action in its menu. */
  public val label: (CropAccessibilityAction) -> String = ::defaultCropActionLabel,

  /**
   * What an assistive technology announces about where the rectangle currently is.
   *
   * Reported as percentages of the viewport rather than pixels on purpose: "320 by 320" means
   * nothing to someone who cannot see the viewport it sits in, whereas "80% wide" is a fact about
   * the picture they are cropping.
   */
  public val describe: (rect: FloatRect, viewport: FloatSize) -> String = ::defaultCropDescription,
) {
  public companion object {
    public val Default: CropAccessibility = CropAccessibility()
  }
}

/**
 * The English labels.
 *
 * "Outward" and "inward" rather than a compass direction: which way an edge has to travel to make
 * the rectangle bigger is the part a listener cannot see, and it is the part that changes between
 * the left edge and the right one.
 */
public fun defaultCropActionLabel(action: CropAccessibilityAction): String = when (action) {
  CropAccessibilityAction.MoveLeft -> "Move crop area left"
  CropAccessibilityAction.MoveUp -> "Move crop area up"
  CropAccessibilityAction.MoveRight -> "Move crop area right"
  CropAccessibilityAction.MoveDown -> "Move crop area down"
  CropAccessibilityAction.GrowLeft -> "Move left edge outward"
  CropAccessibilityAction.ShrinkLeft -> "Move left edge inward"
  CropAccessibilityAction.GrowTop -> "Move top edge outward"
  CropAccessibilityAction.ShrinkTop -> "Move top edge inward"
  CropAccessibilityAction.GrowRight -> "Move right edge outward"
  CropAccessibilityAction.ShrinkRight -> "Move right edge inward"
  CropAccessibilityAction.GrowBottom -> "Move bottom edge outward"
  CropAccessibilityAction.ShrinkBottom -> "Move bottom edge inward"
  CropAccessibilityAction.Reset -> "Reset crop area"
}

/** Position and size as whole percentages of the viewport, read after the accessible name. */
public fun defaultCropDescription(rect: FloatRect, viewport: FloatSize): String {
  if (viewport.isEmpty || rect.isEmpty || !rect.isFinite) return "not yet placed"
  return "${percent(rect.width, viewport.width)}% wide and " +
    "${percent(rect.height, viewport.height)}% tall, " +
    "${percent(rect.left, viewport.width)}% from the left and " +
    "${percent(rect.top, viewport.height)}% from the top"
}

private fun percent(value: Float, total: Float): Int = (value / total * 100f).roundToInt()
