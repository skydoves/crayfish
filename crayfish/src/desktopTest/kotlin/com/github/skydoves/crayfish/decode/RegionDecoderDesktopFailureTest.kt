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

import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Everything a user can hand a cropper that is not an image.
 *
 * The happy path is well covered elsewhere; none of this was. It is also the half that actually
 * happens: a file picker returns a path to something deleted between the pick and the open, a
 * download finishes half way, a "photo" is a PDF someone renamed. Every one of these has to come
 * back as a `null` decoder (which the caller turns into [CropStatus.Failed]) rather than as an
 * exception out of a `withContext(Dispatchers.IO)` that nobody is catching.
 *
 * The other thing being asserted throughout is that **nothing is left open**. The desktop decoder
 * holds an `ImageInputStream` on the file, and one leaked per rejected file is a handle leak in a
 * gallery. It is checked by deleting the file afterwards, which on this platform is the honest
 * proxy available.
 */
class RegionDecoderDesktopFailureTest {

  private val temporary = mutableListOf<File>()

  @AfterTest
  fun deleteTemporaryFiles() {
    temporary.forEach { it.delete() }
    temporary.clear()
  }

  private fun file(name: String, bytes: ByteArray): File =
    File.createTempFile("crayfish-", "-$name").also {
      it.writeBytes(bytes)
      temporary += it
    }

  // -----------------------------------------------------------------------------------------
  // Paths that are not files
  // -----------------------------------------------------------------------------------------

  @Test
  fun declinesAPathThatDoesNotExist() = runTest {
    val missing =
      File(System.getProperty("java.io.tmpdir"), "crayfish-does-not-exist-${System.nanoTime()}")

    assertNull(createRegionDecoder(CropSource.FilePath(missing.absolutePath)))
  }

  /**
   * A directory is a path that exists and cannot be read as a file.
   *
   * What this pins is the *behaviour*, not the `isFile` check that implements it: deleting that
   * check leaves this test green, because opening a directory as a stream throws and the `catch`
   * around it already answers `null`. The guard is belt and braces, and saying so here is better
   * than letting a later reader take the green for proof that it is load-bearing.
   */
  @Test
  fun declinesADirectory() = runTest {
    val directory = assertNotNull(System.getProperty("java.io.tmpdir"))

    assertNull(createRegionDecoder(CropSource.FilePath(directory)))
  }

  @Test
  fun declinesAnEmptyFile() = runTest {
    val empty = file("empty", ByteArray(0))

    assertNull(createRegionDecoder(CropSource.FilePath(empty.absolutePath)))
    assertTrue(empty.delete(), "the empty file is still held open")
  }

  // -----------------------------------------------------------------------------------------
  // Files that are not images
  // -----------------------------------------------------------------------------------------

  @Test
  fun declinesBytesWithNoRecognisableHeader() = runTest {
    assertNull(createRegionDecoder(CropSource.Bytes(ByteArray(64) { 0x7A }, "junk")))
  }

  @Test
  fun declinesAFileWhoseContentIsNotAnImage() = runTest {
    val text =
      file("notes", "this is a text file, not a photograph\n".repeat(8).encodeToByteArray())

    assertNull(createRegionDecoder(CropSource.FilePath(text.absolutePath)))
    assertTrue(text.delete(), "the rejected file is still held open")
  }

  /**
   * A header that says JPEG over bytes that are not.
   *
   * This is the one that reaches furthest: [ImageProbe] recognises the container from the first
   * bytes, the format supports region decoding, so a reader is opened, and only then does ImageIO
   * fail. That is the `catch` around `openDecoder`, and without it the failure would be an
   * exception thrown out of a coroutine rather than a null.
   */
  @Test
  fun declinesAFileThatLiesAboutItsFormat() = runTest {
    val real = TestImages.jpegBytes()
    val truncated = real.copyOf(real.size / 4)

    val decoder = createRegionDecoder(CropSource.Bytes(truncated, "truncated"))

    // Either it declines outright, or it opens and fails at the first read. Both are acceptable;
    // an exception escaping is not, and reaching this line at all is the assertion.
    if (decoder != null) {
      decoder.use {
        assertNull(it.decodeRegion(ImageRegion.of(it.imageSize), 1)?.also { r -> r.close() })
      }
    }
  }

  @Test
  fun declinesAFileWithAnImageHeaderAndNothingElse() = runTest {
    val header = TestImages.pngBytes().copyOf(32)
    val stub = file("header-only", header)

    val decoder = createRegionDecoder(CropSource.FilePath(stub.absolutePath))

    if (decoder != null) decoder.close()
    assertTrue(stub.delete(), "the rejected file is still held open")
  }

  // -----------------------------------------------------------------------------------------
  // The control
  // -----------------------------------------------------------------------------------------

  /**
   * Without this, every assertion above is satisfied by a decoder that declines everything.
   *
   * It is the same call, on the same kind of path, differing only in the bytes behind it.
   */
  @Test
  fun stillOpensARealFileOnTheSamePath() = runTest {
    val good = file("real.png", TestImages.pngBytes())

    val decoder = assertNotNull(
      createRegionDecoder(CropSource.FilePath(good.absolutePath)),
      "a real PNG on disk was declined, so the rejections above prove nothing",
    )

    decoder.use {
      assertEquals(ImageSize(TestImages.WIDTH, TestImages.HEIGHT), it.imageSize)
      assertEquals(ImageFormat.PNG, it.format)
      val region = assertNotNull(it.decodeRegion(ImageRegion.of(it.imageSize), 1))
      region.use { decoded ->
        assertEquals(TestImages.WIDTH, decoded.width)
      }
    }
  }
}
