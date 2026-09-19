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

import com.github.skydoves.crayfish.exif.ImageOrientation

/**
 * No faster path here, deliberately.
 *
 * The Kotlin loop was measured against these targets rather than assumed slow: on a desktop JVM a
 * 27 megapixel quarter turn is 237ms against a 504ms decode, so it is not what a crop is waiting
 * for. Android is the one where it dominated, and Android is where the platform path went.
 *
 * Skia can of course do this, through a `Canvas` with a matrix. What it cannot do is prove it is
 * worth a second implementation of the eight orientation table, where the four mirrored values fail
 * in a way that looks plausible. When somebody measures a Skia target and finds this is the number
 * they are waiting on, the seam is already here.
 */
internal actual fun PlatformImage.platformOriented(orientation: ImageOrientation): PlatformImage? =
  null
