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

import com.github.skydoves.crayfish.exif.ImageOrientation
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFNumberGetValue
import platform.CoreFoundation.CFNumberRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFURLCreateWithFileSystemPath
import platform.CoreFoundation.CFURLRef
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreFoundation.kCFURLPOSIXPathStyle
import platform.CoreGraphics.CGImageCreateWithImageInRect
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.ImageIO.CGImageSourceCopyPropertiesAtIndex
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceCreateWithURL
import platform.ImageIO.CGImageSourceRef
import platform.ImageIO.kCGImagePropertyPixelHeight
import platform.ImageIO.kCGImagePropertyPixelWidth
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceShouldCache
import platform.ImageIO.kCGImageSourceSubsampleFactor
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import kotlin.math.roundToInt

/**
 * The largest factor `kCGImageSourceSubsampleFactor` accepts.
 *
 * ImageIO documents exactly four (1, 2, 4 and 8) and silently ignores the key for any other
 * value, which would decode at full resolution: the precise failure this library exists to
 * prevent. See [decodeFactorFor] for what happens to a larger request.
 */
private const val MAX_SUBSAMPLE_FACTOR = 8

internal actual suspend fun createPlatformRegionDecoder(source: CropSource): RegionDecoder? =
  // Opening reads from disk, so it does not run on the caller's thread. Every actual dispatches its
  // own blocking work, so that using this library never requires knowing which platform needs a
  // dispatcher and which does not.
  //
  // `Dispatchers.Default`, not `Dispatchers.IO` as the Android and desktop actuals use: on
  // Kotlin/Native `Dispatchers.IO` is internal to kotlinx-coroutines and cannot be referenced here.
  withContext(Dispatchers.Default) { openRegionDecoder(source) }

@OptIn(ExperimentalForeignApi::class)
private fun openRegionDecoder(source: CropSource): RegionDecoder? {
  // Format first, from the header bytes, before ImageIO opens anything. [ImageProbe] is the
  // library's single answer to "what container is this", so GIF and AVIF drop out of the tiled
  // path here for the same reason they do on every other target, even though ImageIO itself
  // would happily decode both.
  val format = source.probeFormat() ?: return null
  if (!format.supportsRegionDecoding) return null

  val encoded = source.openImageSource() ?: return null
  val imageSize = encoded.imageSource.readPixelSize()
  if (imageSize == null) {
    encoded.release()
    return null
  }
  return ImageIoRegionDecoder(encoded, imageSize, format)
}

/**
 * A `CGImageSource` together with the `CFData` it reads through, when there is one.
 *
 * They are released as a pair because ImageIO reads the bytes lazily: outliving the data would
 * hand it freed memory on the next tile.
 */
@OptIn(ExperimentalForeignApi::class)
private class EncodedSource(val imageSource: CGImageSourceRef, val data: CFDataRef?) {
  fun release() {
    CFRelease(imageSource)
    if (data != null) CFRelease(data)
  }
}

/**
 * ImageIO's answer to region decoding, which is that it has none.
 *
 * Android's `BitmapRegionDecoder` genuinely decodes only the rectangle asked for. ImageIO has no
 * such entry point: the cheapest thing it will do is decode the whole image subsampled, and the
 * rectangle is taken afterwards with `CGImageCreateWithImageInRect`. So the peak allocation per
 * call is the *whole* source at the sample size, not the tile, which is exactly why the sample
 * size rather than the rectangle is what keeps a 200MP photo decodable here.
 *
 * The practical consequence for the layer above: on Apple a tiled pass over one image repeats that
 * subsampled decode once per tile, where Android pays it per tile's worth of pixels. Nothing here
 * caches the intermediate, deliberately: holding a subsampled full image between calls is a memory
 * policy the renderer owns, and making it here would quietly reintroduce the allocation the decode
 * budget was computed to avoid.
 */
@OptIn(ExperimentalForeignApi::class)
private class ImageIoRegionDecoder(
  private val encoded: EncodedSource,
  override val imageSize: ImageSize,
  override val format: ImageFormat,
) : RegionDecoder {

  private var closed = false

  // `kCGImageSourceCreateThumbnailWithTransform` is deliberately not passed (see `decodeSubsampled`),
  // so ImageIO leaves the Exif tag alone and these pixels stay in the same space as `imageSize`.
  override val appliedOrientation: ImageOrientation get() = ImageOrientation.NORMAL

  override suspend fun decodeRegion(region: ImageRegion, sampleSize: Int): DecodedRegion? =
    // See `createRegionDecoder` for why this is Default rather than IO on Kotlin/Native.
    withContext(Dispatchers.Default) { decodeRegionCatching(region, sampleSize) }

  private fun decodeRegionCatching(region: ImageRegion, sampleSize: Int): DecodedRegion? = try {
    decodeRegionOrNull(region, sampleSize)
  } catch (throwable: Throwable) {
    // The contract makes "this region could not be decoded" a value rather than an exception,
    // because the causes that actually occur (a truncated file off a photo picker, an allocation
    // the platform refused) are ordinary events a tile loop has to keep running through.
    null
  }

  private fun decodeRegionOrNull(region: ImageRegion, sampleSize: Int): DecodedRegion? {
    if (closed) return null
    val clipped = region.intersect(ImageRegion.of(imageSize)) ?: return null
    // Rounded down to a power of two, as the contract says every implementation does: the platform
    // decoders on the other targets round for their own reasons, and a factor that behaved
    // differently here would make the same request return different sizes per platform.
    val effective = sampleSize.coerceIn(1, SampleSize.MAX).takeHighestOneBit()
    // The shared rounding rule, not a local reimplementation of it: the size this returns is the
    // size the caller already budgeted for.
    val target = with(SampleSize) { clipped.size.sampledBy(effective) }

    val decoded = decodeSubsampled(decodeFactorFor(effective)) ?: return null
    try {
      val cropped = decoded.cropTo(clipped) ?: return null
      try {
        val image = cropped.toPlatformImage(target) ?: return null
        return DecodedRegion(image = image, region = clipped, sampleSize = effective)
      } finally {
        CGImageRelease(cropped)
      }
    } finally {
      CGImageRelease(decoded)
    }
  }

  override fun close() {
    if (closed) return
    closed = true
    encoded.release()
  }

  /** Decodes the whole image at [factor], the only granularity ImageIO offers. */
  private fun decodeSubsampled(factor: Int): CGImageRef? {
    // Capacity 0 rather than a count: in Core Foundation a non-zero capacity is a fixed bound, not
    // a hint, and a dictionary that outgrows it is undefined behaviour.
    val options = CFDictionaryCreateMutable(
      allocator = null,
      capacity = 0,
      keyCallBacks = kCFTypeDictionaryKeyCallBacks.ptr,
      valueCallBacks = kCFTypeDictionaryValueCallBacks.ptr,
    ) ?: return null
    try {
      // Without this ImageIO may return the embedded Exif thumbnail, 160x120 on most camera
      // JPEGs, and the caller's rectangle would be applied to a different image entirely.
      CFDictionarySetValue(options, kCGImageSourceCreateThumbnailFromImageAlways, kCFBooleanTrue)

      // kCGImageSourceCreateThumbnailWithTransform is deliberately absent, and its absence is the
      // load-bearing decision in this file. Setting it makes ImageIO apply the Exif orientation
      // while decoding, so the pixels handed back would sit in a different coordinate space from
      // the one [imageSize] describes and the caller's region was measured in, and from every
      // other platform's decoder, none of which honours the tag. The rectangle would still be
      // accepted; it would just crop the wrong part of the photo, on Apple targets only. Crayfish
      // applies orientation once, above this layer, through ImageOrientation.
      //
      // kCGImageSourceThumbnailMaxPixelSize is absent for a related reason: unset, the "thumbnail"
      // is the full image, which leaves the subsample factor as the only thing deciding the
      // decoded size. Setting both would put two independent resizers in the same path.

      // A region decoder is called once per tile. Letting ImageIO retain each subsampled full
      // image would rebuild, inside its cache, the allocation this class exists to avoid.
      CFDictionarySetValue(options, kCGImageSourceShouldCache, kCFBooleanFalse)

      if (factor > 1) {
        val number = cfNumber(factor) ?: return null
        try {
          CFDictionarySetValue(options, kCGImageSourceSubsampleFactor, number)
        } finally {
          CFRelease(number)
        }
      }
      return CGImageSourceCreateThumbnailAtIndex(
        isrc = encoded.imageSource,
        index = 0uL,
        options = options,
      )
    } finally {
      CFRelease(options)
    }
  }

  /**
   * Takes [region], stated in full-image pixels, out of an image ImageIO has already subsampled.
   *
   * The scale comes from the decoded image's actual size rather than from the factor that was
   * asked for, because ImageIO's rounding of an odd dimension is its own business: 1001 pixels at
   * factor 2 may come back as 500 or as 501, and a rectangle derived from the assumed size drifts
   * by a pixel per halving.
   *
   * No vertical flip appears here: `CGImageCreateWithImageInRect` measures its rectangle in the
   * image's own space, origin top-left, which is the same convention [ImageRegion] uses.
   */
  private fun CGImageRef.cropTo(region: ImageRegion): CGImageRef? {
    val decodedWidth = CGImageGetWidth(this).toInt()
    val decodedHeight = CGImageGetHeight(this).toInt()
    if (decodedWidth <= 0 || decodedHeight <= 0) return null

    val scaleX = decodedWidth.toDouble() / imageSize.width
    val scaleY = decodedHeight.toDouble() / imageSize.height
    val left = (region.left * scaleX).roundToInt().coerceIn(0, decodedWidth - 1)
    val top = (region.top * scaleY).roundToInt().coerceIn(0, decodedHeight - 1)
    val right = (region.right * scaleX).roundToInt().coerceIn(left + 1, decodedWidth)
    val bottom = (region.bottom * scaleY).roundToInt().coerceIn(top + 1, decodedHeight)

    return CGImageCreateWithImageInRect(
      image = this,
      rect = CGRectMake(
        x = left.toDouble(),
        y = top.toDouble(),
        width = (right - left).toDouble(),
        height = (bottom - top).toDouble(),
      ),
    )
  }
}

/**
 * The factor to hand `kCGImageSourceSubsampleFactor`, and what becomes of the rest.
 *
 * ImageIO caps subsampling at [MAX_SUBSAMPLE_FACTOR], so a larger [sampleSize] is clamped here and
 * the residual `sampleSize / 8` is applied afterwards, by drawing the cropped image into a
 * destination context sized to the final output (see [toPlatformImage]). Core Graphics resamples
 * during that draw, so the residual costs no buffer of its own.
 *
 * What the clamp costs is the intermediate. ImageIO has no entry point that decodes below 1/8, so
 * a 16384x12288 source still materialises 2048x1536, or 12.6MB, before the crop, however small the
 * requested tile. That is the floor of this API rather than of this code.
 *
 * A [sampleSize] that is not a power of two rounds down, matching the contract on
 * [RegionDecoder.decodeRegion]: decoding more detail than asked for is recoverable, less is not.
 */
internal fun decodeFactorFor(sampleSize: Int): Int =
  sampleSize.coerceIn(1, MAX_SUBSAMPLE_FACTOR).takeHighestOneBit()

/**
 * The container [this] is in, read from its leading bytes.
 *
 * A file is opened and read for its first [ImageProbe.HEADER_BYTE_COUNT] bytes rather than loaded,
 * because the whole point of the file-backed source is that a 200MP image never has to be resident
 * in order to be inspected.
 */
@OptIn(ExperimentalForeignApi::class)
private fun CropSource.probeFormat(): ImageFormat? {
  val header = when (this) {
    is CropSource.Bytes -> bytes.copyOf(minOf(bytes.size, ImageProbe.HEADER_BYTE_COUNT))

    is CropSource.FilePath -> readHeaderBytes(path) ?: return null

    // Fetched before any platform decoder sees it. See `CropSource.resolved`.
    is CropSource.Loader -> return null

    // Never reaches a platform decoder: it is answered in common code.
    is CropSource.Image -> return null
  }
  return ImageProbe.probe(header)?.format
}

@OptIn(ExperimentalForeignApi::class)
private fun readHeaderBytes(path: String): ByteArray? {
  val file = fopen(path, "rb") ?: return null
  try {
    val buffer = ByteArray(ImageProbe.HEADER_BYTE_COUNT)
    val read = buffer.usePinned {
      fread(it.addressOf(0), 1uL, buffer.size.toULong(), file).toInt()
    }
    return if (read <= 0) null else buffer.copyOf(read)
  } finally {
    fclose(file)
  }
}

@OptIn(ExperimentalForeignApi::class)
private fun CropSource.openImageSource(): EncodedSource? = when (this) {
  is CropSource.Bytes -> {
    val data = bytes.toCFData()
    val imageSource = data?.let { CGImageSourceCreateWithData(it, null) }
    if (imageSource == null) {
      if (data != null) CFRelease(data)
      null
    } else {
      EncodedSource(imageSource, data)
    }
  }

  is CropSource.FilePath -> {
    // The source keeps whatever it needs of the URL, so this reference is ours to drop at once.
    val url = path.toFileUrl()
    val imageSource = url?.let { CGImageSourceCreateWithURL(it, null) }
    if (url != null) CFRelease(url)
    imageSource?.let { EncodedSource(it, data = null) }
  }

  // Fetched before any platform decoder sees it. See `CropSource.resolved`.
  is CropSource.Loader -> null

  // Never reaches a platform decoder: it is answered in common code. See `openRegionDecoder`.
  is CropSource.Image -> null
}

/**
 * The source's size as ImageIO's container parser reports it.
 *
 * Read from the properties, never from a decoded image: `CGImageSourceCopyPropertiesAtIndex` only
 * walks headers, so this stays cheap on a 200MP file, and it is also the one answer that is right
 * for HEIF, whose dimensions [ImageProbe] deliberately declines to guess.
 *
 * @return the size, or `null` when there is no readable image at index 0, which is also how an
 *   unreadable or absent file arrives here, since `CGImageSourceCreateWithURL` is lazy enough to
 *   succeed on one.
 */
@OptIn(ExperimentalForeignApi::class)
private fun CGImageSourceRef.readPixelSize(): ImageSize? {
  val properties = CGImageSourceCopyPropertiesAtIndex(this, index = 0uL, options = null)
    ?: return null
  try {
    val width = properties.intValue(kCGImagePropertyPixelWidth) ?: return null
    val height = properties.intValue(kCGImagePropertyPixelHeight) ?: return null
    return if (width > 0 && height > 0) ImageSize(width, height) else null
  } finally {
    CFRelease(properties)
  }
}

@OptIn(ExperimentalForeignApi::class)
private fun CFDictionaryRef.intValue(key: CFStringRef?): Int? {
  val value = CFDictionaryGetValue(this, key) ?: return null
  return memScoped {
    val holder = alloc<IntVar>()
    if (CFNumberGetValue(value.reinterpret(), kCFNumberIntType, holder.ptr)) holder.value else null
  }
}

@OptIn(ExperimentalForeignApi::class)
private fun cfNumber(value: Int): CFNumberRef? = memScoped {
  val holder = alloc<IntVar>()
  holder.value = value
  // CFNumberCreate copies out of the pointer, so the scratch variable may die with the scope.
  CFNumberCreate(allocator = null, theType = kCFNumberIntType, valuePtr = holder.ptr)
}

/**
 * Wraps the encoded bytes as `CFData` for ImageIO to read lazily.
 *
 * `CFDataCreate` copies, which is deliberate: the alternative keeps a Kotlin array pinned for the
 * decoder's entire lifetime, and what is copied is the *encoded* image, orders of magnitude
 * smaller than the decoded bitmap this class exists to avoid allocating.
 */
@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toCFData(): CFDataRef? {
  if (isEmpty()) return null
  return usePinned {
    CFDataCreate(allocator = null, bytes = it.addressOf(0).reinterpret(), length = size.toLong())
  }
}

@OptIn(ExperimentalForeignApi::class)
private fun String.toFileUrl(): CFURLRef? {
  val path = CFStringCreateWithCString(null, this, kCFStringEncodingUTF8) ?: return null
  try {
    return CFURLCreateWithFileSystemPath(
      allocator = null,
      filePath = path,
      pathStyle = kCFURLPOSIXPathStyle,
      isDirectory = false,
    )
  } finally {
    CFRelease(path)
  }
}
