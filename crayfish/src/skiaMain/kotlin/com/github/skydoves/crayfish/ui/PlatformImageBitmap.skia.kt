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

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.github.skydoves.crayfish.decode.PlatformImage

internal actual fun PlatformImage.toImageBitmap(): ImageBitmap? {
  // Asked before the bitmap is touched, and this is the line that does the work.
  //
  // `asComposeImageBitmap` on a closed bitmap does not throw: it wraps freed memory and hands back
  // a perfectly ordinary looking ImageBitmap, and the process dies with SIGSEGV on the frame that
  // draws it. A `runCatching` around it therefore catches nothing - it was written to guard exactly
  // this case and could never have done so. Android's actual gets this for free from
  // `Bitmap.isRecycled`; on Skia the state has to be tracked, which is what `isClosed` is for.
  if (isClosed) return null
  // Kept for the rest: an unsupported colour type or a zero-sized bitmap does throw.
  return runCatching { bitmap.asComposeImageBitmap() }.getOrNull()
}
