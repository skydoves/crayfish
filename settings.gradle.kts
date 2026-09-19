pluginManagement {
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}
dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "CrayfishDemo"

include(":crayfish")
include(":crayfish-landscapist")
include(":crayfish-coil")
include(":crayfish-activity")
include(":samples-shared")
include(":androidApp")
include(":desktopApp")
include(":wasmApp")
include(":benchmark")
