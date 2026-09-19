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

/**
 * The pixels a region decode produced, together with the terms it actually produced them on.
 *
 * A bare bitmap is not enough, and two platform implementations independently proved it:
 *
 * - A decoder that meets an [OutOfMemoryError] retries at a coarser subsampling rather than
 *   failing, so the pixels can come back at a different scale from the one requested. A caller
 *   that positions tiles by assuming `region.size / requestedSampleSize` would place them wrong,
 *   and could only discover this by comparing sizes and guessing why.
 * - The web decoder cannot always stop the browser applying the Exif orientation during decode,
 *   reported by the decoder rather than per region. See [RegionDecoder.appliedOrientation].
 *
 * Closing this closes the underlying image.
 */
public class DecodedRegion(
  public val image: PlatformImage,
  /** The source rectangle these pixels came from, after clipping to the image bounds. */
  public val region: ImageRegion,
  /**
   * The subsampling factor actually used, which may be coarser than the one asked for.
   *
   * Always compute the mapping back to source coordinates from this, never from the value passed
   * in.
   */
  public val sampleSize: Int,
) : AutoCloseable {

  public val width: Int get() = image.width
  public val height: Int get() = image.height

  override fun close(): Unit = image.close()
}

/**
 * Decodes a rectangle of an image without materialising the whole thing.
 *
 * This is the primitive the entire library rests on. A 200MP source is 768MiB decoded, against a
 * hardware-canvas ceiling near 100MB, so "decode it and crop afterwards" is not a slow path, it is
 * an impossible one. Every platform has an API for this and none of them are the same shape, which
 * is why it is an expect/actual seam rather than shared code.
 */
public interface RegionDecoder : AutoCloseable {

  /** The source's full pixel size as the decoder reports it, which is the authoritative value. */
  public val imageSize: ImageSize

  /** The container the source is in. */
  public val format: ImageFormat

  /**
   * The orientation this decoder has already baked into the pixels it returns.
   *
   * [ImageOrientation.NORMAL] on every platform that leaves the Exif tag alone, which is the
   * behaviour this library asks for: orientation belongs to the layer that owns the crop rectangle,
   * because applying it during decode silently moves the pixels into a different coordinate space
   * from [imageSize] and every rectangle expressed in it.
   *
   * The web is the exception. A browser applies the tag by default and the request to suppress it
   * is not honoured everywhere, so wasm may return already-oriented pixels. Without saying so here,
   * a caller that applies the orientation itself would rotate such an image twice.
   */
  public val appliedOrientation: ImageOrientation

  /**
   * Decodes [region] of the source, subsampled by [sampleSize].
   *
   * @param region is clipped to `ImageRegion.of(imageSize)` by the implementation; a rectangle that
   *   does not overlap the image at all yields `null`.
   * @param sampleSize a power of two, normally from [SampleSize.forDecode]. A request that is
   *   not a power of two is rounded **down to the nearest power of two**, so passing 3 decodes at
   *   2, because that is what the platform decoders do and pretending otherwise would return a
   *   bitmap larger than the budget that approved it.
   * @return the decoded pixels and the terms they were decoded on, or `null` if the region could
   *   not be decoded, including when the platform ran out of memory, which is an expected outcome
   *   here and never an exception.
   */
  public suspend fun decodeRegion(region: ImageRegion, sampleSize: Int): DecodedRegion?
}

/**
 * Opens [source] for region decoding.
 *
 * @return a decoder, or `null` when the source is unreadable or its format does not support
 *   region decoding on this platform (see [ImageFormat.supportsRegionDecoding]).
 */
public suspend fun createRegionDecoder(source: CropSource): RegionDecoder? =
  openRegionDecoder(source)

/**
 * Opens [source], resolving a [CropSource.Loader] and answering a [CropSource.Image] here.
 *
 * The `Image` case is handled in common code on purpose. Routing it through the expect/actual seam
 * would put the same `ImageBitmap` copy in four platform files for no platform reason, and every
 * one of those `when` expressions is exhaustive over `CropSource`, so each new source shape would
 * cost four edits to arms that have nothing to say about it.
 */
internal suspend fun openRegionDecoder(source: CropSource): RegionDecoder? =
  when (val resolved = source.resolved()) {
    null -> null
    is CropSource.Image -> ImageBitmapRegionDecoder(resolved.bitmap)
    else -> createPlatformRegionDecoder(resolved)
  }

/**
 * The same source with any [CropSource.Loader] already fetched.
 *
 * Every platform decoder understands bytes and a path and nothing else, so the fetch happens once
 * here rather than four times in four actuals. A loader that returns `null` has no source at all,
 * which is the caller's `SourceUnreadable`.
 */
internal suspend fun CropSource.resolved(): CropSource? = when (this) {
  is CropSource.Loader -> load()?.let { CropSource.Bytes(it, cacheKey) }
  else -> this
}

internal expect suspend fun createPlatformRegionDecoder(source: CropSource): RegionDecoder?
