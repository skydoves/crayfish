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
@file:Suppress("unused", "UNUSED_PARAMETER", "UNUSED_VARIABLE", "ktlint:standard:property-naming")

package com.github.skydoves.crayfish.readme

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.github.skydoves.crayfish.decode.CropSource
import com.github.skydoves.crayfish.decode.DecodeBudget
import com.github.skydoves.crayfish.decode.ImageRegion
import com.github.skydoves.crayfish.decode.ImageSize
import com.github.skydoves.crayfish.encode.EncodeOptions
import com.github.skydoves.crayfish.encode.EncodedFormat
import com.github.skydoves.crayfish.landscapist.CropTransformation
import com.github.skydoves.crayfish.ui.AspectRatio
import com.github.skydoves.crayfish.ui.CropAccessibility
import com.github.skydoves.crayfish.ui.CropAccessibilityAction
import com.github.skydoves.crayfish.ui.CropGestures
import com.github.skydoves.crayfish.ui.CropImage
import com.github.skydoves.crayfish.ui.CropGridMode
import com.github.skydoves.crayfish.ui.CropOverlay
import com.github.skydoves.crayfish.ui.CropRecipe
import com.github.skydoves.crayfish.ui.CropResult
import com.github.skydoves.crayfish.ui.CropShape
import com.github.skydoves.crayfish.ui.CropState
import com.github.skydoves.crayfish.ui.CropStatus
import com.github.skydoves.crayfish.ui.CropStyle
import com.github.skydoves.crayfish.ui.Cropper
import com.github.skydoves.crayfish.ui.ImageCropper
import com.github.skydoves.crayfish.ui.ImageCropperDialog
import com.github.skydoves.crayfish.ui.rememberCropSource
import com.github.skydoves.crayfish.ui.rememberCropState
import com.github.skydoves.crayfish.ui.rememberImageCropper
import kotlinx.coroutines.launch

// Material3 and Landscapist's own request builder are not on this classpath, and pulling a UI
// toolkit into the library's build for a documentation check would be the wrong trade. These stand
// in for them. What is under test is that every Crayfish call in the README resolves.
@Composable
private fun Button(onClick: () -> Unit, content: @Composable () -> Unit) {
  content()
}

@Composable
private fun IconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
  content()
}

@Composable
private fun Text(text: String) = Unit

@Composable
private fun Icon(imageVector: Any?, contentDescription: Any?) = Unit

@Composable
private fun CircularProgressIndicator() = Unit

@Composable
private fun FilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit) {
  label()
}

@Composable
private fun LandscapistImage(imageModel: () -> Any, requestBuilder: Any) = Unit

private fun addTransformation(transformation: Any) = Unit

// Plain rather than @Composable, so a snippet that calls a suspending crop can be a suspend
// function. What is under test is that the Crayfish call resolves, not where it may be called.
private fun Image(bitmap: ImageBitmap, contentDescription: String?) = Unit

private fun Image(bitmap: ImageBitmap, contentDescription: String?, modifier: Modifier) = Unit

private fun Image(painter: Painter, contentDescription: String?) = Unit

private object Icons {
  object Default {
    val RotateLeft = Unit
    val Flip = Unit
    val Refresh = Unit
  }
}

private const val path = ""
private const val url = ""
private const val version = ""
private val bytes = ByteArray(0)
private val source: CropSource = CropSource.Bytes(bytes, "k")
private val uri: Any = "content://photos/1"
private object HttpResponse {
  fun readRawBytes(): ByteArray = ByteArray(0)
}

private object HttpClient {
  suspend fun get(url: String): HttpResponse = HttpResponse
}

private object ContentResolver {
  fun openInputStream(uri: Any): java.io.InputStream? = null
}

// Named the way the README names them at the call site.
private val httpClient = HttpClient
private val contentResolver = ContentResolver
private fun upload(b: ByteArray) = Unit
private fun showError(r: Any) = Unit
private fun localizedLabel(a: CropAccessibilityAction): String = ""
private fun localizedDescription(r: Any, v: Any): String = ""
private val result = CropResult.Success(ByteArray(0), ImageSize(1, 1), ImageRegion(0, 0, 1, 1))

// A block that reads `state` or `cropper` is continuing from the snippet above it on the page.
// The implementations are internal to the library, and nothing here is ever run: this file is
// compiled and then deleted.
@Composable
private fun rememberAsyncImagePainter(url: String): Painter = ColorPainter(Color.Red)

private var cropped: ImageBitmap? = null

private val state: CropState get() = error("compile check only")
private val cropper: ImageCropper get() = error("compile check only")

// Coil and a database stand in for whatever the reader actually has; what is under test is that the
// Crayfish calls resolve.
private object database {
  fun save(id: String, value: String) = Unit
  fun load(id: String): String = ""
}

private val photoId = "photo-1"
private val context: Any = Unit

private class ImageRequestBuilder(ctx: Any) {
  fun data(model: Any) = this
  fun transformations(vararg t: Any) = this
  fun build(): Any = Unit
}

private object ImageRequest {
  fun Builder(ctx: Any) = ImageRequestBuilder(ctx)
}

private class CropTransformation(region: ImageRegion)

@Composable
private fun AsyncImage(model: Any, contentDescription: String?) = Unit

@Composable
private fun Slider(
  value: Float,
  onValueChange: (Float) -> Unit,
  valueRange: ClosedFloatingPointRange<Float>,
) = Unit

@Composable
internal fun block0() {
val cropper = rememberImageCropper()
val scope = rememberCoroutineScope()
var cropped by remember { mutableStateOf<ImageBitmap?>(null) }

Button(
  onClick = {
    scope.launch {
      when (val result = cropper.cropToImage(source)) {
        is CropImage.Success -> cropped = result.image
        is CropImage.Cancelled -> Unit
        is CropImage.Failure -> showError(result.reason)
      }
    }
  },
) {
  Text("Crop a photo")
}

ImageCropperDialog(cropper)

// The result is an ImageBitmap, so drawing it is the ordinary Image composable.
val image = cropped
if (image != null) {
  Image(
    bitmap = image,
    contentDescription = "Cropped photo",
    modifier = Modifier.size(120.dp).clip(CircleShape),
  )
}
}

internal suspend fun block1() {
when (val result = cropper.crop(CropSource.FilePath(path))) {
  is CropResult.Success -> upload(result.bytes)
  is CropResult.Cancelled -> Unit
  is CropResult.Failure -> showError(result.reason)
}
}

@Composable
internal fun block2() {
ImageCropperDialog(
  cropper = cropper,
  shape = CropShape.Circle,
  accessibility = CropAccessibility(contentDescription = "Profile photo crop area"),
)
}

@Composable
internal fun block3() {
val painter = rememberAsyncImagePainter(url)
val source = rememberCropSource(painter, cacheKey = url)

if (source != null) {
  val state = rememberCropState(source)
  Cropper(state = state, modifier = Modifier.fillMaxSize())
}
}

internal fun block4() {
CropSource.FilePath("/storage/emulated/0/DCIM/Camera/IMG_0001.jpg")

CropSource.Bytes(bytes, cacheKey = uri.toString())
}

internal fun block5() {
CropSource.Loader(cacheKey = url) { httpClient.get(url).readRawBytes() }

CropSource.Loader(cacheKey = uri.toString()) {
  contentResolver.openInputStream(uri)?.use { it.readBytes() }
}
}

@Composable
internal fun block6() {
val state = rememberCropState(
  source = CropSource.FilePath(path),
  initialAspectRatio = AspectRatio.Square,
)

Cropper(
  state = state,
  modifier = Modifier.fillMaxSize(),
)
}

@Composable
internal fun block7() {
val state = rememberCropState(source = CropSource.FilePath(path))

when (val status = state.status) {
  is CropStatus.Loading -> CircularProgressIndicator()
  is CropStatus.Ready -> Text("${status.imageSize.width} x ${status.imageSize.height}")
  is CropStatus.Failed -> Text("Could not open this image")
}

Row {
  IconButton(onClick = { state.rotateBy(-90f) }) { Icon(Icons.Default.RotateLeft, null) }
  IconButton(onClick = { state.toggleFlipHorizontal() }) { Icon(Icons.Default.Flip, null) }
  IconButton(onClick = { state.reset() }) { Icon(Icons.Default.Refresh, null) }
}
}

internal suspend fun block8() {
when (val result = state.cropToImage()) {
  is CropImage.Success -> Image(bitmap = result.image, contentDescription = null)
  is CropImage.Cancelled -> Unit
  is CropImage.Failure -> showError(result.reason)
}
}

internal suspend fun block9() {
val result = state.crop(EncodeOptions(format = EncodedFormat.PNG))
}

internal suspend fun block10() {
val avatar = state.crop(
  options = EncodeOptions(EncodedFormat.JPEG),
  budget = DecodeBudget(maxByteCount = 8L * 1024 * 1024, maxDimension = 1024),
)
}

@Composable
internal fun block11() {
val state = rememberCropState(source = source, initialAspectRatio = AspectRatio.Square)

Row {
  listOf(
    "Free" to AspectRatio.Free,
    "1:1" to AspectRatio.Square,
    "3:4" to AspectRatio.Portrait3x4,
    "4:3" to AspectRatio.Landscape4x3,
    "16:9" to AspectRatio.Widescreen16x9,
    "9:16" to AspectRatio.Portrait9x16,
  ).forEach { (label, ratio) ->
    FilterChip(
      selected = state.aspectRatio == ratio,
      onClick = { state.aspectRatio = ratio },
      label = { Text(label) },
    )
  }
}
}

@Composable
internal fun block12() {
Cropper(
  state = state,
  modifier = Modifier.fillMaxSize(),
  gestures = CropGestures.Zoomable,
)
}

@Composable
internal fun block13() {
// When the user is happy with it. Eleven floats, no pixels.
database.save(photoId, state.recipe.encodeToString())

// A week later, on the original file, exactly where they left off.
val recipe = CropRecipe.decodeFromString(database.load(photoId))
val state = rememberCropState(source, initialRecipe = recipe)
}

@Composable
internal fun block14() {
var degrees by remember { mutableStateOf(0f) }

Slider(
  value = degrees,
  onValueChange = { degrees = it; state.rotateTo(it) },
  valueRange = -45f..45f,
)
}

@Composable
internal fun block15() {
Cropper(
  state = state,
  modifier = Modifier.fillMaxSize(),
  style = CropStyle(
    scrimColor = Color.Black.copy(alpha = 0.7f),
    frameColor = Color.White,
    gridMode = CropGridMode.OnTouch,
    handleTouchRadius = 24.dp,
  ),
)
}

@Composable
internal fun block16() {
Cropper(
  state = state,
  modifier = Modifier.fillMaxSize(),
  overlay = { cropState ->
    CropOverlay(
      state = cropState,
      shape = CropShape.Circle,
    )
  },
)
}

internal suspend fun block17() {
state.shape = CropShape.Circle

// Corners come back transparent.
val avatar = state.cropToImage()
}

@Composable
internal fun block18() {
CropOverlay(
  state = state,
  accessibility = CropAccessibility(
    step = 24.dp,
    contentDescription = "Crop area",
    label = { action -> localizedLabel(action) },
  ),
)
}

@Composable
internal fun block19() {
val region = result.region
val request = remember(region) {
  ImageRequest.Builder(context)
    .data(url)
    .transformations(CropTransformation(region))
    .build()
}

AsyncImage(model = request, contentDescription = null)
}

@Composable
internal fun block20() {
val region = result.region
val requestBuilder = remember(region) {
  { addTransformation(CropTransformation(region)) }
}

LandscapistImage(
  imageModel = { url },
  requestBuilder = requestBuilder,
)
}
