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
 * ### 3.0.0 — Bonus and Malus, and why the whole unreleased bundle takes this number
 *
 * Two changes to what the rules engine computes, both in [com.tripletriad.model.AscensionTally]:
 * a card counts itself under Bonus/Malus from the moment it is placed, and the accumulated penalty
 * floors at 1 instead of 0. A match transcript from 2.x therefore replays to a different set of
 * captures on this build, which is the **first** ground above, stated in its purest form — no
 * message changed shape at all.
 *
 * So this is the bump that clause was written for, and it drags the rest with it. Everything below
 * was written as 2.2.0 and never shipped: the intent endpoints, `GET /matches/tickets`,
 * `DELETE /accounts/me`, throttling, and the whole of `withServerOwnedFrom`. Those sections are
 * kept for what they explain, but the number they name is gone. Two reasons, and the second is the
 * one that decides it:
 *
 * - **2.2.0 had no peers.** Nothing was published, nothing deployed, no client built against it.
 *   Keeping it would leave a version in this file that no running thing ever reported.
 * - **A minor promises the replay is unchanged.** That promise is now false for this work, and a
 *   promise that is false is worse than one that was never made. The last version anybody actually
 *   ran is 2.1.x, and against that peer this build must refuse — which is what major 3 does and
 *   what minor 2 could not.
 *
 * [TRANSCRIPT_VERSION] moves to 4 with it. `ReplayDeterminismTest`'s goldens **pass untouched**,
 * and that is not evidence of a missed change: its recorded match is played with no rules at all
 * (`"[]"`), so no Bonus tally ever forms in it. The two facts are consistent, and the file's own
 * "the goldens passing across a major is a legitimate outcome" is what they are consistent with.
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
 * ### Why the rest of that work carried the same number, and not 2.3.0
 *
 * Everything that followed — the remaining intent endpoints, `GET /matches/tickets`, seeds a client
 * may no longer choose, `DELETE /accounts/me`, and the whole of `withServerOwnedFrom` down to
 * `cards` — was folded into one number rather than given its own.
 *
 * Because **it had never shipped**. A version is a promise to peers about what they will find, and
 * there were no peers on this one: nothing was published, nothing deployed, no client built against
 * it. Minting a second number would record a distinction between two states of the world that only
 * ever existed on one machine. The contents of an unreleased version are still open; the moment it
 * is tagged, they are not.
 *
 * That argument is what let the rules-engine change above take the *whole* bundle up to 3.0.0
 * instead of adding a major on top of a minor nobody had. It is the same reasoning reaching a
 * different number because the facts changed, which is what makes it a reason and not a habit.
 *
 * ### 4.0.0 — the auction house, and one new item type
 *
 * A major earned on the second ground, by a single addition: [com.tripletriad.model.PouchItem],
 * serial name `item-type-pouch`, the object a seller's proceeds arrive in.
 *
 * `ignoreUnknownKeys` does not cover this and it is worth being exact about why, because every
 * other field in this bundle is covered by it. An unknown **key** is skipped; an unknown **sealed
 * subtype discriminator** is not a key, it is a type the reader has no constructor for, and
 * `kotlinx.serialization` throws. `Item` is a sealed hierarchy inside `GameSave.bag`, so a 3.x
 * client whose profile holds one pouch cannot parse **the profile** — not the row, the profile. A
 * player who listed a card from a new client and then opened an old one would find their account
 * unreadable, which is precisely the condition a major exists to turn into a refusal at the door.
 *
 * `ItemEffect` gains `pouch-opened` for the same reason and with the same consequence, though it
 * is only reachable by a client that asked to open one.
 *
 * Note what is *not* claiming this number, since the distinction is the whole of what the gate
 * means. Both of these are additive and a 3.x peer survives them untouched:
 *
 * - `CardItem.origin`, an enum with a default, added so a card the auction handed back can say so.
 *   An old peer skips the key and shows the description it always showed.
 * - `ServerInfo.auction`, defaulted like `unlocks` beside it. This one had to be survivable: it
 *   travels in the very response a client reads to discover whether it is *allowed* to talk, and a
 *   field that broke that read would break the client's ability to be told to upgrade.
 *
 * The new endpoints under `/auctions` and everything they carry — `AuctionLot`, `AuctionStatus`,
 * `AuctionRefusal` — break nobody by existing: an old client never calls them.
 *
 * On its own, this addition would not have moved [TRANSCRIPT_VERSION]: the auction touches no
 * rule, and every transcript credited under 3.x would still hash the same. The section below is
 * what moves it, and the two travelling under one number is the unshipped-version argument again.
 *
 * ### Also on this number — the deck-building caps
 *
 * [com.tripletriad.model.DeckLimits] caps a deck at one five-star and two four-stars, and the
 * server refuses a deck that breaks it. It rides 4.0.0 rather than minting a number of its own, on
 * the reasoning two sections above: 4.0.0 has not shipped, so its contents are still open.
 *
 * It would have earned a major anyway, on the second ground and by one field: `RejectionReason`
 * gains `DECK_ILLEGAL`, and an unknown enum member is a value the reader has no case for rather
 * than a key it can skip — the `PvpMatchStatus.AWAITING_CLAIM` case above, exactly. A 3.x client
 * that submitted an over-capped deck would fail to parse the very verdict explaining why.
 *
 * It is a replay change too, by one line and not by the caps themselves. The caps decide which
 * decks may be **brought**, and that alone changes nothing about what the engine computes once
 * five cards are in a hand. `RULE_RANDOM` is what moves: it draws from the *collection* rather
 * than from a deck, so leaving it exempt would have made selling the way around the caps — a
 * player who dumps every low card is dealt the five-ace hand the deck editor refuses to build.
 * `MatchPreparation.randomHand` now takes the first five of the shuffle the caps admit, so a
 * stored Random transcript whose shuffle led with an illegal five replays to a different hand.
 * [TRANSCRIPT_VERSION] is already moving to 5 below and carries this with it.
 *
 * `RULE_SWAP` is deliberately untouched, and is now the only way to hold more than the caps allow:
 * a swap hands over whatever the other side drew, and one that refused an ace would be a rule
 * reading differently depending on what the opponent owns.
 *
 * ### Also on this number — Bonus and Malus land after the placement
 *
 * The first ground, and the half of 3.0.0 that was wrong. A card no longer counts *itself* while
 * its own captures are being resolved: it attacks under the tally as it stood before it landed and
 * joins the tally afterwards, so a beast played onto a `+2` board attacks at `+2` and leaves a
 * `+3` behind it. 3.0.0 had reversed that on the reasoning that a badge must not disagree with the
 * attack it just watched; the badge is what changed instead — it is drawn on hand cards too now,
 * and it says what the board *is*, which is a different claim from what the last placement *did*.
 *
 * Elemental is untouched and deliberately asymmetric with the pair: its ±1 belongs to the cell
 * rather than to a running tally, so a card is on its element the instant it is placed and attacks
 * with the modifier already applied. See [com.tripletriad.model.AscensionTally].
 *
 * A transcript played under Bonus or Malus therefore replays to a different set of captures on this
 * build, and [TRANSCRIPT_VERSION] moves to 5 with it. `ReplayDeterminismTest`'s goldens pass
 * untouched for the reason they did at 3.0.0 — its recorded match is played with no rules at all,
 * so no tally ever forms in it.
 *
 * It rides 4.0.0 rather than minting a major of its own on the same reasoning as the deck caps:
 * 4.0.0 has not shipped, so its contents are still open. Against the last version anybody ran this
 * build already refuses.
 *
 * ### 5.0.0 — the environment opponent stops moving before it is watched
 *
 * A major earned on neither of the two usual grounds. **No type on the wire changes**: not a
 * field, not an enum member, not a sealed subtype. What changes is what one endpoint *means*.
 *
 * `POST /pve/matches` used to answer with the toss already honoured — a deal that gave the
 * opponent the opening carried its card on the board and the placement in [PveMatchView.plays].
 * It now answers with the board **as dealt**, an empty `plays`, and no playable slot, because it
 * is not the player's turn yet. The opponent's opening is computed and written when the client
 * asks for it, which it does with an ordinary `GET /pve/matches/{id}` once its announcements have
 * finished — see [PveMatchView.plays], which carries the whole argument.
 *
 * A 4.x client against a 5.x server parses every byte of that answer and is then stuck: it draws
 * an empty board, holds five cards it is not allowed to play, and waits for a turn that will not
 * arrive until it asks a question it does not know to ask. That is worse than a parse failure,
 * not better — a crash names itself, and this looks like the server hanging. Turning it into
 * `426 Upgrade Required` at the door is precisely what a major is for, and it is the only reason
 * this number moves.
 *
 * [TRANSCRIPT_VERSION] does **not** move. The opponent's choice was never part of a replay — it
 * is written into the row the moment it is made, which is the property that lets the AI change
 * without a version at all — and no rule the engine evaluates is touched here. Deciding it a
 * second later changes when a row is written, not what any row replays to.
 */
val CURRENT_VERSION: AppVersion = AppVersion(5, 0, 0)

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
