# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A single-module Kotlin Multiplatform library published as `com.tripletriad:core` — the Triple Triad
rules engine plus the game data it operates on. It has two consumers, in sibling directories when
they are checked out: `../tto-client` (the app) and `../tto-server` (the referee). Read `README.md`
for why it is its own repository and `docs/RELEASING.md` for how the three move together.

## Commands

```bash
cp local.properties.sample local.properties   # sdk.dir — the Android target needs it locally
./gradlew build                               # the whole gate: ktlint, detekt, both host test runs, coverage floor
```

| | |
|---|---|
| `./gradlew desktopTest` | common tests on the desktop JVM |
| `./gradlew testAndroidHostTest` | the *same* common tests on Android's JVM — every test runs twice |
| `./gradlew desktopTest --tests "com.tripletriad.model.RulesEngineTest"` | one class (or `--tests "*.RulesEngineTest.aMethodName"`) |
| `./gradlew allTests` | every target's tests, aggregated |
| `./gradlew ktlintFormat` / `ktlintCheck` / `detekt` | detekt is `maxIssues: 0` — any finding fails |
| `./gradlew coverageReport` / `coverageVerify` | JaCoCo on the desktop target; `check` depends on the verify |
| `./gradlew publishToMavenLocal` | try an engine change against a consumer before releasing it |
| `python tools/release.py check` | read-only audit of the three repositories' versions |

The Apple targets compile only on macOS — Kotlin/Native skips them silently elsewhere, so a green
build on Linux says nothing about iOS. That is what the `ios` job in `.github/workflows/build.yml`
is for. Coverage floor is 90% line / 75% branch; this module is pure logic, so there is nothing a
test cannot reach.

After a `publishToMavenLocal`, remember that both consumers put `mavenLocal()` **ahead** of GitHub
Packages: a stale local install keeps shadowing the published artifact until
`rm -rf ~/.m2/repository/com/tripletriad`. See `docs/RELEASING.md` § 2.1 for what that cost once.

## The constraint that shapes everything

`commonMain` imports `kotlin`, `kotlinx` and KotlinCrypto, and nothing else. **No Compose, no UI, no
resource bundle, no platform I/O, and no clock.** Anything needing the wall time takes an `at: Long`;
anything needing randomness takes a `Random`. Catalog *parsers* live here (`CardCatalog.parse` and
friends); the code that reads bytes out of a resource bundle stays in the client's `:shared`.

If a change wants a new dependency in `commonMain`, that is almost certainly a sign the code belongs
in a consumer instead.

## Determinism is load-bearing, not a nicety

The server verifies a match by **replaying it with this engine**, so anything that changes what the
engine computes from a seed invalidates transcripts already written.

* One `Random`, seeded once, threaded through the deal and then the opponent's turns, **in the order
  the client used it** — that ordering *is* the protocol (`protocol/TranscriptVerifier.kt`). The
  invariant it imposes on consumers: on the player's own turn, nothing may draw from the match
  generator; `MatchState.playableCards(random)` and any auto-play must be given a separate one.
* `ReplayDeterminismTest` holds golden values. **A golden breaking is not a test to update** — it
  means stored transcripts now replay to a different answer, which is a version bump and a migration
  question.
* Whoever holds the randomness decides the outcome, so it must not be the party the outcome is worth
  something to. That argument is why PvP is refereed (`protocol/PvpMatch.kt`), why booster rolls
  moved server-side (`protocol/Bag.kt`), and why seeds are issued as tickets
  (`protocol/SeedTickets.kt`).

## Layout

* **`model/`** — the rules and the match as immutable values. `RulesEngine.resolve` is one placement
  as a pure function (captures, precedence, combo waves); `MatchState` is the whole match as
  `MatchState -> MatchState` transitions; `Board`, `Card`, `Power` (effective power and the
  Ascension tally), `GameRules`, `Roulette`, `MatchAi`, `MatchSetup`, `GameSave` (the player profile,
  serialized as one JSONB document by the server), `MatchView` (what one side may see).
* **`data/`** — catalogs and economy: `CardCatalog`, `NpcCatalog`, `FormatCatalog`, `CampaignCatalog`,
  `StarterCatalog`, `ShopCatalog` parse JSON the consumers supply; `PveMatches.assemble` turns a
  profile plus an opponent into a playable match; `MatchRewards.credit` / `creditPvp` is the one
  credit path both ends run; `CardValue` / `BoosterPricing` are one authored value ladder read two
  ways.
* **`protocol/`** — every type that crosses the wire, defined once so the two ends cannot disagree:
  `MatchTranscript` and `TranscriptVerifier`, `AppVersion` and the version gate, `Accounts`,
  `PvpMatch` / `PvpTable`, `Bag`, `SeedTickets`, `ServerInfo`, `PeerHandshake` (commit-then-reveal
  seed agreement for peer play).
* **`time/`** — `CivilDate`, UTC day arithmetic, because a daily reset the server cannot verify is
  not a reset.

A format (`data/FormatCatalog.kt`) is the unit that replaced the old per-collection `MODE`: it
decides which cards are legal, which opponents exist, and which rules the roulette may draw. Card ids
are global — `id = (block shl 8) or number` — so legality is `card.id shr 8 in format.blocks`.

## Versions

Four numbers in this system are not the same number (`docs/RELEASING.md` § 1). Two of them live in
this repository and neither is the artifact version:

| Number | Where | Moves when |
|---|---|---|
| Artifact | `-PcoreVersion`, defaulted in `build.gradle.kts`, set from the git tag by CI | every engine release |
| Protocol | `CURRENT_VERSION` in `protocol/AppVersion.kt` | only on a replay-affecting or wire-breaking change |
| Transcript format | `TRANSCRIPT_VERSION` in `protocol/MatchTranscript.kt` | when a stored transcript would be misread |

Nothing in the source states a released artifact version, so the tag and the artifact cannot
disagree. **Publishing is one-way** — GitHub Packages will not overwrite a version and a resolved
version is in somebody's cache; the only remedy for a bad build is another version. A Kotlin upgrade
also goes here first, then the consumers, never the reverse.

`AppVersion`'s KDoc is the authority on what a major bump promises; read it before touching either
constant. `tools/release.py` deliberately refuses to bump when one has moved unless
`--protocol-moved` says it was on purpose.

The four published targets — `android`, `desktop` (JVM 17), `iosArm64`, `iosSimulatorArm64` — must
stay exactly the four the client's `:shared` declares.

## Style

ktlint (`intellij_idea`, 100 columns, explicit imports only — no wildcards) and detekt with the
overrides in `detekt/detekt.yml`, each of which states its reason. `.editorconfig` is shared with the
IDE so the two cannot disagree.

The prevailing comment convention is heavy and deliberate: KDoc says *why* a thing is the way it is,
cites the AS3 original it was ported from (`TTOCore.as:304`, `BaseMatchScreen.as:242`) and names the
behaviour that was reproduced faithfully versus the defect that was corrected on purpose — see
`RulesEngineOptions` and `MatchAiOptions`, which make every such departure a switch rather than a
silent choice. Match that density when editing these files; a bare change to a rule here is
indistinguishable from a bug.
