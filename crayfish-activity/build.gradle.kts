import com.github.skydoves.crayfish.Configuration
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
  alias(libs.plugins.kmp.android.library)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.jetbrains.compose)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.nexus.plugin)
  alias(libs.plugins.dokka)
}

apply(from = "$rootDir/scripts/publish-module.gradle.kts")

mavenPublishing {
  // Real API docs in the javadoc jar; vanniktech's KMP default ships an empty one. See `:crayfish`.
  configure(
    com.vanniktech.maven.publish.KotlinMultiplatform(
      javadocJar = com.vanniktech.maven.publish.JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
      sourcesJar = true,
    ),
  )

  val artifactId = "crayfish-activity"
  coordinates(
    Configuration.artifactGroup,
    artifactId,
    rootProject.extra.get("libVersion").toString(),
  )

  pom {
    name.set(artifactId)
    description.set(
      "An Activity result contract for Crayfish, for apps on Views or Fragments that would rather " +
        "launch a cropper than host one.",
    )
  }
}

// Android only, on purpose. An Activity is an Android concept, and the other targets would carry a
// source set that could never contain anything.
kotlin {
  android {
    namespace = "com.github.skydoves.crayfish.activity"
    compileSdk = Configuration.compileSdk
    minSdk = Configuration.minSdk

    compilations.configureEach {
      compileTaskProvider.configure {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
      }
    }

    lint { abortOnError = false }

    // Off by default under `com.android.kotlin.multiplatform.library`, and this module is the one
    // place in the repo that has real Android resources: the cropper's theme and the FileProvider's
    // `<paths>`. Without it the manifest references them and aapt cannot find either, which fails
    // as a resource linking error rather than as anything that names the cause.
    androidResources {
      enable = true
    }

    // The contract, the manifest entry and the FileProvider only line up on a device. A unit test
    // would exercise the marshalling and prove nothing about whether the Activity can start.
    @Suppress("UnstableApiUsage")
    withDeviceTest {
      instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
  }

  // `apiCheck` depends on a `testClasses` lifecycle task that the KMP plugin does not register.
  tasks.register("testClasses")

  sourceSets {
    androidMain.dependencies {
      // `api`, not `implementation`: a caller writing `CropImageContract()` needs the request and
      // result types, and those name `AspectRatio` and `EncodedFormat` from the core.
      api(project(":crayfish"))
      implementation(libs.androidx.activity.compose)
      implementation(libs.compose.runtime)
      implementation(libs.compose.ui)
      implementation(libs.compose.foundation)
      implementation(libs.compose.material3)
      implementation(libs.kotlinx.coroutines.core)
    }

    val androidDeviceTest by getting {
      dependencies {
        implementation(libs.kotlin.test)
        implementation(libs.androidx.test.runner)
        implementation(libs.androidx.test.espresso)
      }
    }
  }

  explicitApi()
}

tasks.withType<KotlinJvmCompile>().configureEach {
  compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

tasks.withType<JavaCompile>().configureEach {
  this.targetCompatibility = libs.versions.jvmTarget.get()
  this.sourceCompatibility = libs.versions.jvmTarget.get()
}

/**
 * Turns off a copy task the Compose plugin registers without configuring.
 *
 * The same one `:crayfish` disables, for the same reason: enabling `withDeviceTest` makes
 * `org.jetbrains.compose` register `copyAndroidDeviceTestComposeResourcesToAndroidAssets` with no
 * `outputDirectory`, and Gradle refuses to run a task whose required property is unset. This module
 * declares no Compose resources, so the task has nothing to copy and disabling it removes a no-op
 * rather than a feature. Delete this when the plugin configures the task it registers.
 */
tasks.matching { it.name.startsWith("copyAndroidDeviceTestComposeResources") }.configureEach {
  enabled = false
}
