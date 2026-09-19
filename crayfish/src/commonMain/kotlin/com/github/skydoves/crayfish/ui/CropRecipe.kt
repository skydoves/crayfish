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

import androidx.compose.runtime.Immutable
import com.github.skydoves.crayfish.geometry.CropTransform
import com.github.skydoves.crayfish.geometry.FloatPoint
import com.github.skydoves.crayfish.geometry.FloatRect

/**
 * A crop written down as numbers, so it can be stored and edited again later.
 *
 * ## What it is for
 *
 * Every cropper in this category hands back a bitmap and forgets. The crop becomes pixels, the
 * original is gone, and "let me fix that crop from last week" means starting over from whatever
 * survived. Crayfish never held the pixels in the first place, it held a reference to the source and
 * a rectangle over it, and this is that rectangle made durable.
 *
 * ```kotlin
 * // When the user is happy with it.
 * database.save(photoId, state.recipe.encodeToString())
 *
 * // A week later, on the original file, exactly where they left off.
 * val recipe = CropRecipe.decodeFromString(database.load(photoId))
 * val state = rememberCropState(source, initialRecipe = recipe)
 * ```
 *
 * Eleven floats. Nothing here is pixels, so storing one costs a row rather than a file, and the
 * crop stays non destructive: the original is never rewritten, and a different recipe over the same
 * source is a different crop with no loss between them.
 *
 * ## What it does not carry
 *
 * [CropState.shape] and [CropStyle], for the same reason neither survives process death:
 * [CropShape.Custom] carries a lambda, and both are configuration the caller supplies on the next
 * composition anyway. A recipe describes where the crop is, not what it is drawn with.
 *
 * It is also tied to the source it was taken from. The rectangle is a fraction of the viewport and
 * the transform is relative to the image's fitted bounds, so both survive a change of screen size
 * or density, and neither means anything against a different photo.
 */
@Immutable
public class CropRecipe(
  /** Pan, zoom, rotation and mirroring, exactly as the user left them. */
  public val transform: CropTransform,
  /** The crop rectangle as fractions of the viewport, which is what makes it resolution free. */
  public val normalizedCropRect: FloatRect,
  /** The ratio the rectangle was locked to, or [AspectRatio.Free]. */
  public val aspectRatio: AspectRatio,
) {

  /**
   * A single line, safe for a database column, a preference or a query parameter.
   *
   * Versioned by the prefix so a reader can tell a recipe it understands from one it does not, and
   * [decodeFromString] refuses rather than guessing at anything else.
   */
  public fun encodeToString(): String = buildString {
    append(PREFIX)
    encodeTo(this@CropRecipe).forEach { value ->
      append(':')
      append(value)
    }
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is CropRecipe) return false
    return transform == other.transform &&
      normalizedCropRect == other.normalizedCropRect &&
      aspectRatio == other.aspectRatio
  }

  override fun hashCode(): Int {
    var result = transform.hashCode()
    result = 31 * result + normalizedCropRect.hashCode()
    result = 31 * result + aspectRatio.hashCode()
    return result
  }

  override fun toString(): String = encodeToString()

  public companion object {

    /**
     * Reads back what [encodeToString] wrote.
     *
     * @return the recipe, or `null` for anything this version does not recognise: a different
     *   prefix, the wrong number of fields, or a value that is not a finite number. A null is the
     *   only safe answer, because the alternative is opening a crop somewhere the user never put it.
     */
    public fun decodeFromString(encoded: String?): CropRecipe? {
      val parts = encoded?.split(':') ?: return null
      if (parts.size != FIELD_COUNT + 1 || parts[0] != PREFIX) return null
      val values = parts.drop(1).map { it.toFloatOrNull() ?: return null }
      return decodeFrom(values)
    }

    /** The stored form, shared with `RealCropState.Saver` so there is one format and not two. */
    internal fun encodeTo(recipe: CropRecipe): List<Float> = listOf(
      recipe.transform.scale,
      recipe.transform.offset.x,
      recipe.transform.offset.y,
      recipe.transform.rotationDegrees,
      if (recipe.transform.flipHorizontal) 1f else 0f,
      if (recipe.transform.flipVertical) 1f else 0f,
      recipe.normalizedCropRect.left,
      recipe.normalizedCropRect.top,
      recipe.normalizedCropRect.right,
      recipe.normalizedCropRect.bottom,
      // A ratio is finite and positive, so a negative stands in for AspectRatio.Free with no
      // ambiguity and no second list.
      (recipe.aspectRatio as? AspectRatio.Fixed)?.ratio ?: FREE_ASPECT_RATIO,
    )

    internal fun decodeFrom(values: List<Float>): CropRecipe? {
      if (values.size != FIELD_COUNT) return null
      // The rectangle is the crop. A non-finite or empty one has no sensible repair and opening on
      // a guess would be worse than opening on the default, so it is refused.
      val rect = FloatRect(values[6], values[7], values[8], values[9])
      if (!rect.isFinite || rect.isEmpty) return null
      // The transform is only how that rectangle is being looked at, and `sanitized` has a defined
      // answer for every broken value: the fit. Refusing here would throw away a rectangle the user
      // chose because of a number they never see.
      return CropRecipe(
        transform = CropTransform(
          scale = values[0],
          offset = FloatPoint(values[1], values[2]),
          rotationDegrees = values[3],
          flipHorizontal = values[4] != 0f,
          flipVertical = values[5] != 0f,
        ).sanitized(),
        normalizedCropRect = rect,
        aspectRatio = values[10]
          .takeIf { it.isFinite() && it > 0f }
          ?.let { AspectRatio.Fixed(it) }
          ?: AspectRatio.Free,
      )
    }

    private const val PREFIX = "crayfish1"
    private const val FIELD_COUNT = 11

    /** Stored in place of a ratio when the rectangle is unconstrained. */
    private const val FREE_ASPECT_RATIO = -1f
  }
}
