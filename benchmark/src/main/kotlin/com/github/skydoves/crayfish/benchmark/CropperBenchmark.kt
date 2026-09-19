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

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import com.github.skydoves.crayfish.benchmark.CropperJourney.awaitGallery
import com.github.skydoves.crayfish.benchmark.CropperJourney.cropAndWaitForTheResult
import com.github.skydoves.crayfish.benchmark.CropperJourney.dragTheCropFrame
import com.github.skydoves.crayfish.benchmark.CropperJourney.openBasicsAndWaitForTheImage
import com.github.skydoves.crayfish.benchmark.CropperJourney.scrollTheScreen
import org.junit.Rule
import org.junit.Test

/**
 * What the baseline profile is worth, measured rather than assumed.
 *
 * Each case runs twice, once with the profile and once without, because a profile that is never
 * compared against its absence is a file nobody can defend. The pair is the point: the number on
 * its own says how fast the demo is on one emulator, which is a property of that emulator.
 *
 * ```
 * ./gradlew :benchmark:connectedBenchmarkAndroidTest
 * ```
 */
class CropperBenchmark {

  @get:Rule
  val rule = MacrobenchmarkRule()

  @Test
  fun startupWithoutProfile() = startup(CompilationMode.None())

  @Test
  fun startupWithProfile() = startup(
    CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
  )

  /** Cold start to the first frame of the gallery. */
  private fun startup(mode: CompilationMode) = rule.measureRepeated(
    packageName = targetPackage(),
    metrics = listOf(StartupTimingMetric()),
    compilationMode = mode,
    startupMode = StartupMode.COLD,
    iterations = 5,
  ) {
    pressHome()
    startActivityAndWait()
    awaitGallery()
  }

  @Test
  fun croppingWithoutProfile() = croppingJourney(CompilationMode.None())

  @Test
  fun croppingWithProfile() = croppingJourney(
    CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
  )

  /**
   * Opening a photo, dragging the frame and cropping it.
   *
   * [FrameTimingMetric] over a drag is the number that matters to a user: a cropper that janks
   * while the frame is under their finger is a cropper that feels broken, whatever the totals say.
   */
  private fun croppingJourney(mode: CompilationMode) = rule.measureRepeated(
    packageName = targetPackage(),
    metrics = listOf(FrameTimingMetric()),
    compilationMode = mode,
    startupMode = StartupMode.WARM,
    iterations = 5,
  ) {
    startActivityAndWait()
    awaitGallery()
    openBasicsAndWaitForTheImage()
    dragTheCropFrame()
    cropAndWaitForTheResult()
    scrollTheScreen()
  }
}
