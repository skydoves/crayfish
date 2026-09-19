@file:OptIn(ExperimentalWasmDsl::class)

import com.github.skydoves.crayfish.Configuration
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
  jacoco
  alias(libs.plugins.kmp.android.library)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.jetbrains.compose)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.nexus.plugin)
  alias(libs.plugins.dokka)
}

apply(from = "$rootDir/scripts/publish-module.gradle.kts")

mavenPublishing {
  // Real API docs in the javadoc jar; vanniktech's KMP default ships an empty one. See `:crayfish`.
  configure(
    com.vanniktech.maven.publish.KotlinMultiplatform(
      javadocJar = com.vanniktech.maven.publish.JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
      sourcesJar = true,
    ),
  )

  val artifactId = "crayfish-landscapist"
  coordinates(
    Configuration.artifactGroup,
    artifactId,
    rootProject.extra.get("libVersion").toString(),
  )

  pom {
    name.set(artifactId)
    description.set(
      "Landscapist integration for Crayfish: crop what Landscapist loaded, and re-apply a crop at " +
        "display time without encoding it again.",
    )
  }
}

kotlin {
  android {
    namespace = "com.github.skydoves.crayfish.landscapist"
    compileSdk = Configuration.compileSdk
    minSdk = Configuration.minSdk

    compilations.configureEach {
      compileTaskProvider.configure {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
      }
    }

    lint { abortOnError = false }

  }
  jvm("desktop")
  iosArm64()
  iosSimulatorArm64()
  macosArm64()
  // No iosX64 or macosX64: Compose Multiplatform 1.12.0 publishes neither
  // (org.jetbrains.compose.runtime:runtime-iosx64 and -macosx64 are both 404 on Maven Central),
  // so declaring them fails metadata resolution rather than producing an Intel artifact.
  wasmJs {
    browser { testTask { enabled = false } }
    binaries.library()
  }

  @Suppress("OPT_IN_USAGE")
  applyHierarchyTemplate {
    common {
      group("jvm") {
        // The `com.android.kotlin.multiplatform.library` plugin registers its target as
        // platformType=androidJvm, which `withAndroidTarget()` (matching the legacy
        // KotlinAndroidTarget) does not pick up. Match by platform type instead.
        withCompilations { it.target.platformType == KotlinPlatformType.androidJvm }
        withJvm()
      }
      // Everything whose graphics stack is Skia, which is every target except Android.
      // `skiaMain` is where the Skia-backed decoder/encoder actuals live.
      group("skia") {
        withJvm()
        withWasmJs()
        group("apple") {
          group("ios") {
            withIosArm64()
            withIosSimulatorArm64()
          }
          group("macos") {
            withMacosArm64()
          }
        }
      }
    }
  }

  // `apiCheck` depends on a `testClasses` lifecycle task that the KMP plugin does not register.
  tasks.register("testClasses")

  sourceSets {
    commonMain.dependencies {
      // `api`, not `implementation`: a caller writing `CropTransformation(...)` into a Landscapist
      // request needs both libraries' types on its own compile classpath anyway, and making them
      // hunt for the second coordinate is friction this module exists to remove.
      api(project(":crayfish"))
      api(libs.landscapist.core)
      api(libs.landscapist)
      implementation(libs.compose.runtime)
      implementation(libs.compose.ui)
      implementation(libs.kotlinx.coroutines.core)
    }

    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
    }

    // `org.khronos.webgl.*` and the browser globals are named directly by the wasm decoder.
    // They arrive transitively through skiko today, which would break the moment skiko stops
    // exposing them in its own public API.
    val wasmJsMain by getting {
      dependencies {
        implementation(libs.kotlinx.browser)
      }
    }

    // Tests that need a real composition or an ImageBitmap live here. `runComposeUiTest` wants a
    // window, and `compose.desktop.currentOs` supplies the Skiko backend that draws into it.
    val desktopTest by getting {
      languageSettings.optIn("androidx.compose.ui.test.ExperimentalTestApi")
      dependencies {
        implementation(libs.compose.ui.test)
        implementation(compose.desktop.currentOs)
      }
    }
  }

  explicitApi()
}

tasks.withType<KotlinJvmCompile>().configureEach {
  compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

tasks.withType<JavaCompile>().configureEach {
  this.targetCompatibility = libs.versions.jvmTarget.get()
  this.sourceCompatibility = libs.versions.jvmTarget.get()
}

tasks.named<Test>("desktopTest") {
  // macOS: run the test JVM as an accessory process.
  //
  // Every `runComposeUiTest` class makes Skiko open a real window, and on macOS an AWT process
  // without this shows up in the Dock and takes keyboard focus from whatever the developer is
  // typing into. A mutation loop over the UI tests makes the machine unusable. `UIElement` only
  // suppresses the Dock icon and the activation; the window is still created and still rasterises,
  // so the tests see exactly what they saw before. `java.awt.headless` would NOT do - it stops
  // Skiko creating the window at all.
  systemProperty("apple.awt.UIElement", "true")

  extensions.configure<JacocoTaskExtension> {
    // The Compose compiler rewrites composables heavily and the instrumenter has to see the
    // rewritten classes, not the sources.
    isIncludeNoLocationClasses = true
    excludes = listOf("jdk.internal.*")
  }
}

/**
 * The same coverage gate `:crayfish` has, over the bridge.
 *
 * It is a published artifact, so "the main library is measured" is not an answer for it: the two
 * platform actuals here are the only code in the repo that reaches into Landscapist's own bitmap
 * types, and nothing else would notice them rotting.
 */
val desktopCoverageReport by tasks.registering(JacocoReport::class) {
  dependsOn("desktopTest")
  executionData(layout.buildDirectory.file("jacoco/desktopTest.exec"))
  sourceDirectories.setFrom(
    files("src/commonMain/kotlin", "src/jvmMain/kotlin", "src/desktopMain/kotlin", "src/skiaMain/kotlin"),
  )
  classDirectories.setFrom(
    fileTree(layout.buildDirectory.dir("classes/kotlin/desktop/main")) {
      exclude("**/ComposableSingletons*", "**/*\$\$*")
    },
  )
  reports {
    xml.required.set(true)
    html.required.set(true)
  }
}
