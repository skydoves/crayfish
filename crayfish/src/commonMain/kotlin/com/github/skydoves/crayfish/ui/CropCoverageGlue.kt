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
package com.github.skydoves.crayfish.ui

import com.github.skydoves.crayfish.geometry.CropCoverage
import com.github.skydoves.crayfish.geometry.CropTransform
import com.github.skydoves.crayfish.geometry.FloatRect

/**
 * Puts the image back under the crop rectangle after a gesture moved it out.
 *
 * A single entry point on purpose. Every mutation that can uncover a corner (a pan, a pinch, a
 * rotation, a handle drag, a restore into a different viewport) has to end here, and a list of
 * call sites is only auditable if they all name the same function.
 *
 * The correction itself is uCrop's: translate first and grow the scale only by the deficit, so
 * nudging the rotation dial does not make the image visibly jump in size on every degree.
 */
internal fun recoverCropCoverage(
  transform: CropTransform,
  contentBounds: FloatRect,
  cropRect: FloatRect,
): CropTransform = CropCoverage.correct(
  transform = transform,
  contentBounds = contentBounds,
  coverRegion = cropRect,
).transform
