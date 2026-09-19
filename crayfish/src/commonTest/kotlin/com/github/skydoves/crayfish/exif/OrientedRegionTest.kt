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

import com.github.skydoves.crayfish.decode.ImageRegion
import com.github.skydoves.crayfish.decode.ImageSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OrientedRegionTest {

  /**
   * The same 3x2 labelled grid `ImageOrientationTest` pins the pixel convention with.
   *
   *     A B C
   *     D E F
   *
   * Asymmetric in both axes and in its labels, so that no mirror can pass for a rotation and no
   * transposition can pass for a turn. A square of one colour would satisfy every assertion below
   * for every one of the eight values.
   */
  private val rawSize = ImageSize(width = 3, height = 2)
  private val rawPixels = intArrayOf(
    'A'.code,
    'B'.code,
    'C'.code,
    'D'.code,
    'E'.code,
    'F'.code,
  )

  /** A non-square source with no symmetry left to hide behind, for the absolute fixtures. */
  private val sensorSize = ImageSize(width = 100, height = 40)

  /**
   * One rectangle, asymmetric about both centre lines of [sensorSize] in either reading.
   *
   * `left != width - right` and `top != height - bottom` in both the 100x40 and the 40x100 frame,
   * which is what makes every one of the eight expected answers below a different rectangle.
   */
  private val orientedCrop = ImageRegion(left = 5, top = 10, right = 15, bottom = 25)

  // -------------------------------------------------------------------------------------------
  // The round trip. Necessary, and nowhere near sufficient: see the labelled grid below.
  // -------------------------------------------------------------------------------------------

  /**
   * Mapping a rectangle down to the raw grid and back returns it unchanged, for all eight values.
   *
   * This is the cheap property, and it is worth knowing exactly how little it proves: it holds for
   * *any* pair of mutually inverse maps, whether or not either of them agrees with the pixels.
   * Measured, not assumed: dropping the mirror from the mapping leaves
   * [ImageOrientation.FLIP_HORIZONTAL] and [ImageOrientation.FLIP_VERTICAL] round-tripping happily
   * here, because a value whose angle is 0 or 180 stays its own inverse with or without the mirror.
   * Half the mirrored values walk straight through this test, and the labelled grid below is what
   * stops them.
   */
  @Test
  fun everyOrientationMapsARegionDownAndBackUnchanged() {
    ImageOrientation.entries.forEach { orientation ->
      val orientedSize = orientation.transformSize(sensorSize)
      regionsWithin(orientedSize).forEach { region ->
        val raw = orientation.toRawRegion(region, sensorSize)
        assertEquals(
          region,
          orientation.toOrientedRegion(raw, sensorSize),
          "${orientation.name}: $region mapped to raw $raw and did not come back",
        )
      }
    }
  }

  /** The other direction: a rectangle of the file's own grid survives the trip up and back. */
  @Test
  fun everyOrientationMapsARawRegionUpAndBackUnchanged() {
    ImageOrientation.entries.forEach { orientation ->
      regionsWithin(sensorSize).forEach { region ->
        val oriented = orientation.toOrientedRegion(region, sensorSize)
        assertEquals(
          region,
          orientation.toRawRegion(oriented, sensorSize),
          "${orientation.name}: $region mapped to oriented $oriented and did not come back",
        )
      }
    }
  }

  /** A rectangle inside the image maps to one inside the image; the whole image maps to itself. */
  @Test
  fun theWholeImageMapsToTheWholeFile() {
    ImageOrientation.entries.forEach { orientation ->
      val orientedSize = orientation.transformSize(sensorSize)
      assertEquals(
        ImageRegion.of(sensorSize),
        orientation.toRawRegion(ImageRegion.of(orientedSize), sensorSize),
        orientation.name,
      )

      regionsWithin(orientedSize).forEach { region ->
        val raw = orientation.toRawRegion(region, sensorSize)
        assertTrue(
          raw.left >= 0 && raw.top >= 0 &&
            raw.right <= sensorSize.width && raw.bottom <= sensorSize.height,
          "${orientation.name}: $region left the file's grid as $raw",
        )
        assertEquals(
          if (orientation.transposesDimensions) region.size.transposed() else region.size,
          raw.size,
          "${orientation.name}: $region changed size on the way to $raw",
        )
      }
    }
  }

  // -------------------------------------------------------------------------------------------
  // The labelled grid: the region mapping checked against the pixel mapping it has to agree with.
  // -------------------------------------------------------------------------------------------

  /**
   * The property the crop pipeline actually depends on.
   *
   * For every orientation and every sub-rectangle of the oriented image: decoding the raw region
   * this maps to, and then applying the orientation to those pixels, must produce exactly the
   * pixels of the oriented image inside the rectangle the user drew: same labels, same
   * arrangement, same size.
   *
   * It is checked against [ImageOrientation.applyTo], which is the library's reference for the
   * convention, rather than against a second copy of the arithmetic written here. A test that
   * restates the code it checks agrees with itself whether or not either is right.
   *
   * This is the assertion a dropped mirror cannot survive: the rectangle lands on the wrong half of
   * the file and the labels come back as a different word.
   */
  @Test
  fun aDecodedRawRegionReorientsIntoExactlyTheFramedPixels() {
    ImageOrientation.entries.forEach { orientation ->
      val orientedSize = orientation.transformSize(rawSize)
      val orientedPixels = orientation.applyTo(rawPixels, rawSize)

      regionsWithin(orientedSize).forEach { framed ->
        val raw = orientation.toRawRegion(framed, rawSize)

        val decoded = rawPixels.crop(rawSize, raw)
        val reoriented = orientation.applyTo(decoded, raw.size)
        val expected = orientedPixels.crop(orientedSize, framed)

        assertEquals(
          expected.render(framed.width),
          reoriented.render(framed.width),
          "${orientation.name}: framing $framed of the oriented image decoded $raw, which " +
            "reorients into the wrong pixels",
        )
      }
    }
  }

  /**
   * The mirror canary, at the level of rectangles.
   *
   * Each mirrored orientation shares its angle with a rotation-only twin. An implementation that
   * reads the angle and forgets [ImageOrientation.isMirrored] maps both to the same rectangle, and
   * then produces a crop that is upright and back-to-front, the Exif bug that a suite of rotation
   * tests cannot see, here made impossible to ship.
   */
  @Test
  fun everyMirroredOrientationMapsElsewhereThanItsRotationOnlyTwin() {
    val twins = listOf(
      ImageOrientation.FLIP_HORIZONTAL to ImageOrientation.NORMAL,
      ImageOrientation.FLIP_VERTICAL to ImageOrientation.ROTATE_180,
      ImageOrientation.TRANSPOSE to ImageOrientation.ROTATE_90,
      ImageOrientation.TRANSVERSE to ImageOrientation.ROTATE_270,
    )

    twins.forEach { (mirrored, rotationOnly) ->
      assertEquals(
        mirrored.rotationDegrees,
        rotationOnly.rotationDegrees,
        "${mirrored.name} and ${rotationOnly.name} should share an angle",
      )
      assertNotEquals(
        rotationOnly.toRawRegion(orientedCrop, sensorSize),
        mirrored.toRawRegion(orientedCrop, sensorSize),
        "${mirrored.name} must not map $orientedCrop where ${rotationOnly.name} maps it",
      )
    }
  }

  // -------------------------------------------------------------------------------------------
  // Absolute fixtures. Hand-computed, because a self-consistent property pins no convention.
  // -------------------------------------------------------------------------------------------

  /**
   * Every one of the eight, on a 100x40 source, with the answer worked out by hand.
   *
   * Written against the convention [ImageOrientation] documents (rotate clockwise, then mirror),
   * so that the *inverse* of it is pinned here as an absolute value rather than as a relationship.
   * `CropTransform`'s pivot, in this same repository, stayed green through a change of convention
   * because only self-consistent properties covered it; these are the fixtures that would not have.
   */
  @Test
  fun mapsTheKnownAsymmetricRectangleOntoTheHandComputedRawRectangle() {
    val expected = mapOf(
      // (u, v) -> (u, v).
      ImageOrientation.NORMAL to ImageRegion(5, 10, 15, 25),
      // (u, v) -> (100 - u, v): mirrored, never turned.
      ImageOrientation.FLIP_HORIZONTAL to ImageRegion(85, 10, 95, 25),
      // (u, v) -> (100 - u, 40 - v).
      ImageOrientation.ROTATE_180 to ImageRegion(85, 15, 95, 30),
      // (u, v) -> (u, 40 - v): a vertical flip, which is rotate-180-then-mirror.
      ImageOrientation.FLIP_VERTICAL to ImageRegion(5, 15, 15, 30),
      // (u, v) -> (v, 40 - u): undoing a clockwise quarter turn of a 100x40 file.
      ImageOrientation.ROTATE_90 to ImageRegion(10, 25, 25, 35),
      // (u, v) -> (100 - v, u).
      ImageOrientation.ROTATE_270 to ImageRegion(75, 5, 90, 15),
      // (u, v) -> (v, u): reflection in the main diagonal, not a quarter turn.
      ImageOrientation.TRANSPOSE to ImageRegion(10, 5, 25, 15),
      // (u, v) -> (100 - v, 40 - u): reflection in the anti-diagonal.
      ImageOrientation.TRANSVERSE to ImageRegion(75, 25, 90, 35),
    )

    assertEquals(
      ImageOrientation.entries.size,
      expected.size,
      "every orientation needs a hand-computed fixture",
    )
    expected.forEach { (orientation, raw) ->
      assertEquals(
        raw,
        orientation.toRawRegion(orientedCrop, sensorSize),
        "${orientation.name} (exif ${orientation.exifValue})",
      )
    }
  }

  /**
   * The inverse table itself.
   *
   * Only the two quarter turns exchange. Every mirrored value is a reflection and so its own
   * inverse, the line where "negate the angle" would get [ImageOrientation.TRANSPOSE] and
   * [ImageOrientation.TRANSVERSE] the wrong way round.
   */
  @Test
  fun onlyTheQuarterTurnsHaveAnInverseOtherThanThemselves() {
    assertEquals(ImageOrientation.ROTATE_270, ImageOrientation.ROTATE_90.inverse)
    assertEquals(ImageOrientation.ROTATE_90, ImageOrientation.ROTATE_270.inverse)

    listOf(
      ImageOrientation.NORMAL,
      ImageOrientation.ROTATE_180,
      ImageOrientation.FLIP_HORIZONTAL,
      ImageOrientation.FLIP_VERTICAL,
      ImageOrientation.TRANSPOSE,
      ImageOrientation.TRANSVERSE,
    ).forEach { assertEquals(it, it.inverse, "${it.name} should be its own inverse") }

    ImageOrientation.entries.forEach {
      assertEquals(it, it.inverse.inverse, "${it.name} twice inverted")
    }
  }

  /** A degenerate rectangle stays degenerate rather than turning into pixels out of nowhere. */
  @Test
  fun anEmptyRectangleMapsToAnEmptyRectangle() {
    ImageOrientation.entries.forEach { orientation ->
      val empty = ImageRegion(left = 12, top = 7, right = 12, bottom = 7)
      assertTrue(orientation.toRawRegion(empty, sensorSize).isEmpty, orientation.name)
    }
  }

  /** A handful of asymmetric rectangles inside [size], including ones touching each edge. */
  private fun regionsWithin(size: ImageSize): List<ImageRegion> {
    val width = size.width
    val height = size.height
    return listOf(
      ImageRegion(0, 0, width, height),
      ImageRegion(0, 0, 1, 1),
      ImageRegion(width - 1, height - 1, width, height),
      ImageRegion(0, height - 1, 1, height),
      ImageRegion(width - 1, 0, width, 1),
      ImageRegion(width / 3, height / 4, width - width / 4, height - height / 3),
    ).filterNot { it.isEmpty }
  }

  /** The pixels of [region], read out of a [size]-shaped buffer. */
  private fun IntArray.crop(size: ImageSize, region: ImageRegion): IntArray {
    val out = IntArray(region.width * region.height)
    for (y in 0 until region.height) {
      for (x in 0 until region.width) {
        out[y * region.width + x] = this[(region.top + y) * size.width + region.left + x]
      }
    }
    return out
  }

  /** As rows of labels, so a failure reads as a picture rather than as two integer lists. */
  private fun IntArray.render(width: Int): String =
    toList().chunked(width).joinToString("\n") { row ->
      row.joinToString(" ") { it.toChar().toString() }
    }

  private fun ImageSize.transposed(): ImageSize = ImageSize(height, width)
}
