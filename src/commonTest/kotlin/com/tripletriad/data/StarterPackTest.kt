package com.tripletriad.data

import com.tripletriad.model.Card
import com.tripletriad.model.Deck
import com.tripletriad.model.GameSave
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Granting the authored starter, and repairing a character that cannot field a hand.
 *
 * The catalogue here is a fixture, not the shipped one: these are rules about *a* starter, and
 * pinning them to `starters.json` would make every one of them fail the day a card is swapped for
 * flavour. That the shipped file obeys the composition rule is `StarterBundleTest`'s job.
 *
 * ### What used to be tested here and is now untestable
 *
 * Four of these tests were about `copy(mode = FF8)` stranding every card a profile owned — the
 * defect this object was written for. `MODE` is gone, so there is no field left that can strand
 * anything: a profile owns what it owns, and whether a card may be *played* is the format's
 * question, asked at the match. Those tests were not repaired, they were removed, because a test
 * that can no longer fail is not a test. What survives is the repair path, which is still reachable
 * — an account can still arrive short of five cards — and the shape of the grant.
 */
class StarterPackTest {
    private fun character() = GameSave.new(username = "Tester", createdAt = 1L)

    @Test
    fun aFreshCharacterIsOwedNothing() {
        val save = character()

        assertFalse(StarterPack.isOwedBy(save))
        assertEquals(save, StarterPack.grantedTo(save, catalog), "nothing to grant")
    }

    /** Opening a box replaces what the profile was seeded with, rather than adding to it. */
    @Test
    fun openingABoxDealsExactlyThatBox() {
        val opened = StarterPack.opened(character(), ff8)

        assertEquals(ff8.cards.associateWith { 1 }, opened.cards)
        assertEquals(listOf(ff8.deck), opened.decks.map { it.cards })
        assertFalse(StarterPack.isOwedBy(opened), "the character can play")
    }

    /**
     * Nothing about a box confines the character to its set.
     *
     * The point of document 19, stated as an assertion: a profile opened with the FFXIV box can be
     * handed an FFVIII card and simply owns it. While `MODE` existed this was the defect the whole
     * object was written to work around.
     */
    @Test
    fun aBoxDoesNotConfineTheCharacterToItsSet() {
        val opened = StarterPack.opened(character(), ff14)
        val other = ff8.cards.first()

        val mixed = opened.withCard(other)

        assertTrue(mixed.ownsCard(other))
        assertEquals(ff14.cards.size + 1, StarterPack.playableCards(mixed))
    }

    /**
     * An empty catalogue grants nothing rather than inventing ids.
     *
     * `StarterCatalog.violations` refuses this at authoring time, so it is unreachable through the
     * shipped bundle — and handled anyway, because the alternative to "nothing happened" is a
     * character holding cards nobody chose.
     */
    @Test
    fun anEmptyCatalogueIsLeftAlone() {
        val empty = StarterCatalog(emptyList())
        val destitute = character().copy(cards = emptyMap())

        assertEquals(destitute, StarterPack.grantedTo(destitute, empty))
    }

    @Test
    fun grantingRepairsADestituteCharacterWithoutTouchingWhatItKept() {
        val kept = Card.idFor(block = 2, number = 99)
        val destitute = character().copy(cards = mapOf(kept to 3))

        val repaired = StarterPack.grantedTo(destitute, catalog)

        assertFalse(StarterPack.isOwedBy(repaired))
        assertEquals(3, repaired.copiesOf(kept), "copies already held are not disturbed")
        for (id in ff14.cards) {
            assertTrue(repaired.ownsCard(id), "starter card $id was not granted")
        }
    }

    /** A card already in the starter is topped up to one copy, never to two. */
    @Test
    fun grantingDoesNotDoubleWhatIsAlreadyOwned() {
        val partial = character().copy(cards = mapOf(ff14.cards.first() to 1))

        val repaired = StarterPack.grantedTo(partial, catalog)

        assertEquals(ff14.cards.associateWith { 1 }, repaired.cards)
    }

    @Test
    fun grantingLeavesAPlayableDeckAtTheTop() {
        val destitute = character().copy(
            cards = emptyMap(),
            decks = listOf(Deck("Stranded", ff8.deck)),
        )

        val repaired = StarterPack.grantedTo(destitute, catalog)
        val deck = repaired.decks.first()

        assertTrue(deck.isComplete, "the granted deck is a full hand")
        assertTrue(deck.isAffordable(repaired.cards), "and every card in it is owned")
        assertEquals(ff14.deck, deck.cards)
        assertTrue(repaired.decks.size <= GameSave.MAX_DECKS)
    }

    /** Copies are not cards: five of one is not a hand, and must not read as one. */
    @Test
    fun fiveCopiesOfOneCardIsStillOwedThePack() {
        val hoarder = character().copy(cards = mapOf(ff14.cards.first() to 5))

        assertEquals(1, StarterPack.playableCards(hoarder))
        assertTrue(StarterPack.isOwedBy(hoarder))
    }

    /** Only the starters of released sets may be opened with. */
    @Test
    fun anUnreleasedSetIsNotOnOffer() {
        val sets = listOf(
            CardSet(block = 1, slug = "ff14", nameKey = "A", sortOrder = 1, released = true),
            CardSet(block = 2, slug = "ff8", nameKey = "B", sortOrder = 2, released = false),
        )

        assertEquals(listOf(ff14.id), catalog.released(sets).map { it.id })
    }

    private companion object {
        /** Ten ids in block 1, the last of them the rare, and a deck of five holding it. */
        val ff14 = Starter(
            id = "test-ff14",
            block = 1,
            nameKey = "APP_TEST_FF14",
            cards = (1..10).map { Card.idFor(block = 1, number = it) },
            deck = listOf(1, 2, 3, 4, 10).map { Card.idFor(block = 1, number = it) },
        )

        val ff8 = Starter(
            id = "test-ff8",
            block = 2,
            nameKey = "APP_TEST_FF8",
            cards = (1..10).map { Card.idFor(block = 2, number = it) },
            deck = listOf(1, 2, 3, 4, 10).map { Card.idFor(block = 2, number = it) },
        )

        val catalog = StarterCatalog(listOf(ff14, ff8))
    }
}
