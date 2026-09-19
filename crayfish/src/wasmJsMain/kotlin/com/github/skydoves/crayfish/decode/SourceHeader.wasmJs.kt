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

internal actual suspend fun CropSource.readHeaderBytes(maxBytes: Int): ByteArray? = when (this) {
  is CropSource.Bytes -> bytes.copyOf(minOf(maxBytes, bytes.size))

  // A browser has no filesystem, which is also why `createRegionDecoder` refuses a file path here.
  is CropSource.FilePath -> null

  // Fetched before any platform decoder sees it. See `CropSource.resolved`.
  is CropSource.Loader -> null

  // Already decoded: no header exists to read. See `openRegionDecoder`.
  is CropSource.Image -> null
}
