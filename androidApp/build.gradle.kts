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
import com.github.skydoves.crayfish.Configuration

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  // Consumes what `:benchmark` generates and packages it into the APK.
  alias(libs.plugins.baseline.profile)
}

android {
  compileSdk = Configuration.compileSdk
  namespace = "com.github.skydoves.crayfishdemo"

  defaultConfig {
    applicationId = "com.github.skydoves.crayfishdemo"
    minSdk = Configuration.demoMinSdk
    targetSdk = Configuration.targetSdk
    versionCode = Configuration.versionCode
    versionName = Configuration.versionName
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }

  buildFeatures {
    compose = true
  }

  lint {
    abortOnError = false
  }
}

dependencies {
  // `:samples-shared` re-exports `:crayfish` via `api(...)`.
  implementation(project(":samples-shared"))
  implementation(libs.androidx.activity.compose)
  implementation(libs.compose.runtime)
  implementation(libs.compose.foundation)
  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  // The picker itself is in `:samples-shared`; this is here for `FileKit.init(activity)`, which is
  // Android-only and has to run before anything asks for a file.
  implementation(libs.filekit.dialogs.compose)
  // Installs the baseline profile at first run on API 28 to 30, where the platform does not do it
  // itself. Above that the Play Store and the installer handle it, and this is harmless.
  implementation(libs.androidx.profileinstaller)

  baselineProfile(project(":benchmark"))
}

kotlin {
  jvmToolchain(17)
}
