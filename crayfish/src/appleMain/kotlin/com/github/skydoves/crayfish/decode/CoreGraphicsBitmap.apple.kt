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

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextGetData
import platform.CoreGraphics.CGBlendMode
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetBlendMode
import platform.CoreGraphics.CGContextSetInterpolationQuality
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.CoreGraphics.kCGInterpolationHigh
import platform.posix.memcpy

/** Bytes per pixel of the one layout this file produces: 8-bit R, G, B, A in memory order. */
private const val BYTES_PER_PIXEL = 4

/**
 * Renders this image into a Skia [Bitmap] of exactly [target], which is the form [PlatformImage]
 * wraps on every non-Android target.
 *
 * Going through a bitmap context rather than reading the `CGImage`'s own backing bytes is what
 * makes the output layout knowable. A `CGImage` out of ImageIO carries whatever its codec felt
 * like producing: 16 bits per component from a PNG, a YCbCr-derived layout from a HEIF, an
 * embedded colour space. Handing those to Skia under a guessed [ImageInfo] is how a decoder ends
 * up correct on one format and channel-swapped on the next. Drawing pins the result to one
 * declared format, at the cost of a pass over the pixels.
 *
 * Drawing into a [target] smaller than the image is also where the residual downsample lands when
 * `kCGImageSourceSubsampleFactor` could only do part of it; see `decodeFactorFor`.
 *
 * @return the bitmap, or `null` if a Core Graphics object could not be created or the pixels do
 *   not fit a [ByteArray].
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CGImageRef.toPlatformImage(target: ImageSize): PlatformImage? {
  if (target.width <= 0 || target.height <= 0) return null

  val rowBytes = target.width.toLong() * BYTES_PER_PIXEL
  val byteCount = rowBytes * target.height
  // A ByteArray is Int-indexed, and Skia could not hold a bitmap this large either.
  if (byteCount > Int.MAX_VALUE) return null

  // kCGImageAlphaPremultipliedLast in an explicitly big-endian 32-bit word lays the channels out
  // as R, G, B, A in ascending memory order, which is exactly Skia's RGBA_8888 / PREMUL. The byte
  // order is stated rather than left to kCGBitmapByteOrderDefault so the two cannot drift apart.
  val bitmapInfo =
    CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big

  val colorSpace = CGColorSpaceCreateDeviceRGB() ?: return null
  try {
    val context = CGBitmapContextCreate(
      data = null,
      width = target.width.toULong(),
      height = target.height.toULong(),
      bitsPerComponent = 8uL,
      bytesPerRow = rowBytes.toULong(),
      space = colorSpace,
      bitmapInfo = bitmapInfo,
    ) ?: return null
    try {
      CGContextSetInterpolationQuality(context, kCGInterpolationHigh)
      // The context is freshly zeroed and the draw covers all of it, so blending would only buy a
      // read-modify-write per pixel; Copy also transfers the source's alpha instead of compositing
      // it against transparent black.
      CGContextSetBlendMode(context, CGBlendMode.kCGBlendModeCopy)
      CGContextDrawImage(
        context,
        CGRectMake(0.0, 0.0, target.width.toDouble(), target.height.toDouble()),
        this,
      )

      val rendered = CGBitmapContextGetData(context) ?: return null
      val pixels = ByteArray(byteCount.toInt())
      // One bulk copy, never a per-byte Kotlin loop: a full-resolution tile is tens of millions of
      // bounds-checked iterations, which is the difference between a decoder that keeps up with a
      // pan gesture and one that does not.
      pixels.usePinned { pinned ->
        memcpy(pinned.addressOf(0), rendered, byteCount.toULong())
      }

      val info = ImageInfo(
        width = target.width,
        height = target.height,
        colorType = ColorType.RGBA_8888,
        alphaType = ColorAlphaType.PREMUL,
        colorSpace = null,
      )
      val bitmap = Bitmap()
      if (!bitmap.installPixels(info, pixels, rowBytes.toInt())) {
        bitmap.close()
        return null
      }
      // Nothing downstream writes to these pixels, and an immutable bitmap lets Skia share them
      // with a texture upload rather than copying on first draw.
      bitmap.setImmutable()
      return PlatformImage(bitmap)
    } finally {
      CGContextRelease(context)
    }
  } finally {
    CGColorSpaceRelease(colorSpace)
  }
}
