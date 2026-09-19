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
package com.github.skydoves.crayfish.device

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.github.skydoves.crayfish.decode.CropSource
import com.github.skydoves.crayfish.encode.EncodeOptions
import com.github.skydoves.crayfish.encode.EncodedFormat
import com.github.skydoves.crayfish.ui.CropResult
import com.github.skydoves.crayfish.ui.CropState
import com.github.skydoves.crayfish.ui.CropStatus
import com.github.skydoves.crayfish.ui.Cropper
import com.github.skydoves.crayfish.ui.LocalTileStore
import com.github.skydoves.crayfish.ui.TileStore
import com.github.skydoves.crayfish.ui.rememberCropState
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The preview and the crop agree about which way up an Exif rotated photo is.
 *
 * Reported from a Galaxy S23: a picked photo opened lying on its side, and cropping it came back
 * upright. Both halves of the cropper were internally consistent, which is what made it survive a
 * green suite: `CropperExifWiringTest` covers the tag reaching the output, and nothing covered the
 * preview, which decodes through its own path.
 *
 * The fixture is a 4000x3000 file, left half red and right half blue, tagged Exif 6. So the photo
 * is a 3000x4000 portrait whose **top** half is red. Every assertion below is written against that
 * sentence rather than against what the code does with it.
 */
@OptIn(ExperimentalTestApi::class)
class DeviceExifPreviewTest {

  private fun source(): CropSource {
    val file = File("/data/local/tmp/crayfish-fixtures", "oriented-rot90.jpg")
    check(file.isFile) {
      "missing device fixture $file: run ./gradlew :crayfish:pushDeviceFixtures"
    }
    return CropSource.FilePath(file.absolutePath)
  }

  @Test
  fun theFixtureIsALandscapeFileThatTheTagTurnsIntoAPortraitPhoto() = runComposeUiTest {
    val (state, _) = mount()

    // Without this the colour assertions below could pass on a photo that was never turned.
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(
      File("/data/local/tmp/crayfish-fixtures", "oriented-rot90.jpg").absolutePath,
      options,
    )
    assertEquals(4_000, options.outWidth, "the file on disk is not landscape")
    assertEquals(3_000, options.outHeight, "the file on disk is not landscape")

    assertEquals(3_000, state.imageSize.width, "the tag was not applied to the reported size")
    assertEquals(4_000, state.imageSize.height, "the tag was not applied to the reported size")
  }

  @Test
  fun thePreviewDrawsThePhotoTheRightWayUp() = runComposeUiTest {
    val (_, store) = mount()

    val base = assertNotNull(store.baseLayer, "the preview never produced a base layer")
    val bitmap = assertNotNull(base.imageBitmap, "the base layer has no pixels")

    assertTrue(
      bitmap.height > bitmap.width,
      "the preview decoded a landscape tile for a portrait photo: ${bitmap.width}x${bitmap.height}",
    )

    val pixels = bitmap.toPixelMap()
    val x = bitmap.width / 2
    assertTrue(
      pixels[x, bitmap.height / 8].red > pixels[x, bitmap.height / 8].blue,
      "the top of the preview is not the file's red left half",
    )
    assertTrue(
      pixels[x, bitmap.height * 7 / 8].blue > pixels[x, bitmap.height * 7 / 8].red,
      "the bottom of the preview is not the file's blue right half",
    )
  }

  @Test
  fun theCropOutputIsTheSameWayUpAsThePreview() = runComposeUiTest {
    val (state, store) = mount()

    val result = runBlocking { state.crop(EncodeOptions(format = EncodedFormat.PNG)) }
    val success = assertIs<CropResult.Success>(result)
    val output: Bitmap = assertNotNull(
      BitmapFactory.decodeByteArray(success.bytes, 0, success.bytes.size),
      "the cropped bytes did not decode",
    )

    val topIsRed = output.isRedAt(output.width / 2, output.height / 8)
    val bottomIsRed = output.isRedAt(output.width / 2, output.height * 7 / 8)

    val base = assertNotNull(store.baseLayer?.imageBitmap)
    val preview = base.toPixelMap()
    val previewTopIsRed = preview[base.width / 2, base.height / 8].let { it.red > it.blue }

    assertEquals(
      previewTopIsRed,
      topIsRed,
      "the preview and the crop disagree about which way up the photo is: the preview's top is " +
        "${if (previewTopIsRed) "red" else "blue"} and the output's top is " +
        "${if (topIsRed) "red" else "blue"}",
    )
    // And the pair agree with the file, rather than merely with each other.
    assertTrue(topIsRed, "the crop's top half is not the file's red left half")
    assertTrue(!bottomIsRed, "the crop's bottom half is not the file's blue right half")
  }

  private fun Bitmap.isRedAt(x: Int, y: Int): Boolean {
    val pixel = getPixel(x.coerceIn(0, width - 1), y.coerceIn(0, height - 1))
    val red = (pixel shr 16) and 0xFF
    val blue = pixel and 0xFF
    return red > blue && abs(red - blue) > 16
  }

  private fun androidx.compose.ui.test.ComposeUiTest.mount(): Pair<CropState, TileStore> {
    // Remembered inside the composition, not built outside it.
    //
    // A store built outside and provided in is the *same* instance for every composition the test
    // harness runs, and `CropPreview` closes the store it is given when it leaves. Two compositions
    // over one store means the first teardown clears the tile cache while the second is still
    // filling it, and the crash lands in `TileCache.clear` as a null element: the shape of a map
    // modified while it is being read. That is the test lending one object to two owners, not the
    // cropper mishandling its own.
    lateinit var store: TileStore
    var held: CropState? = null
    setContent {
      val owned = remember { TileStore() }
      store = owned
      CompositionLocalProvider(LocalTileStore provides owned) {
        val cropState = rememberCropState(source())
        held = cropState
        Cropper(state = cropState, modifier = Modifier.fillMaxSize())
      }
    }
    val start = System.nanoTime()
    waitUntil(timeoutMillis = 30_000) { held?.status is CropStatus.Ready }
    val ready = System.nanoTime()
    waitUntil(timeoutMillis = 30_000) { held?.viewportSize?.isEmpty == false }
    // The base layer is the slow one: a display sampled decode of the whole 12MP source. Timed
    // rather than merely waited on, because `startBaseLayer` makes exactly one attempt per source
    // and a decode that fails leaves the preview empty forever. A timeout here would otherwise be
    // indistinguishable from a decode that was merely slow.
    waitUntil(timeoutMillis = 30_000) { store.baseLayer != null }
    val base = System.nanoTime()
    android.util.Log.i(
      "CrayfishTiming",
      "ready=${(ready - start) / 1_000_000}ms baseLayer=${(base - ready) / 1_000_000}ms",
    )
    waitForIdle()
    return assertNotNull(held) to store
  }
}
