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

/**
 * When the composition guides are drawn inside the crop rectangle.
 *
 * Two independently written croppers (CanHub's `Guidelines` and CropMarker's `gridLinesBehavior`)
 * arrived at exactly these three, which is good evidence that [OnTouch] is the right default:
 * guides help while something is being moved and are clutter when it is not.
 */
public enum class CropGridMode {
  Never,
  Always,
  OnTouch,
}
