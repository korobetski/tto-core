package com.tripletriad.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The record of a solo match, in the form the server can check.
 *
 * ### What a transcript is, and why it is this short
 *
 * It is **not** a recording of what happened. It is the smallest set of facts from which what
 * happened can be *recomputed* — the difference matters, because a recording can be edited into
 * anything and a recomputation cannot.
 *
 * The engine is pure and deterministic, so from a seed the server can derive the opponent's hand
 * ([com.tripletriad.model.Npc.randomHand]), the rules the roulette drew
 * ([com.tripletriad.model.Roulette.augment]), the coin flip, and **every one of the opponent's
 * moves** ([com.tripletriad.model.MatchAi.play]). None of that is in here, because none of it is
 * the player's to state. What is in here is only what the player actually chose: which opponent,
 * which five cards, and where each of them was placed.
 *
 * The consequence is the one that makes this design worth having: an offline player can play badly
 * or well, but **cannot invent a result**. A match played on a plane still counts.
 *
 * ### What is missing, and is meant to be
 *
 * - **A signature.** Without one, anybody can submit a transcript in anybody's name. The transcript
 *   is still unforgeable as a *game*, but not as a *claim*. Signing comes with accounts and keys.
 * - **A signature**, still. See above.
 * - **The profile.** [ownedCards] is stated by the client, which is exactly backwards — the server
 *   holds the profile, and asking the claimant what they own is asking the question of the one
 *   party with a reason to lie. It is here only until accounts exist, and the field carries its own
 *   warning.
 *
 * @property version the format. Checked before anything else, because a transcript this build
 *   cannot read must be refused rather than misread.
 * @property seed the whole of the randomness. Everything derived is derived from it.
 * @property formatId the format the match was played in, by `Format.id`.
 *
 * Replaced `collection`, which named one of two card tables and was the wire's copy of `MODE`. A
 * match is played *in a format* — that decides which opponents exist, which cards are legal and
 * what the roulette may draw — and the server resolves all three from this one field rather than
 * from a profile that no longer has a collection to check against.
 * @property opponentIconId the opponent, by icon id — **not** by `id`, which is not unique.
 * @property deck the five card ids brought to the match, in the order they were selected.
 * @property ownedCards every card the profile owns, id to copies held. Read under `RULE_RANDOM`,
 *   where the hand is drawn from the whole collection rather than the deck, and by the deck
 *   affordability check — which needs counts and not a membership test, since a deck naming a card
 *   twice needs two copies of it. **Temporary**: see above.
 * @property moves the player's own placements, in turn order. The opponent's are absent by design.
 */
@Serializable
data class MatchTranscript(
    @SerialName("v") val version: Int = TRANSCRIPT_VERSION,
    val seed: Int,
    val formatId: String,
    val opponentIconId: String,
    val deck: List<Int>,
    val ownedCards: Map<Int, Int>,
    val moves: List<TranscriptMove>,
)

/**
 * One placement by the player.
 *
 * The card is named by **id** and not by hand slot. A slot index would be one byte shorter and
 * would make an off-by-one in either implementation look like a legal move by a different card; an
 * id that is not in the hand is rejected instead of silently playing something else.
 *
 * @property cardId the card played. Must be in the hand at that moment.
 * @property position the board cell, 0..8, reading rows left to right.
 */
@Serializable
data class TranscriptMove(
    val cardId: Int,
    val position: Int,
)

/**
 * The server's answer.
 *
 * A sealed hierarchy rather than a boolean and some nullable fields, so that "accepted" always
 * carries a score and "rejected" always carries a reason — neither can be constructed without it.
 */
@Serializable
sealed interface MatchVerdict {

    /**
     * The transcript replays, and this is what it comes to.
     *
     * The score is the **server's**, recomputed, not the client's copied back. If the two disagree
     * the client's is wrong by definition, and saying so is the point of the whole exercise.
     */
    @Serializable
    @SerialName("accepted")
    data class Accepted(
        val blue: Int,
        val red: Int,
        /**
         * `"BLUE"`, `"RED"`, or null on a draw.
         *
         * The default is load-bearing, not tidiness. A serialiser configured with
         * `explicitNulls = false` — which the server's is, to keep bodies small — **omits** this
         * field entirely on a draw, and a decoder without a default then fails on a perfectly
         * valid response. A protocol type may not depend on how the far end configured its
         * encoder, so every optional field here carries one.
         */
        val winner: String? = null,
    ) : MatchVerdict

    /**
     * The transcript does not replay, and this is why.
     *
     * [detail] is for a developer reading a log. [reason] is what a client may branch on — a
     * machine-readable code, so the wording can change without breaking anything.
     */
    @Serializable
    @SerialName("rejected")
    data class Rejected(
        val reason: RejectionReason,
        val detail: String,
    ) : MatchVerdict
}

/**
 * Why a transcript was refused.
 *
 * Distinct values rather than one "invalid": most of these are ordinary and expected — an old
 * client, a card table that moved on — and only some of them mean somebody is trying it on. A
 * single code would make the two indistinguishable in a log.
 */
@Serializable
enum class RejectionReason {
    /** [MatchTranscript.version] is not one this build reads. */
    UNSUPPORTED_VERSION,

    /** No opponent with that icon id in that format. */
    UNKNOWN_OPPONENT,

    /** A card id in the deck is not one the profile owns. */
    DECK_NOT_OWNED,

    /**
     * The deck names more cards of a star rank than [com.tripletriad.model.DeckLimits] allows.
     *
     * Separate from [DECK_NOT_OWNED] because the two are different accusations and the player can
     * act on only one of them. Not owning the cards is a claim about the collection — the answer is
     * that the transcript is wrong about who owns what. Breaking a rank cap is a claim about the
     * *deck*, and the answer is to open the deck editor and take a card out, which is why the
     * message names the rank and the count rather than the cards.
     */
    DECK_ILLEGAL,

    /** The deck or a hand could not be dealt — usually an id absent from this build's table. */
    UNDEALABLE,

    /** A placement names a card that was not in hand, or a cell that was not empty. */
    ILLEGAL_MOVE,

    /** The match was still in progress when the moves ran out. */
    TRUNCATED,

    /** Moves were left over once the board was full. */
    TRAILING_MOVES,

    /**
     * The seed was never issued to this account, or has already been used.
     *
     * ### What this closes
     *
     * [MatchTranscript.seed] is the whole of a match's randomness, so a client that chooses it
     * chooses the deal: play the match locally, look at the opponent's hand, and if it is a bad
     * one pick another seed and start again. Nothing about the transcript that finally arrives is
     * detectably wrong — it is a real match, honestly played, from a deal the player auditioned.
     * The design used to name this "seed grinding" and judge the gain small. It is not small: it
     * is unbounded and free.
     *
     * A seed the server issued cannot be auditioned, because there is nothing to choose between.
     *
     * ### What it does not close
     *
     * **Which opponent a ticket is spent on.** A player holding the next seed can compute the deal
     * against each opponent and pick the most favourable. That is bounded — one match per ticket
     * either way — where re-rolling was not, and closing it entirely would mean naming the
     * opponent before the seed is issued, which is a round trip per match and the end of offline
     * play. See `SeedTickets`.
     */
    UNKNOWN_SEED,
}

/**
 * The transcript format version.
 *
 * Bumping this invalidates stored transcripts, so it is bumped when the *engine* changes too, not
 * only when a field is added — a replay that reaches a different answer is a different format
 * whatever the fields look like. See `ReplayDeterminismTest`, whose goldens are the tripwire.
 *
 * **2** — `ownedCards` became id-to-copies, and a deck may no longer use more copies of a card than
 * are owned. `docs/migration/20-CARD-COPIES-AND-PLATFORM-ACCOUNTS.md` § 1.
 *
 * **3** — `collection` became [MatchTranscript.formatId]. `MODE` is gone, so a transcript names the
 * format it was played in rather than the table its author belonged to.
 * `docs/migration/19-CARD-SETS-AND-FORMATS.md`.
 *
 * **4** — Bonus and Malus changed: a card counts itself from the moment it is placed, and the
 * accumulated penalty stops at 1 rather than 0. The **first bump earned by the engine alone**, and
 * the case the paragraph above was written for — no field moved, and a stored transcript of a match
 * played under either rule now replays to a different set of captures. See [AscensionTally].
 *
 * The cost is named rather than hidden: a transcript sitting in a client's offline queue when it
 * updates is refused by [TranscriptVerifier], so a match played before the update and submitted
 * after it is not credited. That is the correct answer — this build cannot honestly say what that
 * match did — and it is bounded by how long a queue waits for a network, which is the reason the
 * queue drains on every launch.
 *
 * **5** — and Bonus and Malus changed again, in the half of 4 that was wrong. A card no longer
 * counts itself while its own placement resolves: it attacks under the board as it was and joins
 * the tally afterwards, which is the order `TTOCore.as:171` had and the order the rule is stated
 * in. The 1-floor from 4 stands untouched. The engine alone once more, with the same cost and the
 * same bound — a queued transcript from a 4 build is refused rather than mis-credited.
 *
 * Riding the same number, and the second engine change on it: `RULE_RANDOM` draws under
 * [com.tripletriad.model.DeckLimits]. `MatchPreparation.randomHand` takes the first five cards of
 * its shuffle the star-rank caps admit rather than the first five outright, so a stored Random
 * transcript whose shuffle led with two five-stars replays to a different hand. The shuffle itself
 * is unchanged and consumes the generator identically, which is why every other Random transcript
 * — and every non-Random one — replays exactly as it did.
 *
 * **6** — Fallen Ace stopped being a value and became a comparison, which is the rule FFXIV
 * actually states: an ace can be captured by a 1 and holds its 10 against everything else, and
 * under Reverse the pair flips so the ace takes the 1 and nothing else. It used to substitute a 0
 * for the printed 10 before any modifier ran, which made an ace worthless against every digit and
 * also silently changed Same Wall, Bonus and Malus wherever an ace was on the board.
 *
 * The engine alone for the third time, and the widest of the three: **every stored transcript of a
 * match played with `RULE_FALLEN_ACE` replays to a different set of captures**, and so does any
 * match where an ace stood next to a wall under Same Wall. Same cost, same bound — a queued
 * transcript from a 5 build is refused by [TranscriptVerifier] rather than mis-credited. Matches
 * played without the rule are untouched: `outranks` is the ordinary comparison with the flag off.
 */
const val TRANSCRIPT_VERSION: Int = 6
