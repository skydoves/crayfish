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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/**
 * Presents whatever crop [cropper] currently has in flight, and nothing when it has none.
 *
 * @param controls the confirm and cancel chrome. The default is built from `foundation` alone, so
 *   that drawing two buttons does not drag Material into every consumer's dependency graph.
 *
 * @param properties how the platform dialog behaves. The default turns off the platform's own
 *   width so the crop surface fills the dialog: a dialog sized to its platform default letterboxes
 *   the very thing being framed. Override it to control dismiss on back or on an outside tap.
 * @param shape the mask drawn over the crop rectangle. Independent of the aspect ratio, so a
 *   circular mask does not force a square output.
 * @param accessibility the strings and step size an assistive technology uses.
 */
@Composable
public fun ImageCropperDialog(
  cropper: ImageCropper,
  modifier: Modifier = Modifier,
  style: CropStyle = CropStyle.Default,
  gestures: CropGestures = CropGestures.Default,
  shape: CropShape = CropShape.Rectangle,
  accessibility: CropAccessibility = CropAccessibility.Default,
  properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false),
  controls: @Composable (confirm: () -> Unit, cancel: () -> Unit) -> Unit = { confirm, cancel ->
    DefaultCropControls(confirm, cancel)
  },
) {
  // Sealed, single implementation. See the note in `Cropper`.
  val real = cropper as RealImageCropper
  val request = real.request ?: return

  Dialog(
    onDismissRequest = { real.finish(request.output.cancelled()) },
    properties = properties,
  ) {
    val state = rememberCropState(request.source, request.aspectRatio)
    // So the dialog's mask is cut out of its result, not merely drawn over it.
    state.shape = shape
    val scope = rememberCoroutineScope()
    // Keyed to the request, not merely remembered. This latch stops a second tap starting a
    // second encode of the same crop. Left un-keyed it also survives into the *next* crop
    // whenever Compose reuses this content's slot, which the desktop Dialog does: its window is
    // torn down asynchronously and a new request can compose before the old content is discarded.
    // The result was a confirm button that did nothing on every crop after the first, silently,
    // because `cropping` was already true and the caller waited for ever.
    var cropping by remember(request) { mutableStateOf(false) }

    // Keyed to the request as well as the state. `rememberCropState` keys its saver on the
    // source, so cropping the *same* image twice hands back an identical state instance; keyed on
    // the state alone this effect never re-runs across a reused composition. Since finishing a
    // crop sets `attachedState` back to null, the second crop of one image then published no
    // state at all: no dialog, and a caller suspended for ever.
    LaunchedEffect(request, state) { real.attachedState = state }

    Box(modifier.fillMaxSize().background(Color.Black)) {
      Cropper(
        state = state,
        modifier = Modifier.fillMaxSize(),
        style = style,
        gestures = gestures,
        // Passed through rather than left to the default. Without this the one line API could not
        // produce a circular crop, which is the most common shape an app asks for, and could not
        // localize a single accessibility string: the defaults are hardcoded English, and the
        // WCAG 2.2 conformance this library claims would have shipped untranslatable on the path
        // most callers use.
        overlay = { cropState ->
          CropOverlay(
            state = cropState,
            style = style,
            shape = shape,
            accessibility = accessibility,
          )
        },
      )

      Box(
        Modifier
          .fillMaxWidth()
          .align(Alignment.BottomCenter)
          // Chrome respects the insets; the crop surface behind it stays full-bleed. Since
          // targetSdk 36 an app cannot opt out of edge-to-edge, so a bar that ignores this sits
          // under the navigation bar and cannot be pressed, which has produced un-closeable crop
          // screens in shipping apps.
          .safeDrawingPadding(),
      ) {
        controls(
          {
            if (!cropping) {
              cropping = true
              scope.launch {
                // One dialog, two tails. The request says which the caller is waiting on, so the
                // bytes path never pays for a decode it will not use and the image path never
                // encodes only to decode again.
                real.finish(
                  when (val output = request.output) {
                    is CropOutput.Bytes -> CropOutcome.OfBytes(state.crop(output.options))
                    CropOutput.Image -> CropOutcome.OfImage(state.cropToImage())
                  },
                )
              }
            }
          },
          { real.finish(request.output.cancelled()) },
        )
      }
    }
  }
}

/**
 * The plain default chrome: cancel on the left, confirm on the right, no Material dependency.
 *
 * @param confirmLabel what the confirm control reads. The defaults are English because a library
 *   with no resource system has nowhere else to get a string, so any app shipping in more than one
 *   language has to pass its own.
 * @param cancelLabel the same for cancel.
 */
@Composable
public fun DefaultCropControls(
  confirm: () -> Unit,
  cancel: () -> Unit,
  modifier: Modifier = Modifier,
  confirmLabel: String = "Crop",
  cancelLabel: String = "Cancel",
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .background(Color.Black.copy(alpha = 0.6f))
      .padding(horizontal = 24.dp, vertical = 16.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    CropTextButton(cancelLabel, cancel)
    CropTextButton(confirmLabel, confirm)
  }
}

@Composable
private fun CropTextButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  BasicText(
    text = label,
    modifier = modifier
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 8.dp),
    style = TextStyle(color = Color.White, fontSize = 16.sp),
  )
}
