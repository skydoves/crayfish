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
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.github.skydoves.crayfish.decode.ImageSize
import com.github.skydoves.crayfish.decode.TestImages
import com.github.skydoves.crayfish.encode.EncodeOptions
import com.github.skydoves.crayfish.encode.EncodedFormat
import com.github.skydoves.crayfish.exif.ByteOrder
import com.github.skydoves.crayfish.exif.ExifBytes
import com.github.skydoves.crayfish.exif.ExifReader
import com.github.skydoves.crayfish.exif.ImageOrientation
import com.github.skydoves.crayfish.geometry.FloatRect
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exif orientation from the file's bytes to the cropped output, through the real composable.
 *
 * Every piece of this was already covered in isolation: [ExifReader] against hand-built headers,
 * [ImageOrientation.applyTo] against pixel arrays, and `cropToBytes` against a state whose
 * orientation the test set by hand. What none of them touched is the **wiring**: that `Cropper`
 * reads the source's header at all, that what it reads reaches [CropStatus.Ready], and that the
 * pipeline then acts on it. A stub returning `NORMAL` anywhere along that chain leaves every one of
 * those unit tests green and ships photographs rotated, which is the single failure this library
 * exists to prevent, and the one the category is worst at.
 *
 * The fixture is deliberately a real JPEG with a real Exif segment spliced in, not a synthetic
 * header: it has to survive both `ImageIO` and our own reader, or it proves nothing about either.
 */
@OptIn(ExperimentalTestApi::class)
class CropperExifWiringTest {

  // -----------------------------------------------------------------------------------------
  // The fixture has to be real before anything asserted with it means anything
  // -----------------------------------------------------------------------------------------

  /**
   * The negative control for every test below.
   *
   * If the splice produced a JPEG the platform decoder rejects, or one our reader cannot find the
   * tag in, the orientation assertions further down would be measuring a broken fixture rather
   * than the library.
   */
  @Test
  fun theSplicedFixtureIsBothADecodableJpegAndCarriesTheTag() {
    val bytes = jpegWithExifOrientation(ROTATE_90)

    val decoded = assertNotNull(
      ImageIO.read(ByteArrayInputStream(bytes)),
      "the spliced Exif segment made the JPEG undecodable",
    )
    assertEquals(TestImages.WIDTH, decoded.width)
    assertEquals(TestImages.HEIGHT, decoded.height)
    assertEquals(
      ImageOrientation.ROTATE_90,
      ExifReader.readOrientation(bytes),
      "our own reader cannot find the tag that was spliced in",
    )
    // And the pixels still say what the un-rotated fixture says, so any turn observed later came
    // from the library rather than from the encoder.
    assertNear(TestImages.TOP_LEFT, decoded.getRGB(16, 12), "the fixture's own top-left")
  }

  // -----------------------------------------------------------------------------------------
  // The wiring
  // -----------------------------------------------------------------------------------------

  /**
   * The seam that no other test crosses: header bytes reaching [CropStatus.Ready].
   *
   * The size assertion is the load-bearing half. A quarter turn swaps the axes, so a cropper that
   * read the tag but laid the image out at the file's own 64x48 would letterbox it wrongly and put
   * every rectangle in the API in the wrong space.
   */
  @Test
  fun readsTheOrientationOutOfTheSourceAndReportsTheUprightSize() = runComposeUiTest {
    val state = readyState(jpegWithExifOrientation(ROTATE_90))

    val status = assertIs<CropStatus.Ready>(state.status)
    assertEquals(ImageOrientation.ROTATE_90, status.orientation, "the Exif tag never arrived")
    assertEquals(
      ImageSize(TestImages.HEIGHT, TestImages.WIDTH),
      status.imageSize,
      "a quarter turn has to swap the reported axes",
    )
    assertEquals(status.imageSize, state.imageSize)
  }

  @Test
  fun leavesASourceWithNoExifSegmentAlone() = runComposeUiTest {
    val state = readyState(TestImages.jpegBytes())

    val status = assertIs<CropStatus.Ready>(state.status)
    assertEquals(ImageOrientation.NORMAL, status.orientation)
    assertEquals(ImageSize(TestImages.WIDTH, TestImages.HEIGHT), status.imageSize)
  }

  /** An orientation value outside 1..8 is a malformed file, not an instruction. */
  @Test
  fun ignoresAnOutOfRangeOrientationValue() = runComposeUiTest {
    val state = readyState(jpegWithExifOrientation(orientation = 9))

    assertEquals(ImageOrientation.NORMAL, assertIs<CropStatus.Ready>(state.status).orientation)
  }

  // -----------------------------------------------------------------------------------------
  // The pixels
  // -----------------------------------------------------------------------------------------

  /**
   * End to end: a photograph a camera wrote sideways comes out of [CropState.crop] upright.
   *
   * The fixture's four quadrants are four different colours, so a quarter turn is visible in where
   * each one lands and cannot be satisfied by a transform that merely resizes. Turning the image
   * 90 degrees clockwise sends the original top-left to the **top-right**.
   */
  @Test
  fun cropsAQuarterTurnedSourceUpright() = runComposeUiTest {
    val state = readyState(jpegWithExifOrientation(ROTATE_90))
    selectTheWholeImage(state)

    val output = assertSuccessPixels(state)

    assertEquals(TestImages.HEIGHT, output.width, "the output was not turned")
    assertEquals(TestImages.WIDTH, output.height, "the output was not turned")
    assertNear(TestImages.BOTTOM_LEFT, output.quadrant(0, 0), "upright top-left")
    assertNear(TestImages.TOP_LEFT, output.quadrant(1, 0), "upright top-right")
    assertNear(TestImages.BOTTOM_RIGHT, output.quadrant(0, 1), "upright bottom-left")
    assertNear(TestImages.TOP_RIGHT, output.quadrant(1, 1), "upright bottom-right")
  }

  /**
   * The mirrored half of the Exif table, which is where every other library stops.
   *
   * Values 2, 4, 5 and 7 mirror as well as turn. A cropper that normalises only the four rotations
   * passes its own test suite and hands back back-to-front photographs; the fixture's asymmetric
   * quadrants are what make the difference observable at all.
   */
  @Test
  fun cropsAMirroredSourceTheRightWayRound() = runComposeUiTest {
    val state = readyState(jpegWithExifOrientation(MIRROR_HORIZONTAL))
    selectTheWholeImage(state)

    val output = assertSuccessPixels(state)

    assertEquals(TestImages.WIDTH, output.width, "a pure mirror must not change the axes")
    assertEquals(TestImages.HEIGHT, output.height)
    assertNear(TestImages.TOP_RIGHT, output.quadrant(0, 0), "mirrored top-left")
    assertNear(TestImages.TOP_LEFT, output.quadrant(1, 0), "mirrored top-right")
    assertNear(TestImages.BOTTOM_RIGHT, output.quadrant(0, 1), "mirrored bottom-left")
    assertNear(TestImages.BOTTOM_LEFT, output.quadrant(1, 1), "mirrored bottom-right")
  }

  /** The control for the two above: with no tag, nothing moves. */
  @Test
  fun leavesAnUntaggedSourcesPixelsExactlyWhereTheyWere() = runComposeUiTest {
    val state = readyState(TestImages.jpegBytes())
    selectTheWholeImage(state)

    val output = assertSuccessPixels(state)

    assertEquals(TestImages.WIDTH, output.width)
    assertEquals(TestImages.HEIGHT, output.height)
    assertNear(TestImages.TOP_LEFT, output.quadrant(0, 0), "untouched top-left")
    assertNear(TestImages.TOP_RIGHT, output.quadrant(1, 0), "untouched top-right")
    assertNear(TestImages.BOTTOM_LEFT, output.quadrant(0, 1), "untouched bottom-left")
    assertNear(TestImages.BOTTOM_RIGHT, output.quadrant(1, 1), "untouched bottom-right")
  }

  // -----------------------------------------------------------------------------------------
  // Harness
  // -----------------------------------------------------------------------------------------

  private fun ComposeUiTest.readyState(bytes: ByteArray): RealCropState {
    var state: CropState? = null
    setContent {
      val cropState = rememberCropState(TestImages.source(bytes, key = bytes.size.toString()))
      state = cropState
      Cropper(state = cropState, modifier = Modifier.fillMaxSize())
    }
    waitUntil(timeoutMillis = TIMEOUT) { state?.status is CropStatus.Ready }
    return assertNotNull(state) as RealCropState
  }

  /**
   * Points the crop frame at the whole image rather than at the default inset rectangle.
   *
   * Written through the *normalised* rectangle because that is the field the state stores; the
   * content bounds are in viewport pixels and have to be divided back out. Selecting everything is
   * what makes the corner assertions above about the whole image instead of about its middle.
   */
  private fun ComposeUiTest.selectTheWholeImage(state: RealCropState) {
    // Where the image is actually drawn, not where it would sit untransformed. A portrait source
    // in a landscape window letterboxes, the default crop frame is then wider than the image, and
    // the coverage pass scales the image up to meet it - so `contentBounds` is no longer the rect
    // the pixels occupy. Selecting that instead of the mapped one asks for the middle of the photo
    // and then asserts it is the whole photo.
    val bounds = state.transform.mapRect(
      state.coordinateSpace.contentBounds,
      state.coordinateSpace.pivot,
    )
    val viewport = state.viewportSize
    assertTrue(!viewport.isEmpty, "the viewport never measured, so nothing below means anything")
    state.normalizedCropRect = FloatRect(
      left = bounds.left / viewport.width,
      top = bounds.top / viewport.height,
      right = bounds.right / viewport.width,
      bottom = bounds.bottom / viewport.height,
    )
    waitForIdle()
  }

  private fun assertSuccessPixels(state: RealCropState): BufferedImage {
    val result = runBlocking { state.crop(EncodeOptions(EncodedFormat.PNG)) }
    val success = assertIs<CropResult.Success>(result, "crop failed: $result")
    return assertNotNull(
      ImageIO.read(ByteArrayInputStream(success.bytes)),
      "the crop produced bytes that are not a readable image",
    )
  }

  /** The colour at the centre of quadrant ([column], [row]) of a 2x2 division of this image. */
  private fun BufferedImage.quadrant(column: Int, row: Int): Int =
    getRGB(width / 4 + column * width / 2, height / 4 + row * height / 2)

  /**
   * Colour equality with room for the JPEG round trip.
   *
   * The fixture is encoded as JPEG so that it can carry an Exif segment at all, and chroma
   * subsampling moves saturated primaries by a few counts. The tolerance is per channel and far
   * tighter than the distance between any two of the four quadrant colours, so it cannot make a
   * wrongly placed quadrant look right.
   */
  private fun assertNear(expected: Int, actual: Int, message: String) {
    val channels = listOf(16, 8, 0)
    val off = channels.any { shift ->
      abs(((expected shr shift) and 0xFF) - ((actual shr shift) and 0xFF)) > TOLERANCE
    }
    assertTrue(
      !off,
      "$message: expected ~${expected.toUInt().toString(
        16,
      )} but was ${actual.toUInt().toString(16)}",
    )
  }

  /**
   * [TestImages.jpegBytes] with an Exif APP1 segment carrying [orientation] spliced in after the
   * SOI marker, which is where a camera writes it.
   */
  private fun jpegWithExifOrientation(orientation: Int): ByteArray {
    val tiff = ExifBytes.exifBlock(orientation = orientation, order = ByteOrder.BIG)
    val payload = "Exif".encodeToByteArray() + byteArrayOf(0, 0) + tiff
    val length = payload.size + 2
    val segment = byteArrayOf(
      0xFF.toByte(),
      0xE1.toByte(),
      ((length shr 8) and 0xFF).toByte(),
      (length and 0xFF).toByte(),
    ) + payload
    val base = TestImages.jpegBytes()
    return base.copyOfRange(0, 2) + segment + base.copyOfRange(2, base.size)
  }

  private companion object {
    const val TIMEOUT = 10_000L
    const val ROTATE_90 = 6
    const val MIRROR_HORIZONTAL = 2
    const val TOLERANCE = 24
  }
}
