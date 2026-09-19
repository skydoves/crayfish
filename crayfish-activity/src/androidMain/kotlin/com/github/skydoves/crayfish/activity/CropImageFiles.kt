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
package com.github.skydoves.crayfish.activity

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.github.skydoves.crayfish.encode.EncodedFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Reads the source through the resolver, off the main thread.
 *
 * `ContentResolver.openInputStream`, never `Uri.getPath`. A `content://` Uri is a handle to whatever
 * the provider decides to give you, and treating it as a filesystem path is the single most common
 * way a cropper breaks under scoped storage.
 *
 * @return the encoded bytes, or `null` if the Uri could not be opened, which the cropper reports as
 *   an unreadable source rather than a crash.
 */
internal suspend fun Context.readSourceBytes(source: Uri): ByteArray? =
  withContext(Dispatchers.IO) {
    try {
      contentResolver.openInputStream(source)?.use { it.readBytes() }
    } catch (_: IOException) {
      null
    } catch (_: SecurityException) {
      // The caller passed a Uri this process was never granted. An unreadable source, not a crash.
      null
    }
  }

/**
 * Writes the crop into this app's own cache and returns a Uri the caller may read.
 *
 * The cache rather than anywhere visible: a crop is a temporary artefact of a screen, and writing to
 * shared storage would cost a permission and leave litter in the user's gallery. A caller who wants
 * it kept copies it somewhere of their own, which is the one thing they are better placed to decide.
 *
 * @return the `content://` Uri, or `null` if the file could not be written.
 */
internal suspend fun Context.writeResult(bytes: ByteArray, format: EncodedFormat): Uri? =
  withContext(Dispatchers.IO) {
    try {
      val directory = File(cacheDir, CROP_DIRECTORY).apply { mkdirs() }
      // Old crops are deleted here rather than never: the directory is ours, nothing else writes to
      // it, and a user who crops twenty photos should not be leaving twenty files behind.
      directory.listFiles()?.forEach { it.delete() }
      val file = File(directory, "crop-${System.currentTimeMillis()}.${format.extension}")
      file.writeBytes(bytes)
      FileProvider.getUriForFile(this@writeResult, "$packageName.crayfish.fileprovider", file)
    } catch (_: IOException) {
      null
    } catch (_: IllegalArgumentException) {
      // FileProvider refuses a path outside what its `<paths>` declares. That would be a packaging
      // mistake in this module rather than anything the caller did, and a null reports it as a
      // failed crop instead of taking the host app down.
      null
    }
  }

private val EncodedFormat.extension: String
  get() = when (this) {
    EncodedFormat.JPEG -> "jpg"
    EncodedFormat.PNG -> "png"
    EncodedFormat.WEBP_LOSSY, EncodedFormat.WEBP_LOSSLESS -> "webp"
  }

private const val CROP_DIRECTORY = "crayfish-crops"
