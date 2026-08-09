// The published artifact is `com.tripletriad:core`, so the root project has to be called `core` —
// Kotlin Multiplatform derives every publication's artifactId from this name (`core`, `core-jvm`,
// `core-android`, `core-iosarm64`, …). Renaming it renames the dependency in two other
// repositories.
rootProject.name = "core"

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Provisions the JDK the toolchain asks for, so a contributor whose only JDK is 21 can still
    // build this against 17. Without it the failure is an unhelpful "no matching toolchain".
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
