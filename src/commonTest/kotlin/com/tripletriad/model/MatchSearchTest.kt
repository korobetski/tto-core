package com.tripletriad.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The opponent looking further than one move.
 *
 * **The test that justifies the file** is [theSearchRefusesTheCaptureThatOffersTheReply]: a
 * position where taking the most cards this turn loses the match next turn, and where only a
 * two-half-move search says so. Everything the search adds over `MatchAi` is that, once.
 *
 * **The test that keeps it honest** is [changingACardTheOpponentCannotSeeDoesNotChangeItsMove].
 * The search is handed a whole [MatchState] — both hands are in there — so not cheating is a
 * property of this file rather than of whoever calls it.
 */
class MatchSearchTest {

    // ---- Seeing the reply -------------------------------------------------

    /**
     * The greedy move captures two and loses; the searched move captures none and wins.
     *
     * The position, blue to move with one card each left:
     *
     * ```
     *  R  .  R
     *  b  .  b
     *  R  b  B
     * ```
     *
     * Capitals are aces, lower case is a one. Cell 1 takes both red corners — and leaves the centre
     * open to a card that takes all three weak blues around it. Cell 4 takes nothing and closes the
     * board. A one-move opponent cannot see the difference, because the difference happens on the
     * turn after the one it is looking at.
     *
     * **The danger is deliberately visible to a substitute.** The search does not know what red
     * holds and plans against an average card, so a trap that only a hidden ace could spring would
     * be one an honest opponent is right to ignore. Three cards showing a single pip are in reach
     * of anything.
     */
    @Test
    fun theSearchRefusesTheCaptureThatOffersTheReply() {
        val greedy = assertNotNull(MatchAi().choose(trap, Random(SEED)))
        val searched = assertNotNull(searcher(depth = 2).choose(trap, random = Random(SEED)))

        assertEquals(GREEDY_CELL, greedy.position, "the fixture must tempt a one-move opponent")
        assertEquals(SAFE_CELL, searched.position, "and the search must decline the temptation")

        assertTrue(playOut(greedy).red > playOut(greedy).blue, "the greedy line loses")
        assertTrue(playOut(searched).blue > playOut(searched).red, "and the searched line wins")
    }

    /** Depth one is the original, exactly: no search, and the same move `MatchAi` would play. */
    @Test
    fun depthOneIsTheOldOpponentAndNotASearchOfDepthOne() {
        val direct = MatchAi().choose(trap, Random(SEED))
        val searched = searcher(depth = 1).choose(trap, random = Random(SEED))

        assertEquals(direct, searched)
    }

    // ---- Not cheating -----------------------------------------------------

    /**
     * **A card the rules do not reveal cannot change the opponent's move.**
     *
     * The anti-cheat assertion. Under no Open rule the opponent's hand is replaced by a substitute
     * before the first node is expanded, so two positions differing only in what is hidden are the
     * same position as far as the search is concerned.
     */
    @Test
    fun changingACardTheOpponentCannotSeeDoesNotChangeItsMove() {
        val weak = trap.withRedHand(listOf(card(90, 1, 1, 1, 1)))
        val strong = trap.withRedHand(listOf(card(90, ACE_POWER, ACE_POWER, ACE_POWER, ACE_POWER)))

        assertEquals(
            searcher(depth = 3).choose(weak, random = Random(SEED))?.position,
            searcher(depth = 3).choose(strong, random = Random(SEED))?.position,
            "the hidden hand leaked into the search",
        )
    }

    /**
     * And a card the rules **do** reveal is allowed to change everything.
     *
     * The other half, and it is what proves the first is a decision rather than an oversight: an
     * opponent that ignored [HandVisibility] entirely would pass the test above for the wrong
     * reason.
     *
     * Same trap, same hand — a single card too weak to take anything. Blind, the search assumes an
     * average card and declines the two captures on offer, which is right. Under an Open rule it
     * can see there is nothing to be afraid of, and takes them.
     */
    @Test
    fun anOpenRuleIsReadRatherThanIgnored() {
        val harmless = trap.withRedHand(listOf(card(90, 1, 1, 1, 1)))
        val open = HandVisibility(setOf(0))

        assertEquals(
            SAFE_CELL,
            searcher(depth = 3).choose(harmless, random = Random(SEED))?.position,
            "blind, the centre is the safe move",
        )
        assertEquals(
            GREEDY_CELL,
            searcher(depth = 3).choose(harmless, visible = open, random = Random(SEED))?.position,
            "seeing the hand, there is nothing to fear and two cards to take",
        )
    }

    // ---- Being a function -------------------------------------------------

    /** Same position, same generator, same answer. The referee replays nothing else. */
    @Test
    fun theSearchIsAFunctionOfWhatItIsGiven() {
        val once = searcher(depth = 3).choose(trap, random = Random(SEED))
        val twice = searcher(depth = 3).choose(trap, random = Random(SEED))

        assertEquals(once, twice)
    }

    /** It does not reach into the board it was handed. */
    @Test
    fun theStateComesBackUntouched() {
        val before = trap.copy()

        searcher(depth = 4).choose(trap, random = Random(SEED))

        assertEquals(before, trap)
    }

    // ---- The guarantees ---------------------------------------------------

    /**
     * A budget of almost nothing still answers, and answers legally.
     *
     * The ceiling exists so that one placement cannot become a request that never returns. Running
     * out of it makes the search shallower, never wrong: the move still comes from the ranked list
     * of moves that are actually available.
     */
    @Test
    fun anExhaustedBudgetGivesAShallowAnswerAndNotNoAnswer() {
        val starved = MatchSearch(
            MatchAiOptions(depth = 5, nodeBudget = 1, cautiousOpening = false),
        )

        val move = assertNotNull(starved.choose(opening, random = Random(SEED)))

        assertTrue(move.position in opening.playablePositions())
        assertTrue(opening.currentHand.any { it.id == move.card.id })
    }

    @Test
    fun aFullBoardHasNoMove() {
        assertNull(searcher(depth = 3).choose(trap.copy(placement = PLACEMENTS_PER_MATCH)))
    }

    /**
     * Solving the endgame is affordable, and asking for it from the first placement is survivable.
     *
     * The threshold exists because the tree below a handful of free cells is small enough to walk
     * exhaustively — the last placements are then played exactly right rather than nearly right.
     * The risk is the other end: a threshold set high enough to solve from move one asks for the
     * whole game tree. It stays bounded because [MatchAiOptions.nodeBudget] is a ceiling and not a
     * hint, and this is that guarantee.
     */
    @Test
    fun solvingIsBoundedEvenWhenAskedForFromTheFirstPlacement() {
        val solving = MatchSearch(MatchAiOptions(depth = 2, solveFrom = Board.SIZE))

        val move = assertNotNull(solving.choose(opening, random = Random(SEED)))

        assertTrue(move.position in opening.playablePositions())
        val solvingTheTrap = MatchSearch(MatchAiOptions(depth = 2, solveFrom = 2))
        assertEquals(SAFE_CELL, solvingTheTrap.choose(trap, random = Random(SEED))?.position)
    }

    /** A blunder rate of one plays the worst move it found, every time. */
    @Test
    fun aCertainBlunderPlaysTheWorstMoveOnThePile() {
        val fool = MatchSearch(MatchAiOptions(depth = 2, blunderRate = 1.0))

        val move = assertNotNull(fool.choose(trap, random = Random(SEED)))

        assertEquals(
            MatchAi().candidates(trap, Random(SEED)).last().position,
            move.position,
            "a certain blunder should be the bottom of the ranking",
        )
    }

    /** The options refuse a shape that would mean nothing rather than behaving oddly. */
    @Test
    fun theOptionsRefuseNonsenseRatherThanActingOnIt() {
        assertFails { MatchAiOptions(depth = 0) }
        assertFails { MatchAiOptions(blunderRate = 2.0) }
        assertFails { MatchAiOptions(nodeBudget = 0) }
    }

    // ---- Fixtures ---------------------------------------------------------

    private fun searcher(depth: Int) = MatchSearch(
        MatchAiOptions(depth = depth, solveFrom = 0, cautiousOpening = false),
    )

    // A card is four faces, a number and a colour. Grouping them to satisfy the count would put a
    // type between the fixture and the thing it is describing.
    @Suppress("LongParameterList")
    private fun card(
        number: Int,
        top: Int,
        right: Int,
        bottom: Int,
        left: Int,
        owner: CardColor = CardColor.BLUE,
    ) = Card(
        id = Card.idFor(block = 1, number = number),
        nameKey = "STR_TEST_$number",
        name = "Test $number",
        top = top,
        right = right,
        bottom = bottom,
        left = left,
        rarity = 1,
        owner = owner,
    )

    /** A card whose four faces are [power], for filling cells nothing is asserted about. */
    private fun filler(number: Int, power: Int, owner: CardColor) =
        card(number, power, power, power, power, owner)

    private fun placed(number: Int, power: Int, owner: CardColor) =
        PlacedCard(filler(number, power, owner), owner)

    /**
     * The trap position.
     *
     * Cells 0 and 2 are weak reds, so cell 1 takes them both. Cells 3, 5 and 7 are strong blues,
     * so only a strong card takes them — which is exactly what red still holds.
     */
    private val trap: MatchState = MatchState(
        board = Board(
            cells = listOf(
                placed(1, STRONG, CardColor.RED),
                null,
                placed(2, STRONG, CardColor.RED),
                placed(3, WEAK, CardColor.BLUE),
                null,
                placed(4, WEAK, CardColor.BLUE),
                placed(5, STRONG, CardColor.RED),
                placed(6, WEAK, CardColor.BLUE),
                placed(7, STRONG, CardColor.BLUE),
            ),
        ),
        hands = mapOf(
            // An ace. It takes the two red corners from cell 1, takes nothing from the centre —
            // the three cards around it are already blue — and cannot be taken back from above.
            CardColor.BLUE to listOf(
                card(10, ACE_POWER, ACE_POWER, ACE_POWER, ACE_POWER),
            ),
            // Never searched: the rules reveal nothing, so this is replaced by a substitute before
            // the first node. It is here because a hand has to hold something.
            CardColor.RED to listOf(
                card(11, MIDDLING, MIDDLING, MIDDLING, MIDDLING, CardColor.RED),
            ),
        ),
        // Blue moves at placement seven when red opened.
        order = TurnOrder(CardColor.RED),
        placement = 7,
    )

    /** A full board at placement zero, for the budget test — the widest tree there is. */
    private val opening: MatchState = MatchState.start(
        blueHand = (40 until 45).map { filler(it, MIDDLING, CardColor.BLUE) },
        redHand = (50 until 55).map { filler(it, MIDDLING, CardColor.RED) },
        first = CardColor.BLUE,
    )

    private fun MatchState.withRedHand(hand: List<Card>) =
        copy(hands = hands + (CardColor.RED to hand.map { it.copy(owner = CardColor.RED) }))

    /** [move] played, then the one-move opponent's best reply, to the end of the board. */
    private fun playOut(move: ScoredMove, from: MatchState = trap): MatchScore {
        var at = from.play(move.card, move.position)
        while (!at.isFinished) {
            val reply = MatchAi().choose(at, Random(SEED)) ?: break
            at = at.play(reply.card, reply.position)
        }
        return at.score
    }

    private companion object {
        const val SEED = 20260821

        const val GREEDY_CELL = 1
        const val SAFE_CELL = 4

        const val WEAK = 2
        const val MIDDLING = 5
        const val STRONG = 7
        const val BEATS_STRONG = 9
    }
}
