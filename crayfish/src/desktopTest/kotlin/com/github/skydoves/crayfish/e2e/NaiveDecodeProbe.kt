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
package com.github.skydoves.crayfish.e2e

import javax.imageio.ImageIO

/**
 * Decodes an image the obvious way (whole, at full resolution) and reports what happened.
 *
 * This is the control the end-to-end memory tests are measured against, and it runs in a **child
 * JVM** rather than the test JVM. Running out of memory is the expected outcome, and an
 * `OutOfMemoryError` raised inside the test process would leave every later test running on a
 * damaged heap. A child process just dies, and its exit code is the result.
 */
internal object NaiveDecodeProbe {
  const val EXIT_DECODED = 0
  const val EXIT_OUT_OF_MEMORY = 42
  const val EXIT_FAILED = 43
}

fun main(args: Array<String>) {
  if (args.isEmpty()) {
    println("usage: NaiveDecodeProbe <image-file>")
    kotlin.system.exitProcess(NaiveDecodeProbe.EXIT_FAILED)
  }
  val exitCode = try {
    val image = ImageIO.read(java.io.File(args[0]))
    if (image == null) {
      println("decoder returned null")
      NaiveDecodeProbe.EXIT_FAILED
    } else {
      println("decoded ${image.width}x${image.height}")
      NaiveDecodeProbe.EXIT_DECODED
    }
  } catch (error: OutOfMemoryError) {
    println("out of memory: ${error.message}")
    NaiveDecodeProbe.EXIT_OUT_OF_MEMORY
  } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
    // ImageIO wraps the failure differently depending on where the allocation gave out.
    val causedByOom = generateSequence(error) { it.cause }.any { it is OutOfMemoryError }
    println("${if (causedByOom) "out of memory (wrapped)" else "failed"}: $error")
    if (causedByOom) NaiveDecodeProbe.EXIT_OUT_OF_MEMORY else NaiveDecodeProbe.EXIT_FAILED
  }
  kotlin.system.exitProcess(exitCode)
}
