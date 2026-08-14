package com.tripletriad.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The match state machine: a full game driven only by `MatchState -> MatchState`
 * transitions, with no stage, no timers and no coroutines.
 */
class MatchStateTest {
    /** Fixtures live in block 1; ids are global, so a bare number is not one. */
    private val testBlock = 1
    private fun card(id: Int, power: Int = 5) = Card(
        // Fixtures number their cards from 1; ids are global.
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

    /** Blue holds 8s, red holds 2s, so blue wins every basic comparison. */
    private fun lopsided(first: CardColor = CardColor.BLUE) = MatchState.start(
        blueHand = hand(from = 1, power = 8),
        redHand = hand(from = 11, power = 2),
        first = first,
    )

    /** Plays the whole match, always onto the lowest free cell. */
    private fun playOut(state: MatchState): MatchState {
        var current = state
        while (!current.isFinished) {
            val card = current.currentHand.first()
            current = current.play(card, current.playablePositions().first())
        }
        return current
    }

    // ---- lifecycle -------------------------------------------------------

    @Test
    fun startRequiresFiveCardsPerSide() {
        val short = hand(from = 1).drop(1)
        assertFailsWith<IllegalArgumentException> {
            MatchState.start(blueHand = short, redHand = hand(from = 11))
        }
    }

    @Test
    fun startStampsEachHandWithItsOwnColour() {
        val state = MatchState.start(hand(from = 1), hand(from = 11))

        assertTrue(state.hands.getValue(CardColor.BLUE).all { it.owner == CardColor.BLUE })
        assertTrue(state.hands.getValue(CardColor.RED).all { it.owner == CardColor.RED })
    }

    @Test
    fun turnsAlternateAndTheFirstPlayerMovesFive() {
        var state = lopsided(first = CardColor.RED)
        val sequence = mutableListOf<CardColor>()

        while (!state.isFinished) {
            sequence += requireNotNull(state.currentPlayer)
            state = state.play(state.currentHand.first(), state.playablePositions().first())
        }

        assertEquals(PLACEMENTS_PER_MATCH, sequence.size)
        assertEquals(CardColor.RED, sequence.first())
        assertEquals(CardColor.BLUE, sequence[1])
        assertEquals(HAND_SIZE, sequence.count { it == CardColor.RED })
        assertEquals(HAND_SIZE - 1, sequence.count { it == CardColor.BLUE })
    }

    @Test
    fun theSecondPlayerKeepsOneCard() {
        val finished = playOut(lopsided(first = CardColor.BLUE))

        assertEquals(0, finished.hands.getValue(CardColor.BLUE).size)
        assertEquals(1, finished.hands.getValue(CardColor.RED).size)
        assertTrue(finished.board.isFull)
    }

    @Test
    fun currentPlayerIsNullOnceFinished() {
        val finished = playOut(lopsided())

        assertNull(finished.currentPlayer)
        assertTrue(finished.currentHand.isEmpty())
        assertTrue(finished.playablePositions().isEmpty())
    }

    @Test
    fun playingAfterTheEndFails() {
        val finished = playOut(lopsided())
        assertFailsWith<IllegalStateException> { finished.play(card(99), 0) }
    }

    @Test
    fun playingACardNotInHandFails() {
        assertFailsWith<IllegalArgumentException> { lopsided().play(card(99), 0) }
    }

    @Test
    fun playingOnATakenCellFails() {
        val state = lopsided().let { it.play(it.currentHand.first(), 4) }
        assertFailsWith<IllegalArgumentException> { state.play(state.currentHand.first(), 4) }
    }

    // ---- captures and scoring --------------------------------------------

    @Test
    fun captureFlipsTheCardAndMovesTheScore() {
        var state = lopsided(first = CardColor.RED)
        state = state.play(state.currentHand.first(), 4) // red 2s at centre
        assertEquals(MatchScore(blue = 5, red = 5), state.score)

        state = state.play(state.currentHand.first(), 1) // blue 8s above it
        assertEquals(CardColor.BLUE, state.board[4]?.owner)
        // Blue holds 4 and owns 2 on the board; red holds 4 and owns none.
        assertEquals(MatchScore(blue = 6, red = 4), state.score)
        assertEquals(listOf(4), state.lastPlay?.captures?.map { it.position })
    }

    @Test
    fun theScoreAlwaysTotalsTen() {
        var state = lopsided(first = CardColor.RED)
        while (!state.isFinished) {
            val current = state.score
            assertEquals(TOTAL_CARDS, current.blue + current.red)
            state = state.play(state.currentHand.first(), state.playablePositions().first())
        }
        assertEquals(TOTAL_CARDS, state.score.let { it.blue + it.red })
    }

    @Test
    fun lastPlayRecordsWhatHappened() {
        val state = lopsided().let { it.play(it.currentHand.first(), 4) }
        val play = requireNotNull(state.lastPlay)

        assertEquals(CardColor.BLUE, play.player)
        assertEquals(4, play.position)
        assertEquals(card(1).id, play.card.id)
        assertTrue(play.captures.isEmpty(), "nothing adjacent to capture yet")
    }

    // ---- outcome ---------------------------------------------------------

    @Test
    fun outcomeIsNullUntilTheBoardIsFull() {
        var state = lopsided()
        repeat(PLACEMENTS_PER_MATCH - 1) {
            assertNull(state.outcome())
            state = state.play(state.currentHand.first(), state.playablePositions().first())
        }
        assertNull(state.outcome())
        state = state.play(state.currentHand.first(), state.playablePositions().first())
        assertTrue(state.outcome() is MatchOutcome.Win)
    }

    @Test
    fun aStrongerHandWins() {
        val outcome = playOut(lopsided(first = CardColor.RED)).outcome()

        assertTrue(outcome is MatchOutcome.Win)
        assertEquals(CardColor.BLUE, outcome.winner)
    }

    @Test
    fun aTiedBoardIsADrawWithoutSuddenDeath() {
        // Every card is 5/5/5/5, so no capture is ever possible: 5-5.
        val even = MatchState.start(hand(from = 1), hand(from = 11))
        val outcome = playOut(even).outcome()

        assertEquals(MatchOutcome.Draw(MatchScore(HAND_SIZE, HAND_SIZE)), outcome)
    }

    @Test
    fun aTiedBoardAsksForSuddenDeathWhenTheRuleIsOn() {
        val even = MatchState.start(
            blueHand = hand(from = 1),
            redHand = hand(from = 11),
            rules = GameRules(suddenDeath = true),
        )
        val outcome = playOut(even).outcome()

        assertTrue(outcome is MatchOutcome.SuddenDeath)
        assertEquals(MatchScore(HAND_SIZE, HAND_SIZE), outcome.score)
    }

    // ---- sudden death ----------------------------------------------------

    @Test
    fun suddenDeathRebuildsHandsFromFinalOwnership() {
        val even = MatchState.start(
            blueHand = hand(from = 1),
            redHand = hand(from = 11),
            rules = GameRules(suddenDeath = true),
        )
        val rematch = playOut(even).suddenDeathRematch()

        assertEquals(HAND_SIZE, rematch.hands.getValue(CardColor.BLUE).size)
        assertEquals(HAND_SIZE, rematch.hands.getValue(CardColor.RED).size)
        assertEquals(0, rematch.placement)
        assertEquals(0, rematch.board.placedCount)
        assertEquals(TOTAL_CARDS, rematch.score.blue + rematch.score.red)
    }

    @Test
    fun suddenDeathCarriesTurnOrderOverUnchanged() {
        val even = MatchState.start(
            blueHand = hand(from = 1),
            redHand = hand(from = 11),
            first = CardColor.RED,
            rules = GameRules(suddenDeath = true),
        )
        val rematch = playOut(even).suddenDeathRematch()

        assertEquals(
            CardColor.RED,
            rematch.order.first,
            "the AS3 reuses the existing timeline — there is no second coin flip",
        )
    }

    @Test
    fun suddenDeathMovesCapturedCardsToTheCaptorsHand() {
        // Blue captures one red card, so blue should end up holding it.
        var state = MatchState.start(
            blueHand = hand(from = 1, power = 8),
            redHand = hand(from = 11, power = 2),
            first = CardColor.RED,
            rules = GameRules(suddenDeath = true),
        )
        state = state.play(state.currentHand.first(), 4)
        state = state.play(state.currentHand.first(), 1)
        val capturedId = card(11).id
        state = playOut(state)

        val rematch = state.suddenDeathRematch()
        assertTrue(
            rematch.hands.getValue(CardColor.BLUE).any { it.id == capturedId },
            "card $capturedId was captured by blue and must join blue's hand",
        )
    }

    @Test
    fun suddenDeathNeedsAFinishedMatch() {
        assertFailsWith<IllegalArgumentException> { lopsided().suddenDeathRematch() }
    }

    // ---- Order and Chaos -------------------------------------------------

    @Test
    fun freeOrderAllowsEveryCard() {
        assertEquals(HAND_SIZE, lopsided().playableCards().size)
    }

    @Test
    fun orderForcesTheFirstRemainingCard() {
        val state = MatchState.start(
            hand(from = 1),
            hand(from = 11),
            rules = GameRules(order = OrderRule.ORDER),
        )
        assertEquals(listOf(card(1).id), state.playableCards().map { it.id })

        val next = state.play(state.playableCards().single(), 0)
        assertEquals(listOf(card(11).id), next.playableCards().map { it.id })
    }

    @Test
    fun chaosForcesOneCardAndIsReproducibleFromASeed() {
        val state = MatchState.start(
            hand(from = 1),
            hand(from = 11),
            rules = GameRules(order = OrderRule.CHAOS),
        )
        val once = state.playableCards(Random(SEED))
        val twice = state.playableCards(Random(SEED))

        assertEquals(1, once.size)
        assertEquals(once.map { it.id }, twice.map { it.id })
        assertTrue(once.single().id in (1..HAND_SIZE).map { card(it).id })
    }

    // ---- Ascension -------------------------------------------------------

    @Test
    fun theTallyGrowsAsTypedCardsArePlaced() {
        val beast = card(1).copy(type = CardType.BEAST)
        val state = MatchState.start(
            blueHand = listOf(beast) + hand(from = 2).drop(1),
            redHand = hand(from = 11),
            rules = GameRules(typeRule = TypeRule.ASCENSION),
        )

        assertEquals(0, state.tally[CardType.BEAST])
        assertEquals(1, state.play(beast, 4).tally[CardType.BEAST])
    }

    /**
     * A card counts **itself** the moment it is placed, so a 5 beats a 5 under Bonus.
     *
     * The inverse of what this file used to assert. The AS3 ran `ascensionPhase` after the flips
     * (`TTOCore.as:171`) and the port copied it, so the card that had just landed resolved its own
     * captures without its own contribution — on the board and not yet counted.
     *
     * It is a rules change and it is deliberate: the board now *draws* the modifier, and a badge
     * reading `+1` on a card that attacked as `+0` would be a screen contradicting itself at the
     * only moment anybody is looking at it. [com.tripletriad.protocol.CURRENT_VERSION] 3.0.0 and
     * [com.tripletriad.protocol.TRANSCRIPT_VERSION] 4 carry the change.
     */
    @Test
    fun aPlacedCardCountsItselfImmediately() {
        val beast = card(1).copy(type = CardType.BEAST)
        var state = MatchState.start(
            blueHand = listOf(beast) + hand(from = 2).drop(1),
            redHand = hand(from = 11),
            first = CardColor.RED,
            rules = GameRules(typeRule = TypeRule.ASCENSION),
        )
        state = state.play(state.currentHand.first(), 4)
        state = state.play(beast, 1)

        assertEquals(
            CardColor.BLUE,
            state.board[4]?.owner,
            "the beast's own +1 makes it a 6 against a 5, so it captures",
        )
        assertEquals(1, state.tally[CardType.BEAST])
    }

    /**
     * And the same placement under **no** rule does not capture, which is what makes the above a
     * statement about Bonus rather than about these two cards.
     *
     * Without it the test overhead would pass on an engine that had simply started capturing ties.
     */
    @Test
    fun theSamePlacementWithoutTheRuleIsStillATie() {
        val beast = card(1).copy(type = CardType.BEAST)
        var state = MatchState.start(
            blueHand = listOf(beast) + hand(from = 2).drop(1),
            redHand = hand(from = 11),
            first = CardColor.RED,
        )
        state = state.play(state.currentHand.first(), 4)
        state = state.play(beast, 1)

        assertEquals(CardColor.RED, state.board[4]?.owner, "5 vs 5 is a tie, so no capture")
    }

    /**
     * A card in hand contributes nothing — the other half of "from the moment it is placed".
     *
     * Four beasts held and none played leaves the tally empty, so the first one down is a `+1` and
     * not a `+4`. The AS3 adjusted hand cards too; this port does not.
     */
    @Test
    fun cardsInHandDoNotCount() {
        val beasts = hand(from = 1).map { it.copy(type = CardType.BEAST) }
        val state = MatchState.start(
            blueHand = beasts,
            redHand = hand(from = 11),
            rules = GameRules(typeRule = TypeRule.ASCENSION),
        )

        assertEquals(AscensionTally.EMPTY, state.tally, "a full hand of beasts counts for nothing")
        assertEquals(1, state.play(beasts.first(), 4).tally[CardType.BEAST])
    }

    @Test
    fun untypedCardsLeaveTheTallyAlone() {
        val state = MatchState.start(
            hand(from = 1),
            hand(from = 11),
            rules = GameRules(typeRule = TypeRule.ASCENSION),
        )
        assertEquals(AscensionTally.EMPTY, state.play(state.currentHand.first(), 0).tally)
    }

    // ---- elements --------------------------------------------------------

    @Test
    fun randomElementsFillsNineCellsFromTheFf8SetOnly() {
        val elements = MatchState.randomElements(Random(SEED))

        assertEquals(Board.SIZE, elements.size)
        assertTrue(elements.filterNotNull().all { it in MatchState.FF8_ELEMENTS })
        assertFalse(
            elements.any { it == CardType.BEAST },
            "the FF14 tribes are types, not elements",
        )
    }

    @Test
    fun elementsSurviveTheStartOfAMatch() {
        val elements = List<CardType?>(Board.SIZE) { CardType.FIRE }
        val state = MatchState.start(hand(from = 1), hand(from = 11), elements = elements)

        assertEquals(elements, state.board.elements)
    }

    private companion object {
        /** A fixed seed keeps Chaos and element generation reproducible. */
        const val SEED = 20260726
    }
}
