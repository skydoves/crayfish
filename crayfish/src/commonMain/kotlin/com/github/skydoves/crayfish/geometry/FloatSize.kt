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
package com.github.skydoves.crayfish.geometry

import com.github.skydoves.crayfish.decode.ImageSize

/**
 * A size in a continuous coordinate space, such as a viewport or a laid-out crop frame.
 *
 * The integer counterpart is [ImageSize], which measures an image's own pixels. The two are not
 * interchangeable and the difference is load-bearing: a viewport is measured in whatever unit the
 * host lays out in, and the ratio between the two is the only thing that turns an on-screen
 * rectangle into a decodable one. [CoordinateSpace] holds that ratio.
 */
public data class FloatSize(public val width: Float, public val height: Float) {

  /** A non-positive dimension bounds nothing, and dividing by it produces infinities. */
  public val isEmpty: Boolean get() = !(width > 0f) || !(height > 0f)

  public val isFinite: Boolean get() = width.isFinite() && height.isFinite()

  /** The centre of a rectangle of this size whose top-left corner is the origin. */
  public val center: FloatPoint get() = FloatPoint(width / 2f, height / 2f)

  public companion object {
    public val Zero: FloatSize = FloatSize(0f, 0f)
  }
}

/** This image's pixel dimensions as a continuous size, for measuring against a viewport. */
public fun ImageSize.toFloatSize(): FloatSize = FloatSize(width.toFloat(), height.toFloat())
