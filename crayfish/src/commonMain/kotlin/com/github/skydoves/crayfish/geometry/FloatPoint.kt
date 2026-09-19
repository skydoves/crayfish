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

import kotlin.math.abs

/**
 * A point, or equivalently a translation, in some continuous coordinate space.
 *
 * Deliberately not `androidx.compose.ui.geometry.Offset`, for the same two reasons the `decode`
 * package avoids `IntSize`: this layer must be unit-testable without standing up a composition,
 * and the name must not collide with the Compose type in the UI layer above it, which would force
 * an import alias at every call site.
 *
 * Which space a value lives in is never encoded in the type; it is stated by the parameter name
 * and the KDoc of whatever consumes it. [CoordinateSpace] is the one place that converts between
 * spaces, so there is a single place to get it wrong rather than one per call site.
 */
public data class FloatPoint(public val x: Float, public val y: Float) {

  /** Whether both components are finite, i.e. this point can be drawn or measured. */
  public val isFinite: Boolean get() = x.isFinite() && y.isFinite()

  public operator fun plus(other: FloatPoint): FloatPoint = FloatPoint(x + other.x, y + other.y)

  public operator fun minus(other: FloatPoint): FloatPoint = FloatPoint(x - other.x, y - other.y)

  public operator fun times(factor: Float): FloatPoint = FloatPoint(x * factor, y * factor)

  /**
   * This point with every non-finite component replaced by zero.
   *
   * A NaN that reaches a transform propagates into every value derived from it and is only noticed
   * once something refuses to draw. Compose's own `VelocityTracker` shipped exactly that bug, so
   * every entry point in this package sanitises rather than trusting its caller.
   */
  public fun sanitized(): FloatPoint = if (isFinite) {
    this
  } else {
    FloatPoint(x = if (x.isFinite()) x else 0f, y = if (y.isFinite()) y else 0f)
  }

  /** Whether this point is within [tolerance] of [other] on both axes. */
  public fun isCloseTo(other: FloatPoint, tolerance: Float): Boolean =
    abs(x - other.x) <= tolerance && abs(y - other.y) <= tolerance

  public companion object {
    public val Zero: FloatPoint = FloatPoint(0f, 0f)
  }
}
