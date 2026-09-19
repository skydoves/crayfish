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

import com.github.skydoves.crayfish.encode.EncodeOptions
import com.github.skydoves.crayfish.encode.EncodedFormat
import com.github.skydoves.crayfish.encode.encodeImage
import com.github.skydoves.crayfish.ui.toImageBitmap
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every consumer of a closed [PlatformImage], in one place, because this bug has now happened three
 * times.
 *
 * A [PlatformImage] is a handle to memory outside the managed heap, and the two platforms fail
 * differently when it is read after `close()`. Android throws - `Bitmap.isRecycled` is free and
 * `compress` raises `IllegalStateException`. **Skia does not throw.** It reads freed memory and the
 * process dies with SIGSEGV: no exception, no stack, nothing an application can catch, and the
 * host app goes down with it.
 *
 * That asymmetry has produced the same defect three times in this codebase:
 *
 * 1. `readArgbPixels` read a closed bitmap and aborted the test JVM.
 * 2. `ImageEncoder.skia` passed one to `Image.makeFromBitmap` - exit 134, the Gradle worker dead
 *    with two of eight results written.
 * 3. `PlatformImageBitmap.skia` wrapped one in a `runCatching`, which cannot catch an abort. The
 *    guard was inert from the day it was written, and a closed tile became a cached `ImageBitmap`
 *    over freed memory that would have taken the process down on the frame that drew it.
 *
 * Each was fixed where it was found, and the rule "guard every reader" was written down twice
 * before it failed a third time. So this is the guard instead of the rule: one closed image, and
 * every seam that touches the platform bitmap asked what it does with it. A new consumer added
 * without a check does not fail here - it fails at the next line, [everyRawBitmapReaderIsGuarded],
 * which reads the source.
 */
class ClosedImageContractTest {

  private fun closedImage(): PlatformImage {
    val size = ImageSize(8, 8)
    val image = assertNotNull(
      platformImageOfArgbPixels(IntArray(size.width * size.height) { 0xFF00FF00.toInt() }, size),
    )
    image.close()
    return image
  }

  @Test
  fun aClosedImageSaysSo() {
    val image = closedImage()

    assertTrue(image.isClosed)
    // The dimensions survive, so a failure can still describe a real image.
    assertEquals(8, image.width)
    assertEquals(8, image.height)
  }

  @Test
  fun readingPixelsFromAClosedImageAnswersNull() {
    assertNull(closedImage().readArgbPixels())
  }

  @Test
  fun aClosedImageIsNeverHandedToComposeAsADrawable() {
    assertNull(
      closedImage().toImageBitmap(),
      "a closed image became an ImageBitmap; drawing it is a SIGSEGV",
    )
  }

  @Test
  fun encodingAClosedImageFailsAsAValueRatherThanAsACrash() = runTest {
    val image = closedImage()

    // The same `IllegalStateException` Android's `Bitmap.compress` raises for a recycled bitmap,
    // so `cropToBytes` turns both into `EncodeFailed` without knowing which platform it is on.
    assertFailsWith<IllegalStateException> {
      encodeImage(image, EncodeOptions(EncodedFormat.PNG))
    }
  }

  /** The positive control: every assertion above must fail for an image that is still open. */
  @Test
  fun anOpenImageIsAcceptedEverywhere() = runTest {
    val size = ImageSize(8, 8)
    val image = assertNotNull(
      platformImageOfArgbPixels(IntArray(size.width * size.height) { 0xFF00FF00.toInt() }, size),
    )

    image.use {
      assertTrue(!it.isClosed)
      assertNotNull(it.readArgbPixels(), "an open image would not give up its pixels")
      assertNotNull(it.toImageBitmap(), "an open image was refused as a drawable")
      assertNotNull(
        encodeImage(it, EncodeOptions(EncodedFormat.PNG)),
        "an open image would not encode",
      )
    }
  }

  /**
   * The mechanical half: no new reader of the raw platform bitmap without a liveness check.
   *
   * Reads the library's own source rather than its behaviour, because the failure this catches is a
   * *new* consumer someone adds next month, which no behavioural test can know about.
   *
   * ## What it asks, and why this rule and not another
   *
   * The question is "does this file operate **on** a [PlatformImage]", which is exactly the set of
   * places that can hold one somebody else already closed. Three shapes cover it: the class itself,
   * an extension on it, and a function taking one and reading `x.bitmap`. Files that *produce* a
   * PlatformImage - every region decoder - construct the bitmap themselves and wrap it, so they can
   * never be holding a closed one, and they are not flagged.
   *
   * Two earlier versions of this gate were wrong in opposite directions, which is why the rule is
   * spelled out. Matching a list of method names (`compress`, `readPixels`, ...) let a planted
   * `bitmap.width` through - a gate for a bug that had already happened three times, inert on its
   * own test case. Matching the word "bitmap" anywhere flagged seven files, most of them producers
   * and one that only says "bitmap" in a comment, which is the kind of red that gets suppressed
   * rather than read.
   */
  @Test
  fun everyRawBitmapReaderIsGuarded() {
    val roots = listOf(
      "commonMain",
      "androidMain",
      "skiaMain",
      "desktopMain",
      "appleMain",
      "wasmJsMain",
    )
      .map { File("src/$it") }
      .filter { it.isDirectory }
    assertTrue(roots.isNotEmpty(), "no source roots found; this gate is examining nothing")

    // Operates on a PlatformImage: is the class, extends it, or reads `.bitmap` off something
    // this file declares to be one.
    val isTheClass = Regex("""class PlatformImage""")
    // An extension on PlatformImage only operates on one if it actually reaches the bitmap. The
    // `expect` declaration has no body, and an actual that declines by returning null touches
    // nothing, so neither can hold a closed image. Flagging them taught the wrong lesson: the
    // remedy would have been to write the word `isClosed` into a file with nothing to guard.
    val extendsAndTouchesIt = Regex("""fun PlatformImage\.""")
    val bitmapRead = Regex("""\b(\w+)\.bitmap\b""")
    val guards = Regex("""\bisClosed\b|\bisRecycled\b""")

    val examined = mutableListOf<String>()
    val unguarded = mutableListOf<String>()
    roots.forEach { root ->
      root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
        val text = file.readText()
        // The receiver's type, not merely the property's name. `CropSource.Image` also has a
        // `bitmap`, an `ImageBitmap` that Compose owns and that no liveness check applies to, and
        // a rule keyed on the name alone reported the file that reads it as an unguarded consumer
        // of the platform bitmap. Asking whether this file declares that identifier to be a
        // PlatformImage is the question the gate was always trying to ask.
        val readsAPlatformBitmap = bitmapRead.findAll(text).any { match ->
          // The capture is `\w+`, so it needs no escaping to be used as a pattern.
          Regex("""\b${match.groupValues[1]}\s*:\s*PlatformImage\b""").containsMatchIn(text)
        }
        val reachesTheBitmap = extendsAndTouchesIt.containsMatchIn(text) &&
          Regex("""\bbitmap\b""").containsMatchIn(text)
        if (!isTheClass.containsMatchIn(text) && !readsAPlatformBitmap && !reachesTheBitmap) {
          return@forEach
        }
        examined += file.name
        if (!guards.containsMatchIn(text)) unguarded += file.path
      }
    }

    // A denominator, so "0 problems" cannot quietly mean "examined nothing". The six are the
    // PlatformImage class and its two consumers, on each of the two platforms.
    assertTrue(
      examined.size >= 6,
      "only ${examined.size} files were examined ($examined); the pattern has stopped matching " +
        "the code and this gate is now measuring nothing",
    )
    assertTrue(
      unguarded.isEmpty(),
      "these operate on a PlatformImage without ever asking whether it is alive, which on Skia " +
        "is a SIGSEGV rather than an exception:\n" +
        unguarded.joinToString("\n") { "  $it" },
    )
  }
}
