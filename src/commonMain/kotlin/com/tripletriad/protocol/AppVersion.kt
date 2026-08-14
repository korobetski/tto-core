package com.tripletriad.protocol

import kotlinx.serialization.Serializable

/**
 * The version the client and the server both claim to be.
 *
 * ### Why this lives in `:core` and not in either side's build file
 *
 * Because the two must not be able to disagree by accident. `:core` is the artifact they share —
 * the rules engine, the card tables' parsers, the transcript format — so a build of the client and
 * a build of the server that link the *same* `:core` necessarily agree about this number. When they
 * link different ones, they differ, and that is exactly the case the gate below exists to catch.
 *
 * ### What a major bump means, precisely
 *
 * **A peer running the previous major cannot be talked to.** There are two ways to earn that, and
 * this paragraph used to name only one.
 *
 * The first is that **the replay can reach a different answer**. That is a wider promise than "the
 * wire format changed": it covers the card and opponent tables, and it covers `RulesEngine`,
 * `MatchState`, `Roulette` and `MatchAi`. When that is what happened, [TRANSCRIPT_VERSION] bumps
 * with it and the goldens in `ReplayDeterminismTest` break — three expressions of one decision.
 *
 * The second is that **a message one side sends can no longer be read by the other**. Additive
 * fields do not qualify: every type here is read under `ignoreUnknownKeys` and written under
 * `encodeDefaults`, so an old peer ignores what it does not know and a new one defaults what it was
 * not sent. What does qualify is a field that changes shape, a sealed hierarchy that becomes a
 * class, or an enum that gains a member an old peer fails on rather than ignores.
 *
 * The two are independent, and 2.0.0 is the case that proves it: the wire broke and the replay did
 * not. So the three do **not** always move together — the goldens passing untouched across a major
 * is a legitimate outcome, not evidence of a missed bump.
 *
 * Without the gate that follows from it, the failure mode is nasty and misdiagnosed: a server
 * dealing from an older card table rejects every transcript from an updated client, and the
 * rejection is indistinguishable from cheating. See
 * `docs/migration/09-PHASE-5-NETWORK.md` § One version, shared.
 *
 * A **minor** bump therefore carries a real obligation: it must mean the replay is unchanged. If
 * that is ever untrue the distinction is decoration, and the goldens are what enforce it.
 */
@Serializable
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<AppVersion> {

    init {
        require(major >= 0 && minor >= 0 && patch >= 0) {
            "a version component may not be negative, was $major.$minor.$patch"
        }
    }

    /**
     * Whether a peer running [other] can be talked to.
     *
     * Deliberately **not** symmetric, and deliberately not "the majors are equal". A client older
     * than the server must update; a client *newer* than the server is the ordinary state of the
     * world during a rollout — the client shipped through a store review while the server had not
     * been deployed yet — and refusing it would take the game away from the people who updated
     * fastest. The newer side is the one that knows how to be careful.
     *
     * Minor and patch are ignored, which is the whole point of separating them: they promise the
     * replay is unchanged, so there is nothing to refuse.
     */
    fun acceptsPeer(other: AppVersion): Boolean = other.major >= major

    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

    /** `"1.4.2"`. The form that goes on the wire and into a log. */
    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        /**
         * Reads `"1.4.2"`, or null if it is not that.
         *
         * Null rather than an exception because the input arrives from the network: a missing or
         * mangled version header is a request to refuse, not a bug to crash on.
         */
        fun parse(text: String): AppVersion? {
            // `mapNotNull` drops anything that is not a non-negative integer, so a bad component
            // shortens the list and the size check below catches it — as does an extra one. That
            // is why the count is verified *after* the mapping rather than before.
            val numbers = text.trim()
                .split('.')
                .mapNotNull { part -> part.toIntOrNull()?.takeIf { it >= 0 } }

            return numbers
                .takeIf { it.size == COMPONENT_COUNT }
                ?.let { AppVersion(it[0], it[1], it[2]) }
        }

        private const val COMPONENT_COUNT = 3
    }
}

/**
 * What this build is.
 *
 * Kept beside the format version it moves with rather than read from a build file, so that a
 * `:core` artifact answers the question by itself — the server has no access to the client's Gradle
 * files, and a number it had to be told would be a number that could be wrong.
 *
 * ### 1.0.0, and why the next strands must not bump it again
 *
 * The major went to 1 for card copies: the save's `CARDS` changed shape, the transcript gained a
 * field, and `HandVisibility` changed what the Open rule reveals — a replay change by the
 * definition above. `docs/migration/20-CARD-COPIES-AND-PLATFORM-ACCOUNTS.md` § Order of work calls
 * for **one** major bump across all of 18, 19 and 20, and this is it. The strands still to land —
 * global card ids, the set model, the per-app gate — are part of the same break and ride this
 * number. Bumping again for each would be four gates and four windows in which a client and a
 * server can be wrong about each other, which is the thing the gate exists to prevent.
 *
 * ### 1.1.0 — daily quests, and why they are a minor
 *
 * The major's meaning is "the replay can reach a different answer", and quests reach none of it:
 * `MatchTranscript` is unchanged, so `fingerprint` is unchanged and every transcript credited under
 * 1.0.0 still hashes the same. What moved is two additive fields with defaults — `GameSave.QUESTS`
 * and `RewardSummary.questIds` — on types read under `ignoreUnknownKeys` and written under
 * `encodeDefaults`, so an old peer ignores them and a new one defaults them.
 *
 * The minor carries the obligation that wording names: **the replay is unchanged**, and
 * `ReplayDeterminismTest` passing untouched is what evidences it. Note this is not the engine
 * artifact's number — that is `coreVersion`, and the two move independently. See
 * `docs/RELEASING.md` § 1.
 *
 * ### 2.0.0 — player-versus-player wagers, and a major with no replay change
 *
 * The first bump earned on the *second* ground above. Three PvP types changed shape in ways an old
 * peer cannot survive:
 *
 * - `PvpStake` went from a sealed interface — encoded with a `type` discriminator — to a plain
 *   class. An old peer reading `{"mgp":50,"trade":"ONE"}` as a sealed hierarchy fails on the
 *   missing discriminator; a new peer reading the old encoding fails on the unknown key.
 * - `PvpMatchStatus` gained `AWAITING_CLAIM`. An unknown enum member is not ignorable — it is a
 *   value the reader has no case for — so a 1.x client polling a 2.x match would fail to parse it
 *   at exactly the moment it was owed a card.
 * - `PvpOutcome` lost `cardWon`/`cardLost` for the plural forms. Dropping a field is survivable
 *   under `ignoreUnknownKeys`; being *silently* told nothing was won is not, which is why this
 *   rides the same break rather than being defaulted.
 *
 * `MatchTranscript` is untouched, so `fingerprint` is untouched and every transcript credited under
 * 1.x still hashes the same. [TRANSCRIPT_VERSION] therefore does not move and the goldens pass
 * unchanged — the outcome the rewritten paragraph above now allows for.
 *
 * ### 2.1.0 — captures on the wire, and a chosen deck
 *
 * Two additions, both of which a 2.0.0 peer survives untouched, which is the whole test:
 *
 * - `PvpMatchView.lastPlay` — nullable with a default. An old client drops it under
 *   `ignoreUnknownKeys` and announces nothing after a placement, which is what it does today; an
 *   old *server* omits it and a new client sees null, which is a board it treats as un-played-on.
 *   Neither is wrong, both are quiet.
 * - `PvpTableRequest.deck`, and `PvpJoinRequest` as the body of joining. Every field defaults, and
 *   the join body is optional server-side **for this reason**: an old client posts nothing and is
 *   dealt its first complete deck, exactly as before it could ask.
 *
 * Note what a minor is *not* being claimed for. `Capture` and `CaptureKind` became `@Serializable`,
 * which changes no existing payload — nothing serialized them before — and the engine's answers are
 * bit-identical. `MatchTranscript` is untouched again, so [TRANSCRIPT_VERSION] holds and the
 * goldens pass unchanged.
 *
 * ### 2.2.0 — the first intent endpoint, and throttling
 *
 * `POST /me/bag/use` moves the booster roll to the server. A **new endpoint** rather than a changed
 * one, so no existing payload changes shape and a 2.1.0 peer is untouched in both directions: an
 * old client keeps rolling locally against a new server, and a new client against an old server
 * gets a 404 from a call it only makes when the player opens a bag item.
 *
 * That second case is the one to be clear about, because it is the standing deployment rule rather
 * than something this number enforces: **the server goes first**. The version gate is major-only by
 * design, so a minor cannot and should not refuse anybody — it records that the surface grew.
 *
 * Also here, and equally invisible to a peer that ignores it: `429` is now an answer the server can
 * give to any endpoint, carrying `Retry-After` and no body. A client that does not know it reads it
 * as an ordinary failure, which is wrong but not broken; `AccountResult.Throttled` is what reads it
 * as the wait it is.
 *
 * `GameSave.withServerOwnedFrom` also widened — achievements and stats now come off the stored
 * document. That changes no *shape*: the fields have always been on the wire, and what changed is
 * which side is believed about them. A 2.1.0 client sending its own is silently corrected, which is
 * exactly what it already experienced for quests.
 *
 * ### Why the rest of that work is also 2.2.0, and not 2.3.0
 *
 * Everything that followed — the remaining intent endpoints, `GET /matches/tickets`, seeds a client
 * may no longer choose, `DELETE /accounts/me`, and the whole of `withServerOwnedFrom` down to
 * `cards` — is folded into this number rather than given its own.
 *
 * Because **2.2.0 has never shipped**. A version is a promise to peers about what they will find,
 * and there are no peers on this one: nothing was published, nothing deployed, no client built
 * against it. Minting 2.3.0 would record a distinction between two states of the world that only
 * ever existed on one machine, and would leave a number in this file that no running thing ever
 * reported. The contents of an unreleased version are still open; the moment it is tagged, they are
 * not.
 */
val CURRENT_VERSION: AppVersion = AppVersion(2, 2, 0)

/**
 * The header both sides put the version in.
 *
 * A header and not a field in [MatchTranscript], because refusing a stale peer is a **protocol**
 * answer and must be possible before the body is parsed — a body this build may not be able to read
 * correctly is precisely what a major mismatch means. Putting it in the transcript would make the
 * server parse the thing it has already decided to refuse, and would make a rejected client look
 * like a rejected *claim*, which is the confusion the whole design is trying to avoid.
 */
const val VERSION_HEADER: String = "X-TTO-Version"
