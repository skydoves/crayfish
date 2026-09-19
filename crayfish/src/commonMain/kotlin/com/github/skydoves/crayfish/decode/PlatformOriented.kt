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
 * Turns these pixels upright using whatever the platform already has, or `null` to say it cannot.
 *
 * ## Why this seam exists
 *
 * [ImageOrientation.applyTo] is a per-pixel Kotlin loop, and on a phone it is the most expensive
 * part of a crop by a wide margin. Measured on a Galaxy S23 (SM-S911N), a 27 megapixel crop of a
 * 12000x9000 source, quarter turned:
 *
 * ```
 * decode  95ms      permute 576ms     encode 177ms
 * ```
 *
 * The decode is fast because `BitmapRegionDecoder` is native and hardware assisted. The permute was
 * 68% of the whole crop because it was the only part written in Kotlin. The same measurement on a
 * desktop JVM inverts: decode 504ms, permute 237ms, because ImageIO is the slow one there.
 *
 * So this is not a general optimisation, it is one aimed at the number that was actually large on
 * the platform where it was large. A target with no faster path returns `null` and gets the Kotlin
 * loop, which is correct everywhere and is the oracle the platform path is tested against.
 *
 * ## What an implementation must guarantee
 *
 * Exactly the pixels [ImageOrientation.applyTo] would produce, for all eight orientations, with no
 * resampling: rotate clockwise by `rotationDegrees`, then mirror horizontally in the rotated frame.
 * The mirrored four are where this goes wrong quietly, because an upright but back to front photo
 * looks plausible, and they are the reason `DeviceOrientedPixelsTest` compares both paths rather
 * than trusting either.
 *
 * @return a new image the caller owns, or `null` when this platform has nothing better than the
 *   Kotlin loop, or when the allocation was refused.
 */
internal expect fun PlatformImage.platformOriented(orientation: ImageOrientation): PlatformImage?
