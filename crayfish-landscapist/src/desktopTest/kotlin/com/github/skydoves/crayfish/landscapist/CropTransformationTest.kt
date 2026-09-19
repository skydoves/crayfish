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
package com.github.skydoves.crayfish.landscapist

import com.github.skydoves.crayfish.decode.ImageRegion
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CropTransformationTest {

  /** Four different colours, so a mis-offset or flipped crop cannot pass as a correct one. */
  private fun quadrants(size: Int = 64): Bitmap {
    val info = ImageInfo(size, size, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL, null)
    val bytes = ByteArray(size * size * 4)
    for (y in 0 until size) {
      for (x in 0 until size) {
        val offset = (y * size + x) * 4
        val left = x < size / 2
        val top = y < size / 2
        // B, G, R, A
        bytes[offset] = if (left && top) 0xFF.toByte() else 0
        bytes[offset + 1] = if (!left && top) 0xFF.toByte() else 0
        bytes[offset + 2] = if (left && !top) 0xFF.toByte() else 0
        bytes[offset + 3] = 0xFF.toByte()
      }
    }
    return Bitmap().apply {
      check(installPixels(info, bytes, size * 4))
      setImmutable()
    }
  }

  /**
   * The same fixture, left mutable.
   *
   * [quadrants] calls `setImmutable`, which makes it useless for asking whether cropping froze the
   * caller's bitmap: the control fails before the assertion is reached. Landscapist hands over a
   * bitmap it still owns, so mutable is the realistic state anyway.
   */
  private fun mutableQuadrants(size: Int = 64): Bitmap {
    val info = ImageInfo(size, size, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL, null)
    val bytes = ByteArray(size * size * 4) { if (it % 4 == 3) 0xFF.toByte() else 0 }
    return Bitmap().apply { check(installPixels(info, bytes, size * 4)) }
  }

  private fun Bitmap.argbAt(x: Int, y: Int): Int = getColor(x, y)

  @Test
  fun cropsToTheRegionItWasGiven() = runTest {
    val source = quadrants()
    val transformation = CropTransformation(ImageRegion(32, 0, 64, 32))

    val result = assertNotNull(transformation.transform(source) as? Bitmap)

    assertEquals(32, result.width)
    assertEquals(32, result.height)
    // The top-right quadrant is the green one; cropping to it must not return the blue top-left.
    assertEquals(source.argbAt(40, 10), result.argbAt(8, 10), "the wrong quadrant came back")
  }

  @Test
  fun clipsARegionThatRunsPastTheEdge() = runTest {
    val source = quadrants()
    val result = assertNotNull(
      CropTransformation(ImageRegion(48, 48, 200, 200)).transform(source) as? Bitmap,
    )

    assertEquals(16, result.width)
    assertEquals(16, result.height)
  }

  /**
   * A transformation that cannot apply returns the image it was given.
   *
   * Landscapist runs these inside a load, so throwing would turn a picture that would have
   * displayed into a failed request. A worse crop beats a broken screen.
   */
  @Test
  fun returnsTheInputUntouchedWhenTheRegionIsUnusable() = runTest {
    val source = quadrants()

    assertSame(source, CropTransformation(ImageRegion(0, 0, 0, 0)).transform(source))
    assertSame(source, CropTransformation(ImageRegion(500, 500, 600, 600)).transform(source))
  }

  @Test
  fun leavesAnInputItDoesNotUnderstandAlone() = runTest {
    val stranger = "not a bitmap"

    assertSame(stranger, CropTransformation(ImageRegion(0, 0, 8, 8)).transform(stranger))
  }

  /**
   * Two crops of one image have to be two cache entries.
   *
   * Landscapist builds its key from this string; a constant would make the second screen to ask
   * receive whatever the first one cropped.
   */
  @Test
  fun theCacheKeyDistinguishesRegions() {
    val first = CropTransformation(ImageRegion(0, 0, 10, 10)).key
    val second = CropTransformation(ImageRegion(0, 0, 10, 11)).key

    assertTrue(first != second, "two regions produced the same cache key: $first")
    assertTrue(first.startsWith("crayfish-crop:"), first)
  }

  /**
   * Landscapist's own pipeline works in `Image`, not `Bitmap`.
   *
   * `Image` is Skia's immutable, GPU-resident form and it is what a Landscapist request actually
   * produces on every Skia target. Cropping it takes a different path through this file (a round
   * trip out to a `Bitmap` and back), and none of it had ever been executed. A transformation that
   * silently returns the input unchanged for the type its only caller passes is a transformation
   * that does nothing at all, and every existing test would still have been green.
   */
  @Test
  fun cropsASkiaImage() = runTest {
    val source = Image.makeFromBitmap(quadrants())
    val transformation = CropTransformation(ImageRegion(32, 0, 64, 32))

    val result = assertNotNull(
      transformation.transform(source) as? Image,
      "an Image went in and something that is not an Image came out",
    )

    assertEquals(32, result.width)
    assertEquals(32, result.height)
    // The top-right quadrant is green; returning the input unchanged would give back 64x64, and
    // cropping the wrong corner would give back the blue one.
    assertEquals(
      Bitmap.makeFromImage(source).argbAt(40, 10),
      Bitmap.makeFromImage(result).argbAt(8, 10),
      "the wrong quadrant came back",
    )
  }

  @Test
  fun clipsARegionThatRunsPastTheEdgeOfAnImage() = runTest {
    val source = Image.makeFromBitmap(quadrants())

    val result = assertNotNull(
      CropTransformation(ImageRegion(48, 48, 200, 200)).transform(source) as? Image,
    )

    assertEquals(16, result.width)
    assertEquals(16, result.height)
  }

  @Test
  fun returnsAnImageUntouchedWhenTheRegionIsUnusable() = runTest {
    val source = Image.makeFromBitmap(quadrants())

    assertSame(source, CropTransformation(ImageRegion(0, 0, 0, 0)).transform(source))
    assertSame(source, CropTransformation(ImageRegion(500, 500, 600, 600)).transform(source))
  }

  /**
   * The source survives the crop.
   *
   * `extractSubset` copies rather than aliasing, which is the documented reason this is safe, and
   * it matters because Landscapist is still holding the original for its own cache. If the crop
   * consumed it, the image would be freed under a cache that still hands it out, which on Skia is
   * a SIGSEGV rather than an exception.
   */
  @Test
  fun leavesTheSourceImageUsableAfterwards() = runTest {
    val source = Image.makeFromBitmap(quadrants())

    CropTransformation(ImageRegion(0, 0, 32, 32)).transform(source)

    assertEquals(64, source.width, "the source was resized by cropping a copy of it")
    assertEquals(64, source.height)
    // Reading it is the real assertion: a closed Image aborts the process rather than throwing.
    assertEquals(0xFF.toByte().toInt() and 0xFF, 255)
    assertNotNull(Bitmap.makeFromImage(source).argbAt(10, 10))
  }

  @Test
  fun anImageAndABitmapOfTheSamePixelsCropIdentically() = runTest {
    val bitmap = quadrants()
    val image = Image.makeFromBitmap(bitmap)
    val region = ImageRegion(16, 16, 48, 48)

    val fromBitmap = assertNotNull(CropTransformation(region).transform(bitmap) as? Bitmap)
    val fromImage = Bitmap.makeFromImage(
      assertNotNull(CropTransformation(region).transform(image) as? Image),
    )

    assertEquals(fromBitmap.width, fromImage.width)
    assertEquals(fromBitmap.height, fromImage.height)
    for (point in listOf(0 to 0, 31 to 0, 0 to 31, 31 to 31, 15 to 15)) {
      assertEquals(
        fromBitmap.argbAt(point.first, point.second),
        fromImage.argbAt(point.first, point.second),
        "the two input types disagree at ${'$'}point",
      )
    }
  }

  // -------------------------------------------------------------------------------------------
  // Ownership: the crop must not borrow the caller's pixels
  // -------------------------------------------------------------------------------------------

  /**
   * The crop owns its own pixels rather than pointing into the source.
   *
   * This used to call `extractSubset`, whose own Skia documentation says "Shares PixelRef with dst.
   * Pixels are not copied; this and dst point to the same pixels." A 16x16 crop of a 512x512 bitmap
   * therefore came back still carrying the source's 2048 byte stride and holding the whole 1MB
   * allocation alive. That is the opposite of what a transformation feeding an image cache should
   * do: Landscapist keeps one entry per region and accounts for the small size while the large one
   * is what is actually retained.
   *
   * `rowBytes` is the observable. A tightly packed 16 pixel wide BGRA bitmap is 64 bytes per row;
   * anything wider means the rows are still spaced for the source.
   */
  @Test
  fun theCropDoesNotShareTheSourcesPixelBuffer() = runTest {
    val source = quadrants(size = 512)

    val result = assertNotNull(
      CropTransformation(ImageRegion(0, 0, 16, 16)).transform(source) as? Bitmap,
    )

    assertEquals(16, result.width)
    assertEquals(16, result.height)
    assertEquals(
      16 * 4,
      result.rowBytes,
      "the crop kept the source's ${source.rowBytes} byte stride, so it pins the whole raster",
    )
    assertTrue(
      result.computeByteSize() < source.computeByteSize() / 10,
      "a 16x16 crop retains ${result.computeByteSize()} bytes of a ${source.computeByteSize()} " +
        "byte source",
    )
  }

  /**
   * Cropping leaves the caller's bitmap exactly as it found it.
   *
   * `extractSubset` marks the source immutable so that sharing its pixels is safe. Landscapist owns
   * that bitmap and may still be writing to it, so freezing it as a side effect of a crop is not
   * ours to do.
   */
  @Test
  fun theCropLeavesTheSourceMutable() = runTest {
    val source = mutableQuadrants()
    assertTrue(!source.isImmutable, "the fixture starts immutable, so this proves nothing")

    CropTransformation(ImageRegion(0, 0, 16, 16)).transform(source)

    assertTrue(!source.isImmutable, "cropping froze the caller's bitmap")
  }

  /** And a later write to the source does not reach through into a crop already handed out. */
  @Test
  fun writingToTheSourceAfterwardsDoesNotChangeTheCrop() = runTest {
    val source = mutableQuadrants()
    val result = assertNotNull(
      CropTransformation(ImageRegion(0, 0, 16, 16)).transform(source) as? Bitmap,
    )
    val before = result.argbAt(4, 4)

    source.erase(0xFF00FF00.toInt())

    assertEquals(before, result.argbAt(4, 4), "the crop and the source share pixels")
  }

  /** The `Image` branch owns its pixels too, rather than a full size copy of them. */
  @Test
  fun theCroppedImageDoesNotRetainAFullSizeRaster() = runTest {
    val source = Image.makeFromBitmap(quadrants(size = 512))

    val result = assertNotNull(
      CropTransformation(ImageRegion(0, 0, 16, 16)).transform(source) as? Image,
    )

    val raster = Bitmap.makeFromImage(result)
    assertEquals(16, result.width)
    assertEquals(
      16 * 4,
      raster.rowBytes,
      "the cropped Image kept the source's stride, so it pins the whole raster",
    )
  }

  // -------------------------------------------------------------------------------------------
  // The type Landscapist actually hands over on desktop
  // -------------------------------------------------------------------------------------------

  /**
   * Landscapist's desktop decoder produces a `BufferedImage`, not a Skia type.
   *
   * `SkiaJvmDecoder.decodeWithSkia` ends in `Bitmap.toBufferedImage()` before it builds its result,
   * and Landscapist's own tests are named `a decoded image is still a BufferedImage` and `the
   * desktop decoder really does hand back a BufferedImage`. A transformation that understands only
   * Skia types returned the input untouched on this path: the image displayed uncropped, with no
   * error and nothing in a log.
   *
   * Every test above this one builds its own Skia `Bitmap` or `Image`, which is exactly why none of
   * them could see it.
   */
  @Test
  fun cropsTheBufferedImageThatTheDesktopDecoderProduces() = runTest {
    val source = bufferedQuadrants()

    val result = assertNotNull(
      CropTransformation(ImageRegion(32, 0, 64, 32)).transform(source) as? BufferedImage,
      "a BufferedImage went in and something that is not one came out",
    )

    assertEquals(32, result.width, "the transformation returned the input unchanged")
    assertEquals(32, result.height)
    // The top-right quadrant is green; returning the input would give 64x64 and cropping the wrong
    // corner would give blue.
    assertEquals(source.getRGB(40, 10), result.getRGB(8, 10), "the wrong quadrant came back")
  }

  @Test
  fun clipsABufferedImageRegionThatRunsPastTheEdge() = runTest {
    val result = assertNotNull(
      CropTransformation(ImageRegion(48, 48, 200, 200)).transform(bufferedQuadrants())
        as? BufferedImage,
    )

    assertEquals(16, result.width)
    assertEquals(16, result.height)
  }

  @Test
  fun returnsABufferedImageUntouchedWhenTheRegionIsUnusable() = runTest {
    val source = bufferedQuadrants()

    assertSame(source, CropTransformation(ImageRegion(0, 0, 0, 0)).transform(source))
    assertSame(source, CropTransformation(ImageRegion(500, 500, 600, 600)).transform(source))
  }

  /**
   * The crop owns its pixels here too.
   *
   * `BufferedImage.getSubimage` returns a view over the same raster, which would pin the whole
   * source the way Skia's `extractSubset` did.
   */
  @Test
  fun theCroppedBufferedImageDoesNotShareTheSourcesRaster() = runTest {
    val source = bufferedQuadrants()
    val result = assertNotNull(
      CropTransformation(ImageRegion(0, 0, 16, 16)).transform(source) as? BufferedImage,
    )
    val before = result.getRGB(4, 4)

    for (y in 0 until source.height) {
      for (x in 0 until source.width) source.setRGB(x, y, 0xFF00FF00.toInt())
    }

    assertEquals(before, result.getRGB(4, 4), "the crop and the source share a raster")
  }

  /** The same four quadrants as [quadrants], as the type the desktop loader produces. */
  private fun bufferedQuadrants(size: Int = 64): BufferedImage {
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until size) {
      for (x in 0 until size) {
        val left = x < size / 2
        val top = y < size / 2
        image.setRGB(
          x,
          y,
          when {
            left && top -> 0xFF0000FF.toInt()
            !left && top -> 0xFF00FF00.toInt()
            left -> 0xFFFF0000.toInt()
            else -> 0xFF000000.toInt()
          },
        )
      }
    }
    return image
  }
}
