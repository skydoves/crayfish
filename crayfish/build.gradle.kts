@file:OptIn(ExperimentalWasmDsl::class)

import com.github.skydoves.crayfish.Configuration
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
  // Line coverage, applied directly rather than through Kover: Kover 0.9.1 refuses to configure a
  // project using AGP's `com.android.kotlin.multiplatform.library` plugin at all. JaCoCo attaches
  // to the desktop JVM test task, which is where the shared code and the Compose UI tests run.
  jacoco
  alias(libs.plugins.kmp.android.library)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.jetbrains.compose)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.nexus.plugin)
  alias(libs.plugins.dokka)
  alias(libs.plugins.baseline.profile)
}

apply(from = "$rootDir/scripts/publish-module.gradle.kts")

mavenPublishing {
  // Real API docs in the javadoc jar. Without this it is `JavadocJar.Empty()`, vanniktech's
  // default for Kotlin Multiplatform, and Maven Central accepts that: Central requires the jar to
  // exist and never looks inside. Measured on the 0.1.0 artifact before this line, the jar was 25
  // bytes, a manifest and nothing else, and that is what javadoc.io would have served for the life
  // of the release.
  configure(
    com.vanniktech.maven.publish.KotlinMultiplatform(
      javadocJar = com.vanniktech.maven.publish.JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
      sourcesJar = true,
    ),
  )

  val artifactId = "crayfish"
  coordinates(
    Configuration.artifactGroup,
    artifactId,
    rootProject.extra.get("libVersion").toString(),
  )

  pom {
    name.set(artifactId)
    description.set(
      "A Compose Multiplatform image cropper with gesture, rotation, EXIF, and large-image support.",
    )
  }
}

kotlin {
  android {
    namespace = "com.github.skydoves.crayfish"
    compileSdk = Configuration.compileSdk
    minSdk = Configuration.minSdk

    compilations.configureEach {
      compileTaskProvider.configure {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
      }
    }

    lint { abortOnError = false }

    // Runs commonTest on the Android compilation as well as on desktop. The header parsers, the
    // orientation maths and the sampling budget are shared code, but "shared code compiles for
    // Android" and "shared code behaves the same on Android" are different claims, and only this
    // task checks the second one.
    withHostTest {}

    // The entry point for on-device end-to-end tests. Android is where the memory ceiling this
    // library exists to respect actually lives. The ~100MB hardware-canvas limit and
    // GL_MAX_TEXTURE_SIZE are Android facts, so the OOM claim is only fully proven here.
    withDeviceTest {
      instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
  }
  jvm("desktop")
  iosArm64()
  iosSimulatorArm64()
  macosArm64()
  // No iosX64 or macosX64: Compose Multiplatform 1.12.0 publishes neither
  // (org.jetbrains.compose.runtime:runtime-iosx64 and -macosx64 are both 404 on Maven Central),
  // so declaring them fails metadata resolution rather than producing an Intel artifact.
  wasmJs {
    browser {
      // Enabled deliberately. Nothing else in this build executes wasm at all: the compiler is
      // happy to emit `js()` glue it never checks: a deliberate syntax error inside one compiles
      // clean, because dead-code elimination removes what nothing calls. Until these run in a real
      // browser, the web target has a test suite that cannot fail.
      testTask {
        useKarma { useChromeHeadless() }
      }
    }
    binaries.library()
  }

  @Suppress("OPT_IN_USAGE")
  applyHierarchyTemplate {
    common {
      group("jvm") {
        // The `com.android.kotlin.multiplatform.library` plugin registers its target as
        // platformType=androidJvm, which `withAndroidTarget()` (matching the legacy
        // KotlinAndroidTarget) does not pick up. Match by platform type instead.
        withCompilations { it.target.platformType == KotlinPlatformType.androidJvm }
        withJvm()
      }
      // Everything whose graphics stack is Skia, which is every target except Android.
      // `skiaMain` is where the Skia-backed decoder/encoder actuals live.
      group("skia") {
        withJvm()
        withWasmJs()
        group("apple") {
          group("ios") {
            withIosArm64()
            withIosSimulatorArm64()
          }
          group("macos") {
            withMacosArm64()
          }
        }
      }
    }
  }

  // `apiCheck` depends on a `testClasses` lifecycle task that the KMP plugin does not register.
  tasks.register("testClasses")

  sourceSets {
    commonMain.dependencies {
      // `api`, not `implementation`. The public surface names `Modifier`, `Color`, `Dp`, `Path`,
      // `@Composable` and `@Stable`, so a consumer cannot call `Cropper` without these on its own
      // compile classpath. As `implementation` they land in the POM at `runtime` scope, and an app
      // that gets Compose transitively rather than declaring it cannot name `Modifier` at all.
      api(libs.compose.runtime)
      api(libs.compose.foundation)
      api(libs.compose.ui)
      implementation(libs.kotlinx.coroutines.core)
    }

    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
    }

    val androidDeviceTest by getting {
      dependencies {
        implementation(libs.kotlin.test)
        implementation(libs.androidx.test.runner)
        // The cropper's whole interaction surface is gestural, and a gesture that works in a
        // headless desktop composition has still never met a real touchscreen, a real view
        // hierarchy, or a real hardware canvas.
        implementation(libs.compose.ui.test)
        implementation(libs.androidx.compose.ui.test.manifest)
        implementation(libs.androidx.test.espresso)
      }
    }

    // `org.khronos.webgl.*` and the browser globals are named directly by the wasm decoder.
    // They arrive transitively through skiko today, which would break the moment skiko stops
    // exposing them in its own public API.
    val wasmJsMain by getting {
      dependencies {
        implementation(libs.kotlinx.browser)
      }
    }

    // Compose UI tests on the Apple targets. The gesture layer is proven on two Android devices
    // and in desktop compositions; iOS delivers touch through its own runtime, and nothing has ever
    // exercised that path.
    val appleTest by getting {
      dependencies {
        @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
        implementation(compose.uiTest)
      }
    }

    // Tests that need a real composition or an ImageBitmap live here. `runComposeUiTest` wants a
    // window, and `compose.desktop.currentOs` supplies the Skiko backend that draws into it.
    val desktopTest by getting {
      languageSettings.optIn("androidx.compose.ui.test.ExperimentalTestApi")
      dependencies {
        implementation(libs.compose.ui.test)
        implementation(compose.desktop.currentOs)
      }
    }
  }

  explicitApi()
}

// The AAR carries its own baseline profile, so a consumer gets AOT-compiled cropper code without
// running any of this. The rules come from the demo journey in `:benchmark`, filtered down to this
// library: shipping the demo's Compose and Kotlin rules from a library would push a caller's own
// profile decisions around, and none of those classes are ours to speak for.
baselineProfile {
  filter {
    include("com.github.skydoves.crayfish.**")
    // The journey runs inside the demo, and the demo's own package sits under the library's. Without
    // these the AAR shipped 224 rules for classes no consumer has, which is dead weight in every app
    // that depends on this.
    exclude("com.github.skydoves.crayfish.demo.**")
    exclude("com.github.skydoves.crayfishdemo.**")
  }
}

dependencies {
  baselineProfile(project(":benchmark"))
}

tasks.withType<KotlinJvmCompile>().configureEach {
  compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

tasks.withType<JavaCompile>().configureEach {
  this.targetCompatibility = libs.versions.jvmTarget.get()
  this.sourceCompatibility = libs.versions.jvmTarget.get()
}

// -------------------------------------------------------------------------------------------------
// End-to-end fixtures and the memory budget the e2e tests run under.
// -------------------------------------------------------------------------------------------------

/**
 * Writes the oversized source images the end-to-end tests decode.
 *
 * They are generated rather than committed because a 108MP JPEG is a large binary to carry in a
 * repository, and generated *here* rather than inside the test because building one costs hundreds
 * of megabytes, which is exactly the allocation the tests exist to prove the library never makes.
 * The Gradle daemon has the headroom (see `org.gradle.jvmargs`); the test JVM deliberately does not.
 */
val generateImageFixtures by tasks.registering {
  val outputDir = layout.buildDirectory.dir("fixtures")
  outputs.dir(outputDir)
  // Regenerate only when the recipe changes, not on every build.
  inputs.property(
    "recipe",
    "v3: 108MP jpeg, 48MP jpeg, 1x14 panorama, 14x1 tall, 1x32767 sliver, 5000 square, 4k png, " +
      "landscape jpeg tagged Exif 6",
  )

  doLast {
    val dir = outputDir.get().asFile
    dir.mkdirs()

    fun write(name: String, width: Int, height: Int, format: String) {
      val file = File(dir, name)
      if (file.exists() && file.length() > 0) return

      // TYPE_3BYTE_BGR rather than ARGB: three bytes per pixel instead of four keeps even the
      // 108MP fixture inside the daemon's heap while generating it.
      val image = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
      val graphics = image.createGraphics()
      // An asymmetric pattern: a uniform fill would let a flipped or mis-offset crop pass.
      val bandHeight = (height / 8).coerceAtLeast(1)
      for (band in 0 until 8) {
        graphics.color = Color(band * 31 % 256, (band * 71 + 40) % 256, (band * 113 + 90) % 256)
        graphics.fillRect(0, band * bandHeight, width, bandHeight)
      }
      graphics.color = Color.WHITE
      graphics.fillRect(0, 0, (width / 16).coerceAtLeast(1), (height / 16).coerceAtLeast(1))
      graphics.dispose()

      ImageIO.write(image, format, file)
      logger.lifecycle("fixture: ${file.name} ${width}x${height} -> ${file.length() / 1024}KB")
    }


    fun writeTaggedRotate90(name: String, width: Int, height: Int) {
      val file = File(dir, name)
      if (file.exists() && file.length() > 0) return

      val plain = File(dir, "$name.untagged")
      val image = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
      val graphics = image.createGraphics()
      // Left half red, right half blue. A clockwise quarter turn puts a left half on top, so a
      // test can name which half should be where without consulting the code that turns it.
      graphics.color = Color.RED
      graphics.fillRect(0, 0, width / 2, height)
      graphics.color = Color.BLUE
      graphics.fillRect(width / 2, 0, width - width / 2, height)
      graphics.dispose()
      ImageIO.write(image, "jpg", plain)

      // A minimal APP1 segment spliced in after SOI: big endian TIFF, one IFD entry, tag 0x0112
      // (Orientation) = 6. Written by hand rather than with a library so the fixture stays a
      // build with no extra dependency.
      val tiff = byteArrayOf(
        0x4D, 0x4D, 0x00, 0x2A, // "MM", 42
        0x00, 0x00, 0x00, 0x08, // offset of IFD0
        0x00, 0x01, // one entry
        0x01, 0x12, // tag 0x0112 Orientation
        0x00, 0x03, // type SHORT
        0x00, 0x00, 0x00, 0x01, // count 1
        0x00, 0x06, 0x00, 0x00, // value 6, left aligned in the 4 byte field
        0x00, 0x00, 0x00, 0x00, // no next IFD
      )
      val payload = "Exif".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0) + tiff
      val length = payload.size + 2
      val segment = byteArrayOf(
        0xFF.toByte(),
        0xE1.toByte(),
        ((length shr 8) and 0xFF).toByte(),
        (length and 0xFF).toByte(),
      ) + payload

      val base = plain.readBytes()
      file.writeBytes(base.copyOfRange(0, 2) + segment + base.copyOfRange(2, base.size))
      plain.delete()
      logger.lifecycle("fixture: ${file.name} ${width}x${height} Exif 6 -> ${file.length() / 1024}KB")
    }

    write("sensor-108mp.jpg", 12_000, 9_000, "jpg")
    write("sensor-48mp.jpg", 8_000, 6_000, "jpg")
    write("panorama-1x14.jpg", 28_000, 2_000, "jpg")
    write("uhd.png", 3_840, 2_160, "png")

    // A square source. Sampling and frame fitting both divide by the aspect ratio, and 1:1 is the
    // one value where a swapped width and height cannot be seen in the result.
    write("square-5000.jpg", 5_000, 5_000, "jpg")

    // The panorama transposed. JPEG stores rows of MCUs, so skipping rows and skipping columns are
    // different paths in every region decoder, and only one of them was covered.
    write("tall-2x28.jpg", 2_000, 28_000, "jpg")

    // One pixel wide, and as tall as Android will hand back in a single bitmap. Every divide in the
    // sampling budget meets a 1 on this one.
    write("sliver-1x32767.jpg", 1, 32_767, "jpg")

    // A landscape file that the Exif tag turns into a portrait photo, which is how every phone
    // stores a portrait shot. Without one, nothing on device ever exercises the orientation round
    // trip: the four fixtures above are all untagged, so raw space and oriented space agree and
    // reading a region off the wrong axis cannot be seen.
    writeTaggedRotate90("oriented-rot90.jpg", 4_000, 3_000)
  }
}

tasks.named<Test>("desktopTest") {
  dependsOn(generateImageFixtures)
  systemProperty("crayfish.fixtures", layout.buildDirectory.dir("fixtures").get().asFile.absolutePath)

  // Headroom for Skiko, which the Compose UI tests need to open a window and rasterise into it.
  // This is NOT the memory budget under test: that is `MemoryProbe.BUDGET_BYTES`, enforced on a
  // child JVM, precisely so that giving the runner more memory cannot weaken the measurement.
  maxHeapSize = "1g"
  // Deterministic collection, so a peak-usage reading reflects the code rather than GC timing.
  jvmArgs("-XX:+UseSerialGC")

  // macOS: run the test JVM as an accessory process.
  //
  // Every `runComposeUiTest` class makes Skiko open a real window, and on macOS an AWT process
  // without this shows up in the Dock and takes keyboard focus from whatever the developer is
  // typing into. A mutation loop over the UI tests makes the machine unusable. `UIElement` only
  // suppresses the Dock icon and the activation; the window is still created and still rasterises,
  // so the tests see exactly what they saw before. `java.awt.headless` would NOT do - it stops
  // Skiko creating the window at all.
  systemProperty("apple.awt.UIElement", "true")
}

/**
 * Fails if anything this library publishes contains native code.
 *
 * "Zero native code" is the claim that makes the Play Store's 16 KB page-size requirement a
 * non-event for consumers, unconditionally, and with no work at the February 2027 cutoff. uCrop
 * has had that same requirement open and unanswered since September 2024, and the community forked
 * it to escape. A claim that load-bearing has to be a gate rather than a sentence in a README, so
 * this task opens the artifacts and looks.
 */
val verifyNoNativeCode by tasks.registering {
  dependsOn("assemble")
  val artifacts = layout.buildDirectory
  outputs.upToDateWhen { false }

  doLast {
    val suspicious = listOf(".so", ".dylib", ".dll", ".a")
    val offenders = mutableListOf<String>()
    var inspected = 0

    fileTree(artifacts) {
      include("**/outputs/aar/*.aar", "**/libs/*.jar", "**/*.klib")
      exclude("**/*sources*", "**/*javadoc*")
    }.forEach { archive ->
      inspected++
      ZipFile(archive).use { zip ->
        zip.entries().asSequence()
          .map { it.name }
          .filter { name -> suspicious.any { name.endsWith(it) } }
          .forEach { offenders += "${archive.name} -> $it" }
      }
    }

    check(inspected > 0) {
      // A check that inspects nothing passes for the wrong reason; this is the positive control.
      "verifyNoNativeCode found no artifacts to inspect: run :crayfish:assemble first"
    }
    check(offenders.isEmpty()) {
      "native code found in published artifacts:\n" + offenders.joinToString("\n")
    }
    logger.lifecycle("verifyNoNativeCode: $inspected artifacts inspected, no native code")
  }
}

/**
 * Fails if the AAR's baseline profile is missing, empty, or names a class this library does not own.
 *
 * The profile is generated by driving the demo app, so every class the journey touches is a
 * candidate: the first filtered build shipped 224 rules for `com.github.skydoves.crayfish.demo`,
 * which sits under the library's own package and therefore passed a plain namespace include. Those
 * rules are dead weight in every consumer.
 *
 * The allowed set is read out of the library's own `*Main` sources rather than written here, so a
 * package added tomorrow is covered without anyone remembering this task exists, and a package that
 * is not the library's fails whatever it is called.
 */
val verifyBaselineProfile by tasks.registering {
  dependsOn("assemble")
  val artifacts = layout.buildDirectory
  val mainSources = fileTree("src") { include("*Main/kotlin/**/*.kt") }
  outputs.upToDateWhen { false }

  doLast {
    val declaration = Regex("""^package (com\.github\.skydoves\.crayfish[\w.]*)""")
    val owned = mainSources.files
      .asSequence()
      .flatMap { it.readLines().asSequence() }
      .mapNotNull { declaration.find(it)?.groupValues?.get(1) }
      .map { "L" + it.replace('.', '/') + "/" }
      .toSortedSet()
    check(owned.isNotEmpty()) {
      "verifyBaselineProfile read no package declarations from crayfish/src/*Main: the gate would " +
        "have passed on an empty allow-list, which measures nothing"
    }

    val aars = fileTree(artifacts) { include("**/outputs/aar/*.aar") }.files
    check(aars.isNotEmpty()) {
      "verifyBaselineProfile found no AAR to inspect: run :crayfish:assemble first"
    }

    aars.forEach { aar ->
      val rules = ZipFile(aar).use { zip ->
        val entry = zip.getEntry("baseline-prof.txt")
        checkNotNull(entry) {
          "${aar.name} ships no baseline-prof.txt: run `./gradlew :crayfish:generateBaselineProfile` " +
            "against a rooted device, which writes crayfish/src/androidMain/generated/baselineProfiles"
        }
        zip.getInputStream(entry).bufferedReader().readLines().filter { it.isNotBlank() }
      }
      check(rules.isNotEmpty()) { "${aar.name} ships an empty baseline-prof.txt" }

      // A rule is optional H/S/P flags, then a type descriptor, then an optional member.
      val foreign = rules.filterNot { rule ->
        val descriptor = rule.dropWhile { it == 'H' || it == 'S' || it == 'P' || it == '[' }
        owned.any { descriptor.startsWith(it) }
      }
      check(foreign.isEmpty()) {
        "${aar.name} baseline profile names ${foreign.size} class(es) outside this library:\n" +
          foreign.take(10).joinToString("\n")
      }
      logger.lifecycle(
        "verifyBaselineProfile: ${aar.name}, ${rules.size} rules, all within ${owned.size} owned packages",
      )
    }
  }
}


/**
 * Puts the oversized fixtures where an instrumentation test can read them.
 *
 * `/data/local/tmp` rather than app storage: it is world-readable, so the test process can open it
 * whichever uid the instrumentation runs as, and nothing has to be granted at runtime. The
 * alternative, shipping a 108MP JPEG inside the test APK, collides with Compose's resource
 * plugin, which configures no assets copy for a device-test source set.
 */
val pushDeviceFixtures by tasks.registering {
  dependsOn(generateImageFixtures)
  val fixtureDir = layout.buildDirectory.dir("fixtures")
  val sdkDir = providers.provider {
    // `local.properties` is where the SDK path lives for everyone who opened this in the IDE; the
    // environment variables are the CI fallback.
    val local = rootProject.file("local.properties")
    val fromLocal = if (local.exists()) {
      local.readLines().firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter("=")
    } else {
      null
    }
    fromLocal ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
  }
  val execOps = providers
  outputs.upToDateWhen { false }

  doLast {
    val adb = File(File(checkNotNull(sdkDir.orNull) { "no Android SDK path" }, "platform-tools"), "adb")
    check(adb.exists()) { "adb not found at $adb" }

    val remote = "/data/local/tmp/crayfish-fixtures"
    val serials = execOps.exec { commandLine(adb.absolutePath, "devices") }
      .standardOutput.asText.get()
      .lineSequence()
      .drop(1)
      .map { it.trim() }
      .filter { it.endsWith("device") && it.isNotEmpty() }
      .map { it.split(Regex("\\s+")).first() }
      .toList()
    check(serials.isNotEmpty()) { "no device or emulator is attached" }

    serials.forEach { serial ->
      execOps.exec {
        commandLine(adb.absolutePath, "-s", serial, "shell", "mkdir", "-p", remote)
      }.standardOutput.asText.get()
      fixtureDir.get().asFile.listFiles().orEmpty().forEach { file ->
        execOps.exec {
          commandLine(adb.absolutePath, "-s", serial, "push", file.absolutePath, remote)
        }.standardOutput.asText.get()
      }
      logger.lifecycle("pushed ${fixtureDir.get().asFile.listFiles()?.size ?: 0} fixtures to $serial")
    }
  }
}

tasks.matching { it.name == "connectedAndroidDeviceTest" }.configureEach {
  dependsOn(pushDeviceFixtures)
}

/**
 * Turns off a copy task the Compose plugin registers without configuring.
 *
 * Enabling `withDeviceTest` makes `org.jetbrains.compose` register
 * `copyAndroidDeviceTestComposeResourcesToAndroidAssets` with no `outputDirectory`, and Gradle
 * refuses to run a task whose required property is unset. This module declares no Compose resources
 * at all (there is no `composeResources` directory anywhere in it), so the task has nothing to
 * copy and disabling it removes a no-op rather than a feature. Delete this when the plugin
 * configures the task it registers.
 */
tasks.matching { it.name.startsWith("copyAndroidDeviceTestComposeResources") }.configureEach {
  enabled = false
}

/**
 * Turns off a check that does not apply to this module.
 *
 * The Compose plugin refuses to run wasm tests unless the target declares `binaries.executable()`,
 * because a Compose *UI* test on the web needs the Skiko runtime bundled. This module's wasm tests
 * are the header parsers, the orientation maths and the browser decoder, with no composition at all,
 * and declaring an executable alongside the published library makes the two sync into one package
 * directory, which Gradle then rejects as an undeclared dependency between their webpack tasks.
 * Compose UI tests run on desktop, Android and the Apple targets instead.
 */
tasks.matching { it.name == "checkComposeUiTestConfigurationForWasmJs" }.configureEach {
  enabled = false
}

tasks.named<Test>("desktopTest") {
  extensions.configure<JacocoTaskExtension> {
    // The Compose compiler rewrites composables heavily and the instrumenter has to see the
    // rewritten classes, not the sources.
    isIncludeNoLocationClasses = true
    excludes = listOf("jdk.internal.*")
  }
}

/** A line-coverage report over the published library, measured on the desktop JVM run. */
val desktopCoverageReport by tasks.registering(JacocoReport::class) {
  dependsOn("desktopTest")
  executionData(layout.buildDirectory.file("jacoco/desktopTest.exec"))
  sourceDirectories.setFrom(
    files("src/commonMain/kotlin", "src/jvmMain/kotlin", "src/desktopMain/kotlin", "src/skiaMain/kotlin"),
  )
  classDirectories.setFrom(
    fileTree(layout.buildDirectory.dir("classes/kotlin/desktop/main")) {
      // Compose generates these wholesale; they have no source form to attribute a miss to.
      exclude("**/ComposableSingletons*", "**/*\$\$*")
    },
  )
  reports {
    xml.required.set(true)
    html.required.set(true)
  }
}
