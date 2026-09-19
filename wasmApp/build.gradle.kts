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
@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.jetbrains.compose)
  alias(libs.plugins.compose.compiler)
}

kotlin {
  wasmJs {
    // Both names are set on purpose: left alone the module is named after `rootProject.name`
    // ("CrayfishDemo"), and `index.html` would have to reference a file whose name changes with
    // the project's.
    outputModuleName = "crayfish-wasm-demo"
    browser {
      commonWebpackConfig {
        outputFileName = "composeApp.js"
        devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
          static(
            directory = project.projectDir.path,
          )
        }
      }
    }
    binaries.executable()
  }

  sourceSets {
    val wasmJsMain by getting {
      dependencies {
        implementation(libs.compose.runtime)
        implementation(libs.compose.foundation)
        implementation(libs.compose.ui)
        implementation(libs.compose.material3)
        implementation(libs.kotlinx.browser)
        // `:samples-shared` re-exports `:crayfish` via `api(...)`.
        implementation(project(":samples-shared"))
      }
    }
  }
}
