<div align="center">

# tto-core

**The rules engine of [Triple Triad Online](https://tto.moebiuscore.fr) — one engine, run by the
client to play a match and by the server to replay it.**

[🌐 Website](https://tto.moebiuscore.fr) ·
[🎮 Play in the browser](https://playtto.moebiuscore.fr) ·
[⬇️ Download the game](https://github.com/korobetski/tto-client/releases/latest) ·
[💬 Discord](https://discord.gg/cPZW74AUTj)

[![Build and Test](https://github.com/korobetski/tto-core/actions/workflows/build.yml/badge.svg)](https://github.com/korobetski/tto-core/actions/workflows/build.yml)
[![Latest version](https://img.shields.io/github/v/tag/korobetski/tto-core?sort=semver&label=com.tripletriad%3Acore)](https://github.com/korobetski/tto-core/tags)
![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)
![Coverage gate](https://img.shields.io/badge/coverage%20gate-90%25%20line%20%C2%B7%2075%25%20branch-brightgreen)

</div>

---

The Triple Triad rules, and the game data they operate on. No UI, no resource bundle, no platform
I/O — a Kotlin Multiplatform library published to GitHub Packages as `com.tripletriad:core`.

```kotlin
implementation("com.tripletriad:core:0.12.5")
```

## Part of Triple Triad Online

| | Repository | Role |
|---|---|---|
| 🃏 | [tto-client](https://github.com/korobetski/tto-client) | the game — Android, Windows, macOS, Linux and the browser. **Plays** the match |
| ⚙️ | **tto-core** — *you are here* | the rules engine both ends link |
| 🛡️ | [tto-server](https://github.com/korobetski/tto-server) | the authority — **replays** the match, and decides the score |
| 🌐 | [tto.moebiuscore.fr](https://tto.moebiuscore.fr) | the public site: news, rules, downloads and sign-up |

## Why it is a repository and not a module

The server verifies a match by **replaying it with the real engine** rather than with a second
implementation of the rules. Two implementations of Triple Triad's chain and combo resolution would
agree until they did not, and the disagreement would surface as a player being told they lost a
match they watched themselves win.

So there is one engine, and both the client and the server link it. It lived inside the client
repository first, which made it linkable from the client and — via whatever a developer had
published into their own `~/.m2` — from a server build that only certain laptops could perform.
Here, both consumers resolve the same artifact the same way, which is what makes the server
buildable by CI and therefore deployable at all.

## What is inside

| Package | Holds |
|---|---|
| `model` | the board, the rules engine (capture, Same, Plus, combo, Ascension…), match state and setup, the roulette, the AI and its search, XP, achievements and daily quests |
| `data` | the catalogs — cards, NPCs, formats, campaigns, starters — and everything priced or paid: rewards, the shop, boosters, the inventory, the auction house's rules |
| `protocol` | what crosses the wire: accounts, PvE and PvP matches, auctions, the bag, the version handshake, and the `TranscriptVerifier` the server judges with |

Anything the server has to compute — what a win pays, what a pack contains, whether a transcript
replays — is computed here, so that the client and the server cannot disagree about it.

## Targets

`android`, `desktop` (JVM 17), `iosArm64`, `iosSimulatorArm64` and `wasmJs` — exactly the targets
the client's `:shared` declares, and they have to stay in step with it: a target published here with
no consumer is a klib nobody links, and a target the client needs and this does not publish is a
build failure in the other repository.

`wasmJs` is what makes [the browser game](https://playtto.moebiuscore.fr) possible at all. The
server rejects a transcript that does not replay as cheating, so a browser client either links this
engine or plays by a second copy of the rules — and the second copy is precisely what this
repository exists to prevent.

JVM 17 rather than 21, though the server runs 21. A consumer can be newer than the library; the
reverse fails at compile time, so the library is the one that stays lower.

## Building it

```bash
cp local.properties.sample local.properties      # sdk.dir, for the Android target
./gradlew build
```

`build` is ktlint, detekt, and the common tests on every host target:

| Where the tests run | Task |
|---|---|
| desktop JVM | `desktopTest` |
| Android host JVM | `testAndroidHostTest` |
| Node | `wasmJsNodeTest` |
| headless Chrome and Firefox | `wasmJsBrowserTest` |

…and a coverage floor of **90% line / 75% branch** that `check` depends on. The floor is high
because this module is pure logic: there is nothing in it a test cannot reach.

> [!NOTE]
> The browser run needs both browsers installed where it runs; the GitHub runners have them.
> Mocha's two-second budget per test is raised in `karma.config.d/`, because `MatchAiLadderTest`
> plays 160 searched matches — about 3.5 s on the JVM and 10 s under wasm — and the ladder is what
> it measures.

> [!WARNING]
> The Apple targets compile only on a Mac. Kotlin/Native skips them silently everywhere else, so a
> green build on Windows says nothing about iOS — that is what the `ios` job in CI is for.

## Releasing

```bash
git tag -a v0.13.0 -m "What changed"
git push origin v0.13.0
```

The workflow re-runs the whole gate on the tagged commit and then publishes
`com.tripletriad:core:0.13.0` to GitHub Packages, from macOS, so the Apple klibs are in the release
rather than missing from it.

> [!CAUTION]
> **Publishing is one-way.** GitHub Packages will not overwrite a released version, and a version
> resolved once is in somebody's cache regardless. There is no un-publishing a bad build; the remedy
> is another version.

Moving the engine, the client and the server together is its own runbook:
[docs/RELEASING.md](docs/RELEASING.md) — written the day it first went wrong end to end, and every
trap in it actually happened.

### Trying a change before releasing it

```bash
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

```properties
gpr.user=your-github-username
gpr.key=<token with read:packages>
```

## Upgrading Kotlin

Here first, then the consumers. A Kotlin library's metadata cannot be read by a consumer on an
older language version, so the reverse order fails — at link time, on a target that is often only
built in CI. A consumer may run *ahead* of this repository, never behind it.

`kotlin`, `serializationJson` and `coroutines` in `gradle/libs.versions.toml` are the three versions
to compare across the three repositories before any upgrade.
