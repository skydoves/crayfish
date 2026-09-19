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

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Where the pixels to crop come from.
 *
 * The public API takes a *reference* to an encoded image, never a decoded bitmap. A cropper that
 * takes a `Bitmap` has already made the caller pay the full-resolution allocation before it runs.
 * Owning the decode is what allows subsampling to happen at all.
 */
public sealed interface CropSource {

  /**
   * A key that identifies this source for caching, stable across recompositions and process death.
   */
  public val cacheKey: String

  /** Encoded bytes already in memory. */
  public class Bytes(public val bytes: ByteArray, override val cacheKey: String) : CropSource

  /**
   * A file readable by path.
   *
   * On Android this is the memory-safe way to handle a `content://` Uri from the photo picker:
   * copy it into `cacheDir` once and decode regions from the file, rather than buffering the whole
   * encoded image. Not available on wasm, which has no filesystem.
   */
  public class FilePath(public val path: String) : CropSource {
    override val cacheKey: String get() = path
  }

  /**
   * Pixels that are already decoded, such as anything a Compose image library has loaded.
   *
   * The ergonomic source, and the one most Compose apps want: whatever produced the `Painter` or
   * `ImageBitmap` on screen can be cropped directly, with no file path and no bytes in between.
   * `rememberCropSource` builds one from a `Painter`.
   *
   * **The trade it makes.** Every other source is a *reference*: Crayfish opens it, reads the
   * header and decodes only the region the frame is over, which is what lets a 100 megapixel photo
   * be cropped without ever fitting in memory. This one is already in memory by the time it gets
   * here, so that guarantee belongs to whoever decoded it. For an image a screen is showing, that
   * is exactly the right trade and costs nothing extra. For a camera original, prefer
   * [FilePath] or [Loader], which never hold the whole thing.
   *
   * Exif is not applied here, because there is no file left to read it from. Anything that decoded
   * these pixels has already applied it, and applying it again would turn the image twice.
   *
   * @param cacheKey what identifies this image. The crop state is saved under it, so it wants to be
   *   stable across process death: the URL or the content Uri the pixels came from, not an
   *   identity hash.
   */
  public class Image(public val bitmap: ImageBitmap, override val cacheKey: String) : CropSource

  /**
   * Encoded bytes fetched on demand: a network image, a content Uri, anything with a loader.
   *
   * This is the seam that keeps the library free of an HTTP client while still letting a caller
   * crop something off the network. [load] is called once per source, off the main thread, and
   * whatever it returns is treated exactly like [Bytes] from then on, so region decoding and the
   * memory budget apply unchanged. Returning `null` is an unreadable source, not a crash.
   *
   * @param cacheKey identifies the image. It is the key the crop state is saved under, so it has
   *   to be stable across process death: a URL is right, a random id is not.
   * @param load fetches the encoded file. Use whatever client the app already has; nothing here
   *   assumes one.
   */

  public class Loader(override val cacheKey: String, public val load: suspend () -> ByteArray?) :
    CropSource
}
