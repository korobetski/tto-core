package com.tripletriad.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [MatchPreparation.prepareVersus] — a match with two audiences instead of one.
 *
 * The claim under test is that the two views are **independent**, not mirrored. Mirroring is the
 * shortcut this design refuses, and it fails in the leaking direction: whichever slots one side
 * happened to reveal would become the slots the other was told about.
 */
class PvpSetupTest {
    private fun card(id: Int, power: Int = 5) = Card(
        id = Card.idFor(block = 1, number = id),
        nameKey = "STR_TEST_$id",
        name = "Test $id",
        top = power,
        right = power,
        bottom = power,
        left = power,
        rarity = 1,
    )

    private fun hand(from: Int) = (from until from + HAND_SIZE).map { card(it) }

    private val blue = hand(from = 1)
    private val red = hand(from = 11)

    private fun setup(rules: GameRules = GameRules(), seed: Int = 1) =
        MatchPreparation.prepareVersus(blue, red, CardColor.BLUE, rules, Random(seed))

    /** The server's choice of who starts is obeyed, not tossed for. */
    @Test
    fun theGivenSideStarts() {
        for (first in CardColor.entries) {
            val prepared = MatchPreparation.prepareVersus(blue, red, first, random = Random(3))

            assertEquals(first, prepared.state.order.first)
            assertEquals(first, prepared.state.currentPlayer)
        }
    }

    /** Both hands are dealt, to their own sides. */
    @Test
    fun bothHandsAreDealt() {
        val prepared = setup()

        assertEquals(HAND_SIZE, prepared.state.hands.getValue(CardColor.BLUE).size)
        assertEquals(HAND_SIZE, prepared.state.hands.getValue(CardColor.RED).size)
        assertTrue(prepared.state.hands.getValue(CardColor.BLUE).all { it.owner == CardColor.BLUE })
        assertTrue(prepared.state.hands.getValue(CardColor.RED).all { it.owner == CardColor.RED })
    }

    /** With no Open rule, neither side sees anything of the other. */
    @Test
    fun aClosedMatchRevealsNothingEitherWay() {
        val prepared = setup()

        assertEquals(HandVisibility.HIDDEN, prepared.blueSeesRed)
        assertEquals(HandVisibility.HIDDEN, prepared.redSeesBlue)
        assertTrue(prepared.viewFor(CardColor.BLUE).opponentHand.all { it == null })
        assertTrue(prepared.viewFor(CardColor.RED).opponentHand.all { it == null })
    }

    /** All Open reveals both hands in full — the one case where the two sides do agree. */
    @Test
    fun allOpenRevealsBothHands() {
        val prepared = setup(GameRules(open = OpenRule.ALL_OPEN))

        assertContentEquals(
            prepared.state.hands.getValue(CardColor.RED),
            prepared.viewFor(CardColor.BLUE).opponentHand,
        )
        assertContentEquals(
            prepared.state.hands.getValue(CardColor.BLUE),
            prepared.viewFor(CardColor.RED).opponentHand,
        )
    }

    /**
     * Three Open reveals three per side, and the two sets are drawn **separately**.
     *
     * The assertion that the sets differ is over a run of seeds rather than one, because two
     * independent draws of three from five legitimately coincide sometimes — a single seed
     * asserting inequality would be a flake waiting for a Kotlin release to reshuffle `Random`.
     */
    @Test
    fun threeOpenDrawsEachSideSeparately() {
        val rules = GameRules(open = OpenRule.THREE_OPEN)
        var differed = 0

        for (seed in 0 until SEEDS) {
            val prepared = setup(rules, seed)

            assertEquals(THREE, prepared.blueSeesRed.visiblePositions.size, "seed $seed")
            assertEquals(THREE, prepared.redSeesBlue.visiblePositions.size, "seed $seed")
            if (prepared.blueSeesRed != prepared.redSeesBlue) differed++
        }

        assertTrue(differed > 0, "the two sides revealed the same slots on all $SEEDS seeds")
    }

    /** Swap exchanges one card between the two real hands, and both sides end with five. */
    @Test
    fun swapExchangesOneCardBetweenThePlayers() {
        val prepared = setup(GameRules(swap = true), seed = 4)
        val blueNow = prepared.state.hands.getValue(CardColor.BLUE)
        val redNow = prepared.state.hands.getValue(CardColor.RED)

        assertEquals(HAND_SIZE, blueNow.size)
        assertEquals(HAND_SIZE, redNow.size)
        assertEquals(1, blueNow.count { it.id in red.map(Card::id) }, "blue took one of red's")
        assertEquals(1, redNow.count { it.id in blue.map(Card::id) }, "red took one of blue's")
    }

    /** Elements are rolled only under the Elemental rule, and are the same board for both sides. */
    @Test
    fun elementsAppearOnlyUnderTheElementalRuleAndAreShared() {
        val plain = setup()
        val elemental = setup(GameRules(typeRule = TypeRule.ELEMENTAL), seed = 5)

        assertTrue(plain.state.board.elements.all { it == null })
        assertTrue(elemental.state.board.elements.any { it != null })
        assertEquals(
            elemental.viewFor(CardColor.BLUE).board.elements,
            elemental.viewFor(CardColor.RED).board.elements,
            "the two players are not on the same board",
        )
    }

    /** Only the side to move is told what it may play. */
    @Test
    fun onlyTheStartingSideIsGivenPlayableSlots() {
        val prepared = MatchPreparation.prepareVersus(blue, red, CardColor.RED, random = Random(2))

        assertTrue(prepared.viewFor(CardColor.BLUE).playableHandIndices.isEmpty())
        assertEquals(HAND_SIZE, prepared.viewFor(CardColor.RED).playableHandIndices.size)
    }

    /** The same seed gives the same match, which is what makes a server-side match reproducible. */
    @Test
    fun theSameSeedGivesTheSameMatch() {
        val rules =
            GameRules(open = OpenRule.THREE_OPEN, swap = true, typeRule = TypeRule.ELEMENTAL)

        assertEquals(setup(rules, seed = 9), setup(rules, seed = 9))
        assertNotEquals(setup(rules, seed = 9).state.board.elements.size, 0)
    }

    // ---- Swap, and what it stops the Open rule hiding ----------------------

    /**
     * Under Swap a closed hand is no longer wholly closed: each side handed over a card out of its
     * own deck and can name it wherever it landed. One of five, and exactly one.
     */
    @Test
    fun aClosedHandUnderSwapShowsEachSideTheCardItGaveAway() {
        for (seed in 1..SEEDS) {
            val prepared = setup(GameRules(swap = true), seed = seed)

            for (side in CardColor.entries) {
                val seen = prepared.viewFor(side).opponentHand.filterNotNull()
                assertEquals(1, seen.size, "seed $seed, $side saw $seen")
                // And it is a card that side owned before the swap, not merely some card.
                val ownedBefore = (if (side == CardColor.BLUE) blue else red).map(Card::id)
                assertTrue(seen.single().id in ownedBefore, "seed $seed: $side saw a stranger")
            }
        }
    }

    /** Three Open still means three. The swapped card is one of them, so two are news. */
    @Test
    fun threeOpenUnderSwapStillRevealsThreeCards() {
        for (seed in 1..SEEDS) {
            val prepared = setup(GameRules(open = OpenRule.THREE_OPEN, swap = true), seed = seed)

            for (side in CardColor.entries) {
                val seen = prepared.viewFor(side).opponentHand.filterNotNull()
                assertEquals(THREE, seen.size, "seed $seed, $side saw $seen")
            }
        }
    }

    @Test
    fun withoutSwapAClosedHandStaysClosed() {
        for (seed in 1..SEEDS) {
            val prepared = setup(GameRules(swap = false), seed = seed)

            for (side in CardColor.entries) {
                assertTrue(
                    prepared.viewFor(side).opponentHand.all { it == null },
                    "seed $seed: $side saw a card with no swap to have shown it",
                )
            }
        }
    }

    private companion object {
        const val SEEDS = 30
        const val THREE = 3
    }
}
