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
@file:OptIn(ExperimentalWasmJsInterop::class)

package com.github.skydoves.crayfish.decode

import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8ClampedArray
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.js.js

/*
 * The browser surface the region decoder stands on, declared by hand.
 *
 * `kotlinx-browser` is on the wasm classpath (Skiko exposes its typed arrays), and the typed arrays
 * below are taken from it for exactly that reason: they are the currency Skiko's own buffer bridge
 * speaks. Everything else is declared here instead, because that library carries no
 * `OffscreenCanvas` at all and reaches `createImageBitmap` only through `window`, which does not
 * exist in a worker. Hand-declaring the five types actually used also keeps this file an honest
 * inventory of what the decoder needs from the platform.
 */

/** An immutable byte blob. The only in-memory source `createImageBitmap` will take. */
internal external interface Blob : JsAny

/**
 * A decoded image owned by the browser, not by the wasm heap.
 *
 * [close] is not optional housekeeping: an `ImageBitmap` holds its pixels outside anything the
 * Kotlin or JS garbage collectors account for, and browsers are in no hurry to reclaim one that has
 * merely gone unreferenced. Every bitmap this decoder creates is closed on the way out.
 */
internal external interface ImageBitmap : JsAny {
  val width: Int
  val height: Int
  fun close()
}

/** The options dictionary of `createImageBitmap`; built by [orientationOptions]/[resizeOptions]. */
internal external interface ImageBitmapOptions : JsAny

/** A canvas with no document behind it, the only kind a decoder has any business making. */
internal external interface OffscreenCanvas : JsAny

internal external interface OffscreenCanvasRenderingContext2D : JsAny {
  fun drawImage(image: ImageBitmap, dx: Double, dy: Double)
  fun getImageData(sx: Int, sy: Int, sw: Int, sh: Int): ImageData
}

internal external interface ImageData : JsAny {
  /**
   * The pixels as **RGBA**, one byte per channel, row-major, and **not** premultiplied.
   *
   * Both halves of that matter downstream: the channel order decides what colour type the Skia
   * bitmap is told it holds, and canvas pixels being unpremultiplied decides the alpha type.
   */
  val data: Uint8ClampedArray
}

/**
 * Wraps [bytes] in a `Blob`.
 *
 * No media type is attached. `createImageBitmap` sniffs the bytes rather than trusting the label,
 * so a type would be decoration, and a wrong one guessed from a header would be worse.
 *
 * Because the blob is built here rather than fetched from a URL, every canvas it is later drawn
 * into stays same-origin and untainted, which is what keeps `getImageData` legal.
 */
internal fun blobOf(bytes: Int8Array): Blob {
  js("return new Blob([bytes]);")
}

internal fun newOffscreenCanvas(width: Int, height: Int): OffscreenCanvas {
  js("return new OffscreenCanvas(width, height);")
}

/**
 * The 2D context of [canvas], or `null` if the browser will not give one out.
 *
 * `willReadFrequently` because every canvas here exists solely to be read back one time: the hint
 * asks for a CPU-backed surface and so avoids a GPU readback stall on `getImageData`.
 */
internal fun canvasContext2d(canvas: OffscreenCanvas): OffscreenCanvasRenderingContext2D? {
  js("return canvas.getContext('2d', { willReadFrequently: true });")
}

/** `createImageBitmap(source, options)`: the whole image, used only to learn its size. */
internal fun createImageBitmap(source: Blob, options: ImageBitmapOptions): Promise<ImageBitmap> {
  js("return createImageBitmap(source, options);")
}

/**
 * `createImageBitmap(source, sx, sy, sw, sh, options)`, the call this whole platform rests on.
 *
 * It crops and rescales while decoding, in one pass, so the only bitmap that ever exists is the one
 * asked for. A source rectangle that runs off the edge of the image is not an error: the spec fills
 * the overhang with transparent black.
 */
internal fun createImageBitmapRegion(
  source: Blob,
  sx: Int,
  sy: Int,
  sw: Int,
  sh: Int,
  options: ImageBitmapOptions,
): Promise<ImageBitmap> {
  js("return createImageBitmap(source, sx, sy, sw, sh, options);")
}

/**
 * `{ imageOrientation }`, or `{}` when [imageOrientation] is empty.
 *
 * The empty string means "do not ask", and it is left off the dictionary rather than spelled as the
 * spec default: WebIDL rejects an enum value it does not know with a `TypeError`, so naming a value
 * a browser has not got would fail the decode outright instead of falling back to its default. An
 * `undefined` member, on the other hand, is defined to read as absent.
 */
internal fun orientationOptions(imageOrientation: String): ImageBitmapOptions {
  js("return { imageOrientation: imageOrientation || undefined };")
}

/**
 * Resize options for a region decode, subsampling to [resizeWidth] x [resizeHeight].
 *
 * `resizeQuality: 'high'` because the output is the pixels a person will look at: a subsampled
 * tile drawn with the default nearest-ish filter aliases visibly on fine detail. See
 * [orientationOptions] for why an empty [imageOrientation] is omitted rather than spelled out.
 */
internal fun resizeOptions(
  resizeWidth: Int,
  resizeHeight: Int,
  imageOrientation: String,
): ImageBitmapOptions {
  js(
    """
    return {
      resizeWidth: resizeWidth,
      resizeHeight: resizeHeight,
      resizeQuality: 'high',
      imageOrientation: imageOrientation || undefined
    };
    """,
  )
}
