# Performance

## What ships

The Android artifact carries a baseline profile at the root of the AAR. A consumer gets it by
depending on Crayfish: AGP merges library profiles into the app's own, and the platform compiles
those methods ahead of time at install. Nothing to add, nothing to configure, and no runtime cost
for apps that never crop.

The profile covers only this library. It holds 787 rules across the five packages Crayfish
publishes:

| Package | What it covers |
|---|---|
| `ui` | `CropState`, the tile store, the overlay, gesture handling |
| `geometry` | The transform, bounds fitting, rotation coverage |
| `decode` | Region decoding and the sampling budget |
| `exif` | Orientation parsing and normalisation |
| `encode` | JPEG, PNG and WebP output |

## What it is worth

Measured on a Galaxy S23, Android 16, five iterations per condition. The comparison is
`CompilationMode.None()` against `CompilationMode.Partial(BaselineProfileMode.Require)` on the same
build of the demo app, so the only variable is whether the profile was installed.

Cold start to the first frame, `StartupTimingMetric`:

| | median | min | max |
|---|---|---|---|
| Without profile | 312.2 ms | 303.2 ms | 328.9 ms |
| With profile | 262.1 ms | 249.8 ms | 293.7 ms |

50 ms, or 16%. The two ranges do not overlap, which is the part worth trusting: five samples each,
and no sample from either condition lands inside the other.

Dragging the crop frame, `FrameTimingMetric`:

| | P50 | P90 | P95 | P99 |
|---|---|---|---|---|
| `frameDurationCpuMs` without profile | 5.29 | 25.21 | 28.55 | 49.83 |
| `frameDurationCpuMs` with profile | 4.83 | 11.67 | 12.90 | 24.22 |
| `frameOverrunMs` without profile | -0.06 | 23.04 | 30.81 | 43.23 |
| `frameOverrunMs` with profile | -1.34 | 5.61 | 7.73 | 17.76 |

`frameOverrunMs` is how late a frame was against its deadline, so a negative number is a frame that
finished early. The medians barely move because the median frame was never the problem. The P90 is
where a profile earns its place: 23.04 ms late becomes 5.61 ms late, which is the difference between
a drag that stutters and one that does not.

## What these numbers do not cover

- **One device.** An S23 is a fast phone. The shape of the result should hold on slower hardware,
  and the size of it is unmeasured there.
- **P99 is thin.** Each condition pooled about 130 frames, so the 99th percentile is one or two
  frames. P50 and P90 are the defensible columns.
- **`CompilationMode.None()` is the floor, not what a user sees.** It means JIT only. A real install
  gets whatever the installer and the Play Store compiled, which is somewhere between the two
  columns. The pair measures what the profile contributes, not what any particular user experiences.
- **The journey is the demo.** Opening a bundled photo, dragging the frame, cropping to JPEG. A
  screen that does something else exercises different code.

## How it is generated

`:benchmark` is a `com.android.test` module that drives the demo app through
`BaselineProfileGenerator`. The journey is in `CropperJourney`, and it is deliberately the same one
the benchmarks measure, because a profile is only worth the code paths it actually walked.

```
./gradlew :crayfish:generateBaselineProfile
./gradlew :androidApp:generateBaselineProfile
```

Both need a **rootable** device: the rule pulls the profile back with `adb root`, which a Play Store
system image refuses. An `aosp` or `google_apis` emulator image, or any userdebug device, works.

On CI there is no device attached, so a Gradle managed device stands in. `-Pcrayfish.managedDevice`
picks it, which is what the `Baseline profile` workflow passes:

```
./gradlew :crayfish:generateBaselineProfile :androidApp:generateBaselineProfile \
    -Pcrayfish.managedDevice
```

It is opt in rather than the default because the managed device downloads a system image on first
use, and a local run already has a device attached. The two are exclusive: adding the managed device
while leaving the connected one enabled puts both in the task graph, and a local run then pays for
an image it never uses.

The output lands in the source tree and is committed:

- `crayfish/src/androidMain/generated/baselineProfiles/baseline-prof.txt`, filtered to the library
- `androidApp/src/release/generated/baselineProfiles/baseline-prof.txt`, the whole demo

Every step of the journey asserts. An earlier version used `findObject(...)?.click()`, which is a
silent no op when nothing matches: every step reported success, the task exited 0, and the profile it
produced had 11,756 rules and not one for the cropper, because the run never left the gallery.

## The filter, and the gate on it

The journey runs inside the demo, and the demo's package sits under the library's own. A plain
`include("com.github.skydoves.crayfish.**")` therefore shipped 224 rules for demo classes that no
consumer has. The filter excludes them:

```kotlin
baselineProfile {
  filter {
    include("com.github.skydoves.crayfish.**")
    exclude("com.github.skydoves.crayfish.demo.**")
    exclude("com.github.skydoves.crayfishdemo.**")
  }
}
```

A filter is a thing that drifts, so `:crayfish:verifyBaselineProfile` opens the built AAR and checks
that its profile exists, is not empty, and names no class outside the packages the library's own
sources declare. The allowed set is read from those sources rather than written into the task, so a
package added tomorrow is covered without anyone remembering the gate exists.

```
./gradlew :crayfish:verifyBaselineProfile
```

## Running the benchmarks

```
ANDROID_SERIAL=<device> ./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest
```

This one does not need root, and a physical device is the right target: an emulator's numbers are a
property of the host. Results land in
`benchmark/build/outputs/connected_android_test_additional_output/`, with the frame percentiles under
`sampledMetrics` rather than `metrics` in the JSON.

`CropperBenchmark` runs every case twice, once with the profile and once without, because a profile
that is never compared against its absence is a file nobody can defend.
