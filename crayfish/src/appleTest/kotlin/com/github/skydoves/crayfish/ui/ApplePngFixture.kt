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

/** An 8x8 PNG, embedded because an Apple test process has no fixture directory to read from. */
internal object ApplePngFixture {
  fun bytes(): ByteArray = byteArrayOf(
    0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(),
    0x1A.toByte(), 0x0A.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0D.toByte(),
    0x49.toByte(), 0x48.toByte(), 0x44.toByte(), 0x52.toByte(), 0x00.toByte(), 0x00.toByte(),
    0x00.toByte(), 0x08.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x08.toByte(),
    0x08.toByte(), 0x06.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0xC4.toByte(),
    0x0F.toByte(), 0xBE.toByte(), 0x8B.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
    0x1D.toByte(), 0x49.toByte(), 0x44.toByte(), 0x41.toByte(), 0x54.toByte(), 0x78.toByte(),
    0x9C.toByte(), 0x63.toByte(), 0xF8.toByte(), 0xFF.toByte(), 0xBF.toByte(), 0xE1.toByte(),
    0x3F.toByte(), 0x32.toByte(), 0x66.toByte(), 0xC0.toByte(), 0xC0.toByte(), 0x74.toByte(),
    0x50.toByte(), 0xC0.toByte(), 0x00.toByte(), 0x94.toByte(), 0x40.toByte(), 0xC2.toByte(),
    0x0C.toByte(), 0xE8.toByte(), 0x98.toByte(), 0xF6.toByte(), 0x0A.toByte(), 0x00.toByte(),
    0x4B.toByte(), 0x3D.toByte(), 0x9F.toByte(), 0x81.toByte(), 0xE6.toByte(), 0x3F.toByte(),
    0x04.toByte(), 0x20.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
    0x49.toByte(), 0x45.toByte(), 0x4E.toByte(), 0x44.toByte(), 0xAE.toByte(), 0x42.toByte(),
    0x60.toByte(), 0x82.toByte(),
  )
}
