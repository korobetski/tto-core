# tto-core

The Triple Triad rules engine, and the game data it operates on. No UI, no resource bundle, no
platform I/O — a Kotlin Multiplatform library published as `com.tripletriad:core`.

```kotlin
implementation("com.tripletriad:core:0.1.0")
```

## Why it is a repository and not a module

The Phase 5 design has the server verify a match by **replaying it with the real engine** rather
than with a second implementation of the rules. Two implementations of Triple Triad's chain and
combo resolution would agree until they did not, and the disagreement would surface as a player
being told they lost a match they watched themselves win.

So there is one engine, and both the client and the server link it. It lived inside the client
repository first, which made it linkable from the client and — via whatever a developer had
published into their own `~/.m2` — from a server build that only certain laptops could perform.
Here, both consumers resolve the same artifact the same way, which is what makes the server
buildable by CI and therefore deployable at all.

| Consumer                                               | Uses it for |
|--------------------------------------------------------|---|
| [tto-client](https://github.com/korobetski/tto-client) | playing the match |
| [tto-server](https://github.com/korobetski/tto-server) | replaying it, and deciding the score |

## Targets

`android`, `desktop` (JVM 17), `iosArm64`, `iosSimulatorArm64` — the same four the client's
`:shared` declares, and it has to stay the same four: a target published here with no consumer is a
klib nobody links, and a target the client needs and this does not publish is a build failure in
the other repository.

JVM 17 rather than 21, though the server runs 21. A consumer can be newer than the library; the
reverse fails at compile time, so the library is the one that stays lower.

## Building it

```
cp local.properties.sample local.properties      # sdk.dir, for the Android target
./gradlew build
```

`build` is ktlint, detekt, the common tests on both host targets — `desktopTest` and
`testAndroidHostTest`, so every common test runs twice — and a coverage floor of 90% line / 75%
branch that `check` depends on. The floor is high because this module is pure logic: there is
nothing in it a test cannot reach.

The Apple targets compile only on a Mac. Kotlin/Native skips them silently everywhere else, so a
green build on Windows says nothing about iOS — that is what the `ios` job in CI is for.

## Releasing

```
git tag -a v0.2.0 -m "What changed"
git push origin v0.2.0
```

The workflow re-runs the whole gate on the tagged commit and then publishes
`com.tripletriad:core:0.2.0` to GitHub Packages, from macOS, so the Apple klibs are in the release
rather than missing from it.

**Publishing is one-way.** GitHub Packages will not overwrite a released version, and a version
resolved once is in somebody's cache regardless. There is no un-publishing a bad build; the remedy
is another version.

### Trying a change before releasing it

```
./gradlew publishToMavenLocal
```

Both consumers list `mavenLocal()` ahead of GitHub Packages, so this is how an engine change is
tried against them before it becomes a version anyone else can resolve. The mirror image is the
trap: a local install that is no longer wanted keeps shadowing the published artifact until it is
removed — `rm -rf ~/.m2/repository/com/tripletriad`.

## Consuming it

GitHub Packages requires authentication **even for a public package** — an anonymous request gets a
401, not a 200. Every consumer therefore needs a GitHub username and a token with `read:packages`,
and it belongs in `~/.gradle/gradle.properties`, outside every repository:

```
gpr.user=your-github-username
gpr.key=ghp_...
```

## Upgrading Kotlin

Here first, then the consumers. A Kotlin library's metadata cannot be read by a consumer on an
older language version, so the reverse order fails — at link time, on a target that is often only
built in CI. `kotlin`, `serializationJson` and `coroutines` in `gradle/libs.versions.toml` are the
three versions that must agree across all three repositories.
