package com.tripletriad.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [DeckLimits] — the star-rank caps and the one-copy rule a deck is built under.
 *
 * The fixtures name a card by its rank on purpose: every case here is about how many of a rank are
 * in the list, and nothing at all about which cards they are.
 */
class DeckLimitsTest {
    private var next = 0

    /** A fresh card of [rarity]. Ids are handed out in order, so a list of them is distinct. */
    private fun card(rarity: Int): Card = Card(
        id = Card.idFor(block = 1, number = ++next),
        nameKey = "STR_TEST_$next",
        name = "Test $next",
        top = 1,
        right = 1,
        bottom = 1,
        left = 1,
        rarity = rarity,
    )

    private fun table(vararg cards: Card): Map<Int, Card> = cards.associateBy { it.id }

    /** The rule as numbers: two cards of four stars or more, one five-star, one copy of each. */
    @Test
    fun theCapsAreTwoTopCardsOfWhichOneFiveStar() {
        assertEquals(1, DeckLimits.MAX_COPIES)
        assertEquals(1, DeckLimits.limitOf(5))
        assertEquals(2, DeckLimits.limitOf(4))
        assertEquals(HAND_SIZE, DeckLimits.limitOf(3), "a rank below four is uncapped")
        assertEquals(HAND_SIZE, DeckLimits.limitOf(1))
    }

    @Test
    fun aDeckAtEveryCapIsLegal() {
        for (top in listOf(listOf(card(5), card(4)), listOf(card(4), card(4)))) {
            val cards = top + listOf(card(3), card(3), card(1))
            val table = table(*cards.toTypedArray())

            assertTrue(DeckLimits.isLegal(cards.map { it.id }, table), "$top")
            assertEquals(emptyMap(), DeckLimits.overLimit(cards.map { it.id }, table))
        }
    }

    /**
     * FFXIV's "two four-stars only if there is no five-star": the five-star spends one of the two
     * slots. A cap per rank would have let this deck through with three top cards.
     */
    @Test
    fun aFiveStarSpendsOneOfTheTwoTopSlots() {
        val cards = listOf(card(5), card(4), card(4), card(1), card(1))
        val table = table(*cards.toTypedArray())

        assertFalse(DeckLimits.isLegal(cards.map { it.id }, table))
        assertEquals(mapOf(4 to 3), DeckLimits.overLimit(cards.map { it.id }, table))
        assertFalse(DeckLimits.admits(cards.take(2), cards[2]), "a four-star after 5 + 4")
    }

    @Test
    fun aCardNamedTwiceIsRefused() {
        val one = card(1)
        val deck = listOf(one.id, one.id, card(1).id)
        val table = table(one)

        assertEquals(mapOf(one.id to 2), DeckLimits.repeated(deck))
        assertEquals(emptyMap(), DeckLimits.overLimit(deck, table), "not a rank cap")
        assertFalse(DeckLimits.isLegal(deck, table))
        assertFalse(DeckLimits.admits(listOf(one.id), table, one))
        assertFalse(DeckLimits.admits(listOf(one), one))
    }

    @Test
    fun aSecondFiveStarIsRefused() {
        val cards = listOf(card(5), card(5), card(1), card(1), card(1))
        val table = table(*cards.toTypedArray())

        assertFalse(DeckLimits.isLegal(cards.map { it.id }, table))
        assertEquals(mapOf(5 to 2), DeckLimits.overLimit(cards.map { it.id }, table))
    }

    @Test
    fun aThirdFourStarIsRefused() {
        val cards = listOf(card(4), card(4), card(4), card(1), card(1))
        val table = table(*cards.toTypedArray())

        assertEquals(mapOf(4 to 3), DeckLimits.overLimit(cards.map { it.id }, table))
    }

    /** Both caps can be broken at once, and both are reported — the editor draws one row each. */
    @Test
    fun everyBrokenCapIsReported() {
        val cards = listOf(card(5), card(5), card(4), card(4), card(4))
        val table = table(*cards.toTypedArray())

        assertEquals(mapOf(5 to 2, 4 to 5), DeckLimits.overLimit(cards.map { it.id }, table))
    }

    /** Five ones is a legal deck, which is what makes a starter collection playable at all. */
    @Test
    fun theUncappedRanksAreReallyUncapped() {
        val cards = List(HAND_SIZE) { card(1) }

        assertTrue(DeckLimits.isLegal(cards.map { it.id }, table(*cards.toTypedArray())))
    }

    /**
     * Every cap is counted, zero included: `0 / 1` is what the editor has to draw. A five-star is
     * counted by both, because the four-star budget is "four or more".
     */
    @Test
    fun theTallyNamesEveryCapIncludingTheEmptyOnes() {
        val five = card(5)

        assertEquals(mapOf(5 to 1, 4 to 1), DeckLimits.tally(listOf(five.id), table(five)))
        assertEquals(mapOf(5 to 0, 4 to 0), DeckLimits.tally(emptyList(), emptyMap()))
    }

    /**
     * An id the table does not resolve is not counted, and is somebody else's refusal.
     *
     * `PveMatches.playableDecks` drops a deck naming a card outside the format and `assemble`
     * throws on one naming no card at all; a rank guessed here would turn one of those into a
     * different, wronger answer.
     */
    @Test
    fun anUnresolvableIdIsNotCounted() {
        val five = card(5)
        val deck = listOf(five.id, 9999)

        assertTrue(DeckLimits.isLegal(deck, table(five)))
        assertEquals(mapOf(5 to 1, 4 to 1), DeckLimits.tally(deck, table(five)))
    }

    /** What the deck editor dims a pick on. */
    @Test
    fun admitsAnswersAboutTheAdditionRatherThanTheDeck() {
        val held = card(5)
        val another = card(5)
        val four = card(4)
        val table = table(held, another, four)

        assertFalse(DeckLimits.admits(listOf(held.id), table, another))
        assertTrue(DeckLimits.admits(listOf(held.id), table, four))
        assertTrue(DeckLimits.admits(emptyList(), table, another))
    }

    /**
     * A deck already over a cap still admits the ranks it has room for.
     *
     * The case is a deck built before the caps existed. Answering "no" to every pick would leave
     * the player unable to do anything in the editor except empty the slot.
     */
    @Test
    fun aDeckAlreadyOverACapStillAdmitsOtherRanks() {
        val over = listOf(card(5), card(5))
        val one = card(1)
        val table = table(*(over + one).toTypedArray())

        assertTrue(DeckLimits.admits(over.map { it.id }, table, one))
        assertFalse(DeckLimits.admits(over.map { it.id }, table, over.first()))
    }

    /** The fallback hand skips what it may not take and keeps the order it was given. */
    @Test
    fun theFirstLegalHandSkipsWhatBreaksACap() {
        val ids = listOf(card(5), card(5), card(4), card(4), card(4), card(1), card(1), card(1))
        val table = table(*ids.toTypedArray())
        val taken = DeckLimits.firstLegalHand(ids.map { it.id }, table)

        assertEquals(HAND_SIZE, taken.size)
        assertTrue(DeckLimits.isLegal(taken, table))
        assertEquals(
            listOf(ids[0], ids[2], ids[5], ids[6], ids[7]).map { it.id },
            taken,
            "the second five-star and every four-star after the first are stepped over, in order",
        )
    }

    /**
     * What `RULE_RANDOM` deals: the first two top cards the order offers, then the rest in order,
     * returned in the order given — so which top cards, and where they sit, is still the order's.
     */
    @Test
    fun theStrongestLegalHandLeadsWithTwoTopCardsAndKeepsTheOrder() {
        val order = listOf(
            card(1), card(2), card(1), card(3), card(2),
            card(1), card(5), card(5), card(4), card(4),
        )

        assertEquals(
            listOf(order[0], order[1], order[2], order[6], order[8]),
            DeckLimits.strongestLegalHand(order),
        )
    }

    @Test
    fun theStrongestLegalHandTakesWhatTopCardsThereAre() {
        val one = listOf(card(1), card(1), card(1), card(1), card(1), card(4))
        val none = List(HAND_SIZE + 1) { card(2) }
        val aces = listOf(card(5), card(5), card(5))

        assertEquals(one.take(4) + one[5], DeckLimits.strongestLegalHand(one))
        assertEquals(DeckLimits.firstLegalHand(none), DeckLimits.strongestLegalHand(none))
        assertEquals(listOf(aces[0]), DeckLimits.strongestLegalHand(aces), "short, as it must be")
    }

    /** A collection listing a card twice is dealt it once. */
    @Test
    fun theStrongestLegalHandNamesACardOnce() {
        val four = card(4)
        val order = listOf(four, four, card(1), card(1), card(1), card(1))

        assertEquals(order.drop(1).distinct(), DeckLimits.strongestLegalHand(order))
    }

    /** Shorter than a hand when the caps cannot be met — a short list is already handled. */
    @Test
    fun theFirstLegalHandCanComeUpShort() {
        val ids = List(HAND_SIZE) { card(5) }
        val table = table(*ids.toTypedArray())

        assertEquals(listOf(ids.first().id), DeckLimits.firstLegalHand(ids.map { it.id }, table))
        assertEquals(emptyList(), DeckLimits.firstLegalHand(listOf(1234), emptyMap()))
    }

    /**
     * The id-free half answers identically — it is the half `RULE_RANDOM` deals through.
     *
     * Asked of the same list twice, once as cards and once as ids, because two functions that are
     * allowed to disagree about the caps are two rules.
     */
    @Test
    fun theCardListOverloadsAnswerAsTheIdOnesDo() {
        val cards = listOf(card(5), card(5), card(4), card(4), card(4), card(1), card(1))
        val table = table(*cards.toTypedArray())

        assertEquals(DeckLimits.tally(cards.map { it.id }, table), DeckLimits.tally(cards))
        assertEquals(
            DeckLimits.firstLegalHand(cards.map { it.id }, table),
            DeckLimits.firstLegalHand(cards).map { it.id },
        )
        assertFalse(DeckLimits.admits(cards.take(1), cards[1]))
        assertTrue(DeckLimits.admits(cards.take(1), cards[2]))
    }

    /** [Deck.isLegal] is the same question asked of a saved slot. */
    @Test
    fun aDeckAsksTheSameQuestion() {
        val cards = listOf(card(5), card(5), card(1), card(1), card(1))
        val table = table(*cards.toTypedArray())

        assertFalse(Deck("over", cards.map { it.id }).isLegal(table))
        assertTrue(Deck("under", cards.drop(1).map { it.id }).isLegal(table))
    }
}
