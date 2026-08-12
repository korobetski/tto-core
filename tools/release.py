"""Move the three repositories to a new engine version, in the fixed order.

Usage, from anywhere:

    python tto-core/tools/release.py check
    python tto-core/tools/release.py bump 0.5.0 --client 1.1.0
    python tto-core/tools/release.py bump 0.5.0 --client 1.1.0 --push

`check` is read-only and always safe.  `bump` edits pins and creates **local**
git tags; nothing leaves the machine unless `--push` is given, and even then it
pushes tags only — never a branch, never a commit.


What this automates, and what it deliberately does not
------------------------------------------------------

RELEASING.md § 3 fixes the order and gives the reason:

    tag tto-core  →  publish  →  client pins it  →  server pins it  →  deploy

Setting three numbers is the easy part and is not why this exists.  What it
exists for is everything *between* the numbers — the checks that were learned
the expensive way on 2026-08-11 and that a person doing this by hand skips
exactly when they are in a hurry:

* **The `~/.m2` trap** (§ 2.1).  Both consumers put `mavenLocal()` ahead of
  GitHub Packages so a `publishToMavenLocal` can be tried before release.  The
  mirror image is that a stale local artifact makes a consumer compile against
  an engine that exists on no other machine — the client's `main` did exactly
  that for weeks.  `check` looks, and `bump` clears it before it verifies
  anything, so a green build means the published artifact was green.

* **The two consumers agreeing.**  A match is verified by replaying it with the
  engine both sides linked, so a client and a server on different engine
  versions is the one bug the whole extraction exists to make impossible.  Both
  pins move in one operation or neither does.

* **The protocol version.**  Not set here — see `--protocol-moved` below.

It does **not** deploy, does not touch `/srv/tto/.env`, and does not run the
data check of § 2.2.  That one is a SQL query against the production database
and a human reading a `cards.json` diff; automating the query without the
reading would be automating the half that never went wrong.


Why the protocol version is not a flag you set
----------------------------------------------

`CURRENT_VERSION` moves on a **replay-affecting break** and on nothing else.
That is a judgement about what a change means, not a number to increment, and a
script that offered `--protocol 2.0.0` would be inviting whoever runs it to
answer a design question in a hurry with their hand on the trigger.

So this reads `CURRENT_VERSION` and `TRANSCRIPT_VERSION`, compares them against
the previous tag, and **refuses to continue** when either has moved unless
`--protocol-moved` says it was deliberate.  The direction of the guard is the
useful one: forgetting to think about it is the failure mode, not mistyping it.
"""

import argparse
import re
import shutil
import subprocess
import sys
from pathlib import Path

# The three repositories, as siblings. `release.py` lives in tto-core/tools/.
CORE = Path(__file__).resolve().parent.parent
ROOT = CORE.parent
CLIENT = ROOT / "tto-client"
SERVER = ROOT / "tto-server"

# Where each number lives. See RELEASING.md § 1 — there are four and they are not
# the same number.
CORE_PIN = "gradle/libs.versions.toml"           # `core = "0.5.0"`, in both consumers
CLIENT_VERSION = "gradle.properties"             # `clientVersion=1.1.0`
PROTOCOL = "src/commonMain/kotlin/com/tripletriad/protocol/AppVersion.kt"
TRANSCRIPT = "src/commonMain/kotlin/com/tripletriad/protocol/MatchTranscript.kt"

# `~/.m2` under the one group that matters. Clearing the whole local repository
# would be rude to every other project on the machine.
MAVEN_LOCAL = Path.home() / ".m2" / "repository" / "com" / "tripletriad"

SEMVER = re.compile(r"^\d+\.\d+\.\d+(-SNAPSHOT)?$")


def run(cmd, cwd, capture=True, check=True):
    """A subprocess that reports the command it was when it fails."""
    result = subprocess.run(
        cmd, cwd=cwd, text=True, check=False,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
    )
    if check and result.returncode != 0:
        out = (result.stdout or "").strip()
        fail(f"{' '.join(cmd)} in {cwd.name} exited {result.returncode}\n{out}")
    return (result.stdout or "").strip()


def fail(message):
    print(f"\n  ✗ {message}\n", file=sys.stderr)
    sys.exit(1)


def note(message):
    print(f"  · {message}")


def ok(message):
    print(f"  ✓ {message}")


# ---- reading the current state ------------------------------------------


def pinned_core(repo):
    """The `core = "…"` a consumer resolves against today."""
    text = (repo / CORE_PIN).read_text()
    found = re.search(r'^core\s*=\s*"([^"]+)"', text, re.M)
    if not found:
        fail(f"{repo.name}/{CORE_PIN} declares no `core` version")
    return found.group(1)


def client_version():
    text = (CLIENT / CLIENT_VERSION).read_text()
    found = re.search(r"^clientVersion=(.+)$", text, re.M)
    if not found:
        fail(f"tto-client/{CLIENT_VERSION} declares no clientVersion")
    return found.group(1).strip()


def constant_in(path, name):
    """A `const val NAME: T = value` or `val NAME: T = AppVersion(1, 1, 0)`."""
    text = path.read_text()
    found = re.search(rf"\b{name}\s*:\s*\w+\s*=\s*(.+)$", text, re.M)
    return found.group(1).strip() if found else None


def protocol_numbers(at_tag=None):
    """`CURRENT_VERSION` and `TRANSCRIPT_VERSION`, now or at a past tag."""
    if at_tag is None:
        return (
            constant_in(CORE / PROTOCOL, "CURRENT_VERSION"),
            constant_in(CORE / TRANSCRIPT, "TRANSCRIPT_VERSION"),
        )
    versions = []
    for path, name in ((PROTOCOL, "CURRENT_VERSION"), (TRANSCRIPT, "TRANSCRIPT_VERSION")):
        text = run(["git", "show", f"{at_tag}:{path}"], CORE, check=False)
        found = re.search(rf"\b{name}\s*:\s*\w+\s*=\s*(.+)$", text, re.M)
        versions.append(found.group(1).strip() if found else None)
    return tuple(versions)


def fetch_tags():
    """Refresh the local tag list from the remote, read-only.

    Not a nicety. On 2026-08-12 this machine's `tto-core` had five tags and the
    remote had six: `v0.4.0` had been pushed from elsewhere and never fetched
    back. Deciding "the latest tag is v0.3.1" from that would have compared the
    protocol against the wrong baseline and, worse, let `bump 0.4.0` through —
    to be refused by a remote that already had it, after the local build had
    already run.
    """
    run(["git", "fetch", "--tags", "--quiet"], CORE, check=False)


def latest_core_tag():
    tags = run(["git", "tag", "--list", "v*", "--sort=-v:refname"], CORE)
    return tags.splitlines()[0].strip() if tags else None


def remote_has_tag(tag):
    """Whether the remote already published [tag]. Empty output means it did not."""
    return bool(run(["git", "ls-remote", "--tags", "origin", tag], CORE, check=False))


def is_dirty(repo):
    return bool(run(["git", "status", "--porcelain"], repo))


# ---- check ---------------------------------------------------------------


def check(args):
    """Everything `bump` would refuse on, reported without changing anything."""
    fetch_tags()
    print("\nWorking trees")
    dirty = [r for r in (CORE, CLIENT, SERVER) if is_dirty(r)]
    for repo in (CORE, CLIENT, SERVER):
        count = len(run(["git", "status", "--porcelain"], repo).splitlines())
        (note if count else ok)(
            f"{repo.name}: {count} uncommitted change(s)" if count
            else f"{repo.name}: clean"
        )

    print("\nThe four numbers (RELEASING.md § 1)")
    client_pin, server_pin = pinned_core(CLIENT), pinned_core(SERVER)
    ok(f"engine tag        {latest_core_tag() or '(none)'}")
    (ok if client_pin == server_pin else note)(
        f"engine pin        client {client_pin}   server {server_pin}"
    )
    protocol, transcript = protocol_numbers()
    ok(f"protocol          {protocol}   transcript {transcript}")
    ok(f"client app        {client_version()}")

    print("\nThe ~/.m2 trap (RELEASING.md § 2.1)")
    if MAVEN_LOCAL.exists():
        local = sorted(p.name for p in (MAVEN_LOCAL / "core").glob("*")) \
            if (MAVEN_LOCAL / "core").exists() else []
        note(f"{MAVEN_LOCAL} holds {local or 'nothing'}")
        if client_pin in local or server_pin in local:
            note(
                f"the pinned {client_pin} is present locally — a green build here "
                "proves nothing about CI"
            )
    else:
        ok("no local com.tripletriad artifact; a green build is a real one")

    problems = []
    if dirty:
        problems.append(f"uncommitted work in {', '.join(r.name for r in dirty)}")
    if client_pin != server_pin:
        problems.append("the two consumers pin different engine versions")
    if "SNAPSHOT" in client_pin or "SNAPSHOT" in server_pin:
        problems.append("a consumer is pinned to a SNAPSHOT, which exists only on this machine")

    print()
    for problem in problems:
        note(f"blocks a release: {problem}")
    if not problems:
        ok("ready to release")
    return 1 if problems else 0


# ---- bump ----------------------------------------------------------------


def rewrite(path, pattern, replacement, what):
    text = path.read_text()
    new, count = re.subn(pattern, replacement, text, flags=re.M)
    if count != 1:
        fail(f"{path}: expected one {what}, matched {count}")
    path.write_text(new)


def bump(args):
    if not SEMVER.match(args.version):
        fail(f"'{args.version}' is not a version this can tag")
    tag = f"v{args.version}"

    print("\n1. Refusing to start on a bad footing")
    for repo in (CORE, CLIENT, SERVER):
        if is_dirty(repo) and not args.allow_dirty:
            fail(
                f"{repo.name} has uncommitted work. A release is cut from what is "
                "committed, or the tag names something nobody else can obtain. "
                "Pass --allow-dirty only if you know why."
            )
    fetch_tags()
    if run(["git", "tag", "--list", tag], CORE) or remote_has_tag(tag):
        fail(
            f"{tag} already exists. GitHub Packages will not overwrite a released "
            "version and the only remedy is another version — see RELEASING.md § 2.1."
        )
    ok("worktrees and tag name")

    print("\n2. The protocol question (this script will not answer it)")
    previous = latest_core_tag()
    now = protocol_numbers()
    if previous:
        before = protocol_numbers(previous)
        moved = [
            f"{name}: {b} → {n}"
            for name, b, n in zip(("CURRENT_VERSION", "TRANSCRIPT_VERSION"), before, now)
            if b is not None and b != n
        ]
        if moved and not args.protocol_moved:
            fail(
                "the protocol moved since " + previous + ":\n      "
                + "\n      ".join(moved)
                + "\n\n    A protocol version moves on a replay-affecting break and on"
                "\n    nothing else. If that is what this is, say so with"
                "\n    --protocol-moved. If it is not, revert it before releasing."
            )
        if moved:
            ok("protocol moved, acknowledged: " + "; ".join(moved))
        else:
            ok(f"protocol unchanged since {previous}")
    else:
        note("no previous tag to compare against")

    print("\n3. Clearing the local artifact (RELEASING.md § 2.1)")
    if MAVEN_LOCAL.exists():
        shutil.rmtree(MAVEN_LOCAL)
        ok(f"removed {MAVEN_LOCAL}")
    else:
        ok("nothing to clear")

    print(f"\n4. Verifying tto-core at {args.version}")
    run(["./gradlew", "build", "--no-daemon", f"-PcoreVersion={args.version}"],
        CORE, capture=False)
    ok("ktlint, detekt and every test")

    print(f"\n5. Tagging tto-core {tag}")
    run(["git", "tag", "-a", tag, "-m", f"core {args.version}"], CORE)
    ok(f"{tag} created locally")

    if not args.push:
        print(f"""
    Stopping here, because the next step leaves this machine and publishing is
    one-way: GitHub Packages will not overwrite {args.version}, ever.

    Push the tag — the publish workflow builds and uploads from it:

        git -C {CORE} push origin {tag}

    Then re-run with --push, or continue by hand from RELEASING.md § 3.
""")
        return 0

    print(f"\n6. Publishing {tag}")
    run(["git", "push", "origin", tag], CORE, capture=False)
    ok("tag pushed; the publish workflow is building it")
    print("""
    Wait for the workflow to go green before continuing — the consumers cannot
    resolve an artifact that has not been uploaded, and a failed resolve here
    looks exactly like a broken pin.
""")
    input("    Press Return once the publish workflow is green, or Ctrl-C. ")

    print("\n7. Pinning both consumers")
    for repo in (CLIENT, SERVER):
        rewrite(repo / CORE_PIN, r'^core\s*=\s*"[^"]+"', f'core = "{args.version}"',
                "`core` pin")
        ok(f"{repo.name} pins {args.version}")
    if args.client:
        rewrite(CLIENT / CLIENT_VERSION, r"^clientVersion=.+$",
                f"clientVersion={args.client}", "clientVersion")
        ok(f"tto-client app version {args.client}")

    print("\n8. Verifying both consumers against the published artifact")
    run(["./gradlew", "build", "--no-daemon"], CLIENT, capture=False)
    ok("tto-client")
    run(["./gradlew", "build", "--no-daemon"], SERVER, capture=False)
    ok("tto-server")

    print(f"""
    Done, and nothing is committed. Review the pins, commit them, and tag:

        git -C {CLIENT} commit -am "pin core {args.version}"
        git -C {SERVER} commit -am "pin core {args.version}"

    ⚠  Deployment order is the reverse of the pinning order: the **server**
       must be deployed before a client build reaches players. An older server
       parses an incoming GameSave with ignoreUnknownKeys, silently drops what
       it does not know, and rewrites the amputated document on every credited
       match. See RELEASING.md § 4.
""")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("check", help="report the four numbers and what blocks a release")

    up = sub.add_parser("bump", help="move the engine version across the three repositories")
    up.add_argument("version", help="the engine version, without the leading v")
    up.add_argument("--client", help="also move the client app version")
    up.add_argument("--push", action="store_true",
                    help="push the tag and continue through the consumers")
    up.add_argument("--protocol-moved", action="store_true",
                    help="acknowledge a deliberate protocol or transcript version change")
    up.add_argument("--allow-dirty", action="store_true",
                    help="release from uncommitted worktrees (it will not be reproducible)")

    args = parser.parse_args()
    for repo in (CORE, CLIENT, SERVER):
        if not (repo / ".git").exists():
            fail(f"{repo} is not a git repository — the three must be siblings")
    sys.exit(check(args) if args.command == "check" else bump(args))


if __name__ == "__main__":
    main()
