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
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.jetbrains.compose)
  alias(libs.plugins.compose.compiler)
}

kotlin {
  // `jvm("desktop")` so this module's source set is `desktopMain`, matching `:samples-shared`
  // and `:crayfish`.
  jvm("desktop")

  sourceSets {
    val desktopMain by getting {
      dependencies {
        implementation(compose.desktop.currentOs)
        // `:samples-shared` re-exports `:crayfish` via `api(...)`.
        implementation(project(":samples-shared"))
        // For `FileKit.init(appId)`, without which the native file dialog never opens.
        implementation(libs.filekit.dialogs.compose)
      }
    }
  }
}

compose.desktop {
  application {
    mainClass = "MainKt"
    nativeDistributions {
      targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
      packageName = "CrayfishDemo"
      packageVersion = "1.0.0"
    }
  }
}

tasks.withType<KotlinJvmCompile>().configureEach {
  compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}
