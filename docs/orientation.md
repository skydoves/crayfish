# Orientation and Exif

## Why this is its own page

An Exif `Orientation` tag has eight values, and **four of them are mirrored**: `FLIP_HORIZONTAL`,
`FLIP_VERTICAL`, `TRANSPOSE`, and `TRANSVERSE`. Handling only the rotations produces an image that is
upright and silently back to front. Nobody notices until a face or a word appears reversed, which
is usually after it has been uploaded.

This is the most common Exif bug in the ecosystem, and it is easy to ship: a test suite that
exercises rotations alone passes with the mirror step deleted. Crayfish's own suite proves this.
Removing the mirror turns exactly the four mirrored cases red and leaves every rotation test green.

## The convention, stated once

Applying an orientation means: **rotate clockwise by `rotationDegrees`, then mirror horizontally if
`isMirrored`.**

That order is not interchangeable. Swapping it turns `TRANSPOSE` into `TRANSVERSE`. The tests pin it
against a labelled, asymmetric grid, because on a symmetric fixture a mirror is indistinguishable
from the identity.

## HEIF declares it twice, and the two disagree

HEIF can carry orientation in its container's own `irot` / `imir` property boxes **and** in an Exif
block. Writers disagree about which to use: iOS's Photos editor sets the Exif tag and writes no
transform boxes, while other tools do the opposite. Reading only one source is wrong for a large
share of files in circulation.

Crayfish reads both and applies a documented precedence: **when a transform box is present the
container wins; otherwise Exif is consulted.** The reasoning is that `irot`/`imir` are normative for
the container, so a decoder that already honoured them has produced pixels the Exif tag would rotate
a second time. A file carrying both and meaning different things is already malformed, so picking
the normative half is the defensible choice rather than a coin flip.

`irot` is specified in **counter-clockwise** quarter turns while this library's rotation is
clockwise, which is the kind of sign flip that ships inverted; the conversion has its own test.

## Off Android, nothing does this for you

Coil 3 applies no Exif at all on iOS, desktop or the web. Its Skia decoder calls
`Image.makeFromEncoded` with no orientation step, and `ExifOrientationStrategy` exists only in its
Android source set.

Crayfish's reader is pure common Kotlin with no dependencies, so the same code runs on every target.
The shared tests run in six places rather than only on the JVM: `desktopTest`, `testAndroidHostTest`,
`connectedAndroidDeviceTest` on a real device, `iosSimulatorArm64Test`, `macosArm64Test`, and
`wasmJsBrowserTest` in headless Chrome.

## What you see in the API

`CropState.imageSize` is the source's size **after** orientation is applied, and every coordinate in
the public API lives in that space. A quarter turn swaps the file's own width and height, so
exposing the raw dimensions would make every rectangle a caller builds silently wrong for half of
all camera photos.

`RegionDecoder.appliedOrientation` reports what a platform baked in during decode. It is `NORMAL`
everywhere except the web, where a browser applies the tag itself and does not always honour the
request to stop. Without that field, a caller applying the orientation would rotate such an image
twice.
