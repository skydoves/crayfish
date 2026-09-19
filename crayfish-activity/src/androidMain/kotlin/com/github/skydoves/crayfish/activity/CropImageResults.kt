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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The cropped bytes, read in this process.
 *
 * What the Uri is for. Returning the bytes *through* the Activity result is what does not work: an
 * Activity result crosses a binder transaction, and on a real device 768 KB went through while 1 MB
 * came back as `FAILED BINDER TRANSACTION`. A JPEG crop of an ordinary photo is bigger than that.
 *
 * Reading them here costs one file read and no IPC, so a caller who wanted bytes gets bytes and the
 * size stops being a question anyone has to think about.
 *
 * @return the bytes, or `null` if the crop file has already been cleaned up.
 */
public suspend fun CropImageResult.Success.readBytes(context: Context): ByteArray? =
  withContext(Dispatchers.IO) {
    try {
      context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (_: IOException) {
      null
    } catch (_: SecurityException) {
      null
    }
  }

/**
 * The cropped pixels as a [Bitmap], for a `View` that wants one.
 *
 * `ImageView.setImageURI(result.uri)` is the shorter route and does not hold a second copy; this is
 * for the cases that genuinely need the pixels, such as an upload that resizes again or a canvas
 * that draws them itself.
 *
 * @return the bitmap, or `null` if the file is gone or the platform declined to decode it.
 */
public suspend fun CropImageResult.Success.readBitmap(context: Context): Bitmap? =
  withContext(Dispatchers.IO) {
    try {
      context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    } catch (_: IOException) {
      null
    } catch (_: SecurityException) {
      null
    }
  }
