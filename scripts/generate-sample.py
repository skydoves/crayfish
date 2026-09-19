#!/usr/bin/env python3
"""
Draws the demo's bundled sample photo.

It exists because the picture it draws used to be an opaque blob. The library was renamed and the
old name stayed painted into the middle of the demo's own screen for weeks, because nothing in the
repository could redraw it and nobody could grep for it: the only copy of that word was pixels.

The picture is deliberately synthetic. A cropper demo needs an image whose orientation and position
can be checked by eye, so the gradient runs top to bottom, the grid gives a ruler, and the two
markers name the corners they sit in. A photograph would look nicer and prove nothing.

    python3 scripts/generate-sample.py

Requires Pillow. Rerun it and commit the result if the wording or the layout changes.
"""
from __future__ import annotations

import pathlib
import sys

from PIL import Image, ImageDraw, ImageFont

OUT = (
    pathlib.Path(__file__).resolve().parent.parent
    / "samples-shared/src/commonMain/composeResources/files/sample.jpg"
)

WIDTH, HEIGHT = 1600, 1200
# Fitted off the original by least squares, sampling only columns that are not grid lines. The
# first attempt read the endpoints straight off x=800, which is a multiple of the grid spacing, so
# it measured the grid rather than the gradient and every pixel of the redraw came out wrong.
TOP_COLOUR = (40, 209, 200)
BOTTOM_COLOUR = (219, 90, 80)
GRID_SPACING = 100
GRID_ALPHA = 0.49
LABEL = "crayfish"

# The corner markers. A crop that comes back mirrored or rotated shows it here and nowhere else,
# which is the whole reason the sample is not a photograph.
TL_BOX = (60, 60, 220, 150)
BR_CENTRE, BR_RADIUS = (1398, 998), 118
BR_COLOUR = (250, 200, 41)

# Verdana Bold at 65 reproduces the original label's ink box to within 3px on both axes. Any bold
# humanist sans does the job; this one is on every macOS install, which keeps the script runnable.
FONT_CANDIDATES = (
    "/System/Library/Fonts/Supplemental/Verdana Bold.ttf",
    "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
)


def font(size: int) -> ImageFont.FreeTypeFont:
    for path in FONT_CANDIDATES:
        try:
            return ImageFont.truetype(path, size)
        except OSError:
            continue
    sys.exit(
        "no bold sans font found. Tried:\n  " + "\n  ".join(FONT_CANDIDATES) +
        "\nInstall one of them, or add the path of another bold sans to FONT_CANDIDATES."
    )


def main() -> int:
    image = Image.new("RGB", (WIDTH, HEIGHT))
    draw = ImageDraw.Draw(image)

    for y in range(HEIGHT):
        t = y / (HEIGHT - 1)
        draw.line(
            [(0, y), (WIDTH, y)],
            fill=tuple(round(a + (b - a) * t) for a, b in zip(TOP_COLOUR, BOTTOM_COLOUR)),
        )

    # White at partial alpha rather than a flat colour, so the grid reads over both ends of the
    # gradient instead of vanishing into the pale top.
    for x in range(0, WIDTH, GRID_SPACING):
        for y in range(HEIGHT):
            base = image.getpixel((x, y))
            image.putpixel((x, y), tuple(round(c + (255 - c) * GRID_ALPHA) for c in base))
    for y in range(0, HEIGHT, GRID_SPACING):
        for x in range(WIDTH):
            base = image.getpixel((x, y))
            image.putpixel((x, y), tuple(round(c + (255 - c) * GRID_ALPHA) for c in base))

    draw.rectangle(TL_BOX, fill=(255, 255, 255))
    corner = font(56)
    draw.text(
        ((TL_BOX[0] + TL_BOX[2]) / 2, (TL_BOX[1] + TL_BOX[3]) / 2),
        "TL",
        font=corner,
        fill=(20, 20, 20),
        anchor="mm",
    )

    draw.ellipse(
        [
            BR_CENTRE[0] - BR_RADIUS,
            BR_CENTRE[1] - BR_RADIUS,
            BR_CENTRE[0] + BR_RADIUS,
            BR_CENTRE[1] + BR_RADIUS,
        ],
        fill=BR_COLOUR,
    )
    draw.text(BR_CENTRE, "BR", font=corner, fill=(20, 20, 20), anchor="mm")

    draw.text((WIDTH / 2, HEIGHT / 2), LABEL, font=font(65), fill=(255, 255, 255), anchor="mm")

    # Quality 75 at 4:2:0, which is what the original was encoded at: it lands within 3KB of the
    # file it replaces, and every other setting tried moved further from the original's own pixels
    # rather than closer. A demo asset ships in every consumer's APK, so the size is not free.
    image.save(OUT, "JPEG", quality=75, subsampling=2)
    print(f"wrote {OUT.relative_to(pathlib.Path.cwd())} {image.size[0]}x{image.size[1]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
