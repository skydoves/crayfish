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
package com.github.skydoves.crayfishdemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.github.skydoves.crayfish.demo.CrayfishDemoApp
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.init

/**
 * Hosts the shared [CrayfishDemoApp] composable.
 *
 * Android is where this library's memory ceiling actually lives, so this is the build worth
 * profiling: the hardware canvas refuses a bitmap much past 100MB, and a cropper that decodes a
 * modern camera photo at full size is already over it before the user touches anything.
 */
class CrayfishDemoActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    // Before `super.onCreate`, not after: called later the window has already been laid out with
    // the old insets and the first frame flashes with an opaque status bar.
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)

    // Registers the photo picker against this activity's result registry. It has to happen before
    // any composable asks for a file, which is why it is not deferred into `setContent`.
    FileKit.init(this)

    setContent {
      CrayfishDemoApp()
    }
  }
}
