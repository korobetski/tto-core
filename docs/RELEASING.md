# Releasing across the three repositories

`tto-core`, `tto-client` and `tto-server` ship as one thing and version as three.
This document is the runbook for moving them together, and — more usefully — the record of what went
wrong the first time it was attempted end to end, on 2026-08-11, going from `core 0.1.0` to
`core 0.2.0` and the client's first signed APK.

Every trap below actually happened that day. None of them announced itself: the expensive ones were
all silent, and several looked like success.

> **Amended 2026-08-11, later the same day.** §7's parked open question is **resolved** — the client
> now knows its own release number, so `TTO_CLIENT_VERSION` finally means what it reads as. The
> workaround this document used to prescribe has become the wrong advice, so §7 and §8 are rewritten
> rather than annotated.

---

## 1. Four numbers that are not the same number

More than half of the confusion came from here. Write these down before touching anything.

| Number | Lives in | Moves when | Value on 2026-08-11 |
|---|---|---|---|
| **Engine artifact** | `-PcoreVersion`, tag on `tto-core` | every engine release | `0.2.0` |
| **Protocol** | `CURRENT_VERSION` in `AppVersion.kt` | only on a replay-affecting break | `1.0.0` |
| **Server** | its **git tag** — nothing in the source | every server release | `v0.2.0` |
| **Client app** | `clientVersion` in `gradle.properties` | every app release | `1.0.4` |

The protocol version is the one that surprises people. It is what `GET /server` reports as
`version` and `minimumClient`, and what the gate compares — so a perfectly current deployment
answers `1.0.0` while running engine `0.2.0`, and that is correct.

It was made worse by an accident of history: both consumers used to pin the engine at
`core = "1.0.0-SNAPSHOT"`, a number that echoed the protocol version and meant something else
entirely. If a version number looks familiar, check which of the four it is before concluding
anything.

The **client app** number is now readable from code as well as from the build: `:shared:buildVersion`
generates `com.tripletriad.CLIENT_VERSION` from that one property, so the app can print which build
it is — it is at the foot of the sign-in screen — and compare itself against a published one. Before
that, `clientVersion` reached the APK's manifest and nothing else, which is what made §7 go wrong.

> **Trap.** "Why does the server expect a client at 1.0.0?" is not a misconfiguration. It is the
> protocol version, hard-coded in `:core`, and no environment variable changes it.

The server is the odd one out, and deliberately so since 2026-08-11: **it has no version constant**.
It used to, and it said `0.1.1` while `v0.1.2` through `v0.2.0` had all shipped — four releases of
drift nothing caught, because nothing read it. The image is tagged from `github.ref_name`, the
deployment pulls by digest, and `GET /server` reports the protocol version. So the constant fed
nothing and was deleted rather than bumped; `tto-server/build.gradle.kts` says why where it used to
be. To read what is deployed, read the tag.

> **Trap, the other way round: a commit named after a number it does not set.** Three commits on
> `tto-server` are named `version 0.2.0` and **none** changes the server's version — one moves the
> `core` pin to `0.2.0`, one hardens the deploy script, one deletes the constant described above.
> They are named after the **engine**.
>
> `tto-client` did the same thing on the same day, and there it had a consequence: `3c48824` is
> named `version 1.0.4` and touches neither `gradle.properties` nor `libs.versions.toml`, so the tag
> `v1.0.4` shipped while the property still read `1.0.3`. The published APK was correct — the
> workflow passes `-PclientVersion` from the tag, overriding the property — but every local build
> disagreed with it, which is exactly what that property's own comment warns against.
>
> The habit is the problem, not either commit. **Check what a commit touched, not what it is
> called**, and bump the property in the commit you name after it.

---

## 2. Before anything: the two checks that are not code

### 2.1 The `~/.m2` trap

`settings.gradle.kts` in both consumers puts `mavenLocal()` **ahead** of GitHub Packages, on
purpose, so that `publishToMavenLocal` can be used to try an engine change before releasing it. The
cost is the mirror image, and it is the single most expensive thing that happened that day:

The client's `main` pinned `core = "1.0.0-SNAPSHOT"`. A stale copy of that snapshot sat in `~/.m2`,
built before the global card ids landed. So `main` compiled — against an engine that no longer
existed in any repository, on any other machine, or in CI. The breakage had been there for weeks and
nothing could see it.

Moving the pin to a real version is what exposed it: seven `Unresolved reference` errors in files
nobody had touched.

```bash
# Before trusting a green build on a version bump:
ls ~/.m2/repository/com/tripletriad/core/
rm -rf ~/.m2/repository/com/tripletriad   # and rebuild
```

> **Trap.** A green local build against a `-SNAPSHOT` proves nothing. CI has no `~/.m2`, and neither
> does anyone else.

### 2.2 The data check

Code compatibility is the easy half. The engine also carries **data**, and the database holds player
saves shaped by it.

`characters.save` is a single `JSONB` document — the whole `GameSave`, including `"CARDS"` (a map
keyed by card id) and `"DECKS"` (each listing ids). No Flyway migration touches it and none could;
it is opaque to the schema.

Between `v0.1.4` and `v0.2.0` the catalogs were renumbered onto global ids:

| | card ids |
|---|---|
| before | `1` … `110` |
| after | `257` … `622` |

Deploying that does not fail. It silently invalidates every stored profile: owned cards resolve to
nothing, decks reference cards that do not exist, and `TranscriptVerifier` rejects them as
`DECK_NOT_OWNED` — which the `VersionGate` KDoc correctly calls *indistinguishable from cheating*.

Run this **before** the deploy, not after:

```sql
SELECT count(*) AS total,
       count(*) FILTER (
         WHERE EXISTS (SELECT 1 FROM jsonb_object_keys(save->'CARDS') k WHERE k::int < 256)
       ) AS to_remap
FROM characters;
```

The threshold is reliable because the id scheme is idempotent by construction — `docs/migration/19`
declares the range `1..255` poison, so `< 256` means "never migrated". The same query is the
verification afterwards.

> **Trap.** `git diff <old-tag> <new-tag> -- src/main/resources/catalog/` is part of release
> preparation. A diff of thousands of lines in `cards.json` is a data migration, whatever the code
> diff says.

---

## 3. The order, and why it is fixed

```
tag tto-core  →  publish  →  client pins it  →  server pins it  →  deploy
```

A Kotlin library's metadata cannot be read by a consumer on an older language version, so the engine
moves first. The client and the server must land on the **same** engine version, because a match is
verified by replaying it with the engine both sides linked — an app submitting a transcript its
server replays with a different engine is the one bug the extraction exists to make impossible.

Check before tagging the consumers:

```bash
grep '^core = ' tto-client/gradle/libs.versions.toml tto-server/gradle/libs.versions.toml
```

---

## 4. Deployment

`scripts/deploy.sh` was hardened after this release; the notes below are why, and they are worth
knowing because the failure modes are all silent.

### 4.1 Never cancel a deployment in flight

The workflow header says so, and the day proved it. A cancel arrives on the VPS as a dead SSH
session partway through a script that was never designed to be interrupted.

The specific damage, before the fix: `pin_image` ran **before** the readiness gate, so `/srv/tto/.env`
already named the new digest for a stack that had never proved it starts. Two consequences —

* a reboot would have brought up the unvalidated image;
* the next run reads `PREVIOUS` from that same `.env`, finds `PREVIOUS == IMAGE`, and takes the
  "nothing to roll back to" branch. **The interrupted run destroys its own rollback target.**

Now the pin happens last, after `/health/ready` answers. Whatever happens, `.env` names a version
that served.

### 4.2 What could hang, and what it cost

Two unbounded operations, both now under `timeout`:

* `docker pull` — a stalled registry read on a small VPS never returns.
* `docker compose up -d` — not obvious. `server` declares
  `depends_on: postgres: condition: service_healthy`, so it blocks until the database is healthy,
  and a postgres in `restart: unless-stopped` that crash-loops re-enters `starting` on every cycle
  and never reaches the terminal `unhealthy` that would end the wait.

Because the release job has `concurrency: cancel-in-progress: false`, a hang did not fail one
release — it queued every later one behind GitHub's six-hour default, which is what forced the
manual cancel that caused §4.1. The job now carries `timeout-minutes: 20`, and the three `ssh` calls
carry `ServerAliveInterval`/`ConnectTimeout` via `~/.ssh/config`.

### 4.3 Do the pull out of band

The safest sequence, and the one that makes a stalled network cost nothing:

```bash
ssh <vps> 'cd /srv/tto && ./scripts/backup.sh'
ssh <vps> 'timeout 900 docker pull ghcr.io/korobetski/tto-server:vX.Y.Z'
ssh <vps> 'cd /srv/tto && nohup timeout 900 ./scripts/deploy.sh "ghcr.io/korobetski/tto-server:vX.Y.Z" > /tmp/deploy.log 2>&1 &'
```

`nohup` matters: it detaches the script from the SSH session, so a dropped connection no longer kills
it mid-flight.

---

## 5. GitHub Actions traps

### 5.1 A tag runs the workflow **as it was at that commit**

This cost two tags. Fixing `release.yml` on `main` and then re-running — or tagging a commit that
predates the fix — runs the old file. A workflow fix is only live for tags created *after* it is
committed and pushed.

> If a release fails on a workflow bug: commit the fix, push, then create a **new** tag. Re-running
> the failed job replays the old file.

### 5.2 Creating a release in the UI creates the tag

"Draft a new release" makes the release *and* the tag, and the tag push triggers the workflow. So by
the time the job reaches `gh release create`, the release already exists — **every time**, by
construction. The step is now idempotent: create if absent, `gh release upload` if the release exists
without the asset, and refuse if the asset is already there.

That last branch is deliberate and has no `--clobber`: an asset already published may already have
been downloaded, and replacing it serves different bytes under a URL that names a version. A rebuild
that must go out takes a new tag.

### 5.3 Environments

* Names are **not** case sensitive — `Production` matches `environment: production`.
* A workflow referencing an environment that does not exist **creates it silently, with no
  protection rules**. Nothing fails. So a required reviewer configured in the wrong repository gates
  nothing, and you will not be told.
* Environments are **per repository**. One created in the client has no effect on the server.
* An environment **secret** is only visible to a job that declares that environment. Repository
  secrets are visible everywhere. Prefer repository secrets unless there is a reason.
* `timeout-minutes` does **not** run while a job waits for approval. Approvals expire after 30 days.
* Do not enable *Prevent self-review* on a solo project — it locks the only reviewer out.

### 5.4 Which secrets, where

| Repository | Required | Optional |
|---|---|---|
| `tto-core` | none | — |
| `tto-server` | `VPS_HOST`, `VPS_USER`, `VPS_SSH_KEY`, `VPS_KNOWN_HOSTS` | `VPS_PORT` (default `22`), `GH_PACKAGES_TOKEN` |
| client | `TTO_KEYSTORE_BASE64`, `TTO_KEYSTORE_PASSWORD`, `TTO_KEY_ALIAS`, `TTO_KEY_PASSWORD` | `GH_PACKAGES_TOKEN` |

`GITHUB_TOKEN` is injected by GitHub and must never be created by hand — doing so breaks all three.

---

## 6. Android release traps

### 6.1 `versionCode` is what makes an update installable

It was hard-coded to `1` and had never moved, alongside a `versionName` of `"0.1.0"` and a desktop
`packageVersion` of `"1.0.0"` — three numbers, edited separately, already disagreeing.

Android refuses an APK whose code is not **greater** than the installed one. A frozen `versionCode`
does not produce a release that fails to install; it produces one that *cannot* be installed, and the
message the player sees says nothing about a version.

All three now derive from `clientVersion` in `gradle.properties`, overridable as
`-PclientVersion=1.1.0` from the tag. The mapping is `major * 10_000 + minor * 100 + patch`, so
`10203` reads back as `1.2.3`, and minor/patch are bounded below 100 — otherwise `1.2.100` and
`1.3.0` both encode `10300`, silently.

### 6.2 An unsigned release APK is not a broken download

Without a `signingConfig`, `assembleRelease` produces an APK that installs on nothing. AGP does give
one honest signal: it names the output `androidApp-release-**unsigned**.apk` and produces no
`androidApp-release.apk` at all. The release workflow uses that — the absence of the expected file
*is* the diagnosis.

### 6.3 PKCS12: the key password is the store password

Since JDK 9 `keytool` writes **PKCS12** by default, whatever the file is called. PKCS12 cannot hold a
per-entry password distinct from the store's, and `keytool` says so and ignores `-keypass`:

> les mots de passe de clé et de banque distincts ne sont pas pris en charge pour les fichiers de
> clés d'accès PKCS12

Verified by building both ways: the store password signs, the ignored `-keypass` fails with
`KeytoolException: Failed to read key`. So `TTO_KEY_PASSWORD` **must equal** `TTO_KEYSTORE_PASSWORD`.
At the `keytool -genkeypair` prompt for the key password, press Enter.

### 6.4 `keytool` writes errors to stdout

This produced a bare `Error: Process completed with exit code 1` with no cause, because the step sent
stdout to `/dev/null`. The check now captures the output and echoes it on failure. The two messages
worth recognising:

| message | meaning |
|---|---|
| `keystore password was incorrect` | `TTO_KEYSTORE_PASSWORD` is wrong |
| `java.io.EOFException` | the file is incomplete — the secret or the keystore itself |

### 6.5 `base64 -d` is not a validity check

GNU coreutils decodes a truncated secret, and one holding something that was never a keystore, and
**exits 0 either way**. It never reports the problem; `keytool` always does. The decoded size is
reported rather than tested, because a real keystore is only a couple of kilobytes and the size alone
does not separate a good one from a bad one.

Encode from a file and verify the round trip rather than trusting a copy-paste:

```bash
base64 -w0 tto-release.jks > tto-release.jks.b64
diff <(base64 -d < tto-release.jks.b64 | sha256sum) <(sha256sum < tto-release.jks) && echo OK
```

### 6.6 The keystore, and its encoded twin

`*.jks` was gitignored from the start; `*.b64` was not — and the base64 is the shape that actually
lands in the working tree, since producing it is a manual step next to the file it encodes. **A
base64 of the keystore is the keystore.** Both patterns are now ignored.

Losing the keystore means never being able to update an installed app again. Back it up, with its
password, somewhere other than the machine that builds.

---

## 7. After the deploy

`TTO_CLIENT_VERSION` and `TTO_CLIENT_DOWNLOAD_ANDROID` in `/srv/tto/.env` are what make the
**server-sourced** update notice appear, and they are easy to forget: `release` is omitted from
`GET /server` when null, so a deployment that forgot them looks identical to one that has nothing to
announce. That silence is no longer total — see the note on the releases page below — but the
deployment's own answer is still the one a player on that server gets first.

**Set it to the app release you just published** — the same number as the tag, without the `v`:

```
TTO_CLIENT_VERSION=1.0.3
TTO_CLIENT_DOWNLOAD_ANDROID=https://github.com/korobetski/tto-client/releases/download/v1.0.3/tto-1.0.3.apk
```

> **This changed on 2026-08-11, and the old advice is now the wrong advice.** Until that day
> `Connectivity.kt` compared the announced `release.version` against `CURRENT_VERSION` — the
> *protocol* version — because common code had no access to the app's own release number. So
> `TTO_CLIENT_VERSION=1.0.2` showed "update available" to every client forever, including one
> already running 1.0.2, and this document told you to put the **protocol** version here instead.
>
> Do not. With the protocol version in that variable the notice can now never fire: `1.0.0` is
> older than every app that will ever read it. The comparison is against
> `com.tripletriad.CLIENT_VERSION` now — see §1 — so the variable finally means what it reads as.
>
> The fix was expected to need an `expect val clientAppVersion` per host. It did not: the number is
> generated from the one `clientVersion` property by `:shared:buildVersion`, so there is a single
> implementation and no `BuildConfig` involved.

**The client also asks GitHub directly**, once per launch, and that path never needed this variable:
it reads `/releases/latest` on the public repository — no token, sixty requests an hour per address —
maps the tag to a version and the `.apk` asset to a download. So a deployment that forgets these two
lines is no longer silent: once a newer build is tagged, the notice appears anyway, sourced from the
releases page. The deployment's answer takes precedence when it has one, because only a deployment
can say "this build cannot be served at all" — and that refusal must not be replaced by a suggestion
the player can dismiss.

What the variable still buys is a deployment that wants to announce something **other** than the
newest public release — a staged rollout, or a self-hosted server pinned to an older client.

Also worth knowing: an outdated client can still *play*, because the game is offline-first and a PvE
match is computed on the device. Nothing it does reaches the server — the gate covers every
state-changing route, and there are no websockets, so it is re-evaluated on every request. There is
nothing to "kick". But its queued transcripts sit in `TranscriptQueue` and will be submitted after an
update, carrying ids from before the renumbering; on a real deployment those need to be dropped.

---

## 8. Parked work

* ~~**`clientAppVersion` in `:shared`**~~ — **done, 2026-08-11.** `:shared:buildVersion` generates
  `com.tripletriad.CLIENT_VERSION` from `clientVersion`, and both update sources compare against it.
  See §7 for what that changes operationally, and §1 for the number itself.
* **A stable `latest` download URL** — GitHub's `/releases/latest/download/<asset>` works but needs a
  fixed asset name. Less pressing than it was: the client reads the releases page itself now, so the
  manual `.env` step is no longer the only way a player hears about a release. Still worth doing for
  the deployments that do set `TTO_CLIENT_DOWNLOAD_ANDROID`, which otherwise names one version
  forever.
* **detekt 2.x** — `1.23.8` emits a Gradle deprecation from inside `DetektPlugin.apply`
  (`ReportingExtension.file`), removed in Gradle 10. Not fixable from the build script.
