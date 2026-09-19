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

import java.io.File

/**
 * The oversized source images the end-to-end tests run against, written by the
 * `generateImageFixtures` Gradle task into the build directory.
 *
 * They are generated outside the test JVM on purpose: building a 108MP image costs hundreds of
 * megabytes, and the test JVM's heap is deliberately too small for that. Paying the cost here would
 * quietly remove the constraint the tests exist to enforce.
 */
internal object Fixtures {

  private val directory: File by lazy {
    val path = System.getProperty("crayfish.fixtures")
      ?: error(
        "System property 'crayfish.fixtures' is not set. The desktopTest task sets it; running " +
          "these tests outside Gradle needs it pointed at the generated fixture directory.",
      )
    File(path).also {
      check(it.isDirectory) { "fixture directory does not exist: $it" }
    }
  }

  fun file(name: String): File = File(directory, name).also {
    check(it.isFile && it.length() > 0) {
      "missing fixture $name; run :crayfish:generateImageFixtures"
    }
  }

  /** 12000x9000. 1.8MB encoded, 412MiB decoded as ARGB_8888: the shape that crashes croppers. */
  val sensor108mp: File get() = file("sensor-108mp.jpg")

  /** 8000x6000, 183MiB decoded. */
  val sensor48mp: File get() = file("sensor-48mp.jpg")

  /** 28000x2000: an aspect ratio that has its own open crash reports. */
  val panorama: File get() = file("panorama-1x14.jpg")

  /** 3840x2160 PNG with an alpha channel, small enough to decode whole. */
  val uhdPng: File get() = file("uhd.png")

  /** 5000x5000, 95MiB decoded. Square, where a swapped width and height leaves no trace. */
  val square5000: File get() = file("square-5000.jpg")

  /** 2000x28000: the panorama transposed, so row skipping is exercised as well as column skipping. */
  val tall: File get() = file("tall-2x28.jpg")

  /** 1x32767. One pixel wide, and as tall as Android hands back in one bitmap. */
  val sliver: File get() = file("sliver-1x32767.jpg")
}
