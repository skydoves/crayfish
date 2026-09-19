import com.github.skydoves.crayfish.Configuration

apply(plugin = "com.vanniktech.maven.publish")

// The javadoc jar is configured per module rather than here. A script applied with `apply(from =)`
// does not inherit the root build's plugin classpath, so `com.vanniktech.maven.publish.JavadocJar`
// cannot even be imported at this point, while `Configuration` can because buildSrc is on it.

rootProject.extra.apply {
  val snapshot = System.getenv("SNAPSHOT").toBoolean()
  val libVersion = if (snapshot) {
    Configuration.snapshotVersionName
  } else {
    Configuration.versionName
  }
  set("libVersion", libVersion)
}
