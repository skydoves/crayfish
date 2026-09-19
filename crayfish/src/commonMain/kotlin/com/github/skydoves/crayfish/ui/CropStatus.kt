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

import androidx.compose.runtime.Immutable
import com.github.skydoves.crayfish.decode.ImageSize
import com.github.skydoves.crayfish.exif.ImageOrientation

/** Where a [CropState] is in opening its source. */
@Immutable
public sealed interface CropStatus {

  /** The source is being opened. Nothing about the image is known yet. */
  public data object Loading : CropStatus

  /**
   * The source is open and its dimensions are known.
   *
   * @param imageSize the size **after** [orientation] is applied: the space every crop rectangle
   *   and every coordinate in the public API is expressed in. A quarter-turn orientation swaps the
   *   source's own width and height, and exposing the raw file dimensions would make every
   *   rectangle a caller builds silently wrong for half of all camera photos.
   * @param orientation what the source's metadata declares, already accounted for in [imageSize].
   */
  @Immutable
  public data class Ready(
    public val imageSize: ImageSize,
    public val orientation: ImageOrientation,
  ) : CropStatus

  /** The source could not be opened. */
  @Immutable
  public data class Failed(
    public val reason: CropResult.Failure.Reason,
    public val cause: Throwable? = null,
  ) : CropStatus
}
