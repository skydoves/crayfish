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

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.skydoves.crayfish.decode.CropSource
import com.github.skydoves.crayfish.encode.EncodeOptions
import com.github.skydoves.crayfish.ui.AspectRatio
import com.github.skydoves.crayfish.ui.CropGestures
import com.github.skydoves.crayfish.ui.CropResult
import com.github.skydoves.crayfish.ui.CropShape
import com.github.skydoves.crayfish.ui.CropStatus
import com.github.skydoves.crayfish.ui.Cropper
import com.github.skydoves.crayfish.ui.rememberCropState
import kotlinx.coroutines.launch

/**
 * The screen [CropImageContract] launches. Not part of the public API; start it through the contract.
 *
 * It is a thin host: the cropper, a confirm and a cancel. Everything it can be asked to do arrives
 * in the Intent, because that is all a process boundary can carry, and anything richer than that is
 * what the Compose API is for.
 */
internal class CropImageActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val request = intent?.readRequest()
    if (request == null) {
      // A cropper with no image is a blank screen the user has to back out of. Cancelling says the
      // same thing and says it immediately.
      setResult(Activity.RESULT_CANCELED)
      finish()
      return
    }

    setContent {
      MaterialTheme(colorScheme = darkColorScheme()) {
        CropScreen(
          request = request,
          onCancel = {
            setResult(Activity.RESULT_CANCELED)
            finish()
          },
          onSuccess = { uri, size, region ->
            setResult(Activity.RESULT_OK, Intent().putSuccess(uri, size, region))
            finish()
          },
          onFailure = { reason ->
            setResult(Activity.RESULT_OK, Intent().putFailure(reason))
            finish()
          },
        )
      }
    }
  }

  @androidx.compose.runtime.Composable
  private fun CropScreen(
    request: CropImageRequest,
    onCancel: () -> Unit,
    onSuccess: (
      android.net.Uri,
      com.github.skydoves.crayfish.decode.ImageSize,
      com.github.skydoves.crayfish.decode.ImageRegion,
    ) -> Unit,
    onFailure: (CropResult.Failure.Reason) -> Unit,
  ) {
    val scope = rememberCoroutineScope()
    val source = remember(request.source) {
      // A `content://` Uri is not a file path, so it is read through the resolver once and handed
      // over as bytes. `CropSource.Loader` is the seam that exists for exactly this.
      CropSource.Loader(cacheKey = request.source.toString()) {
        this@CropImageActivity.readSourceBytes(request.source)
      }
    }
    val state = rememberCropState(
      source = source,
      initialAspectRatio = request.aspectRatio
        ?.takeIf { it > 0f }
        ?.let { AspectRatio.Fixed(it) }
        ?: AspectRatio.Free,
    )
    state.shape = when (request.mask) {
      CropMask.Rectangle -> CropShape.Rectangle
      CropMask.Circle -> CropShape.Circle
      CropMask.RoundedRectangle -> CropShape.RoundedRectangle(request.cornerRadiusDp.dp)
    }

    var cropping by remember { mutableStateOf(false) }

    Column(
      modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .safeDrawingPadding(),
    ) {
      Cropper(
        state = state,
        modifier = Modifier.weight(1f).fillMaxWidth(),
        gestures = if (request.gesturesEnabled) CropGestures.Zoomable else CropGestures.Default,
      )

      Controls(
        cropping = cropping,
        confirmEnabled = !cropping && state.status is CropStatus.Ready,
        onCancel = onCancel,
        onConfirm = {
          cropping = true
          scope.launch {
            when (val result = state.crop(EncodeOptions(request.format, request.quality))) {
              is CropResult.Success -> {
                val uri = this@CropImageActivity.writeResult(result.bytes, request.format)
                if (uri == null) {
                  onFailure(CropResult.Failure.Reason.EncodeFailed)
                } else {
                  onSuccess(uri, result.size, result.region)
                }
              }

              is CropResult.Failure -> onFailure(result.reason)

              CropResult.Cancelled -> onCancel()
            }
            cropping = false
          }
        },
      )
    }
  }

  @androidx.compose.runtime.Composable
  private fun Controls(
    cropping: Boolean,
    confirmEnabled: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
      Button(onClick = onConfirm, enabled = confirmEnabled, modifier = Modifier.weight(1f)) {
        Text(if (cropping) "Cropping…" else "Crop")
      }
    }
  }
}
