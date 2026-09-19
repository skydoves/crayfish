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
package com.github.skydoves.crayfish.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/**
 * The journey both the generator and the benchmarks drive.
 *
 * Written once, for a reason that matters more than tidiness: a baseline profile is only worth the
 * code paths it actually walked. A generator that launches the app and stops produces a profile
 * covering the launcher activity, which is the one part of this library nobody is waiting on. What
 * costs time here is opening a source, decoding tiles, dragging the frame and encoding the result,
 * so the journey does all four, and the benchmark measures the same thing the profile optimises.
 */
internal object CropperJourney {

  /** Long enough for a decode on a cold emulator, short enough that a hang is still a failure. */
  private const val TIMEOUT_MILLIS = 20_000L

  /**
   * Waits for [selector] and returns it, or fails saying what was not there.
   *
   * Never `findObject(...)?.click()`. That is a silent no-op when nothing matches, and the first
   * version of this journey did exactly that: every step "succeeded", the generator reported
   * success, and the profile it produced had 11,756 rules and **not one** for the cropper, because
   * the run never left the gallery. A step that cannot do its job has to say so.
   */
  private fun MacrobenchmarkScope.require(selector: BySelector, what: String): UiObject2 =
    checkNotNull(device.wait(Until.findObject(selector), TIMEOUT_MILLIS)) {
      "the journey could not find $what, so everything after it would have been skipped"
    }

  /** Waits for the gallery, which is the first thing the demo shows. */
  fun MacrobenchmarkScope.awaitGallery() {
    require(By.textContains("Basics"), "the demo gallery")
  }

  /**
   * Opens the first demo and waits for the photo to be on screen.
   *
   * The status line reports the decoded size once the source is open, so waiting for it waits for a
   * real decode rather than for a layout pass over an empty cropper.
   */
  fun MacrobenchmarkScope.openBasicsAndWaitForTheImage() {
    require(By.textContains("Basics"), "the Basics card").click()
    require(By.textContains("NORMAL"), "the decoded image's status line")
    device.waitForIdle()
  }

  /**
   * Drags the crop frame, which is what exercises the gesture and tile paths.
   *
   * Against the cropper's own bounds rather than the whole display: the screen scrolls now, and a
   * swipe measured from the display would start on the toolbar or the code sample instead.
   */
  fun MacrobenchmarkScope.dragTheCropFrame() {
    val cropper = require(By.desc("Crop area"), "the crop rectangle").visibleBounds
    repeat(3) {
      device.swipe(cropper.centerX(), cropper.centerY(), cropper.left + 24, cropper.top + 24, 12)
      device.waitForIdle()
    }
  }

  /**
   * Presses Crop and waits for the result, which is the decode and encode path.
   *
   * The oracle is the result image's description, not the size caption it prints underneath. The
   * caption sits below an image that is up to 320.dp tall, so on a tall phone it lands past the
   * bottom edge and never appears in the hierarchy: waiting for "KB" failed both cropping
   * benchmarks on an S23 while the crop itself had succeeded every time. The description exists
   * exactly when the panel holds a decoded crop, which is the thing being waited for.
   *
   * Scrolls while it waits, because on a shorter screen the panel does start below the fold.
   */
  fun MacrobenchmarkScope.cropAndWaitForTheResult() {
    require(By.textContains("Crop to bytes"), "the crop button").click()

    val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
    while (System.currentTimeMillis() < deadline) {
      if (device.hasObject(By.desc("Cropped result"))) {
        device.waitForIdle()
        return
      }
      device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 0.4f)
      device.waitForIdle()
    }
    error("the crop produced no result in ${TIMEOUT_MILLIS}ms, or it never came into view")
  }

  /** Scrolls the demo screen, which is the other thing a user does on it. */
  fun MacrobenchmarkScope.scrollTheScreen() {
    device.findObject(By.scrollable(true))?.fling(Direction.DOWN)
    device.waitForIdle()
  }
}
