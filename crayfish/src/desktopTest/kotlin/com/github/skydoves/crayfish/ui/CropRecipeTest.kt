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
import com.github.skydoves.crayfish.decode.TestImages
import com.github.skydoves.crayfish.geometry.CropTransform
import com.github.skydoves.crayfish.geometry.FloatPoint
import com.github.skydoves.crayfish.geometry.FloatRect
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A crop stored as numbers, and opened again on the same photo.
 *
 * The whole point of a cropper that never holds pixels: what it produces can be written down. These
 * tests are about that round trip surviving both a string and a different screen.
 */
@OptIn(ExperimentalTestApi::class)
class CropRecipeTest {

  private val sample = CropRecipe(
    transform = CropTransform(
      scale = 2.25f,
      offset = FloatPoint(-30.5f, 12f),
      rotationDegrees = 90f,
      flipHorizontal = true,
      flipVertical = false,
    ),
    normalizedCropRect = FloatRect(0.15f, 0.2f, 0.75f, 0.8f),
    aspectRatio = AspectRatio.Square,
  )

  // -------------------------------------------------------------------------------------------
  // The string
  // -------------------------------------------------------------------------------------------

  @Test
  fun aRecipeSurvivesAStringRoundTrip() {
    val decoded = assertNotNull(CropRecipe.decodeFromString(sample.encodeToString()))
    assertEquals(sample, decoded)
    assertEquals(sample.transform, decoded.transform)
    assertEquals(sample.normalizedCropRect, decoded.normalizedCropRect)
    assertEquals(AspectRatio.Square, decoded.aspectRatio)
  }

  @Test
  fun aFreeRatioSurvivesToo() {
    val free = CropRecipe(CropTransform.Identity, FloatRect(0f, 0f, 1f, 1f), AspectRatio.Free)
    val decoded = assertNotNull(CropRecipe.decodeFromString(free.encodeToString()))
    assertEquals(AspectRatio.Free, decoded.aspectRatio)
  }

  /**
   * Anything unrecognised is a null, never a guess.
   *
   * A recipe that decodes wrong opens the crop somewhere the user never put it, which is worse than
   * opening on the default, because it looks deliberate.
   */
  @Test
  fun anythingThisVersionDoesNotRecogniseIsRefused() {
    assertNull(CropRecipe.decodeFromString(null))
    assertNull(CropRecipe.decodeFromString(""))
    assertNull(CropRecipe.decodeFromString("crayfish2:1:0:0:0:0:0:0:0:1:1:-1"), "a future version")
    assertNull(CropRecipe.decodeFromString("crayfish1:1:0:0:0:0:0:0:0:1:1"), "one field short")
    assertNull(CropRecipe.decodeFromString("crayfish1:1:0:0:0:0:0:0:0:1:1:-1:9"), "one field extra")
    assertNull(CropRecipe.decodeFromString("crayfish1:x:0:0:0:0:0:0:0:1:1:-1"), "not a number")
    assertNull(
      CropRecipe.decodeFromString("crayfish1:1:0:0:0:0:0:0:0:0:0:-1"),
      "an empty rectangle",
    )
    assertNull(
      CropRecipe.decodeFromString("crayfish1:1:0:0:0:0:0:NaN:0:1:1:-1"),
      "a rectangle that is not finite",
    )
  }

  /**
   * A broken transform is repaired rather than refused, which is the opposite of a broken rectangle.
   *
   * The rectangle is the crop the user chose and has no sensible repair. The transform is only how
   * they were looking at it, and `sanitized` has a defined answer for every bad value, so throwing
   * their rectangle away over a number they never see would be the worse trade. Process death takes
   * this same path, and `CropStateSaverTest` holds the other end of it.
   */
  @Test
  fun aBrokenTransformIsRepairedRatherThanRefused() {
    val decoded = assertNotNull(
      CropRecipe.decodeFromString("crayfish1:NaN:NaN:0:Infinity:0:0:0.1:0.1:0.9:0.9:-1"),
    )

    assertTrue(decoded.transform.scale.isFinite(), "a NaN scale survived")
    assertTrue(decoded.transform.offset.isFinite, "a NaN offset survived")
    assertTrue(decoded.transform.rotationDegrees.isFinite(), "an infinite rotation survived")
    assertEquals(FloatRect(0.1f, 0.1f, 0.9f, 0.9f), decoded.normalizedCropRect)
  }

  @Test
  fun theEncodedFormIsOneLineAndNamesItsVersion() {
    val encoded = sample.encodeToString()
    assertTrue(encoded.startsWith("crayfish1:"), "the version prefix is missing: $encoded")
    assertTrue('\n' !in encoded, "the encoded form spans lines, so it is not a column value")
  }

  // -------------------------------------------------------------------------------------------
  // The round trip that matters
  // -------------------------------------------------------------------------------------------

  @Test
  fun aStateOpenedFromARecipeIsWhereTheRecipeLeftIt() = runComposeUiTest {
    val first = readyState()
    // Move it somewhere no default would put it.
    first.normalizedCropRect = FloatRect(0.05f, 0.3f, 0.55f, 0.7f)
    first.transform = CropTransform(scale = 1.8f, offset = FloatPoint(12f, -8f))
    val stored = first.recipe.encodeToString()

    val reopened = readyState(recipe = assertNotNull(CropRecipe.decodeFromString(stored)))

    // Within a fraction of a pixel, not bit for bit. The reopened state runs the same constraint
    // passes the first one did, and those nudge the rectangle back inside the image, so demanding
    // equality here would be demanding that a restored crop skip the checks a fresh one gets.
    // `croppingFromARecipeSelectsTheStoredRegion` is the assertion that has to be exact, and is.
    assertEquals(first.normalizedCropRect.left, reopened.normalizedCropRect.left, 1e-4f)
    assertEquals(first.normalizedCropRect.top, reopened.normalizedCropRect.top, 1e-4f)
    assertEquals(first.normalizedCropRect.right, reopened.normalizedCropRect.right, 1e-4f)
    assertEquals(first.normalizedCropRect.bottom, reopened.normalizedCropRect.bottom, 1e-4f)
    assertEquals(first.transform.scale, reopened.transform.scale, absoluteTolerance = 1e-4f)
  }

  /**
   * The control. Without a recipe the same source opens on the default frame, so the assertion
   * above is about the recipe and not about two states that would agree anyway.
   */
  @Test
  fun theSameSourceWithoutARecipeOpensOnItsDefault() = runComposeUiTest {
    val moved = readyState()
    moved.normalizedCropRect = FloatRect(0.05f, 0.3f, 0.55f, 0.7f)

    val fresh = readyState()

    assertNotEquals(
      moved.normalizedCropRect,
      fresh.normalizedCropRect,
      "a fresh state opened exactly where the moved one was, so nothing was restored",
    )
  }

  @Test
  fun aRecipesRatioBeatsTheInitialOne() = runComposeUiTest {
    val recipe = CropRecipe(
      transform = CropTransform.Identity,
      normalizedCropRect = FloatRect(0.2f, 0.2f, 0.8f, 0.8f),
      aspectRatio = AspectRatio.Widescreen16x9,
    )
    val state = readyState(recipe = recipe, initialAspectRatio = AspectRatio.Square)

    assertEquals(AspectRatio.Widescreen16x9, state.aspectRatio)
  }

  /** And the crop it produces is the crop the recipe described. */
  @Test
  fun croppingFromARecipeSelectsTheStoredRegion() = runComposeUiTest {
    val first = readyState()
    first.normalizedCropRect = FloatRect(0.1f, 0.15f, 0.6f, 0.65f)
    val expected = assertIs<CropResult.Success>(runBlocking { first.crop() }).region

    val reopened = readyState(recipe = first.recipe)
    val actual = assertIs<CropResult.Success>(runBlocking { reopened.crop() }).region

    assertEquals(expected, actual, "the reopened crop selected a different part of the photo")
  }

  private fun androidx.compose.ui.test.ComposeUiTest.readyState(
    recipe: CropRecipe? = null,
    initialAspectRatio: AspectRatio = AspectRatio.Free,
  ): RealCropState {
    var held: CropState? = null
    setContent {
      val state = rememberCropState(
        source = source(),
        initialAspectRatio = initialAspectRatio,
        initialRecipe = recipe,
      )
      held = state
      Cropper(state = state, modifier = Modifier.fillMaxSize())
    }
    waitUntil(timeoutMillis = 10_000) { held?.status is CropStatus.Ready }
    waitUntil(timeoutMillis = 10_000) { held?.viewportSize?.isEmpty == false }
    waitForIdle()
    return assertNotNull(held) as RealCropState
  }

  private fun source(): CropSource = TestImages.source(TestImages.pngBytes(), key = "recipe")
}
