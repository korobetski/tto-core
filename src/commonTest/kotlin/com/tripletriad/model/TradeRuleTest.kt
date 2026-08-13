package com.tripletriad.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [TradeRules] — what a wager moves once the board is full.
 *
 * **The file exists for [TradeRule.DIRECT].** The other three are counting, and the count is easy
 * to read off the rule. Direct is the one that has to look at the board, the one that can move
 * cards towards a player who *lost*, and the one whose obvious implementation — comparing
 * `PlacedCard.owner` against the `Card.owner` inside it — is wrong under Swap in a way nothing else
 * would catch.
 */
class TradeRuleTest {

    private fun card(number: Int) = Card(
        id = Card.idFor(block = 1, number = number),
        nameKey = "STR_TEST_$number",
        name = "Test $number",
        top = 5,
        right = 5,
        bottom = 5,
        left = 5,
        rarity = 1,
    )

    private fun hand(from: Int) = (from until from + HAND_SIZE).map { card(it) }

    private val blueHand = hand(from = 1)
    private val redHand = hand(from = 11)

    private fun dealt() = mapOf(
        CardColor.BLUE to blueHand.map { it.id },
        CardColor.RED to redHand.map { it.id },
    )

    /** Nobody who did not win names anything, under any rule. */
    @Test
    fun onlyAWinnerNamesCards() {
        for (rule in TradeRule.entries) {
            for (result in listOf(MatchResult.LOSE, MatchResult.DRAW)) {
                assertEquals(0, TradeRules.picks(rule, result, winnerScore = 9), "$rule/$result")
            }
        }
    }

    /** One takes one, All takes the whole hand, and neither reads the score. */
    @Test
    fun oneAndAllAreFixedCounts() {
        for (score in TOTAL_CARDS / 2..TOTAL_CARDS) {
            assertEquals(1, TradeRules.picks(TradeRule.ONE, MatchResult.WIN, score))
            assertEquals(HAND_SIZE, TradeRules.picks(TradeRule.ALL, MatchResult.WIN, score))
        }
    }

    /**
     * Diff takes as many as the margin: one at 6–4, four at 9–1.
     *
     * Swept across every score a win can have rather than sampled, because the interesting values
     * are the ends — a 5–5 is not a win at all, and a 10–0 must not ask for a sixth card out of a
     * hand of five.
     */
    @Test
    fun diffTakesTheMargin() {
        val expected = mapOf(6 to 1, 7 to 2, 8 to 3, 9 to 4, 10 to HAND_SIZE)

        for ((score, picks) in expected) {
            assertEquals(picks, TradeRules.picks(TradeRule.DIFF, MatchResult.WIN, score), "$score")
        }
    }

    /** None and Direct name nothing — Direct because nothing is chosen, not because it is free. */
    @Test
    fun noneAndDirectNameNothing() {
        assertEquals(0, TradeRules.picks(TradeRule.NONE, MatchResult.WIN, winnerScore = 9))
        assertEquals(0, TradeRules.picks(TradeRule.DIRECT, MatchResult.WIN, winnerScore = 9))
    }

    /** A match nobody captured anything in transfers nothing, both ways. */
    @Test
    fun anUncapturedBoardTransfersNothing() {
        val state = MatchState.start(blueHand, redHand, CardColor.BLUE)

        assertTrue(TradeRules.directTransfers(state, dealt()).isEmpty())
    }

    /**
     * Under Direct, a captured card is a card won — and it is won by whoever holds it.
     *
     * Blue places one and red takes it. Red is credited with it whether or not red went on to win
     * the match, which is the whole of what Direct means.
     */
    @Test
    fun directCreditsWhoeverHoldsTheCard() {
        val taken = blueHand.first()
        val state = MatchState.start(blueHand, redHand, CardColor.BLUE)
            .play(taken, position = 4)
            .let { it.copy(board = it.board.capture(listOf(4), CardColor.RED)) }

        val transfers = TradeRules.directTransfers(state, dealt())

        assertContentEquals(listOf(taken.id), transfers[CardColor.RED])
        assertEquals(null, transfers[CardColor.BLUE], "blue was credited with its own card")
    }

    /**
     * A card still in hand was never at risk.
     *
     * The second player ends a match holding one unplayed card. Counting only the board would make
     * that card vanish from its owner's side of the arithmetic and reappear as a transfer.
     */
    @Test
    fun anUnplayedCardIsNotTransferred() {
        val state = MatchState.start(blueHand, redHand, CardColor.BLUE)
            .play(blueHand.first(), position = 0)

        assertTrue(TradeRules.directTransfers(state, dealt()).isEmpty())
    }

    /**
     * A swapped card goes back to whoever **brought** it, not to whoever was dealt it.
     *
     * The case that rules out the obvious implementation. `MatchSetup.swap` re-stamps the exchanged
     * cards with the receiving side's colour, so `card.owner` no longer names the collection the
     * card came out of — and settling on it would hand a player their own card as a prize. Counting
     * against the dealt hands has no such hole: red is holding a card blue brought, so blue's card
     * is what red has won.
     */
    @Test
    fun aSwappedCardIsSettledAgainstTheHandAsDealt() {
        val given = blueHand.first()
        // What Swap produces: red is dealt blue's card, stamped red, and blue holds red's back.
        val afterSwap = MatchState.start(
            blueHand = listOf(redHand.first().copy(owner = CardColor.BLUE)) + blueHand.drop(1),
            redHand = listOf(given.copy(owner = CardColor.RED)) + redHand.drop(1),
            first = CardColor.BLUE,
        )

        val transfers = TradeRules.directTransfers(afterSwap, dealt())

        assertContentEquals(
            listOf(given.id),
            transfers[CardColor.RED],
            "red did not win blue's card",
        )
        assertContentEquals(
            listOf(redHand.first().id),
            transfers[CardColor.BLUE],
            "blue did not win red's card",
        )
    }

    /**
     * A player who brought two copies and kept both has won nothing.
     *
     * The multiset case. Subtracting sets would see "they hold this id, and they were dealt it" and
     * stop there — which is right — but the reverse mistake is the live one: holding *two* of an id
     * they brought two of must also be nothing, and a set difference cannot tell those apart from
     * holding two of an id they brought one of.
     */
    @Test
    fun duplicateCopiesAreCountedNotMerelyMatched() {
        val twice = card(1)
        val doubledUp = listOf(twice, twice) + blueHand.drop(2)
        val dealtWithPair = mapOf(
            CardColor.BLUE to doubledUp.map { it.id },
            CardColor.RED to redHand.map { it.id },
        )
        val state = MatchState.start(doubledUp, redHand, CardColor.BLUE)

        assertTrue(TradeRules.directTransfers(state, dealtWithPair).isEmpty())
    }
}
