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
import com.github.skydoves.crayfish.decode.PlatformImage
import com.github.skydoves.crayfish.decode.platformOriented
import com.github.skydoves.crayfish.exif.ImageOrientation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The platform's transform and the Kotlin one produce the same pixels, for all eight orientations.
 *
 * `ImageOrientation.applyTo` is the oracle. It is pure Kotlin, covered directly by
 * `ImageOrientationTest` including a check that every orientation preserves every pixel, and it is
 * frozen: this test does not adjust it, it compares against it.
 *
 * The four mirrored values are why this exists. `TRANSPOSE` and `TRANSVERSE` in particular come out
 * upright and back to front if the matrix mirrors before it rotates instead of after, and an
 * upright back-to-front photo is exactly the Exif failure that survives review.
 *
 * On device rather than on the desktop, because the platform path is Android's and the Skia targets
 * deliberately return `null` and fall back to the oracle itself, which would make the comparison
 * compare the oracle with itself.
 */
class DeviceOrientedPixelsTest {

  /** Deliberately not square, so a transposed result cannot pass by having the same shape. */
  private val width = 7
  private val height = 5

  /** Every pixel distinct, so a wrong permutation cannot land on an equal value by accident. */
  private fun source(): IntArray = IntArray(width * height) { index ->
    0xFF000000.toInt() or (index * 4099)
  }

  @Test
  fun everyOrientationMatchesTheKotlinOracle() {
    ImageOrientation.entries.forEach { orientation ->
      val pixels = source()
      val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

      val turned = PlatformImage(bitmap).platformOriented(orientation)
      if (orientation == ImageOrientation.NORMAL) {
        // Nothing to do, and the contract is to say so rather than to copy.
        assertEquals(null, turned, "NORMAL should not allocate a copy")
        bitmap.recycle()
        return@forEach
      }

      val platform = assertNotNull(turned, "$orientation produced no platform result")
      val expectedSize = orientation.transformSize(
        com.github.skydoves.crayfish.decode.ImageSize(width, height),
      )
      assertEquals(expectedSize.width, platform.width, "$orientation width")
      assertEquals(expectedSize.height, platform.height, "$orientation height")

      val actual = IntArray(platform.width * platform.height)
      platform.bitmap.getPixels(
        actual,
        0,
        platform.width,
        0,
        0,
        platform.width,
        platform.height,
      )

      val expected = orientation.applyTo(
        pixels,
        com.github.skydoves.crayfish.decode.ImageSize(width, height),
      )

      val firstMismatch = actual.indices.firstOrNull { actual[it] != expected[it] }
      assertTrue(
        firstMismatch == null,
        "$orientation disagrees with ImageOrientation.applyTo at index $firstMismatch: " +
          "platform produced ${firstMismatch?.let { actual[it] }}, the oracle says " +
          "${firstMismatch?.let { expected[it] }}",
      )

      platform.close()
      bitmap.recycle()
    }
  }
}
