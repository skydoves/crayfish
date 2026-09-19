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

import com.github.skydoves.crayfish.decode.ImageRegion
import com.github.skydoves.crayfish.decode.ImageSize

/** The outcome of asking a [CropState] for its pixels. */
public sealed interface CropResult {

  /**
   * The cropped image, encoded.
   *
   * @param bytes the encoded file, ready to write or upload.
   * @param size the output's pixel size, which may be smaller than [region] asked for when the
   *   budget required subsampling. Always read the size from here rather than deriving it.
   * @param region the rectangle of the source these pixels came from, in the source's own
   *   coordinates after Exif correction. Always axis aligned, so when the crop was rotated by a
   *   free angle this is the *bounding box* of the tilted frame: it says where the bytes came
   *   from, not what shape they are. [size] is the shape.
   */
  public class Success(
    public val bytes: ByteArray,
    public val size: ImageSize,
    public val region: ImageRegion,
  ) : CropResult

  /** The user dismissed the cropper, or the caller's scope was cancelled. */
  public data object Cancelled : CropResult

  /**
   * The crop could not be produced.
   *
   * Failure is a value rather than an exception because every cause that actually happens (a
   * picked file that turned out to be unreadable, a format this platform cannot encode, a decode
   * the platform refused memory for) is something a screen has to show a message for, not
   * something it should crash on.
   */
  public class Failure(public val reason: Reason, public val cause: Throwable? = null) :
    CropResult {

    public enum class Reason {
      /** The source could not be opened, or its container has no region decoder here. */
      SourceUnreadable,

      /** The crop rectangle did not overlap the image. */
      EmptyRegion,

      /** The region could not be decoded, including because the platform ran out of memory. */
      DecodeFailed,

      /** This platform cannot write the requested format: lossless WebP on Skia, for one. */
      EncodeUnsupported,

      /** The encode itself failed. */
      EncodeFailed,

      /**
       * A crop was already on screen, so this call was declined rather than queued.
       *
       * Its own value because it is the one failure here that says nothing about the image. A
       * caller that treats it like the others shows "this file could not be opened" to someone who
       * pressed the button twice. The honest handling, ignoring it because the first crop is still
       * running, is only available if it can be told apart.
       */
      AlreadyCropping,
    }
  }
}
