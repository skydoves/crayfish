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
@file:OptIn(ExperimentalResourceApi::class)

package com.github.skydoves.crayfish.demo

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.skydoves.crayfish.decode.CropSource
import com.github.skydoves.crayfish.decode.ImageSize
import com.github.skydoves.crayfish.demo.resources.Res
import com.github.skydoves.crayfish.ui.CropStatus
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ExperimentalResourceApi

/** The bundled photo, so every demo has something to crop before anyone picks a file. */
internal const val SAMPLE_PATH = "files/sample.jpg"

/**
 * The source every demo screen shares, plus the picker that replaces it.
 *
 * Hoisted here rather than repeated per screen, because the interesting part of each demo is the
 * cropper's configuration and not the file dialog in front of it.
 */
@Immutable
internal class DemoSource(
  val source: CropSource?,
  val pick: () -> Unit,
  val reloadSample: () -> Unit,
)

@Composable
internal fun rememberDemoSource(): DemoSource {
  val scope = rememberCoroutineScope()
  var source by remember { mutableStateOf<CropSource?>(null) }

  // `remember`, not `rememberSaveable`: a picked photo is megabytes of encoded JPEG, and that in a
  // saved-state Bundle is the TransactionTooLargeException the library's own docs warn about. The
  // crop geometry does survive, because `rememberCropState` saves numbers rather than pixels.
  fun open(bytes: ByteArray, cacheKey: String) {
    source = CropSource.Bytes(bytes, cacheKey)
  }

  LaunchedEffect(Unit) { open(Res.readBytes(SAMPLE_PATH), SAMPLE_PATH) }

  val picker = rememberFilePickerLauncher(type = FileKitType.Image) { file: PlatformFile? ->
    file ?: return@rememberFilePickerLauncher
    scope.launch { open(file.readBytes(), file.name) }
  }

  return DemoSource(
    source = source,
    pick = { picker.launch() },
    reloadSample = { scope.launch { open(Res.readBytes(SAMPLE_PATH), SAMPLE_PATH) } },
  )
}

/**
 * What a demo keeps of a crop: the pixels it draws, and the size of what they came from.
 *
 * Deliberately not the encoded [ByteArray] as well. Keeping both would hold the same image twice,
 * compressed and decoded, for the sake of a number that fits in an [Int].
 */
@Immutable
internal class CropOutput(val image: ImageBitmap, val byteCount: Int?, val size: ImageSize)

@Composable
internal fun CropOutputPanel(
  output: CropOutput?,
  message: String?,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    when {
      output != null -> {
        // Full width and given real height. A crop shown in a thumbnail is a crop nobody can check,
        // and checking it is the entire point of putting it on screen.
        Image(
          bitmap = output.image,
          contentDescription = "Cropped result",
          contentScale = ContentScale.Fit,
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp, max = 320.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Text(
          text = buildString {
            append(output.size.width)
            append(" × ")
            append(output.size.height)
            output.byteCount?.let {
              append("  ·  ")
              append(formatBytes(it))
            }
          },
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      message != null -> Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
      )

      else -> Text(
        text = "Crop to see the result here.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** The code that produced the screen above it, shown on demand rather than always. */
@Composable
internal fun CodeSample(code: String, modifier: Modifier = Modifier) {
  var shown by remember(code) { mutableStateOf(false) }
  Column(modifier = modifier) {
    TextButton(onClick = { shown = !shown }) {
      Text(if (shown) "Hide code" else "Show code")
    }
    if (shown) {
      Card(
        colors = CardDefaults.cardColors(
          containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
      ) {
        Box(Modifier.horizontalScroll(rememberScrollState())) {
          Text(
            text = code.trimIndent(),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(12.dp),
          )
        }
      }
    }
  }
}

internal fun CropStatus.describe(): String = when (this) {
  CropStatus.Loading -> "Opening…"
  is CropStatus.Ready -> "${imageSize.width} × ${imageSize.height} · $orientation"
  is CropStatus.Failed -> "Could not open: $reason"
}

/**
 * Formats a byte count without `String.format`, which is a JVM only API. The demo is one source set
 * and integer arithmetic is the whole of what this needs.
 */
internal fun formatBytes(byteCount: Int): String = when {
  byteCount < 1024 -> "$byteCount B"
  byteCount < 1024 * 1024 -> "${oneDecimal(byteCount, 1024)} KB"
  else -> "${oneDecimal(byteCount, 1024 * 1024)} MB"
}

private fun oneDecimal(value: Int, unit: Int): String {
  val tenths = (value.toLong() * 10 + unit / 2) / unit
  return "${tenths / 10}.${tenths % 10}"
}
