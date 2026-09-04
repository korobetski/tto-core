package com.tripletriad.data

import com.tripletriad.model.BoosterItem
import com.tripletriad.model.BoosterType
import com.tripletriad.model.Card
import com.tripletriad.model.CardItem
import com.tripletriad.model.CardOrigin
import com.tripletriad.model.GameSave
import com.tripletriad.model.MiscItem
import com.tripletriad.model.PotionItem
import com.tripletriad.model.PotionType
import com.tripletriad.model.PouchItem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [Inventory]: stacking, selling, using, and the ordering the bag is kept in.
 *
 * The behaviour worth being careful about is stacking, because the AS3 does not do it — every site
 * `push`es — and the duplicate-row state that produces is what this layer exists to make
 * unreachable.
 */
class InventoryTest {
    private val empty = GameSave.new(createdAt = 0)

    private fun card(number: Int, rarity: Int) = Card(
        id = Card.idFor(block = 1, number = number),
        nameKey = "STR_TEST_$number",
        name = "Test $number",
        top = 1,
        right = 1,
        bottom = 1,
        left = 1,
        rarity = rarity,
    )

    /** Two cards a shop values differently, which is the whole point of the new price. */
    private val common = card(number = 10, rarity = 1)
    private val rare = card(number = 50, rarity = 5)
    private val cards: Map<Int, Card> = listOf(common, rare).associateBy { it.id }

    @Test
    fun addingAnItemPutsItInTheBag() {
        val save = Inventory.add(empty, CardItem(13))

        assertEquals(listOf(CardItem(13, 1)), save.bag)
    }

    /** The AS3 would have produced two rows of "1" here. */
    @Test
    fun addingTheSameStackableItemTwiceMergesTheStack() {
        val save = Inventory.add(Inventory.add(empty, CardItem(13)), CardItem(13, 3))

        assertEquals(listOf(CardItem(13, 4)), save.bag)
        assertEquals(4, Inventory.count(save, CardItem(13)))
    }

    @Test
    fun differentItemsDoNotMerge() {
        val save = listOf(CardItem(13), CardItem(14), PotionItem(PotionType.XP))
            .fold(empty) { current, item -> Inventory.add(current, item) }

        assertEquals(3, save.bag.size)
        assertEquals(1, Inventory.count(save, CardItem(13)))
        assertEquals(0, Inventory.count(save, CardItem(15)))
    }

    @Test
    fun addingAnEmptyStackDoesNothing() {
        assertEquals(empty, Inventory.add(empty, CardItem(13, 0)))
    }

    @Test
    fun addAllMergesEachItemIndependently() {
        val save = Inventory.addAll(
            empty,
            listOf(CardItem(13), CardItem(13), BoosterItem(BoosterType.BRONZE)),
        )

        assertEquals(2, save.bag.size)
        assertEquals(2, Inventory.count(save, CardItem(13)))
    }

    @Test
    fun removingReducesTheStackAndThenDropsTheRow() {
        var save = Inventory.add(empty, CardItem(13, 3))

        save = Inventory.remove(save, CardItem(13), count = 2)
        assertEquals(listOf(CardItem(13, 1)), save.bag)

        save = Inventory.remove(save, CardItem(13))
        assertTrue(save.bag.isEmpty())
    }

    /**
     * Partially fulfilling a removal is how an inventory ends up disagreeing with its transaction.
     */
    @Test
    fun removingMoreThanIsHeldChangesNothing() {
        val save = Inventory.add(empty, CardItem(13, 2))

        assertEquals(save, Inventory.remove(save, CardItem(13), count = 3))
        assertEquals(save, Inventory.remove(save, CardItem(99)))
    }

    /**
     * A sale pays what the *rarity* is worth, not what the id happens to be.
     *
     * It used to assert `id * 4`, which was `CardItem.as:25` and which global ids turned into
     * nonsense — see [CardValue]. What the price is, is that object's business; what is asserted
     * here is that selling routes through it and takes the card away.
     */
    @Test
    fun sellingACardPaysWhatItIsWorthAndRemovesIt() {
        val save = Inventory.add(empty.copy(mgp = 0), CardItem(rare.id, 2))

        val sold = Inventory.sell(save, CardItem(rare.id), cards)

        assertEquals(CardValue.resaleOf(rare.id, cards), sold.mgp)
        assertEquals(1, Inventory.count(sold, CardItem(rare.id)), "one of the two is left")
    }

    /** And a rarer card is worth more, which the id-based price could not promise. */
    @Test
    fun aRarerCardSellsForMore() {
        assertTrue(
            CardValue.resaleOf(rare.id, cards) > CardValue.resaleOf(common.id, cards),
            "a five-star must outsell a common",
        )
    }

    @Test
    fun sellingSeveralAtOncePaysForEach() {
        val save = Inventory.add(empty.copy(mgp = 0), CardItem(common.id, 3))

        assertEquals(
            CardValue.resaleOf(common.id, cards) * 3,
            Inventory.sell(save, CardItem(common.id), cards, count = 3).mgp,
        )
    }

    @Test
    fun whatIsNotSellableCannotBeSold() {
        val booster = BoosterItem(BoosterType.BRONZE)
        val save = Inventory.add(empty.copy(mgp = 0), booster)

        val attempted = Inventory.sell(save, booster, cards)

        assertEquals(save, attempted)
        assertEquals(0, attempted.mgp)
    }

    @Test
    fun sellingWhatIsNotHeldChangesNothing() {
        assertEquals(empty, Inventory.sell(empty, CardItem(common.id), cards))
    }

    /**
     * A pack yields a **card item in the bag**, not a card in the collection.
     *
     * `InventoryScreen.as:252-258` pushes `new CardItem(cardId)` onto `BAG` and leaves `CARDS`
     * alone, so the drawn card is something the player then chooses to use or to sell — the only
     * sink for a duplicate the game has. See [ItemUse.PackOpened].
     */
    @Test
    fun usingABoosterPutsACardItemInTheBagAndConsumesThePack() {
        val booster = BoosterItem(BoosterType.BRONZE)
        val save = Inventory.add(empty, booster)

        val used = Inventory.use(save, booster, Random(1))

        val opened = assertIs<ItemUse.PackOpened>(used)
        assertEquals(1, opened.cardIds.size, "a pack holds exactly one card")
        assertTrue(opened.cardIds.all { it in BoosterType.BRONZE.pool })
        assertEquals(0, Inventory.count(opened.save, booster), "the pack is consumed")
        for ((id, copies) in opened.cardIds.groupingBy { it }.eachCount()) {
            assertEquals(
                copies,
                Inventory.count(opened.save, CardItem(id)),
                "every drawn card should be in the bag: ${opened.save.bag}",
            )
        }
        assertEquals(
            empty.cards,
            opened.save.cards,
            "opening a pack must not add to the collection on its own",
        )
    }

    /** Opening then using is the two-step path, and it ends with the card owned. */
    @Test
    fun openingThenUsingTheDrawnCardAddsItToTheCollection() {
        val booster = BoosterItem(BoosterType.BEAST)
        val opened = assertIs<ItemUse.PackOpened>(
            Inventory.use(Inventory.add(empty, booster), booster, Random(7)),
        )

        val used = opened.cardIds.distinct().fold(opened.save) { profile, id ->
            assertIs<ItemUse.CardDrawn>(Inventory.use(profile, CardItem(id))).save
        }

        assertTrue(opened.cardIds.distinct().all(used::ownsCard))
    }

    /** A pack opened into a bag that already holds that card stacks rather than adding a row. */
    @Test
    fun aSecondCopyOfADrawnCardStacks() {
        val booster = BoosterItem(BoosterType.BRONZE, stack = 2)
        val save = Inventory.add(empty, booster)

        val first = assertIs<ItemUse.PackOpened>(Inventory.use(save, booster, Random(2)))
        val again = assertIs<ItemUse.PackOpened>(
            Inventory.use(first.save, BoosterItem(BoosterType.BRONZE), Random(2)),
        )

        assertEquals(first.cardIds, again.cardIds, "the same seed draws the same cards")
        for ((id, copies) in (first.cardIds + again.cardIds).groupingBy { it }.eachCount()) {
            assertEquals(copies, Inventory.count(again.save, CardItem(id)))
        }
        assertEquals(
            again.cardIds.distinct().size,
            again.save.bag.size,
            "one row per distinct card, stacked — not a row per copy: ${again.save.bag}",
        )
    }

    @Test
    fun usingAPotionRaisesItsBoonAndConsumesIt() {
        val potion = PotionItem(PotionType.BIG_XP)
        val save = Inventory.add(empty, potion)

        val used = Inventory.use(save, potion)

        val raised = assertIs<ItemUse.BoonRaised>(used)
        assertEquals(10, raised.save.boons.xp)
        assertEquals(0, raised.save.boons.mgp)
        assertTrue(raised.save.bag.isEmpty())
    }

    @Test
    fun usingACardItemAddsThatCard() {
        val item = CardItem(75)
        val save = Inventory.add(empty, item)

        val drawn = assertIs<ItemUse.CardDrawn>(Inventory.use(save, item))

        assertEquals(75, drawn.cardId)
        assertTrue(drawn.wasNew)
        assertTrue(drawn.save.ownsCard(75))
    }

    /** A duplicate is still consumed; the flag is how the UI knows to say "already owned". */
    @Test
    fun aDuplicateCardIsStillConsumedAndReportedAsNotNew() {
        val held = Card.idFor(block = 1, number = 1)
        val item = CardItem(held)
        val save = Inventory.add(empty.withCard(held), item)

        val drawn = assertIs<ItemUse.CardDrawn>(Inventory.use(save, item))

        assertEquals(false, drawn.wasNew)
        assertEquals(2, drawn.save.copiesOf(held), "and the second copy is kept")
        assertTrue(drawn.save.bag.isEmpty())
    }

    @Test
    fun whatIsNotUseableIsNotUsed() {
        val misc = MiscItem()
        val save = Inventory.add(empty, misc)

        val used = Inventory.use(save, misc)

        assertIs<ItemUse.NotUseable>(used)
        assertEquals(save, used.save)
    }

    @Test
    fun usingSomethingNotInTheBagChangesNothing() {
        val used = Inventory.use(empty, PotionItem(PotionType.XP))

        assertIs<ItemUse.NotUseable>(used)
        assertEquals(empty, used.save)
    }

    @Test
    fun usingAPouchPaysItIntoThePurseAndConsumesIt() {
        val pouch = PouchItem(mgp = 4200, cardId = 50, lotId = "lot-7")
        val save = Inventory.add(empty, pouch)

        val opened = assertIs<ItemUse.PouchOpened>(Inventory.use(save, pouch))

        assertEquals(4200, opened.mgp)
        assertEquals(50, opened.cardId)
        assertEquals(empty.mgp + 4200, opened.save.mgp)
        assertTrue(opened.save.bag.isEmpty())
    }

    /**
     * The double-tap, at the layer that can actually stop it.
     *
     * The client guards this with a `busy` flag and the server with an operation id, and both are
     * worth having — but neither is where the invariant lives. A pouch pays once because it is
     * *consumed* by the payment, so a second attempt finds a bag that no longer holds it.
     */
    @Test
    fun openingTheSamePouchTwicePaysOnce() {
        val pouch = PouchItem(mgp = 4200, cardId = 50, lotId = "lot-7")
        val save = Inventory.add(empty, pouch)

        val once = assertIs<ItemUse.PouchOpened>(Inventory.use(save, pouch)).save
        val twice = Inventory.use(once, pouch)

        assertIs<ItemUse.NotUseable>(twice)
        assertEquals(empty.mgp + 4200, twice.save.mgp, "paid once, not twice")
    }

    /** Two sales are two rows and two payouts, even for the same card at the same price. */
    @Test
    fun twoPouchesForTheSameSalePriceStayApart() {
        val first = PouchItem(mgp = 4200, cardId = 50, lotId = "lot-7")
        val second = PouchItem(mgp = 4200, cardId = 50, lotId = "lot-8")

        val save = Inventory.addAll(empty, listOf(first, second))
        assertEquals(2, save.bag.size)

        val afterFirst = assertIs<ItemUse.PouchOpened>(Inventory.use(save, first)).save
        val afterSecond = assertIs<ItemUse.PouchOpened>(Inventory.use(afterFirst, second)).save

        assertEquals(empty.mgp + 8400, afterSecond.mgp)
        assertTrue(afterSecond.bag.isEmpty())
    }

    /** A card the auction gave back is its own row: the two say different things. */
    @Test
    fun anUnsoldCardDoesNotMergeWithACopyFromAPack() {
        val save = Inventory.addAll(
            empty,
            listOf(CardItem(50), CardItem(50, origin = CardOrigin.AUCTION_UNSOLD)),
        )

        assertEquals(2, save.bag.size)
        assertTrue(save.bag.all { it.stack == 1 })
    }

    /** `InventoryScreen.sortBag()`'s job: pouches, cards, packs, potions, then the rest. */
    @Test
    fun theBagIsKeptInDisplayOrder() {
        val pouch = PouchItem(mgp = 100, cardId = 3, lotId = "lot-1")
        val save = Inventory.addAll(
            empty,
            listOf(
                MiscItem(),
                PotionItem(PotionType.MGP),
                CardItem(50),
                BoosterItem(BoosterType.GOLD),
                CardItem(3),
                pouch,
            ),
        )

        assertEquals(
            listOf(
                pouch,
                CardItem(3),
                CardItem(50),
                BoosterItem(BoosterType.GOLD),
                PotionItem(PotionType.MGP),
                MiscItem(),
            ),
            save.bag,
        )
    }

    @Test
    fun sortingIsStableAcrossRepeatedAdds() {
        val once = Inventory.addAll(empty, listOf(CardItem(9), CardItem(2)))
        val again = Inventory.add(once, CardItem(5))

        assertEquals(listOf(2, 5, 9), again.bag.map { (it as CardItem).cardId })
    }
}
