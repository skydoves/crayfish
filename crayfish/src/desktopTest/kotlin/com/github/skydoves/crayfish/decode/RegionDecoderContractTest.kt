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

import com.github.skydoves.crayfish.decode.SampleSize.sampledBy
import com.github.skydoves.crayfish.exif.ImageOrientation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The parts of [RegionDecoder]'s contract that exist because two platform implementations found
 * them missing, rather than because a design document predicted them.
 *
 * Both gaps have the same shape: the decoder knew something the caller needed and had nowhere to
 * put it. A caller placing tiles from the values it *passed in* would put them in the wrong place,
 * and would have no way to discover that from the returned bitmap.
 */
class RegionDecoderContractTest {

  private suspend fun openTestImage(): RegionDecoder =
    assertNotNull(createRegionDecoder(TestImages.source(TestImages.pngBytes())))

  @Test
  fun `reports the region it actually decoded, not the one it was asked for`() = runTest {
    openTestImage().use { decoder ->
      // Deliberately overhanging: a crop frame dragged to the border produces exactly this.
      val requested = ImageRegion(left = 40, top = 40, right = 500, bottom = 500)
      val decoded = assertNotNull(decoder.decodeRegion(requested, sampleSize = 1))

      decoded.use {
        val expected = assertNotNull(requested.intersect(ImageRegion.of(decoder.imageSize)))
        assertEquals(expected, it.region)
        assertEquals(expected.width, it.width)
        assertEquals(expected.height, it.height)
      }
    }
  }

  /**
   * A non-power-of-two request is rounded down, and the value reported back is the rounded one.
   *
   * This is the reporting channel the OOM retry path needs, exercised without having to provoke a
   * real `OutOfMemoryError`: both are cases where what happened differs from what was asked.
   */
  @Test
  fun `reports the sample size it actually used, not the one it was asked for`() = runTest {
    openTestImage().use { decoder ->
      val region = ImageRegion(0, 0, 64, 64)
      val decoded = assertNotNull(decoder.decodeRegion(region, sampleSize = 3))

      decoded.use {
        assertEquals(2, it.sampleSize, "3 should round down to 2")
        // `it.region`, not the rectangle passed in: the source is shorter than 64px, so the
        // request was clipped. Computing the expectation from the request is the very mistake the
        // reported region exists to prevent.
        assertEquals(
          it.region.size.sampledBy(it.sampleSize),
          ImageSize(it.width, it.height),
          "the pixels must match the sample size reported, or tiles land in the wrong place",
        )
      }
    }
  }

  @Test
  fun `a power-of-two request is reported unchanged`() = runTest {
    openTestImage().use { decoder ->
      listOf(1, 2, 4).forEach { requested ->
        val decoded = assertNotNull(decoder.decodeRegion(ImageRegion(0, 0, 64, 64), requested))
        decoded.use { assertEquals(requested, it.sampleSize) }
      }
    }
  }

  /**
   * Desktop leaves the Exif tag alone, so the pixels stay in the same coordinate space as
   * `imageSize`. Saying so is what lets the layer above apply the orientation exactly once; the
   * web decoder is the one that cannot always make this promise.
   */
  @Test
  fun `declares that it applied no orientation`() = runTest {
    openTestImage().use { decoder ->
      assertEquals(ImageOrientation.NORMAL, decoder.appliedOrientation)
    }
  }

  @Test
  fun `closing a decoded region releases its image`() = runTest {
    openTestImage().use { decoder ->
      val decoded = assertNotNull(decoder.decodeRegion(ImageRegion(0, 0, 8, 8), sampleSize = 1))

      assertNotNull(decoded.image.readArgbPixels())
      decoded.close()
      // Reading a released bitmap must be a value, not a crash: a tile loop closes as it goes.
      assertEquals(null, decoded.image.readArgbPixels())
    }
  }
}
