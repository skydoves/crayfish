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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The gallery every target renders.
 *
 * Android, desktop, wasm and iOS own a window and this call, and nothing else. Navigation is one
 * nullable rather than a navigation library, because the demo is two levels deep and a dependency
 * here would be a dependency a reader has to understand before reaching the cropper.
 */
@Composable
fun CrayfishDemoApp(modifier: Modifier = Modifier) {
  MaterialTheme(colorScheme = darkColorScheme()) {
    Surface(
      // `safeDrawingPadding` rather than nothing: `:androidApp` goes edge to edge, so without it
      // the toolbar sits under the status bar on Android and under the notch on iOS.
      modifier = modifier.fillMaxSize().safeDrawingPadding(),
      color = MaterialTheme.colorScheme.background,
    ) {
      var open by remember { mutableStateOf<Demo?>(null) }

      // Back belongs to the screen you are on: inside a demo it returns to the list, and on the
      // list it is left alone so the platform can close the app.
      DemoBackHandler(enabled = open != null) { open = null }

      when (val demo = open) {
        null -> DemoGallery(onOpen = { open = it })

        else -> Column(Modifier.fillMaxSize()) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            TextButton(onClick = { open = null }) { Text("‹ Demos") }
            Text(demo.title, style = MaterialTheme.typography.titleMedium)
          }
          DemoScreen(demo, Modifier.weight(1f))
        }
      }
    }
  }
}
