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
 * The part of the crop rectangle a drag has hold of.
 *
 * [Inside] is listed with the edges because moving the whole rectangle is the same gesture from the
 * user's point of view, and keeping it in the same enum means the hit test returns one answer
 * rather than an edge plus a separate "or the middle" flag.
 */
internal enum class CropHandle {
  TopLeft,
  TopRight,
  BottomLeft,
  BottomRight,
  Top,
  Bottom,
  Left,
  Right,
  Inside,
  ;

  val movesLeftEdge: Boolean
    get() = this == TopLeft || this == BottomLeft || this == Left || this == Inside

  val movesRightEdge: Boolean
    get() = this == TopRight || this == BottomRight || this == Right || this == Inside

  val movesTopEdge: Boolean
    get() = this == TopLeft || this == TopRight || this == Top || this == Inside

  val movesBottomEdge: Boolean
    get() = this == BottomLeft || this == BottomRight || this == Bottom || this == Inside

  val isCorner: Boolean
    get() = this == TopLeft || this == TopRight || this == BottomLeft || this == BottomRight
}
