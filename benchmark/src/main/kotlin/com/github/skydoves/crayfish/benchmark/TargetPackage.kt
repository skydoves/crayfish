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

import androidx.test.platform.app.InstrumentationRegistry

/**
 * The app under test, as the build told the runner rather than as a string typed twice.
 *
 * `targetAppId` is set from the tested variant's own manifest in `build.gradle.kts`, so renaming the
 * demo's applicationId cannot leave a benchmark pointing at a package that is no longer installed.
 */
internal fun targetPackage(): String =
  InstrumentationRegistry.getArguments().getString("targetAppId")
    ?: error(
      "targetAppId was not passed to the runner: the benchmark module's androidComponents block " +
        "should set it from the tested variant",
    )
