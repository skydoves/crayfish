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
package com.github.skydoves.crayfish.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.skydoves.crayfish.decode.CropSource
import com.github.skydoves.crayfish.e2e.Fixtures
import com.github.skydoves.crayfish.encode.EncodeOptions
import com.github.skydoves.crayfish.encode.EncodedFormat
import com.github.skydoves.crayfish.geometry.FloatRect
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The pixel work of a crop never runs on the thread that asked for it.
 *
 * `crop()` and `cropToImage()` are called from a UI scope, the dialog's own confirm included, and
 * only the decode used to leave that thread: the rotate and flip pass, the shape mask, the bitmap
 * hand-off and the Skia encode all ran where the caller was. Reported on an iPhone as a three to
 * four second freeze on confirming a rotated crop.
 *
 * Observed rather than inferred. A sampler reads every thread's stack while the crop runs and notes
 * where each heavy step was seen. Every step must have been seen somewhere, which is what keeps a
 * renamed function or a sampler too slow to catch it from reading as "never on the caller".
 */
@OptIn(ExperimentalTestApi::class)
class CropOffTheCallersThreadTest {

  @Test
  fun aRotatedCropToImageLeavesTheCallersThreadFree() = runComposeUiTest {
    val state = rotatedCircleState()

    val sightings = sampleWhile {
      assertIs<CropImage.Success>(runBlocking { state.cropToImage() }, "the crop failed")
    }

    sightings.assertSeenOnlyOffTheCaller(PIXEL_PASS, SHAPE_MASK)
  }

  @Test
  fun aRotatedCropToBytesLeavesTheCallersThreadFree() = runComposeUiTest {
    val state = rotatedCircleState()

    val sightings = sampleWhile {
      assertIs<CropResult.Success>(
        runBlocking { state.crop(EncodeOptions(EncodedFormat.PNG)) },
        "the crop failed",
      )
    }

    sightings.assertSeenOnlyOffTheCaller(PIXEL_PASS, SHAPE_MASK, SKIA_ENCODE)
  }

  // -----------------------------------------------------------------------------------------
  // Harness
  // -----------------------------------------------------------------------------------------

  /** Where each step was seen: on the calling thread, and anywhere else. */
  private class Sightings(val onCaller: Map<String, Int>, val elsewhere: Map<String, Int>) {

    fun assertSeenOnlyOffTheCaller(vararg steps: String) {
      for (step in steps) {
        val off = elsewhere[step] ?: 0
        val on = onCaller[step] ?: 0
        // The positive control: a step never seen at all proves nothing about where it ran.
        assertTrue(
          off + on > 0,
          "$step was never observed on any thread, so the sampler cannot vouch for where it ran",
        )
        assertTrue(
          on == 0,
          "$step was seen $on times on the calling thread (and $off elsewhere). A UI scope " +
            "calling crop() is frozen for as long as this runs.",
        )
      }
    }
  }

  private fun sampleWhile(crop: () -> Unit): Sightings {
    val caller = Thread.currentThread()
    val onCaller = HashMap<String, Int>()
    val elsewhere = HashMap<String, Int>()
    val done = AtomicBoolean(false)
    val sampler = thread(name = "crop-stack-sampler") {
      while (!done.get()) {
        for ((sampled, stack) in Thread.getAllStackTraces()) {
          for (step in STEPS) {
            if (stack.none { it.methodName == step }) continue
            val counts = if (sampled === caller) onCaller else elsewhere
            counts[step] = (counts[step] ?: 0) + 1
          }
        }
      }
    }
    try {
      crop()
    } finally {
      done.set(true)
      sampler.join()
    }
    println("[off-caller] on caller $onCaller, elsewhere $elsewhere")
    return Sightings(onCaller, elsewhere)
  }

  private fun ComposeUiTest.rotatedCircleState(): RealCropState {
    var state: CropState? = null
    setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        Box(Modifier.size(900.dp, 700.dp)) {
          val cropState = rememberCropState(CropSource.FilePath(Fixtures.uhdPng.absolutePath))
          state = cropState
          Cropper(state = cropState, modifier = Modifier.fillMaxSize())
        }
      }
    }
    waitUntil(timeoutMillis = 30_000) { state?.status is CropStatus.Ready }
    waitUntil(timeoutMillis = 30_000) { state?.viewportSize?.isEmpty == false }
    waitForIdle()
    val ready = assertNotNull(state) as RealCropState

    ready.rotateBy(90f)
    ready.shape = CropShape.Circle
    waitForIdle()
    val bounds = ready.transform.mapRect(
      ready.coordinateSpace.contentBounds,
      ready.coordinateSpace.pivot,
    )
    val viewport = ready.viewportSize
    ready.normalizedCropRect = FloatRect(
      left = bounds.left / viewport.width,
      top = bounds.top / viewport.height,
      right = bounds.right / viewport.width,
      bottom = bounds.bottom / viewport.height,
    )
    waitForIdle()
    return ready
  }

  private companion object {
    const val PIXEL_PASS = "frameThroughViewerTransform"
    const val SHAPE_MASK = "maskToShape"
    const val SKIA_ENCODE = "encodeToData"
    val STEPS = listOf(PIXEL_PASS, SHAPE_MASK, SKIA_ENCODE)
  }
}
