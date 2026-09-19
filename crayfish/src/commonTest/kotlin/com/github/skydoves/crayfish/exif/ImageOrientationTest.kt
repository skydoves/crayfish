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
package com.github.skydoves.crayfish.exif

import com.github.skydoves.crayfish.decode.ImageSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ImageOrientationTest {

  /**
   * A 3x2 grid of distinct, asymmetric labels. Asymmetry is the whole point: on a symmetric
   * fixture a mirror is indistinguishable from the identity, which is exactly how mirrored
   * orientations get shipped broken.
   *
   *     A B C
   *     D E F
   */
  private val size = ImageSize(width = 3, height = 2)
  private val source = intArrayOf(
    'A'.code,
    'B'.code,
    'C'.code,
    'D'.code,
    'E'.code,
    'F'.code,
  )

  private fun assertOriented(
    orientation: ImageOrientation,
    expectedWidth: Int,
    vararg expectedRows: String,
  ) {
    val expected = expectedRows.joinToString("\n")
    val actual = orientation.applyTo(source, size).render(expectedWidth)

    assertEquals(
      expected,
      actual,
      "${orientation.name} (exif ${orientation.exifValue})\nexpected:\n$expected\nactual:\n$actual",
    )
  }

  private fun IntArray.render(width: Int): String =
    toList().chunked(width).joinToString("\n") { row ->
      row.joinToString(" ") { it.toChar().toString() }
    }

  @Test
  fun normalIsTheIdentity() {
    assertOriented(
      ImageOrientation.NORMAL,
      expectedWidth = 3,
      "A B C",
      "D E F",
    )
  }

  @Test
  fun flipHorizontalMirrorsColumns() {
    assertOriented(
      ImageOrientation.FLIP_HORIZONTAL,
      expectedWidth = 3,
      "C B A",
      "F E D",
    )
  }

  @Test
  fun rotate180TurnsTheGridUpsideDown() {
    assertOriented(
      ImageOrientation.ROTATE_180,
      expectedWidth = 3,
      "F E D",
      "C B A",
    )
  }

  @Test
  fun flipVerticalMirrorsRows() {
    assertOriented(
      ImageOrientation.FLIP_VERTICAL,
      expectedWidth = 3,
      "D E F",
      "A B C",
    )
  }

  @Test
  fun rotate90TurnsClockwise() {
    assertOriented(
      ImageOrientation.ROTATE_90,
      expectedWidth = 2,
      "D A",
      "E B",
      "F C",
    )
  }

  @Test
  fun rotate270TurnsAnticlockwise() {
    assertOriented(
      ImageOrientation.ROTATE_270,
      expectedWidth = 2,
      "C F",
      "B E",
      "A D",
    )
  }

  /** Rotating without mirroring yields [ROTATE_90] instead. */
  @Test
  fun transposeReflectsAcrossTheMainDiagonal() {
    assertOriented(
      ImageOrientation.TRANSPOSE,
      expectedWidth = 2,
      "A D",
      "B E",
      "C F",
    )
  }

  @Test
  fun transverseReflectsAcrossTheAntiDiagonal() {
    assertOriented(
      ImageOrientation.TRANSVERSE,
      expectedWidth = 2,
      "F C",
      "E B",
      "D A",
    )
  }

  /**
   * The regression that matters. An implementation that reads [ImageOrientation.rotationDegrees]
   * and ignores [ImageOrientation.isMirrored] passes every rotation test above and produces a
   * back-to-front image for half of the eight values. Each mirrored orientation must differ from
   * the plain rotation it shares an angle with.
   */
  @Test
  fun everyMirroredOrientationDiffersFromItsRotationOnlyTwin() {
    val pairs = listOf(
      ImageOrientation.FLIP_HORIZONTAL to ImageOrientation.NORMAL,
      ImageOrientation.FLIP_VERTICAL to ImageOrientation.ROTATE_180,
      ImageOrientation.TRANSPOSE to ImageOrientation.ROTATE_90,
      ImageOrientation.TRANSVERSE to ImageOrientation.ROTATE_270,
    )

    pairs.forEach { (mirrored, rotationOnly) ->
      assertEquals(
        mirrored.rotationDegrees,
        rotationOnly.rotationDegrees,
        "${mirrored.name} and ${rotationOnly.name} should share an angle",
      )
      assertTrue(mirrored.isMirrored, "${mirrored.name} must be mirrored")

      assertNotEquals(
        rotationOnly.applyTo(source, size).toList(),
        mirrored.applyTo(source, size).toList(),
        "${mirrored.name} must not render identically to ${rotationOnly.name}",
      )
    }
  }

  @Test
  fun onlyQuarterTurnsSwapTheDimensions() {
    val portrait = ImageSize(width = 3000, height = 4000)

    listOf(
      ImageOrientation.ROTATE_90,
      ImageOrientation.ROTATE_270,
      ImageOrientation.TRANSPOSE,
      ImageOrientation.TRANSVERSE,
    )
      .forEach {
        assertTrue(it.transposesDimensions, it.name)
        assertEquals(ImageSize(4000, 3000), it.transformSize(portrait), it.name)
      }

    listOf(
      ImageOrientation.NORMAL,
      ImageOrientation.FLIP_HORIZONTAL,
      ImageOrientation.ROTATE_180,
      ImageOrientation.FLIP_VERTICAL,
    )
      .forEach {
        assertTrue(!it.transposesDimensions, it.name)
        assertEquals(portrait, it.transformSize(portrait), it.name)
      }
  }

  @Test
  fun mapsEveryDefinedExifValue() {
    (1..8).forEach { value ->
      assertEquals(value, ImageOrientation.fromExifValue(value).exifValue)
    }
  }

  /**
   * An unreadable tag must mean "already upright". Any other default rotates images that were
   * correct to begin with, which is a worse failure than leaving a rotated one alone.
   */
  @Test
  fun treatsUndefinedExifValuesAsNormal() {
    listOf(0, 9, -1, 255, Int.MAX_VALUE, Int.MIN_VALUE).forEach { value ->
      assertEquals(ImageOrientation.NORMAL, ImageOrientation.fromExifValue(value), "value $value")
    }
  }

  @Test
  fun everyOrientationPreservesEveryPixel() {
    ImageOrientation.entries.forEach { orientation ->
      val result = orientation.applyTo(source, size)

      assertEquals(source.size, result.size, orientation.name)
      assertEquals(
        source.toList().sorted(),
        result.toList().sorted(),
        "${orientation.name} must permute the pixels, not lose or duplicate any",
      )
    }
  }
}
