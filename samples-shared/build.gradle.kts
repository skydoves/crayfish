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

import com.github.skydoves.crayfish.Configuration
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
  alias(libs.plugins.kmp.android.library)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.jetbrains.compose)
  alias(libs.plugins.compose.compiler)
}

// Deliberately unpublished: this is the one demo screen `:androidApp`, `:desktopApp`, `:wasmApp`
// and `iosApp` all render, and nothing else.
kotlin {
  android {
    namespace = "com.github.skydoves.crayfish.demo.shared"
    compileSdk = Configuration.compileSdk
    minSdk = Configuration.demoMinSdk

    // Off by default under `com.android.kotlin.multiplatform.library`. Without it the
    // `composeResources` directory is never packaged into the Android artifact and the sample
    // image resolves everywhere except Android, at runtime, with no build error.
    androidResources {
      enable = true
    }
  }

  // `jvm("desktop")`, not `jvm()`: the source set has to be named `desktopMain` for `:desktopApp`
  // and for the rest of this repo's desktop wiring to line up.
  jvm("desktop")

  listOf(
    iosArm64(),
    iosSimulatorArm64(),
  ).forEach {
    // Debug and release both. An optimised Compose framework link needs more heap than a 2g
    // daemon leaves (it dies mid-link without reporting why), but this repo's
    // `org.gradle.jvmargs=-Xmx4g` is enough, measured.
    it.binaries.framework {
      baseName = "shared"
      isStatic = true
      // `:crayfish` is deliberately not `export`ed: Swift only ever calls `MainViewController()`,
      // and exporting would make the Objective-C header generator walk the library's entire
      // public API, which is stated in Compose types, on behalf of no caller.
    }
  }
  // No `iosX64`: Compose Multiplatform 1.12.0 publishes no Intel simulator artifacts, matching
  // `:crayfish`.

  wasmJs {
    browser()
    binaries.library()
  }

  sourceSets {
    androidMain.dependencies {
      // Only for `BackHandler`: the demo's detail screens send the system back to the list rather
      // than out of the app. Android is the one target here with a system back to intercept.
      implementation(libs.androidx.activity.compose)
    }

    commonMain.dependencies {
      // `api` rather than `implementation` so the four demo apps see the library's public
      // composables transitively instead of re-declaring the dependency four times.
      api(project(":crayfish"))

      implementation(libs.compose.runtime)
      implementation(libs.compose.foundation)
      implementation(libs.compose.ui)
      implementation(libs.compose.material3)
      // Ships the built-in sample photo to every target.
      implementation(libs.compose.components.resources)
      // Picks an image on all four targets with no expect/actual of our own.
      implementation(libs.filekit.dialogs.compose)
    }
  }
}

// Pins the generated `Res` class to a known package so common code can import it; left to itself
// it lands in a package derived from the Android namespace, which only the Android target has.
compose.resources {
  packageOfResClass = "com.github.skydoves.crayfish.demo.resources"
}

tasks.withType<KotlinJvmCompile>().configureEach {
  compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}
