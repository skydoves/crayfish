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
import kotlin.random.Random
import kotlin.test.assertTrue

/**
 * Every float comparison in this package states its tolerance explicitly, because the alternative
 * (an `assertEquals` on two floats) either passes by luck or fails on a platform whose libm rounds
 * the last bit differently. Each call site says which tolerance it chose and why.
 */
internal fun assertClose(expected: Float, actual: Float, tolerance: Float, message: String = "") {
  assertTrue(
    actual.isFinite() && abs(expected - actual) <= tolerance,
    "$message: expected $expected but was $actual (tolerance $tolerance)",
  )
}

internal fun assertClose(
  expected: FloatPoint,
  actual: FloatPoint,
  tolerance: Float,
  message: String = "",
) {
  assertTrue(
    actual.isFinite && expected.isCloseTo(actual, tolerance),
    "$message: expected $expected but was $actual (tolerance $tolerance)",
  )
}

internal fun assertClose(
  expected: FloatRect,
  actual: FloatRect,
  tolerance: Float,
  message: String = "",
) {
  assertClose(expected.left, actual.left, tolerance, "$message left")
  assertClose(expected.top, actual.top, tolerance, "$message top")
  assertClose(expected.right, actual.right, tolerance, "$message right")
  assertClose(expected.bottom, actual.bottom, tolerance, "$message bottom")
}

/** Asserts that nothing derived from this value is a NaN or an infinity waiting to be drawn. */
internal fun assertFinite(point: FloatPoint, message: String = "") {
  assertTrue(point.isFinite, "$message: $point is not finite")
}

internal fun assertFinite(rect: FloatRect, message: String = "") {
  assertTrue(rect.isFinite, "$message: $rect is not finite")
}

internal fun assertFinite(transform: CropTransform, message: String = "") {
  assertTrue(
    transform.scale.isFinite() && transform.rotationDegrees.isFinite() && transform.offset.isFinite,
    "$message: $transform is not finite",
  )
}

/** `kotlin.random.Random` offers this for `Double` and `Int` but not for `Float`. */
internal fun Random.nextFloat(from: Float, until: Float): Float =
  from + nextFloat() * (until - from)
