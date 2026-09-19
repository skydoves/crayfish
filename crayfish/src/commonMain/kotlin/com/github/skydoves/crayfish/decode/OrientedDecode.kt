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
import com.github.skydoves.crayfish.exif.toOrientedRegion
import com.github.skydoves.crayfish.exif.toRawRegion

/**
 * Decodes [region] given in **oriented** space and returns pixels in the same space.
 *
 * A [RegionDecoder] reads the file's own grid. Everything above it, `CropState.imageSize` and every
 * rectangle in the public API included, is in oriented space, where a quarter turn has already
 * swapped width and height. The two agree only while the Exif tag is `NORMAL`, and the gap between
 * them does not announce itself: a decoder clips a region it cannot satisfy rather than refusing
 * it, so asking for `0,0,3000,4000` of a 4000x3000 file returns a square of the left hand edge and
 * no error at all.
 *
 * That is the shape of the bug this exists to close. The crop pipeline had the round trip and the
 * preview did not, so a phone photo opened lying on its side, the frame was placed against the
 * wrong pixels, and the crop came back upright: the preview and the result disagreed about which
 * way up the photo was, and each was internally consistent.
 *
 * @param orientation the orientation still outstanding, which is the Exif tag minus whatever the
 *   decoder has already baked in. `NORMAL` makes this a straight pass through with nothing copied.
 * @return pixels and a region both in oriented space, or `null` when the region is off the image or
 *   the platform would not produce it.
 */
internal suspend fun RegionDecoder.decodeOrientedRegion(
  region: ImageRegion,
  sampleSize: Int,
  orientation: ImageOrientation,
): DecodedRegion? {
  if (orientation == ImageOrientation.NORMAL) return decodeRegion(region, sampleSize)

  val rawSize = imageSize
  val rawRegion = orientation.toRawRegion(region, rawSize)
    .intersect(ImageRegion.of(rawSize))
    ?: return null

  val decoded = decodeRegion(rawRegion, sampleSize) ?: return null
  // Read before anything is closed: `DecodedRegion.close` closes the image these describe.
  val decodedRegion = decoded.region
  val decodedSampleSize = decoded.sampleSize

  val turned = reorientPixels(decoded, orientation)
  // The turned pixels are a fresh buffer, so the decoder's own is released either way. On the null
  // path that is the difference between a failed tile and a leaked one.
  decoded.close()
  if (turned == null) return null

  return DecodedRegion(
    image = turned,
    region = orientation.toOrientedRegion(decodedRegion, rawSize),
    sampleSize = decodedSampleSize,
  )
}

/**
 * Turns [decoded]'s pixels upright, returning a new image the caller owns.
 *
 * Reads the region out, permutes it with [ImageOrientation.applyTo], and installs the result. The
 * round trip costs one extra buffer the size of the decoded region rather than of the source, which
 * is why it happens after the decode and not before it.
 *
 * @return `null` when the pixels cannot be read (an Android hardware bitmap) or the allocation is
 *   refused. Both are ordinary outcomes on a large region and neither is worth an exception.
 */
internal fun reorientPixels(
  decoded: DecodedRegion,
  orientation: ImageOrientation,
): PlatformImage? {
  // The platform's own transform where there is a faster one, the Kotlin loop everywhere else.
  // `DeviceOrientedPixelsTest` asserts the two agree for all eight orientations, so this is a
  // choice about speed and never about the answer.
  decoded.image.platformOriented(orientation)?.let { return it }

  val pixels = decoded.image.readArgbPixels() ?: return null
  val size = ImageSize(decoded.width, decoded.height)
  val turned = orientation.applyTo(pixels, size)
  return platformImageOfArgbPixels(turned, orientation.transformSize(size))
}
