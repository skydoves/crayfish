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
package com.github.skydoves.crayfish.decode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The seam that turns a pixel array back into a platform image.
 *
 * Every Exif reorientation goes through it, so it is on the path of every rotated photograph. Its
 * guards are all about sizes that do not agree with each other (a dimension of zero, an array
 * shorter than the rectangle claims) and getting one wrong is a native buffer overrun rather than
 * an exception. The channel-order assertion is the other half: [PlatformImage.readArgbPixels] is
 * the exact inverse, and if the two ever disagree red and blue swap, which looks plausible enough
 * in a screenshot to ship.
 */
class PlatformImageFactorySkiaTest {

  @Test
  fun roundTripsPixelsThroughTheExactInverse() {
    val pixels = intArrayOf(
      0xFFFF0000.toInt(),
      0xFF00FF00.toInt(),
      0xFF0000FF.toInt(),
      0x80FFFF00.toInt(),
    )

    val image = assertNotNull(platformImageOfArgbPixels(pixels, ImageSize(2, 2)))

    image.use {
      assertEquals(2, it.width)
      assertEquals(2, it.height)
      val read = assertNotNull(it.readArgbPixels(), "the image it just built cannot be read")
      assertEquals(
        pixels.map { pixel -> pixel.toUInt().toString(16) },
        read.map { pixel -> pixel.toUInt().toString(16) },
        "the pixels changed on the way through; red and blue are the usual pair",
      )
    }
  }

  /** A dimension of zero has no rectangle; the allocation below it would be zero bytes. */
  @Test
  fun refusesADimensionOfZero() {
    assertNull(platformImageOfArgbPixels(IntArray(0), ImageSize(0, 4)))
    assertNull(platformImageOfArgbPixels(IntArray(0), ImageSize(4, 0)))
    assertNull(platformImageOfArgbPixels(IntArray(0), ImageSize.Zero))
  }

  /**
   * A negative dimension, which is what an arithmetic slip upstream looks like.
   *
   * Worth its own case because `width * height` is *positive* when both are negative, so a check
   * written against the product alone would let it through and then compute a byte count for a
   * rectangle that does not exist.
   */
  @Test
  fun refusesANegativeDimension() {
    assertNull(platformImageOfArgbPixels(IntArray(16), ImageSize(-4, 4)))
    assertNull(platformImageOfArgbPixels(IntArray(16), ImageSize(4, -4)))
    assertNull(platformImageOfArgbPixels(IntArray(16), ImageSize(-4, -4)))
  }

  /**
   * An array shorter than the size claims is refused rather than read past.
   *
   * This is the one that matters: the loop indexes `pixels[index]` for every pixel the *size*
   * names, and the bytes it fills are handed to Skia as a buffer of that length. Without the guard
   * the failure is an out-of-bounds read at best and a native buffer of uninitialised memory at
   * worst.
   */
  @Test
  fun refusesAPixelArrayTooShortForTheSizeItIsGiven() {
    assertNull(platformImageOfArgbPixels(IntArray(15), ImageSize(4, 4)))
    assertNull(platformImageOfArgbPixels(IntArray(0), ImageSize(1, 1)))
  }

  /** A longer array is fine: only the first `width * height` entries are the image. */
  @Test
  fun acceptsAPixelArrayLongerThanTheSize() {
    val pixels = IntArray(100) { 0xFF123456.toInt() }

    val image = assertNotNull(platformImageOfArgbPixels(pixels, ImageSize(2, 2)))

    image.use {
      assertEquals(4, assertNotNull(it.readArgbPixels()).size)
    }
  }

  /**
   * The image it produces is closed exactly once and answers `null` afterwards.
   *
   * Reading a closed Skia bitmap aborts the process rather than throwing, so `isClosed` and the
   * `null` from `readArgbPixels` are the whole protocol by which anything downstream can tell.
   */
  @Test
  fun theImageItBuildsReportsItsOwnClosure() {
    val image = assertNotNull(platformImageOfArgbPixels(IntArray(4), ImageSize(2, 2)))

    assertTrue(!image.isClosed)
    assertNotNull(image.readArgbPixels())

    image.close()

    assertTrue(image.isClosed)
    assertNull(image.readArgbPixels(), "a closed image handed its pixels out anyway")
    // Dimensions survive the close, which is what lets a failure be reported about a real image.
    assertEquals(2, image.width)
    assertEquals(2, image.height)

    // Closing twice is documented as safe, and is what a `finally` around a `use` produces.
    image.close()
    assertTrue(image.isClosed)
  }
}
