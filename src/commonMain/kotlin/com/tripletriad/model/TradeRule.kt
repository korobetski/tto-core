package com.tripletriad.model

import kotlinx.serialization.Serializable

/**
 * What a match is played *for*, when it is played for cards.
 *
 * The four trade rules of the original game. They are a property of the **wager**, not of the
 * board: nothing here changes how a card captures, only what happens to the two collections when
 * the ninth card is down. That is why this is not one of [GameRules]' twelve slots — a rule set is
 * replayed to verify a transcript, and a transcript that replayed differently depending on what was
 * at stake would not be a transcript.
 *
 * [DIRECT] is the odd one and the reason this is an enum rather than a count: it is the only rule
 * under which the **loser can also win cards**, so every settlement has to branch on the rule
 * rather than ask "how many does the winner take".
 */
@Serializable
enum class TradeRule {
    /** Nothing changes hands. The default, and what a match played for MGP alone is. */
    NONE,

    /** The winner names one of the loser's five. */
    ONE,

    /** The winner names as many as the margin: one at 6–4, four at 9–1. */
    DIFF,

    /** Each side keeps what it holds at the end — captures are permanent. */
    DIRECT,

    /** The winner takes all five. */
    ALL,
}

/**
 * Settling a wager, as arithmetic with no storage in it.
 *
 * Kept apart from [TradeRule] itself so the enum stays a name and the rules stay testable without a
 * match, a database or an account. The server is the only caller — a client that could compute what
 * it had won could also compute what it had not.
 */
object TradeRules {
    /**
     * How many of the loser's cards the winner gets to **name**, which is not the same as how many
     * change hands.
     *
     * Zero for [TradeRule.NONE] and for anybody who did not win. Zero for [TradeRule.DIRECT] too,
     * and that is the distinction this function exists to make: Direct moves cards without anybody
     * choosing, so a caller reading a zero here must not conclude that nothing was won. See
     * [directTransfers].
     *
     * @param winnerScore the winner's score, out of [TOTAL_CARDS]. Only [TradeRule.DIFF] reads it.
     */
    fun picks(rule: TradeRule, result: MatchResult, winnerScore: Int): Int {
        if (result != MatchResult.WIN) return 0
        return when (rule) {
            TradeRule.NONE, TradeRule.DIRECT -> 0
            TradeRule.ONE -> 1
            TradeRule.DIFF -> (winnerScore - TOTAL_CARDS / 2).coerceIn(0, HAND_SIZE)
            TradeRule.ALL -> HAND_SIZE
        }
    }

    /**
     * Under [TradeRule.DIRECT]: what each side ends up holding that it did not bring.
     *
     * ### Why this counts against the dealt hands and not against `Card.owner`
     *
     * `PlacedCard.owner` says who holds a card now, and the `Card` inside it carries an owner too —
     * the side that was dealt it, since [MatchState.start] stamps both hands and [Board.capture]
     * copies the `PlacedCard` without touching the card. So `card.owner != placed.owner` looks like
     * a complete test for "this was taken".
     *
     * It is not, because of Swap. `MatchSetup.swap` re-stamps the two exchanged cards with the
     * colour of whoever **receives** them, so a swapped card's `owner` is no longer the collection
     * it came out of. Settling on that would hand the winner a card they themselves brought.
     *
     * Counting against the hands as they were **dealt** — which is what the wager was over — has
     * neither problem, and it is multiplicity-safe: a player who owns two copies of a card and
     * brings both is not made to give up one they still hold.
     *
     * @param state the finished match. Both the board and whatever is left in hand count: a card
     *   never played was never at risk.
     * @param dealt the five ids each side brought, **before** any swap.
     * @return per side, the ids it takes from the other. A side with nothing to take is absent.
     */
    fun directTransfers(
        state: MatchState,
        dealt: Map<CardColor, List<Int>>,
    ): Map<CardColor, List<Int>> = CardColor.entries.associateWith { side ->
        surplus(held = held(state, side), brought = dealt[side].orEmpty())
    }.filterValues { it.isNotEmpty() }

    /** Every id [side] holds when the match ends, on the board and in hand alike. */
    private fun held(state: MatchState, side: CardColor): List<Int> =
        state.board.cells.filterNotNull().filter { it.owner == side }.map { it.card.id } +
            state.hands[side].orEmpty().map { it.id }

    /**
     * [held] minus [brought], as multisets.
     *
     * The multiset is the point. Subtracting sets would lose a second copy of a card the player
     * already owned one of, which is the one case where "did I win this" cannot be answered by
     * looking at whether they hold one.
     */
    private fun surplus(held: List<Int>, brought: List<Int>): List<Int> {
        val remaining = brought.toMutableList()
        return held.filterNot { remaining.remove(it) }
    }
}
