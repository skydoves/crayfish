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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.skydoves.crayfish.decode.CropSource
import com.github.skydoves.crayfish.encode.EncodeOptions
import com.github.skydoves.crayfish.encode.EncodedFormat
import com.github.skydoves.crayfish.ui.AspectRatio
import com.github.skydoves.crayfish.ui.CropAccessibility
import com.github.skydoves.crayfish.ui.CropGestures
import com.github.skydoves.crayfish.ui.CropGridMode
import com.github.skydoves.crayfish.ui.CropImage
import com.github.skydoves.crayfish.ui.CropOverlay
import com.github.skydoves.crayfish.ui.CropRecipe
import com.github.skydoves.crayfish.ui.CropResult
import com.github.skydoves.crayfish.ui.CropShape
import com.github.skydoves.crayfish.ui.CropState
import com.github.skydoves.crayfish.ui.CropStatus
import com.github.skydoves.crayfish.ui.CropStyle
import com.github.skydoves.crayfish.ui.Cropper
import com.github.skydoves.crayfish.ui.ImageCropperDialog
import com.github.skydoves.crayfish.ui.rememberCropSource
import com.github.skydoves.crayfish.ui.rememberCropState
import com.github.skydoves.crayfish.ui.rememberImageCropper
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.math.roundToInt

@Composable
internal fun DemoScreen(demo: Demo, modifier: Modifier = Modifier) {
  val demoSource = rememberDemoSource()
  val source = demoSource.source

  // Scrolls, and nothing inside claims a weight.
  //
  // With `weight(1f)` the cropper took whatever the result panel left over, so cropping a tall
  // image shrank the cropper above it. Everything here is its own height now and the screen scrolls
  // past the bottom of it, which is also what lets the result be shown at a size worth looking at.
  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = demo.subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(1f),
      )
      TextButton(onClick = demoSource.reloadSample) { Text("Sample") }
      Button(onClick = demoSource.pick) { Text("Pick") }
    }

    if (source == null) {
      Box(
        Modifier.fillMaxWidth().height(CROPPER_HEIGHT),
        contentAlignment = Alignment.Center,
      ) {
        CircularProgressIndicator()
      }
      return@Column
    }

    // Keyed on the source so switching photos rebuilds the screen's own state with it.
    key(source.cacheKey, demo) {
      when (demo) {
        Demo.Basics -> BasicsDemo(source, Modifier.fillMaxWidth())
        Demo.Styled -> StyledDemo(source, Modifier.fillMaxWidth())
        Demo.Avatar -> AvatarDemo(source, Modifier.fillMaxWidth())
        Demo.Dialog -> DialogDemo(source, Modifier.fillMaxWidth())
        Demo.PainterSource -> PainterDemo(source, Modifier.fillMaxWidth())
        Demo.Gestures -> GesturesDemo(source, Modifier.fillMaxWidth())
        Demo.Straighten -> StraightenDemo(source, Modifier.fillMaxWidth())
        Demo.Recipe -> RecipeDemo(source, Modifier.fillMaxWidth())
        Demo.CustomOverlay -> CustomOverlayDemo(source, Modifier.fillMaxWidth())
      }
    }
  }
}

// -------------------------------------------------------------------------------------------
// 1. Basics
// -------------------------------------------------------------------------------------------

@Composable
private fun BasicsDemo(source: CropSource, modifier: Modifier = Modifier) {
  val state = rememberCropState(source)
  CropperPane(state, modifier) {
    CropToolbar(state, Modifier.fillMaxWidth())
    BytesCropButton(state)
    CodeSample(
      """
      val state = rememberCropState(source)

      Cropper(state = state, modifier = Modifier.fillMaxSize())

      val result = state.crop(EncodeOptions(EncodedFormat.JPEG))
      """,
    )
  }
}

// -------------------------------------------------------------------------------------------
// 2. A style of your own
// -------------------------------------------------------------------------------------------

private val Sunflower = Color(0xFFFFC107)

@Composable
private fun StyledDemo(source: CropSource, modifier: Modifier = Modifier) {
  val state = rememberCropState(source)
  val style = CropStyle(
    scrimColor = Color.Black.copy(alpha = 0.78f),
    frameColor = Sunflower,
    frameWidth = 2.dp,
    gridColor = Sunflower.copy(alpha = 0.45f),
    gridMode = CropGridMode.Always,
    handleColor = Sunflower,
    handleLength = 28.dp,
    handleThickness = 5.dp,
  )

  CropperPane(state, modifier, style = style) {
    CropToolbar(state, Modifier.fillMaxWidth())
    BytesCropButton(state)
    CodeSample(
      """
      Cropper(
        state = state,
        style = CropStyle(
          scrimColor = Color.Black.copy(alpha = 0.78f),
          frameColor = Color(0xFFFFC107),
          frameWidth = 2.dp,
          gridColor = Color(0xFFFFC107).copy(alpha = 0.45f),
          gridMode = CropGridMode.Always,
          handleColor = Color(0xFFFFC107),
          handleLength = 28.dp,
          handleThickness = 5.dp,
        ),
      )
      """,
    )
  }
}

// -------------------------------------------------------------------------------------------
// 3. A circular avatar, cropped straight to pixels
// -------------------------------------------------------------------------------------------

@Composable
private fun AvatarDemo(source: CropSource, modifier: Modifier = Modifier) {
  val state = rememberCropState(source, initialAspectRatio = AspectRatio.Square)
  // On the state rather than on the overlay, so the circle is cut out of the result and not merely
  // drawn over it. `CropOverlay` picks it up from here.
  state.shape = CropShape.Circle
  val scope = rememberCoroutineScope()
  var output by remember { mutableStateOf<CropOutput?>(null) }
  var message by remember { mutableStateOf<String?>(null) }

  Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Cropper(
      state = state,
      modifier = Modifier
        .fillMaxWidth()
        .height(CROPPER_HEIGHT)
        .clip(MaterialTheme.shapes.medium)
        .background(MaterialTheme.colorScheme.surfaceVariant),
      overlay = { cropState ->
        CropOverlay(
          state = cropState,
          accessibility = CropAccessibility(contentDescription = "Profile photo crop area"),
        )
      },
    )
    Text(state.status.describe(), style = MaterialTheme.typography.bodySmall)

    Button(
      onClick = {
        scope.launch {
          output = null
          message = null
          when (val result = state.cropToImage()) {
            is CropImage.Success ->
              output = CropOutput(result.image, byteCount = null, size = result.size)

            is CropImage.Failure -> message = "Crop failed: ${result.reason}"

            CropImage.Cancelled -> message = "Crop cancelled"
          }
        }
      },
      enabled = state.status is CropStatus.Ready,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text("Crop to ImageBitmap")
    }

    CropOutputPanel(output, message, Modifier.fillMaxWidth())
    CodeSample(
      """
      val state = rememberCropState(source, initialAspectRatio = AspectRatio.Square)
      state.shape = CropShape.Circle

      // The overlay reads the shape off the state, and so does the crop, so the corners really do
      // come back transparent rather than merely looking round in the viewfinder.
      Cropper(state = state)

      // No encode step, so no EncodeOptions and no quality lost on the way back in.
      when (val result = state.cropToImage()) {
        is CropImage.Success -> Image(bitmap = result.image, contentDescription = null)
        is CropImage.Cancelled -> Unit
        is CropImage.Failure -> showError(result.reason)
      }
      """,
    )
  }
}

// -------------------------------------------------------------------------------------------
// 4. The one line dialog
// -------------------------------------------------------------------------------------------

@Composable
private fun DialogDemo(source: CropSource, modifier: Modifier = Modifier) {
  val cropper = rememberImageCropper()
  val scope = rememberCoroutineScope()
  var output by remember { mutableStateOf<CropOutput?>(null) }
  var message by remember { mutableStateOf<String?>(null) }

  Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      text =
      "The whole cropper is one suspending call. The dialog below is the library's, styled " +
        "through the same parameters as the inline Cropper.",
      style = MaterialTheme.typography.bodyMedium,
    )

    Button(
      onClick = {
        scope.launch {
          output = null
          message = null
          when (val result = cropper.cropToImage(source)) {
            is CropImage.Success ->
              output = CropOutput(result.image, byteCount = null, size = result.size)

            is CropImage.Failure -> message = "Crop failed: ${result.reason}"

            CropImage.Cancelled -> message = "Crop cancelled"
          }
        }
      },
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text("Open the cropper")
    }

    CropOutputPanel(output, message, Modifier.fillMaxWidth())

    ImageCropperDialog(
      cropper = cropper,
      style = CropStyle(frameColor = Sunflower, handleColor = Sunflower),
      shape = CropShape.RoundedRectangle(cornerRadius = 20.dp),
      controls = { confirm, cancel ->
        Row(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          TextButton(onClick = cancel, modifier = Modifier.weight(1f)) { Text("Not now") }
          Button(onClick = confirm, modifier = Modifier.weight(1f)) { Text("Use this") }
        }
      },
    )

    CodeSample(
      """
      val cropper = rememberImageCropper()

      Button(onClick = {
        scope.launch {
          when (val result = cropper.cropToImage(source)) {
            is CropImage.Success -> avatar = result.image
            is CropImage.Cancelled -> Unit
            is CropImage.Failure -> showError(result.reason)
          }
        }
      }) { Text("Open the cropper") }

      ImageCropperDialog(
        cropper = cropper,
        shape = CropShape.RoundedRectangle(cornerRadius = 20.dp),
        controls = { confirm, cancel -> YourOwnChrome(confirm, cancel) },
      )
      """,
    )
  }
}

// -------------------------------------------------------------------------------------------
// 5. A Painter, which is what a Compose app already has
// -------------------------------------------------------------------------------------------

@Composable
private fun PainterDemo(source: CropSource, modifier: Modifier = Modifier) {
  // Stands in for a network loader: whatever put the picture on screen produced these pixels, and
  // that is all `rememberCropSource` wants.
  val bytes = (source as? CropSource.Bytes)?.bytes
  val loaded = remember(source.cacheKey) {
    bytes?.let { runCatching { it.decodeToImageBitmap() }.getOrNull() }
  }

  Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (loaded == null) {
      Box(
        Modifier.fillMaxWidth().height(CROPPER_HEIGHT),
        contentAlignment = Alignment.Center,
      ) {
        Text("This source could not be decoded into an ImageBitmap.")
      }
      return@Column
    }

    val imageSource = rememberCropSource(loaded, cacheKey = "painter:${source.cacheKey}")
    val state = rememberCropState(imageSource)
    CropperPane(state, Modifier.fillMaxWidth()) {
      CropToolbar(state, Modifier.fillMaxWidth())
      BytesCropButton(state)
      CodeSample(
        """
        // Anything already on screen is a crop source. A painter from your image library:
        val painter = rememberAsyncImagePainter(url)
        val source = rememberCropSource(painter, cacheKey = url)

        // Or pixels you already hold:
        val source = rememberCropSource(imageBitmap, cacheKey = url)

        if (source != null) {
          val state = rememberCropState(source)
          Cropper(state = state, modifier = Modifier.fillMaxSize())
        }
        """,
      )
    }
  }
}

// -------------------------------------------------------------------------------------------
// 6. Gestures
// -------------------------------------------------------------------------------------------

@Composable
private fun GesturesDemo(source: CropSource, modifier: Modifier = Modifier) {
  val state = rememberCropState(source)
  var gestures by remember { mutableStateOf(CropGestures.Default) }

  CropperPane(state, modifier, gestures = gestures) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      listOf(
        "Fixed" to CropGestures.Default,
        "Zoomable" to CropGestures.Zoomable,
        "All" to CropGestures.All,
      ).forEach { (label, set) ->
        FilterChip(
          selected = gestures == set,
          onClick = { gestures = set },
          label = { Text(label) },
        )
      }
    }
    Text(
      text = "Fixed is the default: the photo sits still and every gesture belongs to the frame. " +
        "The other two hand pinch, pan and twist back to the image.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    BytesCropButton(state)
    CodeSample(
      """
      // The default. The photo does not move; the crop rectangle does.
      Cropper(state = state, gestures = CropGestures.Default)

      // Pinch, two finger pan, double tap, fling.
      Cropper(state = state, gestures = CropGestures.Zoomable)

      // And the two finger twist on top.
      Cropper(state = state, gestures = CropGestures.All)
      """,
    )
  }
}

// -------------------------------------------------------------------------------------------
// 7. Straighten
// -------------------------------------------------------------------------------------------

@Composable
private fun StraightenDemo(source: CropSource, modifier: Modifier = Modifier) {
  val state = rememberCropState(source)
  var degrees by remember { mutableStateOf(0f) }

  CropperPane(state, modifier) {
    Text(
      text = "${degrees.roundToInt()}°",
      style = MaterialTheme.typography.titleMedium,
    )
    Slider(
      value = degrees,
      onValueChange = {
        degrees = it
        // Absolute, not a delta. A slider reports where it is, and feeding differences back in
        // accumulates the rounding of every frame it passed through.
        state.rotateTo(it)
      },
      valueRange = -45f..45f,
      modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(
        onClick = {
          degrees = 0f
          state.rotateTo(0f)
        },
      ) {
        Text("Level")
      }
      OutlinedButton(
        onClick = {
          degrees = 0f
          state.rotateBy(-90f)
        },
      ) {
        Text("Rotate left")
      }
    }
    Text(
      text = "The frame stays where it is and the photo grows just enough to keep filling it, so " +
        "sliding out and back leaves the crop exactly where it started.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    BytesCropButton(state)
    CodeSample(
      """
      var degrees by remember { mutableStateOf(0f) }

      Slider(
        value = degrees,
        onValueChange = { degrees = it; state.rotateTo(it) },
        valueRange = -45f..45f,
      )
      """,
    )
  }
}

// -------------------------------------------------------------------------------------------
// 8. Save the crop, reopen it later
// -------------------------------------------------------------------------------------------

@Composable
private fun RecipeDemo(source: CropSource, modifier: Modifier = Modifier) {
  // Stands in for a database row. Survives leaving and returning to this screen.
  var stored by rememberSaveable { mutableStateOf<String?>(null) }
  var generation by remember { mutableStateOf(0) }

  key(generation) {
    val state = rememberCropState(
      source = source,
      initialRecipe = CropRecipe.decodeFromString(stored),
    )

    CropperPane(state, modifier) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { stored = state.recipe.encodeToString() }) { Text("Save crop") }
        OutlinedButton(
          onClick = { generation++ },
          enabled = stored != null,
        ) {
          Text("Reopen saved")
        }
        OutlinedButton(onClick = {
          stored = null
          generation++
        }) { Text("Forget") }
      }
      Text(
        text = stored?.let { "Stored: $it" }
          ?: "Move the frame, press Save, then move it again and press Reopen.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      CodeSample(
        """
        // Eleven numbers, no pixels. A database column, not a file.
        database.save(photoId, state.recipe.encodeToString())

        // Later, on the original photo, exactly where they left off.
        val recipe = CropRecipe.decodeFromString(database.load(photoId))
        val state = rememberCropState(source, initialRecipe = recipe)
        """,
      )
    }
  }
}

// -------------------------------------------------------------------------------------------
// 9. Your own overlay
// -------------------------------------------------------------------------------------------

@Composable
private fun CustomOverlayDemo(source: CropSource, modifier: Modifier = Modifier) {
  val state = rememberCropState(source)
  state.shape = CropShape.RoundedRectangle(cornerRadius = 28.dp)

  Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Cropper(
      state = state,
      modifier = Modifier
        .fillMaxWidth()
        .height(CROPPER_HEIGHT)
        .clip(MaterialTheme.shapes.medium)
        .background(MaterialTheme.colorScheme.surfaceVariant),
      overlay = { cropState ->
        // The slot hands you the same state the gestures drive, so an overlay of your own is
        // drawn against exactly what the cropper will return.
        CropOverlay(
          state = cropState,
          style = CropStyle(
            scrimColor = Color(0xFF1B5E20).copy(alpha = 0.55f),
            frameColor = Color(0xFF69F0AE),
            frameWidth = 3.dp,
            gridMode = CropGridMode.Never,
            handleColor = Color(0xFF69F0AE),
            handleLength = 36.dp,
            handleThickness = 6.dp,
          ),
        )
      },
    )
    Text(state.status.describe(), style = MaterialTheme.typography.bodySmall)
    BytesCropButton(state)
    CodeSample(
      """
      Cropper(
        state = state,
        overlay = { cropState ->
          CropOverlay(
            state = cropState,
            style = CropStyle(frameColor = Color(0xFF69F0AE), handleLength = 36.dp),
            shape = CropShape.RoundedRectangle(cornerRadius = 28.dp),
          )
        },
      )
      """,
    )
  }
}

// -------------------------------------------------------------------------------------------
// Shared pieces
// -------------------------------------------------------------------------------------------

/** The cropper plus whatever the demo puts under it. */
@Composable
private fun CropperPane(
  state: CropState,
  modifier: Modifier = Modifier,
  style: CropStyle = CropStyle.Default,
  gestures: CropGestures = CropGestures.Default,
  below: @Composable ColumnScope.() -> Unit,
) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Cropper(
      state = state,
      modifier = Modifier
        .fillMaxWidth()
        .height(CROPPER_HEIGHT)
        .clip(MaterialTheme.shapes.medium)
        .background(MaterialTheme.colorScheme.surfaceVariant),
      style = style,
      gestures = gestures,
    )
    Text(state.status.describe(), style = MaterialTheme.typography.bodySmall)
    below()
  }
}

/** Crops to encoded bytes and shows what came back. */
@Composable
private fun ColumnScope.BytesCropButton(state: CropState) {
  val scope = rememberCoroutineScope()
  var output by remember { mutableStateOf<CropOutput?>(null) }
  var message by remember { mutableStateOf<String?>(null) }
  var cropping by remember { mutableStateOf(false) }

  Button(
    onClick = {
      scope.launch {
        cropping = true
        // The previous result goes before the next decode starts, not after it finishes: two
        // decoded bitmaps alive at once would double the peak, at exactly the worst moment.
        output = null
        message = null
        when (val result = state.crop(EncodeOptions(EncodedFormat.JPEG))) {
          is CropResult.Success -> {
            val decoded = runCatching { result.bytes.decodeToImageBitmap() }
            output = decoded.getOrNull()?.let {
              CropOutput(it, byteCount = result.bytes.size, size = result.size)
            }
            message = decoded.exceptionOrNull()?.let { "Could not show the crop: $it" }
          }

          is CropResult.Failure -> message = "Crop failed: ${result.reason}"

          CropResult.Cancelled -> message = "Crop cancelled"
        }
        cropping = false
      }
    },
    enabled = !cropping && state.status is CropStatus.Ready,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Text(if (cropping) "Cropping…" else "Crop to bytes")
  }

  CropOutputPanel(output, message, Modifier.fillMaxWidth())
}

/**
 * How tall every cropper on these screens is.
 *
 * A fixed height rather than a weight. The screen scrolls now, and a weight inside a scrolling
 * column has nothing to be a fraction of; before it did, and the cropper quietly shrank whenever the
 * result below it grew.
 */
private val CROPPER_HEIGHT = 360.dp
