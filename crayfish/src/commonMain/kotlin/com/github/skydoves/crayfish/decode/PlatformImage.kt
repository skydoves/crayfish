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
 * A decoded image held in whatever form the platform natively uses: `android.graphics.Bitmap` on
 * Android, `org.jetbrains.skia.Bitmap` everywhere else.
 *
 * Kept opaque so the decode and encode paths can hand pixels to each other without a round trip
 * through an `IntArray`, while the layer above stays free of Compose types.
 */
public expect class PlatformImage : AutoCloseable {
  public val width: Int
  public val height: Int

  /**
   * The pixels as packed ARGB_8888, row-major, or `null` if they cannot be read.
   *
   * `null` is returned rather than thrown for the case that actually happens in production: an
   * Android hardware bitmap, which is immutable and unreadable, and which `ALLOCATOR_DEFAULT`
   * hands out on API 28+ unless a decoder asks for software memory.
   */
  public fun readArgbPixels(): IntArray?

  /**
   * Whether [close] has already been called, and so whether the pixels are gone.
   *
   * A [PlatformImage] is a handle to memory outside the managed heap, and a read after close is
   * not the same on both sides: Android's recycled bitmap throws, while Skia's closed one takes
   * the process down with SIGSEGV, leaving no exception to catch. That asymmetry is why this sits
   * on the common type. Anything reaching past this class to the platform bitmap must consult it.
   */
  public val isClosed: Boolean

  /** Releases the native memory. Safe to call more than once. */
  override fun close()
}

/**
 * Builds a decoded image from packed ARGB_8888 pixels, row-major.
 *
 * The return leg of [PlatformImage.readArgbPixels], and the seam that makes it possible to change
 * pixels in common code at all. Without it `ImageOrientation.applyTo`'s `IntArray` could never
 * reach the encoder, and an Exif-rotated crop could only be refused rather than corrected.
 *
 * @return `null` if [size] does not describe [pixels], or if the platform refused the allocation.
 *   Reorienting a large region just after decoding it is exactly where an allocation gives out,
 *   and that is an ordinary outcome here rather than a crash.
 */
internal expect fun platformImageOfArgbPixels(pixels: IntArray, size: ImageSize): PlatformImage?
