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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics

/**
 * The scrim, frame, composition guides and drag handles drawn over the image.
 *
 * Public so an app can replace it wholesale: the shape of the chrome is the part every product
 * wants to own.
 *
 * The overlay is two nodes, not one, and the split is load-bearing:
 *
 * - the **chrome** draws and is the surface a drag lands on. It is silenced with
 *   `clearAndSetSemantics {}`, because a full-screen node a screen reader can focus but not
 *   usefully operate offers a drag surface next to the actions that actually work, with no way to
 *   tell them apart.
 * - the **accessible node** carries the name, the state description, thirteen custom actions and
 *   the key handling, and draws nothing. It is where [CropAccessibilityAction] is exposed, the
 *   library's answer to WCAG 2.2 SC 2.5.7, which makes drag-only resizing a Level AA failure.
 *
 * Both paths end in the same mutation (`nudgeCropRect`), so a nudge from a screen reader and a
 * nudge from a finger cannot disagree about the minimum size or a locked ratio.
 *
 * @param shape the mask the cut-out and the frame both follow. Orthogonal to the aspect ratio: a
 *   circular mask on a 16:9 crop is a legitimate thing to ask for.
 * @param accessibility the labels, the step size and the announced description. Every string is a
 *   parameter because only the caller can reach a localised string resource.
 */
@Composable
public fun CropOverlay(
  state: CropState,
  modifier: Modifier = Modifier,
  style: CropStyle = CropStyle.Default,
  // Defaults to the state's, so setting `state.shape` drives the viewfinder and the output
  // together. Passing it here still wins, for a caller who wants them to differ.
  shape: CropShape = state.shape,
  accessibility: CropAccessibility = CropAccessibility.Default,
) {
  // Sealed, single implementation. See the note in `Cropper`.
  val real = state as RealCropState

  Box(modifier.fillMaxSize()) {
    Box(
      Modifier
        .matchParentSize()
        .clearAndSetSemantics {}
        .cropOverlayChrome(state, style, shape),
    )
    CropAccessibilityTarget(
      state = real,
      style = style,
      accessibility = accessibility,
      modifier = Modifier.matchParentSize(),
    )
  }
}
