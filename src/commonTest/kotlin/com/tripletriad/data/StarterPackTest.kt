package com.tripletriad.data

import com.tripletriad.model.Card
import com.tripletriad.model.Deck
import com.tripletriad.model.GameSave
import com.tripletriad.model.HAND_SIZE
import kotlin.random.Random
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
 * ### The draw is seeded, and that is the only reason these assertions are exact
 *
 * Four of the nine cards are drawn. Every test below passes a `Random(seed)`, so the box is a
 * function of its seed and can be compared with itself — which is also the property the server
 * relies on to be able to say what it dealt. What is asserted about the draw is what is true of
 * *every* seed: four cards, commons of the block, none of them one of the authored five.
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

    /**
     * A character nobody has dealt a box to owns nothing, and the pack is what it is owed.
     *
     * The assertion that would have caught the bug this was all written for: `GameSave.new` used to
     * seed five cards of block 1, so a freshly registered account read as *complete* and the box it
     * chose was never granted. It is empty now, and being owed a box is the whole difference.
     */
    @Test
    fun aFreshCharacterOwnsNothingAndIsOwedTheBox() {
        val save = character()

        assertEquals(emptyMap(), save.cards)
        assertEquals(emptyList(), save.decks)
        assertTrue(StarterPack.isOwedBy(save))
    }

    @Test
    fun aCharacterThatCanFieldAHandIsOwedNothing() {
        val playing = StarterPack.opened(character(), ff8, cards, Random(1))

        assertFalse(StarterPack.isOwedBy(playing))
        assertEquals(
            playing,
            StarterPack.grantedTo(playing, catalog, cards, Random(1)),
            "nothing to grant",
        )
    }

    @Test
    fun openingABoxDealsNineCardsAndTheAuthoredDeck() {
        val opened = StarterPack.opened(character(), ff8, cards, Random(7))

        assertEquals(StarterCatalog.SIZE, opened.cards.size)
        assertEquals(setOf(1), opened.cards.values.toSet(), "one copy of each")
        assertEquals(listOf(ff8.deck), opened.decks.map { it.cards })
        assertTrue(opened.cards.keys.containsAll(ff8.deck), "the deck is owned")
        assertFalse(StarterPack.isOwedBy(opened), "the character can play")
    }

    /** The four that are not authored: commons of the block, and never one of the five. */
    @Test
    fun theDrawnCardsAreCommonsOfTheBlockAndNotTheAuthoredFive() {
        for (seed in 1..50) {
            val drawn = StarterPack.drawn(ff8, cards, Random(seed))

            assertEquals(StarterPack.DRAWN, drawn.size, "seed $seed")
            assertEquals(drawn.size, drawn.toSet().size, "seed $seed drew a card twice")
            for (id in drawn) {
                val card = cards.getValue(id)
                assertEquals(ff8.block, card.block, "seed $seed drew outside the block")
                assertEquals(StarterCatalog.COMMON_RARITY, card.rarity, "seed $seed drew a rare")
                assertFalse(id in ff8.deck, "seed $seed drew an authored card")
            }
        }
    }

    /** Two players who opened the same box do not hold the same collection. */
    @Test
    fun theDrawVariesWithTheSeedAndTheDeckDoesNot() {
        val one = StarterPack.opened(character(), ff8, cards, Random(1))
        val other = StarterPack.opened(character(), ff8, cards, Random(2))

        assertEquals(one.decks, other.decks, "the deck is authored, not drawn")
        assertTrue(one.cards.keys != other.cards.keys, "the draw is a draw")
    }

    /** A block with fewer than four spare commons deals a small box rather than failing. */
    @Test
    fun aThinBlockDealsWhatItHas() {
        val thin = Starter(
            id = "test-thin",
            block = 9,
            nameKey = "APP_TEST_THIN",
            deck = (1..HAND_SIZE).map { Card.idFor(block = 9, number = it) },
        )
        val table = (1..HAND_SIZE + 1).associate { number ->
            val id = Card.idFor(block = 9, number = number)
            id to card(id, rarity = if (number == HAND_SIZE) 2 else 1)
        }

        val opened = StarterPack.opened(character(), thin, table, Random(1))

        assertEquals(HAND_SIZE + 1, opened.cards.size, "the five plus the one spare common")
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
        val opened = StarterPack.opened(character(), ff14, cards, Random(3))
        val other = ff8.deck.first()

        val mixed = opened.withCard(other)

        assertTrue(mixed.ownsCard(other))
        assertEquals(StarterCatalog.SIZE + 1, StarterPack.playableCards(mixed))
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
        val destitute = character()

        assertEquals(destitute, StarterPack.grantedTo(destitute, empty, cards, Random(1)))
    }

    /**
     * The box the player asked for, not the catalogue's first.
     *
     * This is the bug, in one assertion. `grantedTo` used to take `catalog.starters.first()` and
     * nothing else, so an account claiming the FFVIII box was dealt the FFXIV one — and the client
     * had no way to say otherwise, because the request carried no id.
     */
    @Test
    fun aNamedStarterIsTheOneGranted() {
        val granted = StarterPack.grantedTo(character(), catalog, cards, Random(1), starter = ff8)

        assertEquals(ff8.deck, granted.decks.first().cards)
        for (id in ff8.deck) assertTrue(granted.ownsCard(id), "FFVIII card $id was not granted")
        for (id in ff14.deck) assertFalse(granted.ownsCard(id), "FFXIV card $id was granted")
    }

    /** Naming none is the shop's repair, which is offered the first box and asks for no choice. */
    @Test
    fun namingNoStarterFallsBackToTheFirst() {
        val granted = StarterPack.grantedTo(character(), catalog, cards, Random(1))

        assertEquals(ff14.deck, granted.decks.first().cards)
    }

    @Test
    fun grantingRepairsADestituteCharacterWithoutTouchingWhatItKept() {
        val kept = Card.idFor(block = 3, number = 99)
        val destitute = character().copy(cards = mapOf(kept to 3))

        val repaired = StarterPack.grantedTo(destitute, catalog, cards, Random(1))

        assertFalse(StarterPack.isOwedBy(repaired))
        assertEquals(3, repaired.copiesOf(kept), "copies already held are not disturbed")
        for (id in ff14.deck) {
            assertTrue(repaired.ownsCard(id), "starter card $id was not granted")
        }
    }

    /** A card already in the starter is topped up to one copy, never to two. */
    @Test
    fun grantingDoesNotDoubleWhatIsAlreadyOwned() {
        val held = ff14.deck.first()
        val partial = character().copy(cards = mapOf(held to 1))

        val repaired = StarterPack.grantedTo(partial, catalog, cards, Random(1))

        assertEquals(1, repaired.copiesOf(held))
        assertEquals(setOf(1), repaired.cards.values.toSet())
    }

    @Test
    fun grantingLeavesAPlayableDeckAtTheTop() {
        val destitute = character().copy(decks = listOf(Deck("Stranded", ff8.deck)))

        val repaired = StarterPack.grantedTo(destitute, catalog, cards, Random(1))
        val deck = repaired.decks.first()

        assertTrue(deck.isComplete, "the granted deck is a full hand")
        assertTrue(deck.isAffordable(repaired.cards), "and every card in it is owned")
        assertEquals(ff14.deck, deck.cards)
        assertTrue(repaired.decks.size <= GameSave.MAX_DECKS)
    }

    /** Copies are not cards: five of one is not a hand, and must not read as one. */
    @Test
    fun fiveCopiesOfOneCardIsStillOwedThePack() {
        val hoarder = character().copy(cards = mapOf(ff14.deck.first() to 5))

        assertEquals(1, StarterPack.playableCards(hoarder))
        assertTrue(StarterPack.isOwedBy(hoarder))
    }

    /** Only the starters of released sets may be opened with. */
    @Test
    fun anUnreleasedSetIsNotOnOffer() {
        val sets = listOf(
            CardSet(
                blocks = listOf(1),
                slug = "ff14",
                nameKey = "A",
                sortOrder = 1,
                released = true,
            ),
            CardSet(
                blocks = listOf(2),
                slug = "ff8",
                nameKey = "B",
                sortOrder = 2,
                released = false,
            ),
        )

        assertEquals(listOf(ff14.id), catalog.released(sets).map { it.id })
    }

    private companion object {
        /** Five authored ids in block 1, the last of them the rare. */
        val ff14 = Starter(
            id = "test-ff14",
            block = 1,
            nameKey = "APP_TEST_FF14",
            deck = listOf(1, 2, 3, 4, 20).map { Card.idFor(block = 1, number = it) },
        )

        val ff8 = Starter(
            id = "test-ff8",
            block = 2,
            nameKey = "APP_TEST_FF8",
            deck = listOf(1, 2, 3, 4, 20).map { Card.idFor(block = 2, number = it) },
        )

        val catalog = StarterCatalog(listOf(ff14, ff8))

        fun card(id: Int, rarity: Int) = Card(
            id = id,
            nameKey = "STR_TEST_$id",
            name = "Test $id",
            top = 1,
            right = 1,
            bottom = 1,
            left = 1,
            rarity = rarity,
        )

        /**
         * Blocks 1 and 2, twenty cards each: nineteen commons and a rarity-2 at number 20 — the
         * one each starter above names. Nineteen so that the pool is comfortably larger than the
         * draw and a seed change cannot exhaust it.
         */
        val cards: Map<Int, Card> = listOf(1, 2).flatMap { block ->
            (1..20).map { number ->
                val id = Card.idFor(block = block, number = number)
                id to card(id, rarity = if (number == 20) 2 else 1)
            }
        }.toMap()
    }
}
