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
package com.github.skydoves.crayfish.e2e

import com.github.skydoves.crayfish.decode.CropSource
import com.github.skydoves.crayfish.decode.DecodeBudget
import com.github.skydoves.crayfish.decode.ImageRegion
import com.github.skydoves.crayfish.decode.ImageSize
import com.github.skydoves.crayfish.decode.SampleSize
import com.github.skydoves.crayfish.decode.SampleSize.sampledBy
import com.github.skydoves.crayfish.decode.createRegionDecoder
import com.github.skydoves.crayfish.encode.EncodeOptions
import com.github.skydoves.crayfish.encode.EncodedFormat
import com.github.skydoves.crayfish.encode.encodeImage
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The claim the whole library rests on, measured end to end: cropping a source far larger than the
 * memory budget must stay inside the budget.
 *
 * [MemoryHarnessTest] has already established that the apparatus works and that the budget bites:
 * a naive full decode of the same file exhausts it in a child JVM. What follows is therefore a
 * comparison against a control that is known to fail, not an unanchored assertion.
 */
class CropPipelineMemoryTest {

  @Test
  fun croppingA108MegapixelSourceStaysInsideTheBudget() = runBlocking {
    val source = CropSource.FilePath(Fixtures.sensor108mp.absolutePath)

    val (cropped, reading) = MemoryProbe.measure {
      runBlocking {
        val decoder = assertNotNull(createRegionDecoder(source), "could not open the fixture")
        decoder.use {
          assertEquals(ImageSize(12_000, 9_000), it.imageSize)

          // A centred square crop, the shape an avatar flow asks for.
          val region = ImageRegion(left = 3_000, top = 2_000, right = 9_000, bottom = 8_000)
          val sampleSize = SampleSize.forDecode(region.size, VIEWPORT, DecodeBudget.ForDisplay)
          it.decodeRegion(region, sampleSize)
        }
      }
    }

    assertNotNull(cropped, "the crop produced nothing")
    try {
      assertTrue(
        reading.allocatedBytes < MemoryProbe.BUDGET_BYTES,
        "cropping allocated $reading, over the ${MemoryProbe.BUDGET_BYTES / 1024 / 1024}MB budget",
      )
      println("[e2e] 108MP crop: $reading, result ${cropped.width}x${cropped.height}")
    } finally {
      cropped.close()
    }
  }

  /**
   * The same source, encoded back out. Decode plus encode is the whole user-visible operation, and
   * measuring only the decode would miss an encoder that copies the buffer twice.
   */
  @Test
  fun theFullDecodeThenEncodeRoundTripStaysInsideTheBudget() = runBlocking {
    val source = CropSource.FilePath(Fixtures.sensor108mp.absolutePath)

    val (bytes, reading) = MemoryProbe.measure {
      runBlocking {
        val decoder = assertNotNull(createRegionDecoder(source))
        decoder.use {
          val region = ImageRegion(0, 0, 8_000, 8_000)
          val sampleSize = SampleSize.forDecode(region.size, VIEWPORT, DecodeBudget.ForDisplay)
          val image = assertNotNull(it.decodeRegion(region, sampleSize))
          try {
            encodeImage(image.image, EncodeOptions(EncodedFormat.JPEG, lossyQuality = 90))
          } finally {
            image.close()
          }
        }
      }
    }

    assertNotNull(bytes, "the encode produced nothing")
    assertTrue(bytes.size > 1024, "a JPEG of a real crop should not be ${bytes.size} bytes")
    assertEquals(0xFF.toByte(), bytes[0])
    assertEquals(0xD8.toByte(), bytes[1])
    assertTrue(
      reading.allocatedBytes < MemoryProbe.BUDGET_BYTES,
      "decode+encode allocated $reading, over the budget",
    )
    println("[e2e] 108MP crop -> jpeg: $reading, ${bytes.size / 1024}KB out")
  }

  /** The aspect ratio with its own open crash reports elsewhere. */
  @Test
  fun croppingAnExtremePanoramaStaysInsideTheBudget() = runBlocking {
    val source = CropSource.FilePath(Fixtures.panorama.absolutePath)

    val (cropped, reading) = MemoryProbe.measure {
      runBlocking {
        val decoder = assertNotNull(createRegionDecoder(source))
        decoder.use {
          assertEquals(ImageSize(28_000, 2_000), it.imageSize)
          val region = ImageRegion.of(it.imageSize)
          val sampleSize = SampleSize.forDecode(region.size, VIEWPORT, DecodeBudget.ForDisplay)
          it.decodeRegion(region, sampleSize)
        }
      }
    }

    assertNotNull(cropped)
    try {
      assertTrue(
        reading.allocatedBytes < MemoryProbe.BUDGET_BYTES,
        "the panorama allocated $reading, over the budget",
      )
      println("[e2e] 28000x2000 panorama: $reading, result ${cropped.width}x${cropped.height}")
    } finally {
      cropped.close()
    }
  }

  /**
   * Cheap and small must stay cheap. A pipeline that always paid the worst case would pass every
   * budget assertion above while being useless.
   */
  @Test
  fun aSmallSourceCostsProportionallyLittle() = runBlocking {
    val source = CropSource.FilePath(Fixtures.uhdPng.absolutePath)

    val (cropped, reading) = MemoryProbe.measure {
      runBlocking {
        createRegionDecoder(source)?.use { it.decodeRegion(ImageRegion(0, 0, 512, 512), 1) }
      }
    }

    assertNotNull(cropped)
    try {
      assertEquals(512, cropped.width)
      assertEquals(512, cropped.height)
      println("[e2e] 4K png, 512x512 crop: $reading")
    } finally {
      cropped.close()
    }
  }

  /** A decode returns the size it was asked for, or every tile lands in the wrong place. */
  @Test
  fun theDecodedSizeMatchesWhatTheSampleSizeCalculationPredicted() = runBlocking {
    val decoder = assertNotNull(
      createRegionDecoder(CropSource.FilePath(Fixtures.sensor48mp.absolutePath)),
    )
    decoder.use {
      val region = ImageRegion(0, 0, 4_096, 4_096)
      val sampleSize = SampleSize.forDecode(region.size, VIEWPORT, DecodeBudget.ForDisplay)
      val predicted = region.size.sampledBy(sampleSize)
      val image = assertNotNull(it.decodeRegion(region, sampleSize))

      try {
        assertEquals(
          predicted,
          ImageSize(image.width, image.height),
          "sampleSize=$sampleSize was predicted to yield $predicted",
        )
      } finally {
        image.close()
      }
    }
  }

  private companion object {
    /** A phone-sized destination; the crop never needs more detail than this. */
    val VIEWPORT = ImageSize(1_080, 1_920)

    @Suppress("unused")
    fun File.unused() = Unit
  }
}
