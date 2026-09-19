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
package com.github.skydoves.crayfish.decode

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A fixture built so that no symmetry can hide a bug.
 *
 * The four quadrant colours are all different, all fully saturated on a single channel where
 * possible, and laid out on a non-square canvas. That combination means a horizontal flip, a
 * vertical flip, a transposed width and height, and a red/blue channel swap each land on a colour
 * the assertions do not expect. None of them can pass by accident.
 */
internal object TestImages {

  const val WIDTH: Int = 64
  const val HEIGHT: Int = 48

  /** Top-left. Pure red is also the channel-order canary: BGRA read as RGBA turns it blue. */
  const val TOP_LEFT: Int = 0xFFFF0000.toInt()

  const val TOP_RIGHT: Int = 0xFF00FF00.toInt()

  /** Bottom-left. Pure blue is the other half of the channel-order canary. */
  const val BOTTOM_LEFT: Int = 0xFF0000FF.toInt()

  /** Bottom-right. Yellow rather than a third primary, so two channels are exercised at once. */
  const val BOTTOM_RIGHT: Int = 0xFFFFFF00.toInt()

  fun colourAt(x: Int, y: Int): Int = when {
    x < WIDTH / 2 && y < HEIGHT / 2 -> TOP_LEFT
    x >= WIDTH / 2 && y < HEIGHT / 2 -> TOP_RIGHT
    x < WIDTH / 2 -> BOTTOM_LEFT
    else -> BOTTOM_RIGHT
  }

  /** The fixture as an ARGB image. */
  fun quadrants(): BufferedImage {
    val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until HEIGHT) {
      for (x in 0 until WIDTH) {
        image.setRGB(x, y, colourAt(x, y))
      }
    }
    return image
  }

  /**
   * An image whose alpha varies, for pinning that the decode path stays un-premultiplied.
   *
   * Half-alpha red and quarter-alpha teal are the giveaways: multiplying a channel by an alpha of
   * 0x80 or 0x40 and dividing it back out does not round-trip through 8 bits, so a `PREMUL` bitmap
   * comes back visibly off rather than exactly equal.
   */
  fun translucent(): BufferedImage {
    val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until 4) {
      for (x in 0 until 4) {
        image.setRGB(x, y, if (x < 2) 0x80FF0000.toInt() else 0x40008080.toInt())
      }
    }
    return image
  }

  /** [image] encoded by ImageIO, so the decoder under test is fed bytes it did not produce. */
  fun encode(image: BufferedImage, format: String): ByteArray {
    val out = ByteArrayOutputStream()
    assertTrue(ImageIO.write(image, format, out), "ImageIO cannot write $format on this JDK")
    return out.toByteArray()
  }

  fun pngBytes(image: BufferedImage = quadrants()): ByteArray = encode(image, "png")

  /** The quadrant fixture as an opaque JPEG, which decodes through a `TYPE_3BYTE_BGR` raster. */
  fun jpegBytes(): ByteArray {
    val opaque = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
    val graphics = opaque.createGraphics()
    graphics.drawImage(quadrants(), 0, 0, null)
    graphics.dispose()
    return encode(opaque, "jpg")
  }

  fun source(bytes: ByteArray, key: String = "test"): CropSource = CropSource.Bytes(bytes, key)
}

/**
 * The pixel at ([x], [y]) of a decoded region.
 *
 * [DecodedRegion] already stands in for its image on size and lifetime, so tests read pixels
 * through it too rather than reaching past it at every assertion.
 */
internal fun DecodedRegion.pixelAt(x: Int, y: Int): Int = image.pixelAt(x, y)

/** The pixel at ([x], [y]), read back through the public API. */
internal fun PlatformImage.pixelAt(x: Int, y: Int): Int {
  val pixels = requireNotNull(readArgbPixels()) { "readArgbPixels returned null" }
  assertEquals(width * height, pixels.size, "unexpected pixel buffer size")
  return pixels[y * width + x]
}

/** Compares as unsigned hex, so a failure names the colours instead of two negative integers. */
internal fun assertColour(expected: Int, actual: Int, message: String) {
  assertEquals(
    expected.toUInt().toString(16),
    actual.toUInt().toString(16),
    message,
  )
}
