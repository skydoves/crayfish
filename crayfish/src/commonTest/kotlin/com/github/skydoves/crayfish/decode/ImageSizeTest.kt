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
import kotlin.test.assertTrue

class ImageSizeTest {

  /**
   * The arithmetic that motivates the whole tiled pipeline. A 108MP sensor's output is 412MiB
   * decoded and a 200MP sensor's is exactly 768MiB, against a hardware-canvas ceiling of roughly
   * 100MB, so both are undrawable at full resolution, unconditionally.
   */
  @Test
  fun computesTheDecodedCostOfModernPhoneSensors() {
    assertEquals(432_000_000L, ImageSize(12_000, 9_000).argb8888ByteCount)
    assertEquals(805_306_368L, ImageSize(16_384, 12_288).argb8888ByteCount)

    val hardwareCanvasCeiling = 100L * 1024 * 1024
    assertTrue(ImageSize(12_000, 9_000).argb8888ByteCount > hardwareCanvasCeiling)
    assertTrue(ImageSize(16_384, 12_288).argb8888ByteCount > hardwareCanvasCeiling)
  }

  /**
   * Why the type widens to [Long] before multiplying.
   *
   * At Skia's 32766-per-dimension cap the pixel count still fits in an [Int]; the byte count does
   * not, and computing it in [Int] arithmetic wraps to a negative number, which reads as "well
   * under the ceiling" to any budget check. A stitched panorama overflows the pixel count too.
   */
  @Test
  fun doesNotOverflowOnLargeSources() {
    val atSkiaDimensionLimit = ImageSize(32_766, 32_766)

    assertEquals(1_073_610_756L, atSkiaDimensionLimit.pixelCount)
    assertTrue(atSkiaDimensionLimit.pixelCount < Int.MAX_VALUE)
    assertEquals(4_294_443_024L, atSkiaDimensionLimit.argb8888ByteCount)
    assertTrue(atSkiaDimensionLimit.argb8888ByteCount > Int.MAX_VALUE)

    val panorama = ImageSize(100_000, 40_000)

    assertTrue(panorama.pixelCount > Int.MAX_VALUE)
    assertEquals(panorama.pixelCount * 4, panorama.argb8888ByteCount)
  }
}
