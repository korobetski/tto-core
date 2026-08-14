package com.tripletriad.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What one side of a match may see.
 *
 * **The assertions that matter here are the negative ones.** A view that shows too little is a
 * cosmetic bug; a view that shows too much is the whole reason the type exists, and it would be
 * invisible in play — the leaking client renders correctly and the player it cheats never knows.
 * So every visibility test below checks what is *absent* as well as what is present.
 */
class MatchViewTest {
    private val testBlock = 1

    private fun card(id: Int, power: Int = 5) = Card(
        id = Card.idFor(testBlock, id),
        nameKey = "STR_TEST_$id",
        name = "Test $id",
        top = power,
        right = power,
        bottom = power,
        left = power,
        rarity = 1,
    )

    private fun hand(from: Int, power: Int = 5) =
        (from until from + HAND_SIZE).map { card(it, power) }

    private fun match(first: CardColor = CardColor.BLUE, rules: GameRules = GameRules()) =
        MatchState.start(
            blueHand = hand(from = 1, power = 8),
            redHand = hand(from = 11, power = 2),
            first = first,
        ).copy(rules = rules)

    /**
     * The hands as the *state* holds them, which is not as they were handed in.
     *
     * `MatchState.start` stamps `Card.owner` on every card, so a fixture list compares unequal to
     * the same cards inside a match. Reading them back off the state is what makes these tests
     * assertions about visibility rather than about who wrote the fixture.
     */
    private val blueHand get() = match().hands.getValue(CardColor.BLUE)
    private val redHand get() = match().hands.getValue(CardColor.RED)

    private fun viewOf(
        state: MatchState = match(),
        side: CardColor = CardColor.BLUE,
        visibility: HandVisibility = HandVisibility.HIDDEN,
        random: Random = Random(1),
    ) = MatchView.of(state, side, visibility, random)

    /** The default: you see your five, and five backs. */
    @Test
    fun aHiddenOpponentIsFiveSlotsAndNoCards() {
        val view = viewOf()

        assertContentEquals(blueHand, view.ownHand)
        assertEquals(HAND_SIZE, view.opponentHand.size, "the count is public")
        assertTrue(view.opponentHand.all { it == null }, view.opponentHand.toString())
    }

    /**
     * Three Open reveals three of five **in place**.
     *
     * The positional part is the point: a filtered list would say "three cards" and the screen
     * would draw a three-card hand, which is a different game.
     */
    @Test
    fun threeOpenRevealsThreeCardsInTheirOwnSlots() {
        val visible = setOf(0, 2, 4)

        val view = viewOf(visibility = HandVisibility(visible))

        assertEquals(HAND_SIZE, view.opponentHand.size)
        for (index in view.opponentHand.indices) {
            if (index in visible) {
                assertEquals(redHand[index], view.opponentHand[index], "slot $index")
            } else {
                assertNull(view.opponentHand[index], "slot $index leaked")
            }
        }
    }

    @Test
    fun allOpenRevealsEverythingAndNothingMore() {
        val view = viewOf(visibility = HandVisibility(redHand.indices.toSet()))

        assertContentEquals(redHand, view.opponentHand)
    }

    /** Each side is shown its own cards, and neither is shown the other's by default. */
    @Test
    fun theTwoSidesSeeMirrorImages() {
        val state = match()

        val blue = MatchView.of(state, CardColor.BLUE, HandVisibility.HIDDEN)
        val red = MatchView.of(state, CardColor.RED, HandVisibility.HIDDEN)

        assertContentEquals(blueHand, blue.ownHand)
        assertContentEquals(redHand, red.ownHand)
        assertTrue(blue.opponentHand.all { it == null })
        assertTrue(red.opponentHand.all { it == null })
        assertEquals(CardColor.RED, blue.opponent)
        assertEquals(CardColor.BLUE, red.opponent)
    }

    /**
     * Only the side to move is told what it may play.
     *
     * Not a nicety: rolling the waiting side's Chaos card would decide something nobody acts on,
     * and the next poll would decide it differently.
     */
    @Test
    fun onlyTheSideToMoveIsToldWhatItMayPlay() {
        val state = match(first = CardColor.BLUE)

        val blue = viewOf(state, CardColor.BLUE)
        val red = viewOf(state, CardColor.RED)

        assertTrue(blue.isMyTurn)
        assertEquals(blueHand.indices.toList(), blue.playableHandIndices)
        assertFalse(red.isMyTurn)
        assertTrue(red.playableHandIndices.isEmpty())
        assertTrue(red.playablePositions().isEmpty(), "a waiting side has nowhere to play")
    }

    /** Order allows the first card only, and says so as an index rather than a rule to re-run. */
    @Test
    fun theOrderRuleAllowsOneSlot() {
        val state = match(rules = GameRules(order = OrderRule.ORDER))

        val view = viewOf(state)

        assertEquals(listOf(0), view.playableHandIndices)
        assertContentEquals(listOf(blueHand.first()), view.playableCards)
    }

    /**
     * Chaos allows exactly one slot, and **the same one for a given generator**.
     *
     * The reason the view carries indices at all. Two devices rolling their own would disagree,
     * and the player whose client rolled differently would have a move refused with nothing on
     * screen to explain it.
     */
    @Test
    fun chaosAllowsOneSlotAndTheSameOneForTheSameSeed() {
        val state = match(rules = GameRules(order = OrderRule.CHAOS))

        val first = viewOf(state, random = Random(7))
        val again = viewOf(state, random = Random(7))
        val other = viewOf(state, random = Random(8))

        assertEquals(1, first.playableHandIndices.size, first.playableHandIndices.toString())
        assertEquals(first.playableHandIndices, again.playableHandIndices)
        // Not an assertion that the two differ — two seeds may agree — only that both are legal.
        assertEquals(1, other.playableHandIndices.size)
    }

    /** The score is derived from the board and the two hand sizes, and still totals ten. */
    @Test
    fun theScoreIsVisibleWithoutSeeingTheCards() {
        val played = match().let { it.play(it.currentHand.first(), 4) }

        val view = MatchView.of(played, CardColor.BLUE, HandVisibility.HIDDEN)

        assertEquals(played.score, view.score)
        assertEquals(TOTAL_CARDS, view.score.blue + view.score.red)
    }

    /** A finished match has no current player, no playable slots and no positions. */
    @Test
    fun aFinishedMatchOffersNothing() {
        var state = match()
        while (!state.isFinished) {
            state = state.play(state.currentHand.first(), state.playablePositions().first())
        }

        val view = MatchView.of(state, CardColor.BLUE, HandVisibility.HIDDEN)

        assertTrue(view.isFinished)
        assertNull(view.currentPlayer)
        assertFalse(view.isMyTurn)
        assertTrue(view.playableHandIndices.isEmpty())
        assertTrue(view.playablePositions().isEmpty())
    }

    /**
     * A view reaches the same verdict the state does, from either side of it.
     *
     * The claim that matters is the second assertion: **red**, who can see none of blue's cards,
     * still says blue won. It works because a hand's *length* is public even when its contents are
     * not, and the score counts unplayed cards from the lengths.
     */
    @Test
    fun bothSidesReadTheSameOutcomeOffTheirOwnView() {
        var state = match()
        while (!state.isFinished) {
            state = state.play(state.currentHand.first(), state.playablePositions().first())
        }

        val blue = MatchView.of(state, CardColor.BLUE, HandVisibility.HIDDEN)
        val red = MatchView.of(state, CardColor.RED, HandVisibility.HIDDEN)

        assertEquals(state.outcome(), blue.outcome())
        assertEquals(state.outcome(), red.outcome())
    }

    /** A match still being played has no verdict to report, which is not the same as a draw. */
    @Test
    fun aLiveViewHasNoOutcome() {
        assertNull(MatchView.of(match(), CardColor.BLUE, HandVisibility.HIDDEN).outcome())
    }

    /** The last play is public — it is on the board — and carries who did what. */
    @Test
    fun theLastPlayIsShownToBothSides() {
        val played = match().let { it.play(it.currentHand.first(), 0) }

        val red = MatchView.of(played, CardColor.RED, HandVisibility.HIDDEN)

        assertEquals(blueHand.first(), red.lastPlay?.card)
        assertEquals(0, red.lastPlay?.position)
    }

    /**
     * A view cannot be built naming a slot that is not in the hand.
     *
     * The constructor is public because the client rebuilds one from the wire, and a malformed
     * payload should fail where it is decoded rather than as an index out of bounds three frames
     * later inside a composable.
     */
    @Test
    fun aViewCannotNameASlotThatIsNotInTheHand() {
        assertFailsWith<IllegalArgumentException> {
            MatchView(
                side = CardColor.BLUE,
                rules = GameRules(),
                board = Board(),
                ownHand = blueHand,
                opponentHand = List(HAND_SIZE) { null },
                order = TurnOrder(CardColor.BLUE),
                placement = 0,
                playableHandIndices = listOf(0, HAND_SIZE),
            )
        }
    }
}
