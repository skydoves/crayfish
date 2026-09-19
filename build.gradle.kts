plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.test) apply false
  alias(libs.plugins.kmp.android.library) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.jetbrains.compose) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.baseline.profile) apply false
  alias(libs.plugins.nexus.plugin)
  alias(libs.plugins.spotless)
  alias(libs.plugins.dokka)
  alias(libs.plugins.kotlin.binary.compatibility)
}

// Only the published libraries are API-stable. Demo, benchmark and docs modules are not, and are
// added here as they are created. The validator fails the build on a name that matches no project,
// so this list cannot be written ahead of the modules.
apiValidation {
  ignoredProjects.addAll(listOf("samples-shared", "androidApp", "desktopApp", "wasmApp"))

  // Most of this library's targets are native or wasm, and BCV registers no `androidApiCheck`
  // under `com.android.kotlin.multiplatform.library`, only `desktopApiCheck`. Without klib
  // validation an API break on iOS, macOS or wasm would pass CI unnoticed.
  @OptIn(kotlinx.validation.ExperimentalBCVApi::class)
  klib {
    enabled = true
  }
}

subprojects {
  apply(plugin = rootProject.libs.plugins.spotless.get().pluginId)

  configure<com.diffplug.gradle.spotless.SpotlessExtension> {
    kotlin {
      target("**/*.kt")
      targetExclude(
        "${layout.buildDirectory.get()}/**/*.kt",
        // Holds every README Kotlin block verbatim so the compiler type checks them, and
        // `ReadmeSnippetsTest` asserts each block is still in it character for character.
        // Reformatting it would break that comparison on the first re-indent.
        "**/readme/ReadmeSnippets*.kt",
      )
      ktlint().editorConfigOverride(
        mapOf(
          "indent_size" to "2",
          "continuation_indent_size" to "2",
          // Composables are named like types by convention, which the naming rule does not know.
          "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
        ),
      )
      licenseHeaderFile(rootProject.file("spotless/copyright.kt"))
      trimTrailingWhitespace()
      endWithNewline()
    }
    format("xml") {
      target("**/*.xml")
      targetExclude("**/build/**/*.xml")
      // Look for the first XML tag that isn't a comment (<!--) or the xml declaration (<?xml)
      licenseHeaderFile(rootProject.file("spotless/copyright.xml"), "(<[^!?])")
      trimTrailingWhitespace()
      endWithNewline()
    }
  }
}

/**
 * Fails if any published type is never named by a test.
 *
 * Line coverage is unavailable here, because Kover 0.9.1 refuses to configure a project using AGP's
 * `com.android.kotlin.multiplatform.library` plugin, so this measures the thing a library cares
 * about most instead. It reads the surface from the binary-compatibility-validator dump, the same
 * file that gates API changes, so the two cannot drift: adding a public type without a test now
 * fails the build.
 *
 * What it is not: proof that a type's behaviour is covered, only that something exercises it.
 * It found three real holes on its first run, the one-line `ImageCropper` API among them.
 */
val apiCoverage by tasks.registering(Exec::class) {
  // Reads the committed dumps rather than regenerating them.
  //
  // It used to `dependsOn(apiDump)` so the files were guaranteed to exist. Two things were wrong
  // with that. A check task rewriting a committed file is a side effect nobody expects from a
  // check, and `apiDump` writing the same file `apiCheck` reads made Gradle refuse the pair:
  // `./gradlew apiCheck apiCoverage` failed with an implicit dependency error while either alone
  // passed. `apiCheck` is what proves the committed dump matches the code, so the two compose:
  // one says the dump is current, the other says everything in it is tested.
  commandLine("python3", rootProject.file("scripts/api-coverage.py").absolutePath)
  workingDir = rootProject.projectDir
}
