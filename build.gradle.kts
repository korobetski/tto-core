// The game, without a way to show it.
//
// ### Why this repository exists
//
// The Phase 5 design has the server verify a match by **replaying it with the real engine** rather
// than with a second implementation of the rules. That is what makes it impossible for a client
// and the server to disagree about who won — and it is only true if there is exactly one engine,
// linkable from both.
//
// It lived in the client repository as `:core` first, which made it linkable from the client and
// from nowhere else: the server resolved it out of whatever a developer happened to have published
// into their own `~/.m2`, so the server could be built on the machines that had also built the
// client, and on no others. That is not a dependency, it is a coincidence, and it made automated
// deployment impossible. Here it is an artifact both consumers resolve the same way.
//
// So the constraint on this module is negative and absolute: **no Compose, no UI, no resource
// bundle, no platform I/O.** `commonMain` here imports `kotlin` and `kotlinx` and nothing else.
// The two functions that read the catalogs out of the Compose resource bundle stayed in the
// client's `:shared` (see `CatalogLoaders.kt`); the parsers they call are here.
//
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.kotlinSerialization)
    // Applied directly, where the client's root build applied them to every subproject. A
    // single-module repository has no root to hang an `allprojects` block on, and the
    // configuration below is what that block used to do.
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    id("jacoco")
    id("maven-publish")
}

group = "com.tripletriad"

// Set by the publish workflow from the tag it was triggered by: `-PcoreVersion=0.2.0` for `v0.2.0`.
// The default is a SNAPSHOT so that `publishToMavenLocal` overwrites instead of being cached — that
// is the loop for trying an engine change against a consumer before releasing it.
//
// A property rather than a constant edited by hand, because the alternative is a tag and an
// artifact that eventually disagree about what `v0.3.0` is, and nothing in the build would notice.
//
// It has to be raised as the release it is heading towards, not left behind: a default two releases
// under what the consumers pin publishes a local artifact nothing resolves, and the comment above
// then describes a loop that does not work.
version = providers.gradleProperty("coreVersion").getOrElse("0.7.14")

kotlin {
    // 17, matching `:shared`. The server runs 21 and consumes this happily; the reverse would not
    // work, so the library is the one that has to stay lower.
    jvmToolchain(17)

    android {
        namespace = "com.tripletriad.core"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
        // Same reason as in `:shared`: without it there is no `androidHostTest` source set, and
        // the common tests would run once on the desktop JVM instead of twice.
        withHostTestBuilder {}
    }
    jvm("desktop")

    // The same two targets `:shared` declares, and it has to be the same two: an `iosX64` klib
    // here would have no consumer, since the module that would use it cannot build for that target
    // any more. See the note in `shared/build.gradle.kts`.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "core"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // `api`, not `implementation`: `GameSave`, `Card` and the rest are `@Serializable` and
            // their consumers — `:shared` and the server — serialise them directly.
            api(libs.kotlinx.serialization.json)
            // The peer handshake's two primitives — see the catalog for why neither is hand-rolled
            // and why `kotlin.random.Random` is not one of them.
            implementation(libs.kotlincrypto.sha2)
            implementation(libs.kotlincrypto.rand)
            // `CardRepository` guards its cache with `sync.Mutex`.
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// ---------------------------------------------------------------------------------------
// Coverage
//
// The same shape as `:shared`'s, and for the same reasons — see that file for why JaCoCo rather
// than Kover, and why the desktop target alone is enough. The thresholds are higher here because
// this module is pure logic with no UI: there is nothing in it that a test cannot reach.
// ---------------------------------------------------------------------------------------

val desktopTestTask = tasks.named<Test>("desktopTest")

tasks.register<JacocoReport>("coverageReport") {
    group = "verification"
    description = "HTML + XML coverage for the desktop target, from desktopTest."
    dependsOn(desktopTestTask)
    executionData(
        desktopTestTask.map { test ->
            test.extensions.getByType<JacocoTaskExtension>().destinationFile!!
        },
    )
    classDirectories.setFrom(
        kotlin.targets.getByName("desktop")
            .compilations.getByName("main")
            .output.classesDirs
            .asFileTree,
    )
    sourceDirectories.setFrom(files("src/commonMain/kotlin"))

    reports {
        html.required = true
        xml.required = true
        csv.required = false
    }
}

tasks.register<JacocoCoverageVerification>("coverageVerify") {
    group = "verification"
    description = "Fails if desktop coverage drops well below what it was."
    val report = tasks.named<JacocoReport>("coverageReport")
    dependsOn(report)
    executionData(report.map { it.executionData })
    classDirectories.setFrom(report.map { it.classDirectories })
    sourceDirectories.setFrom(report.map { it.sourceDirectories })

    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.90".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.75".toBigDecimal()
            }
        }
    }
}

tasks.named("check") { dependsOn("coverageVerify") }

// ---------------------------------------------------------------------------------------
// Static analysis
//
// Lifted from the client's root `allprojects` block, unchanged in behaviour. It is applied here
// rather than inherited because a one-module repository has nothing to inherit from.
// ---------------------------------------------------------------------------------------

ktlint {
    filter {
        // Generated sources are not ours to format.
        exclude { it.file.path.contains("${File.separator}build${File.separator}") }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(file("detekt/detekt.yml"))
    // detekt defaults to the JVM `main`/`test` source sets, which in a KMP module are empty —
    // point it at every source set instead.
    source.setFrom(files("src"))
    parallel = true
}

// ---------------------------------------------------------------------------------------
// Publishing
//
// The Kotlin Multiplatform plugin has already created one publication per target plus the root
// `kotlinMultiplatform` one; this block only says where they go. The root publication is the one
// consumers name — `com.tripletriad:core` — and Gradle module metadata is what lets it resolve to
// `core-desktop` for the server's JVM and `core-android` for the app, from a single coordinate.
//
// Which is also why every consumer must resolve this with **Gradle**. A plain Maven consumer reads
// the POM, not the module metadata, and would get the root artifact with no target behind it.
// ---------------------------------------------------------------------------------------

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/korobetski/tto-core")
            // From the environment, never from a file in the repository. In Actions these are the
            // built-in actor and `secrets.GITHUB_TOKEN`, which is scoped to this repository and
            // expires with the job — so nothing long-lived has to exist for a release to happen.
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
