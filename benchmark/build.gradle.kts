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
  alias(libs.plugins.android.test)
  alias(libs.plugins.baseline.profile)
}

android {
  namespace = "com.github.skydoves.crayfish.benchmark"
  compileSdk = Configuration.compileSdk

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  defaultConfig {
    // 28 is where `ProfileInstaller` and macrobenchmark both start working. Below it there is no
    // profile to install and nothing to measure, which is why this floor is higher than the
    // library's own minSdk of 21.
    minSdk = 28
    targetSdk = Configuration.targetSdk
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  testOptions {
    managedDevices {
      localDevices {
        create("pixel6Api34") {
          device = "Pixel 6"
          apiLevel = 34
          // `aosp`, not `google_apis` and not the Play image. Generation pulls the profile back
          // with `adb root`, which only a rootable image allows.
          systemImageSource = "aosp"
        }
      }
    }
  }

  targetProjectPath = ":androidApp"
  experimentalProperties["android.experimental.self-instrumenting"] = true

  lint { abortOnError = false }
}

baselineProfile {
  // One device or the other, never both. Adding the managed device unconditionally and only
  // flipping `useConnectedDevices` leaves both in the graph, so a local run downloads a system
  // image it will not use. A dry run is what showed that: it listed
  // `connectedNonMinifiedReleaseAndroidTest` and `pixel6Api34Setup` side by side.
  if (providers.gradleProperty("crayfish.managedDevice").isPresent) {
    managedDevices += "pixel6Api34"
    useConnectedDevices = false
  } else {
    useConnectedDevices = true
  }

  // The device you have locally, the managed one on CI.
  //
  // Generation needs a rootable image either way: the rule pulls the profile back with `adb root`,
  // which a Play Store system image refuses. Locally an `aosp` or `google_apis` emulator, or any
  // userdebug phone, is already attached and costs nothing. The managed device is reproducible but
  // downloads a system image on first use, which is minutes an inner loop should not pay for, so
  // it is opt in: `-Pcrayfish.managedDevice`, which is what the workflow passes.
}

dependencies {
  implementation(libs.androidx.test.runner)
  implementation(libs.androidx.test.uiautomator)
  implementation(libs.androidx.benchmark.macro)
  implementation(libs.kotlin.test)
}

androidComponents {
  onVariants { variant ->
    val artifactsLoader = variant.artifacts.getBuiltArtifactsLoader()
    variant.instrumentationRunnerArguments.put(
      "targetAppId",
      variant.testedApks.map { artifactsLoader.load(it)?.applicationId.orEmpty() },
    )

    // The two kinds of test in this module do not run together.
    //
    // `generateBaselineProfile` drives the `nonMinified` variants and sets
    // `androidx.benchmark.enabledRules=BaselineProfile`, which makes every `MacrobenchmarkRule`
    // throw an assumption failure. Those surface as failures rather than skips and take the whole
    // task red, on a run that produced a perfectly good profile. So each variant runs only what it
    // is for.
    val generator = "com.github.skydoves.crayfish.benchmark.BaselineProfileGenerator"
    // `ignoreCase`: the variant is named `nonMinifiedRelease`, and matching "NonMinified" quietly
    // never fired, so the generator was excluded from the one run that needs it and the task
    // reported "No baseline profile rules were generated" while exiting 0.
    if (variant.name.contains("nonMinified", ignoreCase = true)) {
      variant.instrumentationRunnerArguments.put("class", generator)
    } else {
      variant.instrumentationRunnerArguments.put("notClass", generator)
    }
  }
}

kotlin {
  jvmToolchain(17)
}
