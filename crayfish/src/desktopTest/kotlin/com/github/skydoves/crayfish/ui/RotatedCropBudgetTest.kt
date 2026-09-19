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
import com.github.skydoves.crayfish.decode.DecodeBudget
import com.github.skydoves.crayfish.e2e.Fixtures
import com.github.skydoves.crayfish.geometry.FloatRect
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A rotated crop must cost no more than the budget it was given.
 *
 * Turning the pixels reads the decoded buffer into one array and writes a second, and both live at
 * once. Only the first was ever budgeted. A rotated crop of the 108MP fixture asked for a single
 * 166MB allocation on a device whose whole heap cap was 192MB, and the `OutOfMemoryError` came out
 * of `crop()` into the caller's coroutine, which is the one failure this library exists to prevent.
 *
 * Found by a storm of activity recreations rather than by review, and it is not a leak: the heap
 * across eight recreations went 10MB to 14MB. The single allocation was the whole story.
 */
@OptIn(ExperimentalTestApi::class)
class RotatedCropBudgetTest {

  /**
   * A rotated crop is decoded against half the budget, so its result is smaller than the same crop
   * unrotated.
   *
   * Sizes rather than memory counters. `MemoryProbe` sums allocation process wide, so running this
   * after the Compose UI tests moved the reading from 1.44x to 3.06x with nothing changed, and a
   * threshold loose enough to absorb that no longer separated the two cases. Output sizes come off
   * a power of two sample step and do not move at all.
   *
   * Measured on this fixture, unrotated 6000x4500 either way:
   *
   * | | rotated result | peak heap | allocated |
   * |---|---|---|---|
   * | Budget split | 2250x3000, 25MB | 242MB | 416MB |
   * | Not split | 4500x6000, 102MB | 558MB | 1535MB |
   */
  @Test
  fun aRotatedCropIsDecodedAgainstHalfTheBudget() = runComposeUiTest {
    val plain = readyState()
    selectTheWholeImage(plain)
    val unrotated = assertIs<CropImage.Success>(
      runBlocking { plain.cropToImage(BUDGET) },
      "the unrotated crop failed",
    ).size

    val turned = readyState()
    turned.rotateBy(90f)
    waitForIdle()
    selectTheWholeImage(turned)
    val rotated = assertIs<CropImage.Success>(
      runBlocking { turned.cropToImage(BUDGET) },
      "the rotated crop failed",
    ).size

    println(
      "[budget] unrotated $unrotated ${unrotated.argb8888ByteCount / 1024 / 1024}MB, " +
        "rotated $rotated ${rotated.argb8888ByteCount / 1024 / 1024}MB, " +
        "budget ${BUDGET.maxByteCount / 1024 / 1024}MB",
    )

    // The precondition: the unrotated decode has to be limited by the budget rather than by the
    // source, or halving the budget changes no sample step and the comparison below is vacuous.
    // Sample sizes are powers of two, so "budget bound" means the next step up would not fit.
    assertTrue(
      unrotated.argb8888ByteCount * 4 > BUDGET.maxByteCount,
      "the unrotated crop came back at ${unrotated.argb8888ByteCount / 1024 / 1024}MB with a " +
        "${BUDGET.maxByteCount / 1024 / 1024}MB budget and room for a finer step, so the budget " +
        "is not what limited it and this comparison proves nothing",
    )
    assertTrue(
      rotated.argb8888ByteCount <= unrotated.argb8888ByteCount / 2,
      "a rotated crop came back at $rotated, ${rotated.argb8888ByteCount / 1024 / 1024}MB, " +
        "against ${unrotated.argb8888ByteCount / 1024 / 1024}MB unrotated. Turning the pixels " +
        "writes a second array while the decoded one is still alive, so the decode has to be " +
        "budgeted for half of what the caller allowed, not all of it",
    )
  }

  private fun ComposeUiTest.readyState(): RealCropState {
    var state: CropState? = null
    setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        Box(Modifier.size(900.dp, 700.dp)) {
          val cropState = rememberCropState(
            source = CropSource.FilePath(Fixtures.sensor108mp.absolutePath),
          )
          state = cropState
          Cropper(state = cropState, modifier = Modifier.fillMaxSize())
        }
      }
    }
    waitUntil(timeoutMillis = 30_000) { state?.status is CropStatus.Ready }
    waitUntil(timeoutMillis = 30_000) { state?.viewportSize?.isEmpty == false }
    waitForIdle()
    return assertNotNull(state) as RealCropState
  }

  private fun ComposeUiTest.selectTheWholeImage(state: RealCropState) {
    val bounds = state.transform.mapRect(
      state.coordinateSpace.contentBounds,
      state.coordinateSpace.pivot,
    )
    val viewport = state.viewportSize
    state.normalizedCropRect = FloatRect(
      left = bounds.left / viewport.width,
      top = bounds.top / viewport.height,
      right = bounds.right / viewport.width,
      bottom = bounds.bottom / viewport.height,
    )
    waitForIdle()
  }

  private companion object {
    /**
     * The shipped output budget, because that is the configuration the failure happened under and
     * a smaller one hides it: at 24MiB the decode is already sampled past the point where the
     * second buffer matters.
     */
    val BUDGET = DecodeBudget.ForOutput
  }
}
