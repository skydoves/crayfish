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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal actual suspend fun CropSource.readHeaderBytes(maxBytes: Int): ByteArray? = when (this) {
  is CropSource.Bytes -> bytes.copyOf(minOf(maxBytes, bytes.size))

  is CropSource.FilePath -> withContext(Dispatchers.IO) {
    runCatching {
      File(path).inputStream().buffered().use { stream ->
        // Bounded on purpose: reading the whole file to find a tag in its first kilobytes is the
        // allocation this library exists to avoid, and on a 108MP source it is 1.8MB of it.
        val buffer = ByteArray(maxBytes)
        var read = 0
        while (read < maxBytes) {
          val count = stream.read(buffer, read, maxBytes - read)
          if (count <= 0) break
          read += count
        }
        if (read <= 0) null else buffer.copyOf(read)
      }
    }.getOrNull()
  }

  // Fetched before any platform decoder sees it. See `CropSource.resolved`.
  is CropSource.Loader -> null

  // Already decoded: no header exists to read. See `openRegionDecoder`.
  is CropSource.Image -> null
}
