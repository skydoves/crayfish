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
package com.github.skydoves.crayfish.coil

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.Bitmap
import com.github.skydoves.crayfish.decode.ImageRegion
import com.github.skydoves.crayfish.ui.cropTo

/** `asImageBitmap` and `asAndroidBitmap` both wrap rather than copy, so the only copy is the crop. */
internal actual fun cropCoilBitmap(input: Bitmap, region: ImageRegion): Bitmap? {
  if (input.isRecycled) return null
  return input.asImageBitmap().cropTo(region)?.asAndroidBitmap()
}
