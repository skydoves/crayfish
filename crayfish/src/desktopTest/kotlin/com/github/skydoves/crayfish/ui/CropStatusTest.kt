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

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.github.skydoves.crayfish.decode.CropSource
import com.github.skydoves.crayfish.decode.ImageProbe
import com.github.skydoves.crayfish.decode.ImageProbeResult
import com.github.skydoves.crayfish.decode.ImageSize
import com.github.skydoves.crayfish.decode.TestImages
import com.github.skydoves.crayfish.geometry.CoverageCorrection
import com.github.skydoves.crayfish.geometry.CropCoverage
import com.github.skydoves.crayfish.geometry.CropTransform
import com.github.skydoves.crayfish.geometry.FloatRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a cropper does before and instead of succeeding.
 *
 * These paths had no test at all until an API-surface audit noticed that `CropStatus.Loading` and
 * `CropStatus.Failed` were never named anywhere, which meant nothing checked what a screen shows
 * while a large photo opens, or when the file a user picked turns out to be unreadable. Both are
 * ordinary events, and both are what a caller has to render something for.
 */
@OptIn(ExperimentalTestApi::class)
class CropStatusTest {

  @Test
  fun aCropperStartsOutLoading() = runComposeUiTest {
    var seenLoading = false
    var state: CropState? = null

    setContent {
      val cropState = rememberCropState(TestImages.source(TestImages.pngBytes()))
      state = cropState
      if (cropState.status is CropStatus.Loading) seenLoading = true
      Cropper(state = cropState, modifier = Modifier.fillMaxSize())
    }

    assertTrue(seenLoading, "the cropper never reported Loading, so nothing can show a spinner")
    waitUntil(timeoutMillis = TIMEOUT) { state?.status is CropStatus.Ready }
  }

  /**
   * A picked file that turns out to be unreadable is an ordinary event on a photo picker, and it
   * has to arrive as a value a screen can render rather than an exception.
   */
  @Test
  fun anUnreadableSourceEndsInFailedRatherThanAnException() = runComposeUiTest {
    var state: CropState? = null

    setContent {
      val cropState = rememberCropState(
        CropSource.Bytes("this is not an image".encodeToByteArray(), cacheKey = "garbage"),
      )
      state = cropState
      Cropper(state = cropState, modifier = Modifier.fillMaxSize())
    }

    waitUntil(timeoutMillis = TIMEOUT) { state?.status is CropStatus.Failed }

    val failed = assertIs<CropStatus.Failed>(assertNotNull(state).status)
    assertEquals(CropResult.Failure.Reason.SourceUnreadable, failed.reason)
    assertEquals(ImageSize.Zero, state!!.imageSize, "a failed source must report no size")
  }

  /** A crop asked for while the source is unreadable reports the failure rather than hanging. */
  @Test
  fun croppingAFailedSourceReportsAFailure() = runComposeUiTest {
    var state: CropState? = null
    setContent {
      val cropState = rememberCropState(
        CropSource.Bytes(byteArrayOf(1, 2, 3, 4), cacheKey = "not-an-image"),
      )
      state = cropState
      Cropper(state = cropState, modifier = Modifier.fillMaxSize())
    }
    waitUntil(timeoutMillis = TIMEOUT) { state?.status is CropStatus.Failed }

    val result = kotlinx.coroutines.runBlocking { assertNotNull(state).crop() }

    assertIs<CropResult.Failure>(result, "cropping an unreadable source returned $result")
  }

  /** The probe's result type, named explicitly rather than reached through a nullable chain. */
  @Test
  fun theProbeReturnsATypedResult() {
    val result: ImageProbeResult = assertNotNull(ImageProbe.probe(TestImages.pngBytes()))

    assertEquals(ImageSize(TestImages.WIDTH, TestImages.HEIGHT), result.size)
  }

  /** The coverage correction says what it did, not merely what it produced. */
  @Test
  fun theCoverageCorrectionReportsWhichStepItTook() {
    val content = FloatRect(0f, 0f, 200f, 200f)
    val alreadyCovered: CoverageCorrection = CropCoverage.correct(
      transform = CropTransform.Identity,
      contentBounds = content,
      coverRegion = FloatRect(50f, 50f, 150f, 150f),
    )

    assertTrue(!alreadyCovered.translated, "a covered frame should not have been translated")
    assertTrue(!alreadyCovered.scaled, "a covered frame should not have been scaled")

    val uncovered: CoverageCorrection = CropCoverage.correct(
      transform = CropTransform.Identity,
      contentBounds = FloatRect(80f, 80f, 120f, 120f),
      coverRegion = FloatRect(0f, 0f, 200f, 200f),
    )
    assertTrue(
      uncovered.translated || uncovered.scaled,
      "a frame larger than the content had to be corrected somehow",
    )
  }

  private companion object {
    const val TIMEOUT = 10_000L
  }
}
