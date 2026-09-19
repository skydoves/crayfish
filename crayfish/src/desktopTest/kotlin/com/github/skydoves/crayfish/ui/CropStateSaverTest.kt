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

import com.github.skydoves.crayfish.decode.TestImages
import com.github.skydoves.crayfish.geometry.CropTransform
import com.github.skydoves.crayfish.geometry.FloatRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What survives the activity being destroyed under the user.
 *
 * This is not a nicety. Android 16 ignores `screenOrientation` on any display 600dp or wider, so
 * the portrait lock every existing cropper leans on to keep its geometry simple is gone: a tablet
 * rotates, the activity is recreated, and an unsaved crop rectangle is a lost edit. The `Saver` was
 * written for exactly that and had almost no coverage, two of its fourteen branches, until this.
 */
class CropStateSaverTest {

  // The save-and-restore round trip through a real activity lives in the Android device suite:
  // Compose Multiplatform's desktop `StateRestorationTester.emulateSaveAndRestore()` is a `TODO()`,
  // and process death is an Android concept anyway. See `DeviceCropStateRestorationTest`.

  /**
   * The rectangle is stored as fractions, so it comes back in the same *relative* place even when
   * the viewport it was measured in never exists again: a different screen, a resized window, a
   * rotated tablet. Pixels would restore into the wrong place.
   */
  @Test
  fun theSavedRectangleIsViewportIndependent() {
    val saver = RealCropState.Saver(TestImages.source(TestImages.pngBytes()))
    val state = RealCropState(
      source = TestImages.source(TestImages.pngBytes()),
      initialAspectRatio = AspectRatio.Free,
    )
    state.normalizedCropRect = FloatRect(0.25f, 0.25f, 0.75f, 0.75f)

    val saved = assertNotNull(with(saver) { SaverScopeStub.save(state) })

    // Every number that leaves is a fraction or a transform component; none is a pixel.
    assertTrue(saved.all { it.isFinite() }, "the saved form holds a non-finite value: $saved")
    assertTrue(
      saved.subList(6, 10).all { it in 0f..1f },
      "the rectangle was not saved as fractions: ${saved.subList(6, 10)}",
    )
  }

  /** A saved form from an older or corrupted build must be refused, not half-applied. */
  @Test
  fun aSavedFormOfTheWrongShapeIsRefused() {
    val saver = RealCropState.Saver(TestImages.source(TestImages.pngBytes()))

    assertEquals(null, saver.restore(listOf()))
    assertEquals(null, saver.restore(listOf(1f, 2f, 3f)))
    assertEquals(null, saver.restore(List(20) { 1f }))
  }

  /** A restored transform that arrived non-finite is sanitised rather than propagated. */
  @Test
  fun aNonFiniteSavedTransformIsSanitisedOnRestore() {
    val saver = RealCropState.Saver(TestImages.source(TestImages.pngBytes()))

    val restored = assertNotNull(
      saver.restore(
        listOf(
          Float.NaN, Float.NaN, 0f, Float.POSITIVE_INFINITY, 0f, 0f,
          0.1f, 0.1f, 0.9f, 0.9f,
          -1f,
        ),
      ),
    )

    assertTrue(restored.transform.scale.isFinite(), "a NaN scale survived the restore")
    assertTrue(restored.transform.offset.isFinite, "a NaN offset survived the restore")
    assertTrue(restored.transform.rotationDegrees.isFinite(), "an infinite rotation survived")
  }

  @Test
  fun bothFlipsRoundTripIndependently() {
    val saver = RealCropState.Saver(TestImages.source(TestImages.pngBytes()))

    listOf(false to false, true to false, false to true, true to true).forEach { (h, v) ->
      val state = RealCropState(TestImages.source(TestImages.pngBytes()), AspectRatio.Free)
      state.transform = CropTransform(flipHorizontal = h, flipVertical = v)
      val saved = assertNotNull(with(saver) { SaverScopeStub.save(state) })
      val restored = assertNotNull(saver.restore(saved))

      assertEquals(h, restored.transform.flipHorizontal, "horizontal flip $h did not round-trip")
      assertEquals(v, restored.transform.flipVertical, "vertical flip $v did not round-trip")
    }
  }

  /**
   * The aspect ratio is saved with everything else.
   *
   * It has to be, because the state owns it: `rememberCropState` applies its parameter once and
   * then stops, so nothing re-applies a ratio the user picked from a toolbar. Before this was
   * saved, rotating a tablet silently unlocked the crop.
   *
   * `Free` is stored as a negative, which no real ratio can be, so the two cases need neither a
   * second list nor a sentinel that could collide with a value.
   */
  @Test
  fun theAspectRatioSurvivesTheSaver() {
    val saver = RealCropState.Saver(TestImages.source(TestImages.pngBytes()))

    listOf(
      AspectRatio.Free,
      AspectRatio.Square,
      AspectRatio.Widescreen16x9,
      AspectRatio.Portrait9x16,
      AspectRatio.Fixed(14f),
    ).forEach { ratio ->
      val state = RealCropState(TestImages.source(TestImages.pngBytes()), ratio)
      val saved = assertNotNull(with(saver) { SaverScopeStub.save(state) })
      val restored = assertNotNull(saver.restore(saved), "$ratio did not restore")

      assertEquals(ratio, restored.aspectRatio, "$ratio was lost by the saver")
    }
  }

  /** A saved list of the previous length is refused rather than read off by one. */
  @Test
  fun aSavedListFromAnOlderShapeIsRefused() {
    val saver = RealCropState.Saver(TestImages.source(TestImages.pngBytes()))

    assertEquals(
      null,
      saver.restore(listOf(1f, 0f, 0f, 0f, 0f, 0f, 0.1f, 0.1f, 0.9f, 0.9f)),
      "a ten element list was accepted, so every field after the rectangle reads the wrong slot",
    )
  }
}

private object SaverScopeStub : androidx.compose.runtime.saveable.SaverScope {
  override fun canBeSaved(value: Any): Boolean = true
}
