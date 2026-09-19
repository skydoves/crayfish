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
package com.github.skydoves.crayfish.demo

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.skydoves.crayfish.ui.AspectRatio
import com.github.skydoves.crayfish.ui.CropState

/** The proportions a cropper is asked for often enough to deserve a button. */
@Immutable
internal class AspectPreset(val label: String, val ratio: AspectRatio)

internal val aspectPresets: List<AspectPreset> = listOf(
  AspectPreset("Free", AspectRatio.Free),
  AspectPreset("1:1", AspectRatio.Square),
  AspectPreset("3:4", AspectRatio.Portrait3x4),
  AspectPreset("4:3", AspectRatio.Landscape4x3),
  AspectPreset("16:9", AspectRatio.Widescreen16x9),
  AspectPreset("9:16", AspectRatio.Portrait9x16),
)

/**
 * The chrome the library deliberately does not ship.
 *
 * `Cropper` owns the gestures and the overlay and stops there, so every row below is the demo's own
 * which is the point: the toolbar is the part an app styles itself, and it drives the cropper
 * through nothing but the public [CropState].
 *
 */
@Composable
fun CropToolbar(state: CropState, modifier: Modifier = Modifier) {
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    // Horizontally scrollable so a narrow phone shows every preset rather than clipping the last.
    Row(
      modifier = Modifier.horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      aspectPresets.forEach { preset ->
        FilterChip(
          selected = preset.ratio == state.aspectRatio,
          onClick = { state.aspectRatio = preset.ratio },
          label = { Text(preset.label) },
        )
      }
    }

    // Rotation and the flips are deliberately not here. The library still offers them, but a
    // rotate button turns the photo underneath a frame that stays where it is, which is correct
    // and is not what anyone expects from pressing it. Until the frame follows the turn, showing
    // the button in the demo teaches the wrong thing.
    Row(
      modifier = Modifier.horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      OutlinedButton(onClick = { state.reset() }) { Text("Reset") }
    }
  }
}
