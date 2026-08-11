package com.tripletriad.data

import com.tripletriad.model.Card
import com.tripletriad.model.CardCollection
import com.tripletriad.model.CardColor
import com.tripletriad.model.CardType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parser-level tests. These use inline JSON rather than the shipped resource so they
 * run on every target without a resource loader; the real `cards.json` is exercised
 * end-to-end by `desktopTest/CardBundleTest`.
 *
 * The ids are the real ones: `0x013e` is Ultima Weapon, card 62 of block 1, and `0x0201` is
 * Geezard, card 1 of block 2. Writing them in hex would read better and is deliberately not done —
 * these have to look like the integers a save file and a transcript carry.
 */
class CardCatalogTest {

    private val json = """
        {
          "sets": [
            {"block": 1, "slug": "ff14", "nameKey": "APP_SET_FF14",
             "sortOrder": 1, "released": true},
            {"block": 2, "slug": "ff8", "nameKey": "APP_SET_FF8",
             "sortOrder": 2, "released": true},
            {"block": 9, "slug": "unreleased", "nameKey": "APP_SET_LATER",
             "sortOrder": 9, "released": false}
          ],
          "cards": [
            {
              "id": 318, "block": 1, "number": 62, "nameKey": "STR_FF14_CARD_62",
              "name": "Ultima Weapon", "top": 1, "right": 8, "bottom": 10, "left": 8,
              "rarity": 5, "type": null
            },
            {
              "id": 320, "block": 1, "number": 64, "nameKey": "STR_FF14_CARD_64",
              "name": "Gaius van Baelsar", "top": 4, "right": 10, "bottom": 5, "left": 9,
              "rarity": 5, "type": "garlean"
            },
            {
              "id": 513, "block": 2, "number": 1, "nameKey": "STR_FF8_CARD_1",
              "name": "Geezard", "top": 1, "right": 4, "bottom": 1, "left": 5,
              "rarity": 1, "type": null
            },
            {
              "id": 518, "block": 2, "number": 6, "nameKey": "STR_FF8_CARD_6",
              "name": "Thrustaevis", "top": 2, "right": 1, "bottom": 4, "left": 4,
              "rarity": 1, "type": "lightning"
            }
          ]
        }
    """.trimIndent()

    private val catalog = CardCatalogParser.parse(json)

    @Test
    fun everySetIsParsedIntoOneCardList() {
        assertEquals(2, catalog.block(1).size)
        assertEquals(2, catalog.block(2).size)
        assertEquals(4, catalog.all.size)
        assertEquals(3, catalog.sets.size)
    }

    /** The whole point of the scheme: the id carries the set and the number. */
    @Test
    fun anIdDecodesToItsBlockAndNumber() {
        val ultima = catalog[318]!!

        assertEquals(1, ultima.block)
        assertEquals(62, ultima.number)
        assertEquals(318, Card.idFor(block = 1, number = 62))
    }

    /** Blocks start at 1, so every legacy id is refused rather than remapped onto the first set. */
    @Test
    fun anIdBelowTheFirstBlockIsRefused() {
        assertFailsWith<IllegalArgumentException> { card(id = 62) }
        assertFailsWith<IllegalArgumentException> { card(id = Card.FIRST_ID - 1) }
    }

    /** Number 0 stays reserved: an empty deck slot is stored as `0`. */
    @Test
    fun numberZeroIsNotACard() {
        assertFailsWith<IllegalArgumentException> { card(id = Card.idFor(1, 1) - 1) }
        assertFailsWith<IllegalArgumentException> { Card.idFor(block = 1, number = 0) }
        assertFailsWith<IllegalArgumentException> { Card.idFor(block = 0, number = 1) }
    }

    /** A set holds at most 255 cards; a bigger one takes a second block. */
    @Test
    fun aBlockCannotHoldMoreThanTwoHundredAndFiftyFive() {
        assertEquals(511, Card.idFor(block = 1, number = 255))
        assertFailsWith<IllegalArgumentException> { Card.idFor(block = 1, number = 256) }
    }

    @Test
    fun powersKeepTheAs3TopRightBottomLeftOrder() {
        val geezard = catalog[513]!!
        // cards.as: power:[1,4,1,5]
        assertEquals(1, geezard.top)
        assertEquals(4, geezard.right)
        assertEquals(1, geezard.bottom)
        assertEquals(5, geezard.left)
    }

    @Test
    fun hexPowerAIsTen() {
        // cards.as line 82: power:[1,8,'A',8] -- read via uint("0x" + ...) in Card.as.
        assertEquals(10, catalog[318]!!.bottom)
    }

    @Test
    fun typeCoversBothTheFf14TribesAndTheFf8Elements() {
        assertEquals(CardType.GARLEAN, catalog[320]!!.type)
        assertEquals(CardType.LIGHTNING, catalog[518]!!.type)
        assertNull(catalog[513]!!.type)
    }

    @Test
    fun ownerDefaultsToBlueBecauseTheDataDoesNotStoreIt() {
        assertTrue(catalog.all.all { it.owner == CardColor.BLUE })
    }

    /** The bridge that survives until formats land — see [CardCollection]. */
    @Test
    fun aShippedTableIsStillReachableByItsCollection() {
        assertEquals(catalog.block(1), catalog.collection(CardCollection.FF14))
        assertEquals(catalog.block(2), catalog.collection(CardCollection.FF8))
        assertEquals(CardCollection.FF14, CardCollection.forBlock(1))
        assertNull(CardCollection.forBlock(9))
    }

    /** A block nothing ships is empty rather than an error: a format may name one early. */
    @Test
    fun anUnknownBlockIsEmpty() {
        assertEquals(emptyList(), catalog.block(9))
    }

    @Test
    fun onlyReleasedSetsAreOffered() {
        assertEquals(listOf("ff14", "ff8"), catalog.releasedSets.map { it.slug })
    }

    @Test
    fun unknownFieldsDoNotBreakParsing() {
        val withExtra = json.replace(
            "\"name\": \"Geezard\"",
            "\"name\": \"Geezard\", \"unknownFutureField\": 42",
        )
        assertEquals(4, CardCatalogParser.parse(withExtra).all.size)
    }

    @Test
    fun invalidDataIsRejectedAtConstruction() {
        val broken = json.replace("\"right\": 4", "\"right\": 99")
        assertFailsWith<IllegalArgumentException> { CardCatalogParser.parse(broken) }
    }

    private fun card(id: Int) = Card(
        id = id,
        nameKey = "STR_TEST",
        name = "Test",
        top = 1,
        right = 1,
        bottom = 1,
        left = 1,
        rarity = 1,
    )
}
