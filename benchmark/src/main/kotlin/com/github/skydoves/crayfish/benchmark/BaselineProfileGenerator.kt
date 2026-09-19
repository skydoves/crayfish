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
package com.github.skydoves.crayfish.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import com.github.skydoves.crayfish.benchmark.CropperJourney.awaitGallery
import com.github.skydoves.crayfish.benchmark.CropperJourney.cropAndWaitForTheResult
import com.github.skydoves.crayfish.benchmark.CropperJourney.dragTheCropFrame
import com.github.skydoves.crayfish.benchmark.CropperJourney.openBasicsAndWaitForTheImage
import com.github.skydoves.crayfish.benchmark.CropperJourney.scrollTheScreen
import org.junit.Rule
import org.junit.Test

/**
 * Writes `baseline-prof.txt` from a real crop.
 *
 * A baseline profile is a list of methods the runtime compiles ahead of time instead of
 * interpreting, and it is worth exactly the paths the generator walked. So this walks the ones that
 * cost something: opening a source, decoding it into tiles, dragging the frame, and encoding the
 * result. Launching the app and stopping would produce a profile for the launcher and nothing else.
 *
 * Run it with:
 *
 * ```
 * ./gradlew :androidApp:generateBaselineProfile
 * ```
 *
 * against a rootable device. A Play Store system image refuses the `adb root` the rule needs to pull
 * the profile back; an `aosp` or `google_apis` AVD, or any userdebug build, works.
 */
class BaselineProfileGenerator {

  @get:Rule
  val rule = BaselineProfileRule()

  @Test
  fun generate() = rule.collect(
    packageName = targetPackage(),
    // Three passes, which is the default and is about variance rather than coverage: the rule keeps
    // what is stable across them, so a path taken once by a background task does not get compiled.
    maxIterations = 3,
    stableIterations = 2,
  ) {
    pressHome()
    startActivityAndWait()
    awaitGallery()
    // Open the demo before scrolling anything. Flinging the gallery first pushed the card off
    // screen, and the run then found nothing to click: the first version of this generator did
    // exactly that and produced 11,756 rules without one for the cropper.
    openBasicsAndWaitForTheImage()
    dragTheCropFrame()
    cropAndWaitForTheResult()
    scrollTheScreen()
  }
}
