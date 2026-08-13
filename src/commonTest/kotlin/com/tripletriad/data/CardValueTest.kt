package com.tripletriad.data

import com.tripletriad.model.BoosterItem
import com.tripletriad.model.BoosterType
import com.tripletriad.model.Card
import com.tripletriad.model.CardItem
import com.tripletriad.model.GameSave
import com.tripletriad.model.PotionItem
import com.tripletriad.model.PotionType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [CardValue], and the loop it closes.
 *
 * ### The defect, stated as a test
 *
 * `CardItem.value` was `cardId * 4`. With global ids that made the *block* dominate the price, so
 * the shop sold cards for less than it bought them back for: 120 MGP for a Tonberry that resold at
 * 1 032, 350 for an FFVIII Tonberry that resold at 2 176. A 500 MGP Bronze pack held five cards
 * worth about five thousand on resale. Any of those is an unbounded MGP loop, and an economy with
 * one of those has no prices at all.
 *
 * [aShopCannotPayMoreForACardThanItChargesForOne] and [noPackIsWorthOpeningForResale] are the two
 * halves of that hole, asserted so it cannot reopen — including from a direction nobody is thinking
 * about, like a pool gaining a card or a rate being tuned.
 */
class CardValueTest {
    private fun card(id: Int, rarity: Int) = Card(
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
     * Every id the shelf or any pool names, rarity rising along each pool as authored pools do.
     *
     * The shop's single cards are given the **top** rarity, which is the hostile case for the pack
     * assertions below: it is the most a card can be worth, so it is the hardest number for a pack
     * price to stay above.
     */
    private val cards: Map<Int, Card> = buildMap {
        for (offer in ShopCatalog.shelf) {
            (offer.item as? CardItem)?.let { put(it.cardId, card(it.cardId, TOP_RARITY)) }
        }
        for (type in BoosterType.entries) {
            type.pool.forEachIndexed { index, id ->
                if (id !in this) put(id, card(id, (1 + index / 2).coerceIn(1, 5)))
            }
        }
    }

    @Test
    fun worthRisesWithRarityAndAnUnknownCardIsACommon() {
        val ladder = (1..TOP_RARITY).map { CardValue.MGP_BY_RARITY.getValue(it) }

        assertEquals(ladder.sorted(), ladder, "a star must never be worth less than the one below")
        assertEquals(
            CardValue.MGP_BY_RARITY.getValue(1),
            CardValue.worthOf(cardId = 999_999, cards = cards),
            "an id no catalogue holds is worth the least it could be, not a crash",
        )
    }

    /** Selling is worth doing, or the button is a trap; but never worth more than the card is. */
    @Test
    fun aShopPaysSomethingAndPaysLessThanTheCardIsWorth() {
        for (card in cards.values) {
            val paid = CardValue.resaleOf(card.id, cards)

            assertTrue(paid > 0, "card ${card.id} sells for nothing")
            assertTrue(
                paid < CardValue.worthOf(card),
                "card ${card.id} resells at $paid, at or above its worth",
            )
        }
    }

    /**
     * **A shop can never pay more for a card than it charges for one.**
     *
     * The structural half of the fix, and the reason the loop cannot reopen: both numbers read the
     * same ladder, so `RESALE_RATE < MARKUP` makes buying-and-reselling a loss for *every* card at
     * *every* rarity, with no case analysis. The old code could not state this at all — pack prices
     * and card values came from unrelated arithmetic and there was nothing to compare.
     *
     * That the **authored** single-card prices also clear their resale is a claim about content,
     * and it needs the shipped rarities: `ShopBundleTest` in the client makes it, where the real
     * card table is. `:core` ships none, and a fixture inventing rarities here would be marking its
     * own homework.
     */
    @Test
    fun aShopCannotPayMoreForACardThanItChargesForOne() {
        assertTrue(
            CardValue.RESALE_RATE < BoosterPricing.MARKUP,
            "resale ${CardValue.RESALE_RATE} must sit under the markup ${BoosterPricing.MARKUP}",
        )

        for (rarity in 1..TOP_RARITY) {
            val worth = CardValue.MGP_BY_RARITY.getValue(rarity)
            val charged = worth * BoosterPricing.MARKUP
            val paid = worth * CardValue.RESALE_RATE

            assertTrue(paid < charged, "$rarity★ is bought at $paid and sold at $charged")
        }
    }

    /**
     * **No pack is worth buying to open and resell.**
     *
     * The subtler half, and the one that survived the first fix in the original code: even with
     * every card priced sanely, a pack whose contents resold above its price would be the same
     * loop with an animation in front of it. It holds by construction — the shop marks up by
     * [BoosterPricing.MARKUP] and buys back at [CardValue.RESALE_RATE], so a pack returns about a
     * third — and it is asserted because "by construction" is a claim about two numbers that live
     * in different files.
     */
    @Test
    fun noPackIsWorthOpeningForResale() {
        for (type in BoosterType.entries) {
            val price = BoosterPricing.priceOf(type, cards)
            val pack = BoosterItem(type)

            val returned = (0 until SAMPLES).map { seed ->
                pack.open(Random(seed)).sumOf { CardValue.resaleOf(it, cards) }
            }
            val mean = returned.sum().toDouble() / SAMPLES

            assertTrue(
                mean < price,
                "${type.name} costs $price and returns $mean on resale",
            )
            assertTrue(
                returned.max() < price * LUCKY_HEADROOM,
                "${type.name}: even a lucky ${returned.max()} must not dwarf its $price",
            )
        }
    }

    // ---- The path a player actually takes ---------------------------------

    @Test
    fun sellingACardPaysWhatTheBagSaidItWould() {
        val id = cards.keys.first()
        val save = Inventory.add(GameSave.new(createdAt = 0L).copy(mgp = 0), CardItem(id))

        val shown = Inventory.priceOf(CardItem(id), cards)
        val sold = Inventory.sell(save, CardItem(id), cards)

        assertEquals(shown, sold.mgp, "the button must pay what it promised")
        assertEquals(CardValue.resaleOf(id, cards), shown)
        assertTrue(sold.bag.isEmpty(), "and the card is gone")
    }

    @Test
    fun aShopWillNotBuyWhatIsNotSellable() {
        val potion = PotionItem(PotionType.MGP)
        val save = Inventory.add(GameSave.new(createdAt = 0L).copy(mgp = 0), potion)

        assertEquals(0, Inventory.priceOf(potion, cards))
        assertEquals(save, Inventory.sell(save, potion, cards), "and selling it does nothing")
    }

    @Test
    fun sellingWhatIsNotInTheBagChangesNothing() {
        val save = GameSave.new(createdAt = 0L).copy(mgp = 7)

        assertEquals(save, Inventory.sell(save, CardItem(cards.keys.first()), cards))
    }

    private companion object {
        const val TOP_RARITY = 5
        const val SAMPLES = 400

        /** A lucky pack may beat its price; it must not be a strategy. */
        const val LUCKY_HEADROOM = 2
    }
}
