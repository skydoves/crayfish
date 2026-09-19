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

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.closeFile
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.readDataOfLength
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun CropSource.readHeaderBytes(maxBytes: Int): ByteArray? = when (this) {
  is CropSource.Bytes -> bytes.copyOf(minOf(maxBytes, bytes.size))

  is CropSource.FilePath -> withContext(Dispatchers.Default) {
    val handle = NSFileHandle.fileHandleForReadingAtPath(path) ?: return@withContext null
    try {
      handle.readDataOfLength(maxBytes.toULong()).toByteArray()
    } catch (_: Throwable) {
      null
    } finally {
      handle.closeFile()
    }
  }

  // Fetched before any platform decoder sees it. See `CropSource.resolved`.
  is CropSource.Loader -> null

  // Already decoded: no header exists to read. See `openRegionDecoder`.
  is CropSource.Image -> null
}

/** One `memcpy`, not a per-byte loop: the loop is unusably slow once the length is real. */
@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray? {
  val size = length.toInt()
  if (size <= 0) return null
  val out = ByteArray(size)
  out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
  return out
}
