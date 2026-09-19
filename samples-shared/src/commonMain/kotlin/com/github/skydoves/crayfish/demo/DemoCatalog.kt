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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Every screen in the demo, and what each one is for.
 *
 * One catalog rather than a screen per file plus a list that has to be kept in step with it: a demo
 * added here is reachable, and one that is not here does not exist.
 */
internal enum class Demo(val title: String, val subtitle: String) {
  Basics(
    title = "Basics",
    subtitle = "The default cropper, aspect ratio presets, and encoded bytes out",
  ),
  Styled(
    title = "Custom style",
    subtitle = "Yellow frame, handles and grid, always-on thirds, heavier scrim",
  ),
  Avatar(
    title = "Circular avatar",
    subtitle = "A circular mask over a square ratio, cropped straight to an ImageBitmap",
  ),
  Dialog(
    title = "One line dialog",
    subtitle = "ImageCropperDialog with its own confirm and cancel chrome",
  ),
  PainterSource(
    title = "Painter and ImageBitmap",
    subtitle = "Crop what is already on screen, with no file path and no bytes",
  ),
  Gestures(
    title = "Gestures",
    subtitle = "Fixed by default, pinch and pan and twist switched on per screen",
  ),
  Straighten(
    title = "Straighten",
    subtitle = "A degree slider: the frame holds still and the photo tilts under it",
  ),
  Recipe(
    title = "Save and reopen",
    subtitle = "Store the crop as eleven numbers, then open it again exactly where it was",
  ),
  CustomOverlay(
    title = "Custom overlay",
    subtitle = "The overlay slot: your own frame, drawn against the same state",
  ),
}

@Composable
internal fun DemoGallery(onOpen: (Demo) -> Unit, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("crayfish", style = MaterialTheme.typography.headlineMedium)
    Text(
      text =
      "Each screen crops the same photo a different way. Open one, then read the code that " +
        "produced it.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Demo.entries.forEach { demo ->
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .clickable { onOpen(demo) },
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Text(demo.title, style = MaterialTheme.typography.titleMedium)
          Text(
            text = demo.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}
