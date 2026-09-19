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

import com.github.skydoves.crayfish.decode.CropSource
import com.github.skydoves.crayfish.decode.DecodedRegion
import com.github.skydoves.crayfish.decode.ImageRegion
import com.github.skydoves.crayfish.decode.ImageSize
import com.github.skydoves.crayfish.decode.RegionDecoder
import com.github.skydoves.crayfish.decode.TestImages
import com.github.skydoves.crayfish.decode.assertColour
import com.github.skydoves.crayfish.decode.createRegionDecoder
import com.github.skydoves.crayfish.e2e.Fixtures
import com.github.skydoves.crayfish.e2e.MemoryProbe
import com.github.skydoves.crayfish.encode.EncodeOptions
import com.github.skydoves.crayfish.encode.EncodedFormat
import com.github.skydoves.crayfish.exif.ImageOrientation
import com.github.skydoves.crayfish.geometry.FloatRect
import com.github.skydoves.crayfish.geometry.FloatSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The output pipeline, end to end: a crop rectangle on screen in, encoded bytes out.
 *
 * Every assertion about the pixels is made on **specific colours at specific coordinates**, decoded
 * back out of the bytes the pipeline produced. A size-only assertion passes just as happily on a
 * crop that is flipped, mirrored or offset by half the image, which is precisely the family of
 * bugs this layer exists to avoid, so the fixture is asymmetric in both axes and the corners are
 * checked one by one.
 */
class CropPipelineTest {

  private val opened = mutableListOf<RegionDecoder>()

  @AfterTest
  fun closeDecoders() {
    opened.forEach { runCatching { it.close() } }
    opened.clear()
  }

  // -------------------------------------------------------------------------------------------
  // The pixels
  // -------------------------------------------------------------------------------------------

  @Test
  fun cropsTheTopLeftQuadrantAtTheRightSizeAndColour() = runTest {
    val state = quadrantState(crop = FloatRect(0f, 0f, 0.5f, 0.5f))

    val result = assertSuccess(cropToBytes(state, EncodeOptions(EncodedFormat.PNG)))

    assertEquals(ImageSize(32, 24), result.size)
    assertEquals(ImageRegion(0, 0, 32, 24), result.region)
    val image = result.decoded()
    assertEquals(32, image.width)
    assertEquals(24, image.height)
    // The whole crop is one quadrant, so every corner of it is the same colour, and it is the
    // red one, which is also the channel-order canary: read as RGBA it would come back blue.
    assertColour(TestImages.TOP_LEFT, image.getRGB(0, 0), "top-left of the crop")
    assertColour(TestImages.TOP_LEFT, image.getRGB(31, 23), "bottom-right of the crop")
  }

  /**
   * The assertion that a flipped or offset crop cannot pass.
   *
   * A centred rectangle spanning all four quadrants: its own four corners must land on four
   * different colours, each the one the fixture puts there. A horizontal flip swaps red with green,
   * a vertical flip swaps red with blue, a transposition swaps green with blue, and an offset of a
   * single quadrant moves all four.
   */
  @Test
  fun cropsACentredRectangleWithEveryQuadrantInItsOwnCorner() = runTest {
    val state = quadrantState(crop = FloatRect(0.25f, 0.25f, 0.75f, 0.75f))

    val result = assertSuccess(cropToBytes(state, EncodeOptions(EncodedFormat.PNG)))

    assertEquals(ImageSize(32, 24), result.size)
    assertEquals(ImageRegion(16, 12, 48, 36), result.region)
    val image = result.decoded()
    assertColour(TestImages.TOP_LEFT, image.getRGB(0, 0), "top-left")
    assertColour(TestImages.TOP_RIGHT, image.getRGB(31, 0), "top-right")
    assertColour(TestImages.BOTTOM_LEFT, image.getRGB(0, 23), "bottom-left")
    assertColour(TestImages.BOTTOM_RIGHT, image.getRGB(31, 23), "bottom-right")
  }

  /**
   * A frame dragged past the edge of the image yields the overlap, not a failure and not padding.
   *
   * Platform region decoders disagree about an out-of-bounds rectangle (clamp, throw, or return
   * undefined pixels) so the pipeline has to arrive with a legal one. Half of this rectangle is
   * off the top-left corner of the image.
   */
  @Test
  fun clipsARectangleDraggedOffTheEdgeToTheImage() = runTest {
    val state = quadrantState(crop = FloatRect(-0.5f, -0.5f, 0.5f, 0.5f))

    val result = assertSuccess(cropToBytes(state, EncodeOptions(EncodedFormat.PNG)))

    assertEquals(ImageRegion(0, 0, 32, 24), result.region)
    assertEquals(ImageSize(32, 24), result.size)
    assertColour(TestImages.TOP_LEFT, result.decoded().getRGB(0, 0), "top-left of the clipped crop")
  }

  @Test
  fun reportsAnEmptyRegionForAFrameEntirelyOffTheImage() = runTest {
    val state = quadrantState(crop = FloatRect(1.5f, 1.5f, 2f, 2f))

    val result = cropToBytes(state, EncodeOptions(EncodedFormat.PNG))

    assertEquals(CropResult.Failure.Reason.EmptyRegion, assertFailure(result).reason)
  }

  /** A frame collapsed to nothing is the same answer, and must not reach a decoder. */
  @Test
  fun reportsAnEmptyRegionForACollapsedFrame() = runTest {
    val state = quadrantState(crop = FloatRect(0.5f, 0.5f, 0.5f, 0.5f))

    val result = cropToBytes(state, EncodeOptions(EncodedFormat.PNG))

    assertEquals(CropResult.Failure.Reason.EmptyRegion, assertFailure(result).reason)
  }

  // -------------------------------------------------------------------------------------------
  // Formats and alpha
  // -------------------------------------------------------------------------------------------

  /**
   * Lossless WebP has no Skia encoder, verified by measurement in `ImageEncoder.skia`, and the
   * encoder returns null for it. Null is the expect declaration's "this platform cannot write that
   * format", so it must surface as a refusal and not as a generic failure: the caller's fix is to
   * choose another format.
   */
  @Test
  fun refusesAFormatThisPlatformCannotWrite() = runTest {
    val state = quadrantState(crop = FloatRect(0f, 0f, 0.5f, 0.5f))

    val result = cropToBytes(state, EncodeOptions(EncodedFormat.WEBP_LOSSLESS))

    assertEquals(CropResult.Failure.Reason.EncodeUnsupported, assertFailure(result).reason)
  }

  /**
   * Transparency is refused by a format that cannot carry it, rather than flattened onto black.
   *
   * The rule the pipeline commits to. A silently flattened crop is unrecoverable: the caller finds
   * out when a fringe appears around a logo, by which time the original is gone. So the failure is
   * raised before the encode, and carries a cause that says what to do about it.
   */
  @Test
  fun refusesToFlattenTranslucentPixelsIntoAFormatWithoutAlpha() = runTest {
    val state = translucentState()

    val result = cropToBytes(state, EncodeOptions(EncodedFormat.JPEG))

    val failure = assertFailure(result)
    assertEquals(CropResult.Failure.Reason.EncodeUnsupported, failure.reason)
    assertNotNull(failure.cause, "a refusal this surprising has to say why")
  }

  /** The same pixels into a format that can hold them: the alpha survives, untouched. */
  @Test
  fun keepsTranslucentPixelsWhenTheFormatCanHoldThem() = runTest {
    val state = translucentState()

    val result = assertSuccess(cropToBytes(state, EncodeOptions(EncodedFormat.PNG)))

    assertEquals(ImageSize(4, 4), result.size)
    val image = result.decoded()
    assertColour(0x80FF0000.toInt(), image.getRGB(0, 0), "half-alpha red")
    assertColour(0x40008080.toInt(), image.getRGB(3, 0), "quarter-alpha teal")
  }

  /**
   * The control for the rule above, without which it would be indistinguishable from "PNG sources
   * may never be written as JPEG". An opaque PNG has nothing to lose and is allowed through.
   */
  @Test
  fun stillWritesAnOpaquePngSourceAsJpeg() = runTest {
    val state = quadrantState(crop = FloatRect(0f, 0f, 0.5f, 0.5f))

    val result = assertSuccess(cropToBytes(state, EncodeOptions(EncodedFormat.JPEG)))

    assertEquals(ImageSize(32, 24), result.size)
    assertTrue(
      result.bytes.size > 3 && result.bytes[0] == 0xFF.toByte() && result.bytes[1] == 0xD8.toByte(),
      "expected a JPEG SOI marker",
    )
    // Lossy, so channel dominance rather than equality: red must still be red.
    val red = result.decoded().getRGB(4, 4)
    assertTrue((red shr 16) and 0xFF > 200, "red channel of the crop")
    assertTrue(red and 0xFF < 80, "blue channel of the crop")
  }

  // -------------------------------------------------------------------------------------------
  // What the decode actually did, as opposed to what it was asked to do
  // -------------------------------------------------------------------------------------------

  /**
   * The output size comes from the decode that happened, never from the sample size requested.
   *
   * A decoder that meets an `OutOfMemoryError` retries at a coarser subsampling instead of failing,
   * so the pixels can arrive at half the scale that was asked for. `DecodedRegion.sampleSize`
   * reports that, and a pipeline that derives its output size from its own request instead reports
   * a size twice the bytes it is handing back. Simulated here by a decoder that always coarsens,
   * because provoking a real out-of-memory retry inside a test JVM is not reproducible.
   */
  @Test
  fun reportsTheSizeTheDecoderProducedNotTheSizeItWasAskedFor() = runTest {
    val state = quadrantState(crop = FloatRect(0f, 0f, 1f, 1f))
    state.decoder = CoarseningDecoder(assertNotNull(state.decoder), coarsenBy = 2)

    val result = assertSuccess(cropToBytes(state, EncodeOptions(EncodedFormat.PNG)))

    // The pipeline asked for sampleSize 1 (a 64x48 region is nowhere near the output budget) and
    // the decoder answered at 2. 64x48 is what a pipeline reading its own request would report.
    assertEquals(ImageSize(32, 24), result.size)
    assertEquals(ImageRegion(0, 0, 64, 48), result.region)
    val image = result.decoded()
    assertEquals(32, image.width, "the bytes themselves are half size")
    assertEquals(24, image.height, "the bytes themselves are half size")
  }

  /**
   * A job cancelled while the encode was running is reported as [CropResult.Cancelled].
   *
   * The ordering this pins: the encode bottoms out in one blocking platform call with no suspension
   * point, so it cannot be interrupted and the job can only be consulted once it returns, and that
   * consultation has to happen *before* the encoder's null is read, because both Skia and Android
   * report a cancelled caller with the same null they use for a format they cannot write. Reading
   * the null first turns a cancelled crop into `EncodeUnsupported` for PNG.
   */
  @Test
  fun reportsCancellationRatherThanAnEncodeFailureWhenTheJobEndedMidEncode() = runBlocking<Unit> {
    val state = quadrantState(crop = FloatRect(0f, 0f, 0.5f, 0.5f))
    var running: Job? = null
    state.decoder =
      CoarseningDecoder(assertNotNull(state.decoder), onDecoded = { running?.cancel() })

    var result: CropResult? = null
    val scope = CoroutineScope(Dispatchers.Default)
    val job = scope.launch { result = cropToBytes(state, EncodeOptions(EncodedFormat.PNG)) }
    running = job
    job.join()

    assertIs<CropResult.Cancelled>(assertNotNull(result, "the pipeline returned nothing at all"))
  }

  // -------------------------------------------------------------------------------------------
  // Exif orientation
  // -------------------------------------------------------------------------------------------

  /**
   * A quarter-turn orientation comes out upright, with the dimensions swapped.
   *
   * The whole point of reading the Exif tag. A cropper that decodes the file's own grid and encodes
   * it unchanged returns a photo lying on its side for every portrait shot from a phone that
   * records orientation in metadata rather than in pixels, which is most of them.
   */
  @Test
  fun bringsAQuarterTurnedSourceUpright() = runTest {
    val state = quadrantState(
      crop = FloatRect(0f, 0f, 1f, 1f),
      orientation = ImageOrientation.ROTATE_90,
    )

    val success = assertSuccess(cropToBytes(state, EncodeOptions(EncodedFormat.PNG)))
    val decoded = assertNotNull(ImageIO.read(ByteArrayInputStream(success.bytes)))

    assertEquals(success.size.width, decoded.width)
    assertEquals(success.size.height, decoded.height)
    assertTrue(
      decoded.height > decoded.width,
      "a quarter turn of a landscape crop has to come out portrait, got " +
        "${decoded.width}x${decoded.height}",
    )
    // Rotating clockwise carries the bottom-left quadrant into the top-left corner.
    assertColour(TestImages.BOTTOM_LEFT, decoded.getRGB(2, 2), "top-left after a quarter turn")
    assertColour(
      TestImages.TOP_LEFT,
      decoded.getRGB(decoded.width - 3, 2),
      "top-right after a quarter turn",
    )
  }

  /**
   * A mirrored orientation is mirrored, not merely rotated.
   *
   * The regression that matters most. Four of the eight Exif values involve a mirror, and an
   * implementation that reads the rotation and ignores the flip passes every rotation test while
   * returning a back-to-front image: upright, plausible, and wrong. Only an asymmetric fixture
   * catches it, which is why the quadrants are four different colours.
   */
  @Test
  fun mirrorsASourceThatDeclaresAMirroredOrientation() = runTest {
    val upright = assertSuccess(
      cropToBytes(
        quadrantState(crop = FloatRect(0f, 0f, 1f, 1f)),
        EncodeOptions(EncodedFormat.PNG),
      ),
    )
    val mirrored = assertSuccess(
      cropToBytes(
        quadrantState(
          crop = FloatRect(0f, 0f, 1f, 1f),
          orientation = ImageOrientation.FLIP_HORIZONTAL,
        ),
        EncodeOptions(EncodedFormat.PNG),
      ),
    )

    val plain = assertNotNull(ImageIO.read(ByteArrayInputStream(upright.bytes)))
    val flipped = assertNotNull(ImageIO.read(ByteArrayInputStream(mirrored.bytes)))

    assertEquals(plain.width, flipped.width, "a horizontal flip does not change the size")
    assertEquals(plain.height, flipped.height)
    // The left edge now shows what used to be on the right.
    assertColour(
      plain.getRGB(plain.width - 3, 2),
      flipped.getRGB(2, 2),
      "the flipped image's top-left must be the original's top-right",
    )
    assertTrue(
      plain.getRGB(2, 2) != flipped.getRGB(2, 2),
      "a mirrored orientation that renders identically to the unmirrored one has ignored the flip",
    )
  }

  @Test
  fun reportsAnUnreadableSourceWhenNoDecoderIsOpen() = runTest {
    val state = quadrantState(crop = FloatRect(0f, 0f, 0.5f, 0.5f))
    state.closeDecoder()

    val result = cropToBytes(state, EncodeOptions(EncodedFormat.PNG))

    assertEquals(CropResult.Failure.Reason.SourceUnreadable, assertFailure(result).reason)
  }

  // -------------------------------------------------------------------------------------------
  // The memory claim, on a source far larger than the budget
  // -------------------------------------------------------------------------------------------

  /**
   * The whole of a 108MP source, cropped and encoded inside a mid-range phone's heap.
   *
   * Two claims at once. The budget applies **even though this crop asks for full resolution**: a
   * 12000x9000 region is 412MiB decoded, over `DecodeBudget.ForOutput`, so the pipeline must
   * subsample to 6000x4500 rather than honouring the request. And the whole operation (decode,
   * encode, hand back) must allocate less than `MemoryProbe.BUDGET_BYTES`, which is less than a
   * single full-resolution decode of this file would cost.
   */
  @Test
  fun cropsA108MegapixelSourceWithinTheBudgetBySubsamplingIt() = runBlocking {
    val state = stateFor(
      source = CropSource.FilePath(Fixtures.sensor108mp.absolutePath),
      viewport = FloatSize(1200f, 900f),
      crop = FloatRect(0f, 0f, 1f, 1f),
    )
    assertEquals(ImageSize(12_000, 9_000), state.imageSize)

    val (result, reading) = MemoryProbe.measure {
      runBlocking { cropToBytes(state, EncodeOptions(EncodedFormat.JPEG, lossyQuality = 85)) }
    }

    val success = assertSuccess(result)
    assertEquals(ImageRegion(0, 0, 12_000, 9_000), success.region)
    assertEquals(
      ImageSize(6_000, 4_500),
      success.size,
      "the output budget has to force a sample size of 2 on a 412MiB region",
    )
    assertTrue(
      success.bytes.size > 1024,
      "a JPEG of a real crop is not ${success.bytes.size} bytes",
    )
    assertTrue(
      reading.allocatedBytes < MemoryProbe.BUDGET_BYTES,
      "cropping allocated $reading, over the ${MemoryProbe.BUDGET_BYTES / 1024 / 1024}MB budget",
    )
    println("[pipeline] 108MP crop -> jpeg: $reading, ${success.bytes.size / 1024}KB out")
  }

  // -------------------------------------------------------------------------------------------

  /** The quadrant fixture, laid out ten times its own size so the maths stays exact. */
  private suspend fun quadrantState(
    crop: FloatRect,
    orientation: ImageOrientation = ImageOrientation.NORMAL,
  ): RealCropState = stateFor(
    source = TestImages.source(TestImages.pngBytes()),
    viewport = FloatSize(TestImages.WIDTH * SCALE, TestImages.HEIGHT * SCALE),
    crop = crop,
    orientation = orientation,
  )

  /** The 4x4 fixture whose alpha varies, framed whole. */
  private suspend fun translucentState(): RealCropState = stateFor(
    source = TestImages.source(TestImages.pngBytes(TestImages.translucent()), key = "translucent"),
    viewport = FloatSize(400f, 400f),
    crop = FloatRect(0f, 0f, 1f, 1f),
  )

  /**
   * A state standing where `Cropper` would have left one: source open, size known, viewport
   * measured, crop rectangle held as fractions of that viewport.
   *
   * Assembled by hand rather than through a composition because the pipeline has no UI in it, and
   * a test that needs a window to check a rectangle is a test that will be skipped.
   */
  private suspend fun stateFor(
    source: CropSource,
    viewport: FloatSize,
    crop: FloatRect,
    orientation: ImageOrientation = ImageOrientation.NORMAL,
  ): RealCropState {
    val regionDecoder = assertNotNull(createRegionDecoder(source), "could not open the fixture")
    opened += regionDecoder
    val state = RealCropState(source, AspectRatio.Free)
    state.decoder = regionDecoder
    // Exactly what Cropper computes: the size after the orientation is applied, and the
    // orientation that is still outstanding.
    state.status = CropStatus.Ready(
      imageSize = orientation.transformSize(regionDecoder.imageSize),
      orientation = orientation,
    )
    state.viewportSize = viewport
    state.normalizedCropRect = crop
    return state
  }

  private fun assertSuccess(result: CropResult): CropResult.Success {
    if (result is CropResult.Failure) {
      throw AssertionError("expected a crop, got ${result.reason}: ${result.cause}")
    }
    return assertIs<CropResult.Success>(result)
  }

  private fun assertFailure(result: CropResult): CropResult.Failure {
    if (result is CropResult.Success) {
      throw AssertionError("expected a failure, got ${result.size} of ${result.region}")
    }
    return assertIs<CropResult.Failure>(result)
  }

  /** The encoded bytes, read back by ImageIO, a decoder the pipeline had no hand in. */
  private fun CropResult.Success.decoded(): BufferedImage =
    assertNotNull(ImageIO.read(ByteArrayInputStream(bytes)), "the output did not decode")

  private companion object {
    /** Ten viewport pixels per image pixel: every mapping below is exact at this scale. */
    const val SCALE = 10f
  }
}

/**
 * A decoder that answers every request at a coarser sample size than it was given.
 *
 * Stands in for the one thing a test JVM cannot be made to do on demand: run out of memory
 * mid-decode and retry. The retry is not hypothetical (both the Android and the desktop decoders
 * implement it) and it is the reason `DecodedRegion` reports the sample size it really used.
 */
private class CoarseningDecoder(
  private val delegate: RegionDecoder,
  private val coarsenBy: Int = 1,
  private val onDecoded: () -> Unit = {},
) : RegionDecoder by delegate {

  override suspend fun decodeRegion(region: ImageRegion, sampleSize: Int): DecodedRegion? {
    val decoded = delegate.decodeRegion(region, sampleSize * coarsenBy) ?: return null
    onDecoded()
    return decoded
  }
}
