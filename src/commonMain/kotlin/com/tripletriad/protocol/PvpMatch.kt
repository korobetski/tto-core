package com.tripletriad.protocol

import com.tripletriad.model.AscensionTally
import com.tripletriad.model.Board
import com.tripletriad.model.Card
import com.tripletriad.model.CardCollection
import com.tripletriad.model.CardColor
import com.tripletriad.model.CardType
import com.tripletriad.model.GameRules
import com.tripletriad.model.MatchResult
import com.tripletriad.model.MatchView
import com.tripletriad.model.PlacedCard
import com.tripletriad.model.TurnOrder
import kotlinx.serialization.Serializable

/**
 * Player versus player, over the server.
 *
 * ### Why the server is the referee and not a relay
 *
 * A PvE match is played entirely on the client, and the server checks it afterwards by replaying
 * the transcript. That works because the client already holds both hands — it is running the
 * opponent — so hiding one from itself is a courtesy, not a secret.
 *
 * Against a person it cannot work. If each client held both hands, "do not look" would be the only
 * thing protecting a player's cards, and a modified client would see everything with no trace in
 * any transcript. So the server holds the one `MatchState`, deals both hands, decides whose turn it
 * is, and sends each side a [PvpMatchView] containing **only what that side may see**. A client
 * cannot leak what it was never sent.
 *
 * That also settles who owns the dice. Under `OrderRule.CHAOS` the playable card is a roll; two
 * devices rolling separately would disagree and one player's move would be refused for no visible
 * reason. The roll happens once, on the server, and travels as [PvpMatchView.playable].
 *
 * ### Why the wire carries ids and not cards
 *
 * Both ends hold `cards.json`. Sending a whole [Card] — name key, four sides, type, rarity — for
 * every slot and every cell would put a catalogue on the wire many times per match to say what an
 * integer already says. `RewardSummary` sends achievement ids on exactly this argument.
 *
 * The client turns ids back into cards through its own catalogue and rebuilds a [MatchView]. If the
 * two catalogues ever disagree, that is a version problem and `AppVersion` is the thing that
 * refuses the connection — not something a fatter payload would have rescued.
 */

/** A card in a cell, as two integers: which card, and whose it is now. */
@Serializable
data class PvpCell(val cardId: Int, val owner: CardColor)

/**
 * One side's view of a live match, as it travels.
 *
 * The wire form of [MatchView], and deliberately not [MatchView] itself: that type holds real
 * [Card]s and is what the *screen* wants, while this holds ids and is what the *network* wants.
 * Keeping them apart is what stops the model's shape from becoming a protocol that cannot be
 * changed without a version bump.
 *
 * @property cells the board, nine entries, `null` where empty.
 * @property elements the nine board elements, `null` where a tile has none. Only ever non-null
 *   under the Elemental rule.
 * @property hand this side's cards, by id, in slot order.
 * @property opponentHand the other side's slots: an id where the rules reveal it, `null` otherwise.
 *   The **length** is the opponent's real card count, which is public — a player can count the
 *   cards in front of them.
 * @property playable slots of [hand] that may be played now. Empty when it is not this side's turn,
 *   which is how a client knows the board is read-only.
 * @property deadline epoch millis by which this side must move, or null when it is not their turn.
 *   Sent rather than a remaining duration because a client's clock offset is a fixed error on an
 *   instant and a compounding one on a countdown restarted at every poll.
 */
@Serializable
data class PvpMatchView(
    val matchId: String,
    val side: CardColor,
    val opponentName: String,
    val rules: GameRules,
    val collection: CardCollection,
    val cells: List<PvpCell?>,
    val elements: List<CardType?>,
    val hand: List<Int>,
    val opponentHand: List<Int?>,
    val first: CardColor,
    val placement: Int,
    val ascension: Map<CardType, Int> = emptyMap(),
    val playable: List<Int> = emptyList(),
    val deadline: Long? = null,
    val status: PvpMatchStatus = PvpMatchStatus.PLAYING,
    val stake: PvpStake = PvpStake.None,
    val outcome: PvpOutcome? = null,
) {
    /**
     * This view as the model type the screen renders, resolving ids through [cards].
     *
     * Returns null if any id is unknown, rather than dropping the card: a board with a hole in it
     * would be a match the player cannot reason about, and a client whose catalogue disagrees with
     * the server's has a version problem to report, not a frame to render.
     */
    fun toMatchView(cards: Map<Int, Card>): MatchView? {
        // Every id is checked before any is used, so the refusal is one decision taken once
        // rather than three `?: return null`s that each mean the same thing in a different place.
        val named = hand + opponentHand.filterNotNull() + cells.mapNotNull { it?.cardId }
        if (named.any { it !in cards }) return null

        val ownCards = hand.map(cards::getValue)
        val theirCards = opponentHand.map { id -> id?.let(cards::getValue) }
        val board = Board(
            cells = cells.map { cell ->
                cell?.let { PlacedCard(cards.getValue(it.cardId), it.owner) }
            },
            elements = elements,
        )

        return MatchView(
            side = side,
            rules = rules,
            board = board,
            ownHand = ownCards,
            opponentHand = theirCards,
            order = TurnOrder(first),
            placement = placement,
            tally = AscensionTally(ascension),
            playableHandIndices = playable,
        )
    }

    companion object {
        /** [view] projected onto the wire. The inverse of [toMatchView], up to the ids. */
        @Suppress("LongParameterList")
        fun of(
            view: MatchView,
            matchId: String,
            opponentName: String,
            collection: CardCollection,
            status: PvpMatchStatus = PvpMatchStatus.PLAYING,
            stake: PvpStake = PvpStake.None,
            deadline: Long? = null,
            outcome: PvpOutcome? = null,
        ): PvpMatchView = PvpMatchView(
            matchId = matchId,
            side = view.side,
            opponentName = opponentName,
            rules = view.rules,
            collection = collection,
            cells = view.board.cells.map { placed ->
                placed?.let { PvpCell(it.card.id, it.owner) }
            },
            elements = view.board.elements,
            hand = view.ownHand.map { it.id },
            opponentHand = view.opponentHand.map { it?.id },
            first = view.order.first,
            placement = view.placement,
            ascension = view.tally.counts,
            playable = view.playableHandIndices,
            deadline = deadline,
            status = status,
            stake = stake,
            outcome = outcome,
        )
    }
}

/**
 * Where a match is in its life.
 *
 * [ABANDONED] and [FORFEITED] are separate because they are different facts: one player walked away
 * from a match that had begun and lost it, versus a match that never started because nobody
 * arrived. Collapsing them would make a queue that timed out look like a defeat on the record.
 */
@Serializable
enum class PvpMatchStatus {
    /** Paired, both sides notified, waiting for the first move. */
    PLAYING,

    /** Nine cards placed, or a sudden-death rematch decided it. */
    FINISHED,

    /** One side ran out of time to come back. See [PvpOutcome.forfeitedBy]. */
    FORFEITED,

    /** Never began: the invitation lapsed, or the queue was left. Nothing is credited. */
    ABANDONED,
}

/**
 * How a finished match ended, from the reader's own side.
 *
 * @property result this side's result. A sudden-death draw is not one — the rematch decides it —
 *   which is why `MatchResult.of` returns null there and why this field carries the *settled*
 *   answer only.
 * @property forfeitedBy who walked away, or null if the match was played out. Named rather than
 *   implied by the result, because "you won" and "you won because they left" are not the same
 *   sentence to show a player.
 * @property cardWon the card taken from the loser, when the match was played for one.
 * @property cardLost the card given up. Exactly one of the two is set on each side.
 */
@Serializable
data class PvpOutcome(
    val result: MatchResult,
    val blue: Int,
    val red: Int,
    val forfeitedBy: CardColor? = null,
    val mgp: Int = 0,
    val xp: Int = 0,
    val cardWon: Int? = null,
    val cardLost: Int? = null,
)

/**
 * What is being played for.
 *
 * ### Why a card wager is a sealed pair and not a nullable id
 *
 * [Card] is the classic Triple Triad stake and the reason the game has any tension; MGP alone makes
 * a loss free. But a card is a *possession*, and losing one is the only irreversible thing this
 * game can do to a player. So the two are distinct types rather than a nullable field: every place
 * that credits a match has to say which of the two it is handling, and a `when` that forgets one is
 * a compile error rather than a silently unwagered match.
 *
 * The challenger proposes and the other side accepts — see [PvpChallenge]. There is no
 * counter-offer: two rounds of negotiation is a chat feature, and this is a card game.
 */
@Serializable
sealed interface PvpStake {
    /** MGP only, as a PvE match pays. The default, and what the quick queue always plays for. */
    @Serializable
    data object None : PvpStake

    /**
     * Each side puts up one card; the winner takes the loser's.
     *
     * Both ids are named up front so each player sees what they are risking *and* what they stand
     * to win before agreeing. A stake naming only the challenger's card would be an offer the other
     * side cannot evaluate.
     */
    @Serializable
    data class Cards(val challengerCard: Int, val opponentCard: Int) : PvpStake
}

/** A player waiting to be paired, or being told they now are. */
@Serializable
data class PvpQueueState(
    val waiting: Boolean,
    val since: Long? = null,
    val matchId: String? = null,
)

/**
 * An invitation to a named player.
 *
 * @property expiresAt when it lapses. An invitation with no expiry is a notification that never
 *   goes away, and both sides need to know when the offer stops standing rather than discovering
 *   it on a refusal.
 */
@Serializable
data class PvpChallenge(
    val id: String,
    val fromName: String,
    val toName: String,
    val stake: PvpStake = PvpStake.None,
    val expiresAt: Long,
    val matchId: String? = null,
)

/** One placement, as a client asks for it. */
@Serializable
data class PvpMove(val handIndex: Int, val position: Int)
