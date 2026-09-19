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

/**
 * Reads the leading bytes of a source, for the metadata that lives there.
 *
 * Orientation is the reason this exists. It is declared in the file's header, the crop rectangle
 * has to be expressed in the space that header describes, and the decoder cannot answer the
 * question: it reports the file's own grid and nothing about what the grid means.
 *
 * @param maxBytes how much to read. Exif sits in the first APP1 segment and HEIF's property boxes
 *   near the front of the file, so a caller wanting orientation reads far less than the whole
 *   image, which matters because the whole image is the allocation this library exists to avoid.
 * @return the bytes, or `null` when the source cannot be read here. A file path on the web is the
 *   expected case: a browser has no filesystem.
 */
internal expect suspend fun CropSource.readHeaderBytes(
  maxBytes: Int = DEFAULT_HEADER_BYTES,
): ByteArray?

/**
 * Enough for an Exif APP1 segment, which the specification caps at 64KB, plus room for the HEIF
 * property boxes that precede the image data.
 */
internal const val DEFAULT_HEADER_BYTES: Int = 128 * 1024
