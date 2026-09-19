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
package com.github.skydoves.crayfish.readme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every Kotlin block in the README compiles against the published API.
 *
 * `ReadmeSnippets.kt` holds those blocks verbatim inside functions, so the compiler checks them on
 * every build. This test is the other half: it reads the README and asserts each block is actually
 * in that file, because a snippet the file never picked up would be compiled by nobody and the
 * green would mean nothing.
 *
 * It was written after a rename went out with a README still calling the old parameter name. The
 * rename was correct, the tests were green, and the first code a reader would have copied did not
 * compile.
 *
 * This lives in `crayfish-landscapist` rather than `crayfish` because the README documents both
 * artifacts and this is the module that can see both.
 */
class ReadmeSnippetsTest {

  @Test
  fun everyKotlinBlockInTheReadmeIsCompiledBySnippetsFile() {
    val readme = findUpwards("README.md")
    // Both files, because the Activity snippets live in their own package to avoid colliding with
    // the Compose ones. Checking only the first would let every Activity block go uncompiled while
    // this test stayed green, which is the exact failure it exists to prevent.
    val compiled = listOf("ReadmeSnippets.kt", "ReadmeSnippetsActivity.kt")
      .map { name ->
        findUpwards(
          "crayfish-landscapist/src/desktopTest/kotlin/com/github/skydoves/crayfish/readme/$name",
        ).readText()
      }

    val blocks = KOTLIN_BLOCK.findAll(readme.readText()).map { it.groupValues[1].trim() }.toList()
    assertTrue(
      blocks.size >= MINIMUM_BLOCKS,
      "found only ${blocks.size} Kotlin blocks in the README: this check is looking at the wrong " +
        "file or the fence pattern has changed",
    )

    val missing = blocks.filter { block -> compiled.none { block in it } }
    if (missing.isNotEmpty()) {
      fail(
        buildString {
          append(missing.size)
          append(" of ")
          append(blocks.size)
          append(" README Kotlin blocks are in neither snippets file, so nothing compiles them. ")
          append("Add each one as its own function, verbatim.\n\n")
          missing.forEach { append("--- missing ---\n").append(it).append("\n\n") }
        },
      )
    }
  }

  /**
   * Walks up from the working directory.
   *
   * Gradle runs a test with the module directory as the working directory, but that is a default
   * rather than a guarantee, and a path relative to it would fail somewhere else in a way that read
   * as a README problem.
   */
  private fun findUpwards(relative: String): File {
    var directory: File? = File(".").absoluteFile
    while (directory != null) {
      val candidate = File(directory, relative)
      if (candidate.isFile) return candidate
      directory = directory.parentFile
    }
    error("could not find $relative from ${File(".").absolutePath}")
  }
}

private val KOTLIN_BLOCK = Regex("```kotlin\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)

/** A floor rather than an exact count, so adding documentation does not fail the build. */
private const val MINIMUM_BLOCKS = 14
