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
@file:Suppress("unused", "UNUSED_PARAMETER", "UNUSED_VARIABLE")

package com.github.skydoves.crayfish.readme.activity

/*
 * The README's Activity snippets.
 *
 * A separate file, and a separate package, because these reuse names the Compose snippets already
 * have: both sections call something `cropper` and something `result`, which is exactly right in the
 * documentation and a collision in one compilation unit.
 *
 * The types are stubs. `crayfish-activity` is an Android artifact and this module is desktop, so
 * what is checked here is the shape of each call. The real types are covered by that module's own
 * device tests, which run the marshalling and the FileProvider on a device.
 */

private class CropImageContract

private enum class CropMask { Rectangle, RoundedRectangle, Circle }

private class CropImageRequest(
  val source: Any,
  val aspectRatio: Float? = null,
  val mask: CropMask = CropMask.Rectangle,
)

private sealed interface CropImageResult {
  class Success(val uri: Any) : CropImageResult
  data object Cancelled : CropImageResult
  class Failure(val reason: Any) : CropImageResult
}

private class Launcher {
  fun launch(request: Any) = Unit
}

private fun registerForActivityResult(
  contract: Any,
  callback: (CropImageResult) -> Unit,
): Launcher = Launcher()

private object ImageViewStub {
  fun setImageURI(uri: Any) = Unit
}

// Named the way the README names it at the call site.
private val imageView = ImageViewStub

private fun showError(reason: Any) = Unit

private suspend fun CropImageResult.Success.readBytes(context: Any): ByteArray? = null

private suspend fun CropImageResult.Success.readBitmap(context: Any): Any? = null

private val photoUri: Any = Unit
private val context: Any = Unit
private val cropper = Launcher()
private val result = CropImageResult.Success(Unit)

private object activityBlock0 {
private val cropper = registerForActivityResult(CropImageContract()) { result ->
  when (result) {
    is CropImageResult.Success -> imageView.setImageURI(result.uri)
    is CropImageResult.Cancelled -> Unit
    is CropImageResult.Failure -> showError(result.reason)
  }
}
}

private fun activityBlock1() {
cropper.launch(CropImageRequest(source = photoUri, aspectRatio = 1f, mask = CropMask.Circle))
}

private suspend fun activityBlock2() {
val bytes = result.readBytes(context)
val bitmap = result.readBitmap(context)
}
