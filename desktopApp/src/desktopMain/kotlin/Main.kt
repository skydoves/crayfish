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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.github.skydoves.crayfish.demo.CrayfishDemoApp
import io.github.vinceglb.filekit.FileKit

fun main() {
  // Outside `application { }` and before it: the app id names the directory the native file
  // dialog remembers its last location in, and FileKit reads it the first time a picker is built.
  FileKit.init("CrayfishDemo")

  application {
    Window(
      onCloseRequest = ::exitApplication,
      title = "Crayfish",
      state = rememberWindowState(size = DpSize(width = 720.dp, height = 900.dp)),
    ) {
      CrayfishDemoApp()
    }
  }
}
