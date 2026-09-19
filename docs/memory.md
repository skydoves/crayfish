# Large images and memory

## The arithmetic

| Source | Pixels | Decoded as ARGB_8888 |
|---|---|---|
| 48MP | 8000 × 6000 | 183 MiB |
| 108MP (ISOCELL HM2) | 12000 × 9000 | **412 MiB** |
| 200MP (ISOCELL HP2) | 16384 × 12288 | **768 MiB** |

Android's hardware accelerated canvas refuses to draw a bitmap past a device property that defaults
to roughly 100MB, throwing `Canvas: trying to draw too large bitmap`. So the two largest rows are
not slow. They are undrawable, unconditionally.

Separately, uploading a bitmap as a GPU texture fails past `GL_MAX_TEXTURE_SIZE`, which is still
4096 on the largest share of shipping devices. That bites on perfectly ordinary photos: a 3120×4160
portrait is only 50MB and still exceeds a 4096 cap. Both limits have to be checked, because neither
implies the other.

Encoded size tells you nothing. One reported crash decoded a **50 KB** JPEG into 111 MB.

## What Crayfish does

`DecodeBudget` carries a byte cap and a dimension cap, and `SampleSize.forDecode` picks a power of
two that satisfies both, **before a decoder is constructed**, never after. A cropper that decodes
first and checks afterwards has already paid the allocation it was trying to avoid.

The preview is tiled: a low resolution base layer that stays resident, plus tiles for the visible
region only, in a cache with an explicit byte budget that closes what it evicts. Closing matters
because a decoded image holds native memory the garbage collector does not account for.

## Rotated crops get half the budget

A crop that is rotated or mirrored costs two buffers rather than one. The decoded pixels are read
into an array, the turned pixels are written into a second, and both are alive at the same time. So
the decode is budgeted at half of what the caller allowed, and the pair stays inside the ceiling.

The consequence is visible in the result: a rotated crop comes back smaller than the same crop
untransformed. On the 108MP fixture at `DecodeBudget.ForOutput`, selecting the whole image:

| | Result | Peak heap | Allocated |
|---|---|---|---|
| Unrotated | 6000×4500, 102 MB | 244 MB | 288 MB |
| Rotated a quarter turn | 2250×3000, 25 MB | 242 MB | 416 MB |
| Rotated, budget not split | 4500×6000, 102 MB | 558 MB | 1535 MB |

The third row is what the library did before this was found, and it is why the split exists: on a
device whose entire Java heap cap was 192 MB, that path asked for a single 166 MB allocation and the
`OutOfMemoryError` came out of `crop()` into the caller. Pass a larger `budget` if the resolution
matters more than the peak.

## How it is verified

The end to end tests measure rather than assert. They run against generated fixtures, a 12000×9000
JPEG and a 28000×2000 panorama, built by a Gradle task rather than inside the test, because building a
108MP image costs hundreds of megabytes and paying that in the test JVM would remove the constraint
under test.

The budget is a stated constant, deliberately not `Runtime.maxMemory()`: tying it to the test heap
would mean that raising the heap to host a UI test silently relaxes the only thing being measured.

Measured, on the 108MP fixture:

| | Peak heap | Allocated |
|---|---|---|
| Crop to 3000×3000 | 97 MB | 86 MB |
| Crop, then encode to JPEG | 54 MB | 38 MB |
| 28000×2000 panorama | 20 MB | 8 MB |

The control that makes those numbers mean something: a naive full decode of the same file exhausts
the same budget in a child JVM, and the same probe is shown to *succeed* on a 4K image. Without the
second half, "it ran out of memory" would be indistinguishable from a probe that always fails.

Beyond the budget, a stress suite walks seven sources against eight crop rectangles each, including
a 5000×5000 square, a 2000×28000 column, and a source one pixel wide and 32767 tall. It runs on the
desktop JVM and again on a device, where a decoded bitmap's pixels are native and a recycled one
raises only when something reads it. Every result there is read rather than counted, because the
defect that shipped once was a crop that handed back a freed bitmap, and ten tests stayed green
because none of them looked at a pixel.

Repetition is measured as steady state rather than as growth from a baseline: a six pass run over
the whole corpus compares its second half to its first, which sees a leak and not a warm up. A clean
run moves 1 MB; a control holding twenty five decoded regions moves 336 MB.
