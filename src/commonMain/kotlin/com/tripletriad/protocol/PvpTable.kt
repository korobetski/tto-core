package com.tripletriad.protocol

import com.tripletriad.model.GameRules
import kotlinx.serialization.Serializable

/**
 * An open table: somebody waiting to play, on terms they have stated.
 *
 * ### Why a list of tables and not a queue
 *
 * The first version of this was one button. You tapped it, you waited, and the server paired you
 * with whoever had tapped it first — a queue with nothing in it to see. That works exactly as long
 * as every match is the same match, and it stopped being true the moment a match could be played
 * under chosen rules for a stake: two players who agree on nothing have no business being paired,
 * and a player who is told what they agreed to *after* agreeing has not agreed to anything.
 *
 * So the host states the terms and the terms are public. Somebody joining has read the rules and
 * the wager, which is the whole difference between choosing a match and being handed one.
 *
 * The cost is that an empty list says "nobody is here" where a queue said "waiting" — the argument
 * the queue was originally chosen on. It is the right trade now: an empty list is *true*, and a
 * player who can see there is nobody to play can go and do something else instead of watching a
 * spinner that was never going to resolve.
 *
 * @property rules the rules as the host declared them, which is not necessarily what will be played
 *   — see [roulette]. Shown as-is, because it is what the host is offering.
 * @property roulette whether the server draws one to three further rules when the match opens.
 *   Separate from `GameRules.roulette` on purpose: that field is what the Wheel of Fortune
 *   achievements count, and only `Roulette.augment` may set it. A host ticking it directly would
 *   credit a roulette win for a match that never drew one.
 * @property expiresAt when the table lapses. A table with no expiry is a lobby full of people who
 *   left hours ago, and joining one is a match against nobody.
 * @property matchId set once somebody joined. The row survives so the host's client can find out
 *   *which* match its table turned into.
 */
@Serializable
data class PvpTable(
    val id: String,
    val hostName: String,
    val formatId: String,
    val rules: GameRules = GameRules(),
    val roulette: Boolean = false,
    val stake: PvpStake = PvpStake.None,
    val openedAt: Long,
    val expiresAt: Long,
    val matchId: String? = null,
)

/** What a client sends to open one. The host is whoever the token says. */
@Serializable
data class PvpTableRequest(
    val formatId: String,
    val rules: GameRules = GameRules(),
    val roulette: Boolean = false,
    val stake: PvpStake = PvpStake.None,
)

/**
 * The cards a winner names, under [com.tripletriad.model.TradeRule.ONE] or `DIFF`.
 *
 * A list rather than one id because Diff takes as many as the margin. The server checks the count
 * against what it owes and the ids against the loser's dealt hand — a client naming a card that was
 * never at stake is refused rather than obliged.
 */
@Serializable
data class PvpClaim(val cardIds: List<Int>)

/**
 * Why a player-versus-player request was refused, as a value rather than a sentence.
 *
 * ### Why the prose was not enough
 *
 * The routes answer a 4xx with `{"reason": "you already have a table open"}`, and that reason is
 * written for a player to read — which is exactly why a client cannot show it. It is English, and
 * this game ships in four languages. The client that tried to display it would either put an
 * English sentence in a French screen or match on its wording, and a translation table keyed on
 * another server's prose breaks the first time somebody improves the wording.
 *
 * So the refusal travels as both: a code the client switches on, and the sentence it replaces for
 * anyone reading the payload directly. `AccountError` made this decision first and for the same
 * reason; this is the same shape for the other half of the API.
 */
@Serializable
enum class PvpRefusal {
    /** The format named does not exist. A client bug, or a catalogue the two sides disagree on. */
    NO_SUCH_FORMAT,

    /** The rules asked for are not in that format's pool. */
    RULES_NOT_ALLOWED,

    /** The purse does not cover the wager — on either side of it. */
    CANNOT_AFFORD,

    /** One open table per host, and this host has one. */
    ALREADY_WAITING,

    /** You cannot open or join anything while a match of yours is running. */
    ALREADY_PLAYING,

    /** Withdrawn, joined by somebody else, lapsed, or your own. */
    TABLE_GONE,

    /** No such match, or not one you are in. */
    NO_SUCH_MATCH,

    /** A move out of turn, or into a match that has ended. */
    NOT_YOUR_TURN,

    /** A move the rules do not allow this turn — see `OrderRule`. */
    ILLEGAL_MOVE,

    /** A claim on a match that owes you nothing. */
    NOTHING_OWED,

    /** A claim naming a card that was never at stake. */
    NOT_THEIRS,

    /** No such player of that name. */
    NO_SUCH_PLAYER,

    /** You cannot challenge yourself. */
    YOURSELF,
}
