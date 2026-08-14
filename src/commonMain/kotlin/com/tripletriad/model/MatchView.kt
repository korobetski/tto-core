package com.tripletriad.model

import kotlin.random.Random

/**
 * A match as **one side may see it**.
 *
 * ### Why this has to exist for player-versus-player and did not before
 *
 * In a PvE match the client holds the whole truth and hides part of it from itself. That is
 * legitimate there: the opponent is a program the same client is running, so there is nobody to
 * keep a secret from — `HandVisibility` exists to stop the *screen* drawing a card, not to stop the
 * player knowing it. Anyone willing to read memory could see the AI's hand, and it would cost them
 * a match against a program.
 *
 * Against another person it is a different claim entirely. The opponent's five cards are their
 * private information for the length of the match, and "the client has them but promises not to
 * draw them" is not privacy — it is an honour system enforced by a UI. So the server holds
 * [MatchState] and each client is sent one of these: **the cards it may see, and nothing else on
 * the wire to leak.**
 *
 * `MatchSetup.opponentVisibility` says outright that "the reverse direction is not modelled: the
 * original always shows a player their own cards". That is the assumption this type retires. There
 * are two sides now and each has its own view of the other.
 *
 * ### Why the playable cards are data and not a function
 *
 * [MatchState.playableCards] takes a `Random`, because [OrderRule.CHAOS] picks one card at random
 * each turn. In a solo match that is fine — one process rolls and obeys its own roll.
 *
 * Across a network it is a bug waiting to happen: the client would roll one card, the server
 * another, and the move would be refused with no way for the player to understand why. So the roll
 * belongs to whoever holds the state, and the answer travels **as [playableHandIndices]**. The
 * client does not decide what is playable; it is told.
 *
 * The same reasoning applies to [OrderRule.ORDER] even though it is deterministic. One rule for
 * both is one thing to get right.
 *
 * @property side whose view this is. Both players get a view; they differ in everything below.
 * @property ownHand this side's cards, in full. Always visible to their owner.
 * @property opponentHand the other side's cards **positionally**, with `null` where a card is
 *   hidden. A list of the same length as their real hand rather than a filtered list of what is
 *   visible, because the count is public — a player can see how many cards are left — and because
 *   `HandVisibility` is indexed and closes the gap when a card is played. Filtering would lose
 *   which slot a revealed card sits in, and Three Open would render as "three cards" instead of
 *   "five cards, three of them face up".
 * @property playableHandIndices indices into [ownHand] that may be played this turn, decided by
 *   the holder of the state. **Empty when it is not this side's turn**, which is also how a client
 *   knows to render the board as read-only without a second flag meaning the same thing.
 */
data class MatchView(
    val side: CardColor,
    val rules: GameRules,
    val board: Board,
    val ownHand: List<Card>,
    val opponentHand: List<Card?>,
    val order: TurnOrder,
    val placement: Int,
    val tally: AscensionTally = AscensionTally.EMPTY,
    val lastPlay: PlayResult? = null,
    val playableHandIndices: List<Int> = emptyList(),
) {
    init {
        require(placement in 0..PLACEMENTS_PER_MATCH) {
            "placement must be in 0..$PLACEMENTS_PER_MATCH, was $placement"
        }
        require(playableHandIndices.all { it in ownHand.indices }) {
            "playable indices $playableHandIndices are not all in ${ownHand.indices}"
        }
    }

    /** The other side. */
    val opponent: CardColor get() = side.opposite()

    val isFinished: Boolean get() = placement >= PLACEMENTS_PER_MATCH

    /** Whose turn it is, or null once the board is full. */
    val currentPlayer: CardColor? get() = if (isFinished) null else order.colorAt(placement)

    /**
     * Whether this side may move now.
     *
     * Derived from [currentPlayer] rather than from `playableHandIndices.isNotEmpty()`, because the
     * two can legitimately disagree: it is your turn and the board is full is impossible, but it is
     * your turn and every card is unplayable is not a state the rules produce and should not be
     * silently reported as "not your turn" if a bug ever produces it.
     */
    val isMyTurn: Boolean get() = currentPlayer == side

    /**
     * The score, counting unplayed cards for their owner — [score], and the same total of ten.
     *
     * Computable from a view because both hand *sizes* are public: [opponentHand] keeps its nulls,
     * so its length is the opponent's real card count even when none of them may be seen.
     */
    val score: MatchScore
        get() = score(
            board,
            mapOf(side to ownHand.size, opponent to opponentHand.size),
        )

    /**
     * How this ended, or null while it is still being played — [MatchState.outcome] from a view.
     *
     * The same three answers computed the same way, and it can be: [score] counts unplayed cards
     * for their owner from the two hand *lengths*, which a view keeps even when it may not see what
     * is in the other one. So a side that cannot name a single opponent card can still say who won.
     *
     * It exists because a refereed client has no [MatchState] to ask. On this side of the wire the
     * match is a sequence of views, and "the board is full and blue took it" is something the
     * screen has to know in order to announce it.
     */
    fun outcome(): MatchOutcome? {
        if (!isFinished) return null
        val final = score
        val winner = final.winner()
        return when {
            winner != null -> MatchOutcome.Win(winner, final)
            rules.suddenDeath -> MatchOutcome.SuddenDeath(final)
            else -> MatchOutcome.Draw(final)
        }
    }

    /** Where a card may go. Empty when it is not this side's turn, as [playableHandIndices] is. */
    fun playablePositions(): List<Int> =
        if (isMyTurn) board.emptyPositions() else emptyList()

    /** The cards [playableHandIndices] names, for a caller wanting them rather than the slots. */
    val playableCards: List<Card> get() = playableHandIndices.map(ownHand::get)

    companion object {
        /**
         * [state] as [side] may see it, hiding the other hand behind [opponentVisibility].
         *
         * @param random only for [OrderRule.CHAOS], and only when it is [side]'s turn. Passing a
         *   seeded one is what makes a Chaos match reproducible; the server passes the match's own
         *   generator so that the choice is recorded in the transcript's seed like everything else.
         */
        fun of(
            state: MatchState,
            side: CardColor,
            opponentVisibility: HandVisibility,
            random: Random = Random.Default,
        ): MatchView {
            val own = state.hands[side].orEmpty()
            val theirs = state.hands[side.opposite()].orEmpty()
            val mine = state.currentPlayer == side

            return MatchView(
                side = side,
                rules = state.rules,
                board = state.board,
                ownHand = own,
                opponentHand = theirs.mapIndexed { index, card ->
                    card.takeIf { opponentVisibility.isVisible(index) }
                },
                order = state.order,
                placement = state.placement,
                tally = state.tally,
                lastPlay = state.lastPlay,
                // The roll happens here, once, on the side that is to move. Asking for the other
                // side's playable cards would roll a Chaos choice that nobody will act on and that
                // the next call would contradict.
                playableHandIndices = if (!mine) {
                    emptyList()
                } else {
                    val playable = state.playableCards(random).toSet()
                    own.indices.filter { own[it] in playable }
                },
            )
        }
    }
}
