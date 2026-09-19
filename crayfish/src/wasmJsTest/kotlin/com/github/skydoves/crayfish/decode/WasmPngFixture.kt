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

/**
 * A 4x4 PNG with four differently coloured quadrants, embedded as bytes.
 *
 * Embedded rather than generated because a browser test has no filesystem to read from and no
 * compressor to build one with. Asymmetric in both axes on purpose: on a uniform fixture a flipped
 * decode, a wrong origin and a red/blue channel swap all look correct.
 *
 * Top-left red, top-right green, bottom-left blue, bottom-right yellow.
 */
internal object WasmPngFixture {

  val BYTES: ByteArray = byteArrayOf(
    0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(),
    0x1A.toByte(), 0x0A.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0D.toByte(),
    0x49.toByte(), 0x48.toByte(), 0x44.toByte(), 0x52.toByte(), 0x00.toByte(), 0x00.toByte(),
    0x00.toByte(), 0x04.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x04.toByte(),
    0x08.toByte(), 0x06.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0xA9.toByte(),
    0xF1.toByte(), 0x9E.toByte(), 0x7E.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
    0x1A.toByte(), 0x49.toByte(), 0x44.toByte(), 0x41.toByte(), 0x54.toByte(), 0x78.toByte(),
    0x9C.toByte(), 0x63.toByte(), 0xF8.toByte(), 0xCF.toByte(), 0xC0.toByte(), 0xF0.toByte(),
    0x1F.toByte(), 0x84.toByte(), 0x91.toByte(), 0x20.toByte(), 0x9A.toByte(), 0x00.toByte(),
    0x94.toByte(), 0x0F.toByte(), 0x04.toByte(), 0x0C.toByte(), 0x60.toByte(), 0x8C.toByte(),
    0x21.toByte(), 0x00.toByte(), 0x00.toByte(), 0x66.toByte(), 0x16.toByte(), 0x23.toByte(),
    0xDD.toByte(), 0x34.toByte(), 0x96.toByte(), 0x2C.toByte(), 0x07.toByte(), 0x00.toByte(),
    0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x49.toByte(), 0x45.toByte(), 0x4E.toByte(),
    0x44.toByte(), 0xAE.toByte(), 0x42.toByte(), 0x60.toByte(), 0x82.toByte(),
  )

  const val WIDTH: Int = 4
  const val HEIGHT: Int = 4

  const val TOP_LEFT: Int = 0xFFFF0000.toInt()
  const val TOP_RIGHT: Int = 0xFF00FF00.toInt()
  const val BOTTOM_LEFT: Int = 0xFF0000FF.toInt()
  const val BOTTOM_RIGHT: Int = 0xFFFFFF00.toInt()
}
