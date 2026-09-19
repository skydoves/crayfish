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
 * A pixel size in an image's own coordinate space.
 *
 * Deliberately not `androidx.compose.ui.unit.IntSize`: everything in this package must stay usable
 * without a Compose runtime, so that cropping can be driven headlessly from a test, a script or a
 * server, and so the geometry can be unit-tested without standing up a composition.
 */
public data class ImageSize(public val width: Int, public val height: Int) {

  /**
   * The number of pixels, as a [Long] because a 200MP source overflows [Int] once multiplied by
   * the four bytes per pixel that decoding it would cost.
   */
  public val pixelCount: Long
    get() = width.toLong() * height.toLong()

  /**
   * The bytes a full-resolution ARGB_8888 decode of this image would occupy.
   *
   * This is the number that has to be kept away from the platform's rendering ceiling: Android's
   * hardware-accelerated canvas rejects bitmaps beyond roughly 100MB, and a 108MP phone photo is
   * 412MiB. Callers use this to decide a sample size before any decode is attempted.
   */
  public val argb8888ByteCount: Long
    get() = pixelCount * BYTES_PER_ARGB_8888_PIXEL

  public companion object {
    private const val BYTES_PER_ARGB_8888_PIXEL = 4L

    public val Zero: ImageSize = ImageSize(0, 0)
  }
}
