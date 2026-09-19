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
 * Chooses how far to subsample a decode so the result is both useful and allocatable.
 *
 * Every calculation here happens *before* a decoder touches the file. That ordering is the whole
 * point: decoding first and checking afterwards has already paid the allocation it was trying to
 * avoid, and the crash is `Canvas: trying to draw too large bitmap` rather than a handled error.
 */
public object SampleSize {

  /**
   * The largest subsampling factor worth attempting.
   *
   * Beyond this the sampled size has already collapsed to a single pixel for any real image, and
   * an unbounded loop on a malformed header would spin.
   */
  public const val MAX: Int = 1 shl 16

  /**
   * The factor to decode [sourceSize] at so that the result covers [targetSize] and fits [budget].
   *
   * @return a power of two in `1..`[MAX]. Platform decoders round a non-power-of-two down to one
   *   anyway, so returning anything else would silently decode larger than requested.
   */
  public fun forDecode(
    sourceSize: ImageSize,
    targetSize: ImageSize,
    budget: DecodeBudget = DecodeBudget.ForDisplay,
  ): Int {
    if (sourceSize.width <= 0 || sourceSize.height <= 0) return 1

    var sampleSize = 1

    // First, do not decode meaningfully more detail than the destination can show. Halving stops
    // while both halves still cover the target, so the result is never smaller than asked for.
    if (targetSize.width > 0 && targetSize.height > 0) {
      while (
        sampleSize < MAX &&
        sourceSize.width / (sampleSize * 2) >= targetSize.width &&
        sourceSize.height / (sampleSize * 2) >= targetSize.height
      ) {
        sampleSize *= 2
      }
    }

    // Then, independently, keep halving until the allocation is legal. This runs even when the
    // caller asked for full resolution, and it is what makes a 200MP source decodable at all.
    while (sampleSize < MAX && !budget.admits(sourceSize.sampledBy(sampleSize))) {
      sampleSize *= 2
    }

    return sampleSize
  }

  /** The size [ImageSize] becomes when decoded at [sampleSize], matching platform decoder rounding. */
  public fun ImageSize.sampledBy(sampleSize: Int): ImageSize {
    require(sampleSize >= 1) { "sampleSize must be at least 1, was $sampleSize" }
    // Platform decoders floor each dimension but never produce a zero-sized bitmap.
    return ImageSize(
      width = (width / sampleSize).coerceAtLeast(1),
      height = (height / sampleSize).coerceAtLeast(1),
    )
  }

  /** Whether a bitmap of [size] is within both of [this] budget's limits. */
  public fun DecodeBudget.admits(size: ImageSize): Boolean =
    size.argb8888ByteCount <= maxByteCount &&
      size.width <= maxDimension &&
      size.height <= maxDimension
}
