package com.tripletriad.protocol

import com.tripletriad.model.AscensionTally
import com.tripletriad.model.Board
import com.tripletriad.model.Card
import com.tripletriad.model.CardColor
import com.tripletriad.model.CardType
import com.tripletriad.model.GameRules
import com.tripletriad.model.MatchResult
import com.tripletriad.model.MatchView
import com.tripletriad.model.PlacedCard
import com.tripletriad.model.PlayResult
import com.tripletriad.model.TurnOrder
import kotlinx.serialization.Serializable

/**
 * Player versus environment, over the server.
 *
 * ### Why this exists, when a transcript already worked
 *
 * It worked, and it bought something real: a match played on a plane still counted, because a
 * transcript is a *recomputation* rather than a recording and the server could replay it. That
 * design is being retired deliberately, and for a reason that is not the usual one.
 *
 * It is that **the client held both hands**. To replay a match the server had to be able to derive
 * every one of the opponent's moves, so the client had to be running the same AI from the same seed
 * — which means it also knew the opponent's cards and every move it was going to make, from the
 * first placement. A modified client played in perfect information and nothing in the transcript
 * showed it, because the match really did happen exactly as claimed.
 *
 * The server holding the match closes that, and it closes something the transcript never even
 * addressed: what the opponent may *see*. Under All Open and Three Open a program that reads its
 * opponent's hand off the state it happens to hold is not obeying the rule, it is ignoring it.
 * Refereed, the AI is given a view like anybody else.
 *
 * ### The AI is no longer part of the replay, and that is the point
 *
 * A transcript recorded only the player's moves. The opponent's were **derived**, so `MatchAi` was
 * load-bearing for verification: changing how it played invalidated every stored transcript, and
 * cost a major version.
 *
 * Here the opponent's placements are **written down** alongside the player's, in one move list. A
 * row is therefore self-contained: it replays to what it replayed to yesterday whatever the AI does
 * next. That is what lets the opponent get cleverer without a protocol bump, and what lets a match
 * interrupted by a deployment be resumed rather than contradicted.
 *
 * ### One round trip per placement, not two
 *
 * `POST /pve/matches/{id}/moves` applies the player's card **and the opponent's reply**, and
 * answers with both — see [PveMatchView.plays]. Against a person a client polls because it cannot
 * know when the other side will move; against a program the answer exists the moment the question
 * is asked, and making the client come back for it would add a round trip to every turn for
 * nothing.
 */

/** One placement, as a client asks for it. The slot it comes from, and the cell it goes to. */
@Serializable
data class PveMove(val handIndex: Int, val position: Int)

/**
 * What a client posts to sit down against an opponent.
 *
 * The deck is a **slot**, not five card ids, for the reason the refereed player-versus-player path
 * uses one: `PveMatches.playerDeck` resolves it against the profile the *server* holds, so a client
 * can choose which of its decks to bring and still cannot name a card it does not own. [ANY_DECK]
 * means no choice was made and lands on the first complete, affordable deck.
 *
 * @property campaignKey the tournament this match belongs to, or null for an ordinary one.
 *
 * A **claim, not a licence**: it says which run the client believes it is playing, and the run's
 * terms — the opponent's own stake waived, its drops and XP multiplied — are granted only after
 * matching it against the `CampaignRun` the profile actually holds, and only when that run stands
 * on this very opponent. Taken on trust it would be a request for cheaper matches and richer
 * rewards, which is most of what an entry fee buys.
 */
@Serializable
data class PveMatchRequest(
    val opponentIconId: String,
    val formatId: String,
    val deck: Int = ANY_DECK,
    val campaignKey: String? = null,
)

/**
 * Where a match is in its life.
 *
 * Shorter than [PvpMatchStatus] by two members, and both absences are the same fact: **a program is
 * never waiting.** There is no forfeit, because nothing is lost by taking an hour over a turn, and
 * no claim, because there is no wager to name a prize out of.
 *
 * That is the whole of what "a dropped connection must not be an abandon" needs. A match sits in
 * [PLAYING] until it is played out, and coming back to it is an ordinary read.
 */
@Serializable
enum class PveMatchStatus {
    /** Live. The only state a match is in until the ninth card lands. */
    PLAYING,

    /** Played out and credited. */
    FINISHED,

    /**
     * Never finished and no longer live — the player opened another match, or the row was swept.
     *
     * Nothing is credited. Distinct from [FINISHED] because a match nobody completed is not a
     * result, and folding the two would put a defeat on the record that nobody suffered.
     */
    ABANDONED,
}

/**
 * How a finished match ended, and what it paid.
 *
 * ### Why the payout is *in* the outcome and not fetched afterwards
 *
 * Because the pair of writes that used to exist is exactly where rewards went missing. The client
 * credited itself and then told the server, or credited itself from a receipt it had to ask for
 * separately — either way there were two copies of the profile and a window in which they
 * disagreed, which is what an item that never reaches the bag looks like from the inside.
 *
 * One write, on the server, at settlement, and the profile that comes back **is** the answer. A
 * client does not add anything up; it replaces what it holds with [player].
 *
 * @property result the player's result. Never `null` here, unlike [MatchResult.of]: a sudden-death
 *   draw is not an ending, it is a rematch, and a match that reaches this type has one.
 * @property reward what the match paid, as the server computed it — MGP, XP, drops, achievements
 *   and the day's quests.
 * @property player the profile **after** crediting. The one authority on what the player now owns.
 */
@Serializable
data class PveOutcome(
    val result: MatchResult,
    val blue: Int,
    val red: Int,
    val reward: RewardSummary? = null,
    val player: PlayerState? = null,
)

/**
 * The match as the player may see it.
 *
 * The wire form of [MatchView], and deliberately not [MatchView] itself: that type holds real
 * [Card]s and is what the *screen* wants, while this holds ids and is what the *network* wants.
 * Keeping them apart is what stops the model's shape from becoming a protocol that cannot change
 * without a version bump.
 *
 * There is no `side` field. In an environment match the player is always blue — `MatchState.start`
 * stamps the hands that way and `PveMatches.assemble` deals them that way — so a field carrying it
 * would be a constant that a reader could be tempted to branch on.
 *
 * @property opponentIconId which opponent, by icon id. The name, the portrait and the difficulty
 *   are looked up in `npcs.json`, which both ends hold; sending them would put a catalogue on the
 *   wire many times per match to say what one string already says. This is where the refereed
 *   player-versus-player view carries `opponentName`, because a username is in no catalogue.
 * @property cells the board, nine entries, `null` where empty.
 * @property elements the nine board elements, `null` where a tile has none. Only ever non-null
 *   under the Elemental rule.
 * @property hand the player's cards, by id, in slot order.
 * @property opponentHand the opponent's slots: an id where the rules reveal it, `null` otherwise.
 *   The **length** is its real card count, which is public — a player can count the cards in front
 *   of them. This is the field that used to be entirely in the client's hands.
 * @property playable slots of [hand] that may be played now. Empty when it is not the player's
 *   turn, which is how a client knows the board is read-only. The server decides it, so that
 *   [com.tripletriad.model.OrderRule.CHAOS] rolls once rather than once per device.
 * @property rematch how many sudden-death rematches have been played, 0 for the first board. A
 *   rematch resets [cells] and [placement], so without this a client could not tell a fresh board
 *   from the same match starting over and would have nothing to announce.
 * @property plays the placements **this response is announcing**, in order.
 *
 * Not "the last move", which is what the refereed player-versus-player view carries: one request
 * here produces two placements, the player's and the opponent's reply, and a client that could only
 * be told about one of them would animate the reply and swallow the move that caused it.
 *
 * Empty on a plain read. Resuming a match is not a thing to animate — the board is the truth, and
 * replaying six placements at somebody who just reopened the app would be telling them a story they
 * were there for.
 */
@Serializable
data class PveMatchView(
    val matchId: String,
    val opponentIconId: String,
    val rules: GameRules,
    val formatId: String,
    val cells: List<BoardCell?>,
    val elements: List<CardType?>,
    val hand: List<Int>,
    val opponentHand: List<Int?>,
    val first: CardColor,
    val placement: Int,
    val ascension: Map<CardType, Int> = emptyMap(),
    val playable: List<Int> = emptyList(),
    val rematch: Int = 0,
    val status: PveMatchStatus = PveMatchStatus.PLAYING,
    val outcome: PveOutcome? = null,
    val plays: List<Placement> = emptyList(),
) {
    /**
     * This view as the model type the screen renders, resolving ids through [cards].
     *
     * Returns null if any id is unknown, rather than dropping the card: a board with a hole in it
     * would be a match the player cannot reason about, and a client whose catalogue disagrees with
     * the server's has a version problem to report, not a frame to render.
     */
    fun toMatchView(cards: Map<Int, Card>): MatchView? {
        // Every id is checked before any is used, so the refusal is one decision taken once rather
        // than several `?: return null`s that each mean the same thing in a different place.
        val named = hand + opponentHand.filterNotNull() + cells.mapNotNull { it?.cardId } +
            plays.map { it.cardId }
        if (named.any { it !in cards }) return null

        // Stamped, not taken as they come. A catalogue card carries `owner = BLUE` as a default
        // rather than a fact, and `CardFace` fills from exactly this field. `MatchState.start`
        // stamps its hands for the same reason; this is that step for a hand that arrived as
        // integers.
        val ownCards = hand.map { cards.getValue(it).copy(owner = CardColor.BLUE) }
        val theirCards = opponentHand.map { id ->
            id?.let { cards.getValue(it).copy(owner = CardColor.RED) }
        }
        val board = Board(
            cells = cells.map { cell ->
                cell?.let { PlacedCard(cards.getValue(it.cardId), it.owner) }
            },
            elements = elements,
        )

        return MatchView(
            side = CardColor.BLUE,
            rules = rules,
            board = board,
            ownHand = ownCards,
            opponentHand = theirCards,
            order = TurnOrder(first),
            placement = placement,
            tally = AscensionTally(ascension),
            // The **last** of them, because `MatchView` models one placement and the last one is
            // what produced the board it carries. A caller wanting to animate the exchange reads
            // `plays` directly; this keeps the model type meaning what it has always meant.
            lastPlay = plays.lastOrNull()?.let { play ->
                PlayResult(
                    player = play.player,
                    card = cards.getValue(play.cardId).copy(owner = play.player),
                    position = play.position,
                    captures = play.captures,
                    handIndex = play.handIndex,
                )
            },
            playableHandIndices = playable,
        )
    }

    companion object {
        /** [view] projected onto the wire. The inverse of [toMatchView], up to the ids. */
        @Suppress("LongParameterList")
        fun of(
            view: MatchView,
            matchId: String,
            opponentIconId: String,
            formatId: String,
            status: PveMatchStatus = PveMatchStatus.PLAYING,
            rematch: Int = 0,
            outcome: PveOutcome? = null,
            plays: List<Placement> = emptyList(),
        ): PveMatchView = PveMatchView(
            matchId = matchId,
            opponentIconId = opponentIconId,
            rules = view.rules,
            formatId = formatId,
            cells = view.board.cells.map { placed ->
                placed?.let { BoardCell(it.card.id, it.owner) }
            },
            elements = view.board.elements,
            hand = view.ownHand.map { it.id },
            opponentHand = view.opponentHand.map { it?.id },
            first = view.order.first,
            placement = view.placement,
            ascension = view.tally.counts,
            playable = view.playableHandIndices,
            rematch = rematch,
            status = status,
            outcome = outcome,
            plays = plays,
        )
    }
}

/** Why a request about an environment match was refused. Machine-readable, so wording is free. */
@Serializable
enum class PveRefusal {
    /** No opponent with that icon id, or none in that format. */
    NO_SUCH_OPPONENT,

    /** No format by that id. */
    NO_SUCH_FORMAT,

    /** No match by that id, or it is not this player's. */
    NO_SUCH_MATCH,

    /**
     * The board is not waiting on this player.
     *
     * Covers a finished match too, and correctly: a match that has ended has no current player, so
     * "it is not your turn" is the true answer to a placement offered against it.
     */
    NOT_YOUR_TURN,

    /** The slot names a card that may not be played this turn, or the cell is taken. */
    ILLEGAL_MOVE,

    /** The profile cannot field five cards in this format. */
    UNDEALABLE,

    /**
     * The match claims a tournament rung the player is not standing on.
     *
     * No open run, a run in a different ladder, or the wrong rung of the right one. Its own code
     * rather than [NO_SUCH_OPPONENT] because the two ask for opposite things from a client: that
     * one means the catalogues disagree, this one means the client's idea of where the run stands
     * is stale and it should re-read the profile.
     */
    NOT_ON_THAT_RUNG,
}

/** A refusal, as the code a client acts on and the sentence a human reads. */
@Serializable
data class PveFailure(val code: PveRefusal, val detail: String)
