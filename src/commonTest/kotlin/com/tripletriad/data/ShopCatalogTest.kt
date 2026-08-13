package com.tripletriad.data

import com.tripletriad.model.BoosterItem
import com.tripletriad.model.BoosterType
import com.tripletriad.model.Card
import com.tripletriad.model.CardItem
import com.tripletriad.model.GameSave
import com.tripletriad.model.PotionItem
import com.tripletriad.model.PotionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ShopCatalog] against `screens/shopScreen.as:42-70`.
 *
 * The prices are transcribed and follow no formula, so the tests that matter here are the ones that
 * pin the *shape* — that the two tables differ, that ff8 sells no packs, that a purchase is atomic
 * — plus spot checks on numbers a re-derivation would get wrong.
 */
class ShopCatalogTest {
    /** A block-1 card id — the shipped `ff14` table. */
    private fun ff14(number: Int) = Card.idFor(block = 1, number = number)

    /**
     * A card for every id any pool names, its rarity taken from where it sits in that pool.
     *
     * Synthetic, and it has to be: pricing a pack needs a card table, `:core` ships none, and
     * pinning these tests to `cards.json` would make them fail whenever a card is retuned. What is
     * asserted here is that the *rule* holds — a better pool costs more, a guaranteed slot is
     * guaranteed. That the shipped shelf comes out at sensible numbers is `ShopBundleTest`'s job,
     * where the real table is.
     *
     * Rarity rises along the pool because that is how every pool is authored — best last — which is
     * the property `BoosterItem.open`'s bias exists to exploit. It rises with the **absolute**
     * position rather than the fraction, so a longer pool reaches higher stars: normalising it
     * would make every pool worth the same and there would be nothing left to compare.
     */
    private val cards: Map<Int, Card> = BoosterType.entries
        .flatMap { type ->
            type.pool.mapIndexed { index, id ->
                id to rarityAt(index, type.pool.size)
            }
        }
        .distinctBy { it.first }
        .associate { (id, rarity) ->
            id to Card(
                id = id,
                nameKey = "STR_TEST_$id",
                name = "Test $id",
                top = 1,
                right = 1,
                bottom = 1,
                left = 1,
                rarity = rarity,
            )
        }

    /** A star every two places along the pool, capped at five. */
    @Suppress("UNUSED_PARAMETER")
    private fun rarityAt(index: Int, size: Int): Int = (1 + index / 2).coerceIn(1, 5)

    private fun profile(mgp: Int) = GameSave.new(createdAt = 0L).copy(mgp = mgp)

    /**
     * The two authored lists, now that the packs have left them.
     *
     * `FF14_SHOP` had twenty entries and `FF8_SHOP` five. Eight of the twenty were booster packs
     * and are gone from here: a pack's price is computed from its contents ([BoosterPricing]), so
     * it cannot sit in a list of literals. Twelve and five are what is left, and they are still the
     * AS3's entries.
     */
    @Test
    fun theTwoAuthoredTablesHoldWhatTheAs3PricedByHand() {
        assertEquals(12, ShopCatalog.ff14.size, "FF14_SHOP less its eight packs")
        assertEquals(5, ShopCatalog.ff8.size, "FF8_SHOP has 5")
        assertTrue(
            (ShopCatalog.ff14 + ShopCatalog.ff8).none { it.item is BoosterItem },
            "a pack is priced, not authored",
        )
    }

    /** Every pack is on the shelf, and every pack has a price. */
    @Test
    fun everyPackIsOnSale() {
        val packs = ShopCatalog.shelf(cards).mapNotNull { (it.item as? BoosterItem)?.boosterType }

        assertEquals(BoosterType.entries.toSet(), packs.toSet())
        assertTrue(
            ShopCatalog.shelf(cards).all { it.price > 0 },
            "nothing on the shelf is free",
        )
    }

    /**
     * A pack of better cards costs more, which is the whole claim of pricing by contents.
     *
     * Stated over the fixture's pools and not the shipped ones — what is being asserted is the
     * *rule*, and a rule that only held for the cards that happen to ship is not one. Under this
     * fixture a longer pool reaches higher stars, so Mithril's nine beat Bronze's six.
     */
    @Test
    fun aRicherPackCostsMore() {
        fun priceOf(type: BoosterType) =
            ShopCatalog.shelf(cards).first { it.item == BoosterItem(type) }.price

        assertTrue(
            priceOf(BoosterType.MITHRIL) > priceOf(BoosterType.BRONZE),
            "nine cards deep should beat six: ${priceOf(BoosterType.MITHRIL)} vs " +
                "${priceOf(BoosterType.BRONZE)}",
        )
        assertTrue(
            priceOf(BoosterType.GUARDIAN_FORCE) > priceOf(BoosterType.BRONZE),
            "a seventeen-card pool should beat six",
        )
    }

    /**
     * A format sees the offers whose cards it admits, and every potion.
     *
     * The two AS3 shelves are reproduced by *filtering* rather than by lookup, so this asserts the
     * filter reproduces them — which is the claim that would break if a card were priced into the
     * wrong shelf, something a lookup could never have caught.
     */
    @Test
    fun aFormatSeesTheOffersItsBlocksAdmit() {
        val ff14 = ShopCatalog.offers(TestFormats.ff14, cards)
        val ff8 = ShopCatalog.offers(TestFormats.ff8, cards)

        assertTrue(ff14.containsAll(ShopCatalog.ff14), "every authored ff14 offer is admitted")
        assertTrue(ff8.containsAll(ShopCatalog.ff8), "every authored ff8 offer is admitted")
        assertTrue(
            ff14.none { it in ShopCatalog.ff8 - ShopCatalog.ff14.toSet() },
            "an ff14 format must not be sold ff8-only cards",
        )
    }

    /** A potion belongs to no set, so both shelves carry it and neither hides it. */
    @Test
    fun potionsAreOnEveryShelf() {
        val potions = ShopCatalog.shelf(cards).filter { it.block == null }
        assertTrue(potions.isNotEmpty(), "the fixture assumes the shop sells potions")

        for (format in listOf(TestFormats.ff14, TestFormats.ff8)) {
            assertTrue(
                ShopCatalog.offers(format, cards).containsAll(potions),
                "${format.id} is missing a potion",
            )
        }
    }

    /**
     * No booster's pool spans two blocks.
     *
     * [block] answers with the first id's block, which is only honest while that holds. A booster
     * mixing sets would be silently filed under one of them and hidden from the other format.
     */
    @Test
    fun noBoosterPoolSpansTwoBlocks() {
        for (type in BoosterType.entries) {
            val blocks = type.pool.map { it shr Card.BLOCK_SHIFT }.distinct()
            assertEquals(1, blocks.size, "${type.name} draws from blocks $blocks")
        }
    }

    /**
     * An FFVIII format is sold FFVIII packs, which it never used to be.
     *
     * This inverts the test it replaces. The FFVIII shelf held no packs at all, and that was right
     * rather than an omission while every `*_BOOSTER_CARDS` pool named ids that resolved against
     * whichever table `MODE` selected. Ids are global and `MODE` is gone, so the absence became a
     * plain gap — filled by [BoosterType.GALBADIAN], [BoosterType.GUARDIAN_FORCE] and
     * [BoosterType.CHARACTER].
     */
    @Test
    fun bothFormatsAreSoldTheirOwnPacks() {
        fun packsIn(format: Format) = ShopCatalog.offers(format, cards)
            .mapNotNull { (it.item as? BoosterItem)?.boosterType }

        val ff8Packs = packsIn(TestFormats.ff8)
        val ff14Packs = packsIn(TestFormats.ff14)

        // Six: monsters, Galbadia, fiends, companions, GFs and the cast — a ladder from the
        // cheapest pack in the game to the dearest, where there used to be no FFVIII pack at all.
        assertTrue(ff8Packs.size >= 6, "the FFVIII shelf should carry a full ladder")
        assertTrue(BoosterType.CHARACTER in ff8Packs && BoosterType.MONSTER in ff8Packs)
        assertTrue(ff14Packs.isNotEmpty() && ff8Packs.none { it in ff14Packs })
    }

    /** Both shelves open with the same two 50-MGP potions. */
    @Test
    fun bothShopsSellTheTwoFiftyMgpPotions() {
        for (offers in listOf(ShopCatalog.ff14, ShopCatalog.ff8)) {
            assertEquals(ShopOffer(PotionItem(PotionType.MGP), 50), offers[0])
            assertEquals(ShopOffer(PotionItem(PotionType.XP), 50), offers[1])
        }
    }

    /** Prices a formula would not produce: two pairs collide, and card 118 undercuts card 63. */
    @Test
    fun thePricesAreTheHandSetOnes() {
        fun priceOf(id: Int) = ShopCatalog.ff14.first { it.item == CardItem(id) }.price

        // The two pack prices this used to pin — Silver against Scion, Gold against Garlean, equal
        // because somebody typed 1152 and 2160 twice — are gone with the hand-set pack prices. That
        // they were equal was the defect, not the fact: Scion's pool is three stars above Silver's.
        assertEquals(1_000_000, priceOf(ff14(74)))
        assertTrue(priceOf(ff14(118)) < priceOf(ff14(63)), "the later id is the cheaper card")
    }

    /**
     * Platinum is on the shelf now, and it was not.
     *
     * `BoosterItem.as` declares it and `shopScreen.as` sells it nowhere — an orphan the AS3 left
     * behind. With prices derived from pools, every declared pack has a price and there is no list
     * to be left out of, so leaving it unsold would take a deliberate exclusion nobody could
     * justify: its pool is authored, its cards exist, and it is the strongest FFXIV pack there is.
     */
    @Test
    fun theOrphanedPlatinumPackIsSoldNow() {
        val platinum = BoosterItem(BoosterType.PLATINUM)

        assertTrue(ShopCatalog.shelf(cards).any { it.item == platinum })
        assertTrue(
            (ShopCatalog.ff14 + ShopCatalog.ff8).none { it.item == platinum },
            "and it is still in neither hand-authored list",
        )
    }

    @Test
    fun aFreeOfferIsAProgrammingError() {
        assertFailsWith<IllegalArgumentException> { ShopOffer(CardItem(1), price = 0) }
    }

    // ---- Buying ----------------------------------------------------------

    @Test
    fun buyingTakesTheMgpAndGivesTheItem() {
        val offer = ShopOffer(PotionItem(PotionType.MGP), price = 50)

        val bought = ShopCatalog.buy(profile(mgp = 120), offer)

        assertEquals(70, bought.mgp)
        assertEquals(1, Inventory.count(bought, offer.item))
    }

    /**
     * Neither half happens when the profile cannot pay — the AS3 subtracted first and checked
     * afterwards (`:144-146`). See [ShopCatalog.buy].
     */
    @Test
    fun anUnaffordableOfferChangesNothingAtAll() {
        val save = profile(mgp = 49)
        val offer = ShopOffer(PotionItem(PotionType.MGP), price = 50)

        assertFalse(offer.isAffordableBy(save))
        assertEquals(save, ShopCatalog.buy(save, offer))
    }

    @Test
    fun exactlyEnoughIsEnough() {
        val offer = ShopOffer(CardItem(2), price = 120)

        val bought = ShopCatalog.buy(profile(mgp = 120), offer)

        assertEquals(0, bought.mgp)
        assertEquals(1, Inventory.count(bought, CardItem(2)))
    }

    /** Two of the same offer stack rather than adding a second row — the AS3 `push`ed. */
    @Test
    fun buyingTwiceStacksInTheBag() {
        val offer = ShopOffer(PotionItem(PotionType.XP), price = 50)

        val twice = ShopCatalog.buy(ShopCatalog.buy(profile(mgp = 200), offer), offer)

        assertEquals(100, twice.mgp)
        assertEquals(1, twice.bag.size, "one row: ${twice.bag}")
        assertEquals(2, Inventory.count(twice, offer.item))
    }

    /** A bought card is a bag item, not a collection entry — using it is a separate step. */
    @Test
    fun aBoughtCardDoesNotEnterTheCollectionByItself() {
        val offer = ShopOffer(CardItem(44), price = 1_000)

        val bought = ShopCatalog.buy(profile(mgp = 1_000), offer)

        assertFalse(bought.ownsCard(44))
        assertEquals(1, Inventory.count(bought, CardItem(44)))
    }
}
