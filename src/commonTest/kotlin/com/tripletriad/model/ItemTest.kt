package com.tripletriad.model

import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The [Item] hierarchy: its wire format, its per-subtype constants, and the booster draw.
 *
 * The wire format is the part that has to be exactly right, because `Save.DATAS.BAG` holds these
 * objects and a bag written by the original must still load.
 */
class ItemTest {
    /** A block-1 card id — the shipped `ff14` table, which every pack pool names. */
    private fun ff14(number: Int) = Card.idFor(block = 1, number = number)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun aCardItemSerialisesAsTheAs3ToJsonDoes() {
        // `CardItem.__toJSON()` (:33-36) -> {type: 'item-type-card', card: 13, stack: 2}
        //
        // `origin` is the port's, and it appears here only because this fixture encodes
        // defaults the way `SaveJson` does. It is additive and defaulted, so the AS3 shape
        // still *parses* — which is what `aBagWrittenByTheAs3BuildStillParses` pins.
        val encoded = json.encodeToString<Item>(CardItem(13, stack = 2))

        assertEquals(
            """{"type":"item-type-card","card":13,"stack":2,"origin":"plain"}""",
            encoded,
        )
    }

    @Test
    fun aBoosterItemSerialisesAsTheAs3ToJsonDoes() {
        val encoded = json.encodeToString<Item>(BoosterItem(BoosterType.BEAST, stack = 3))

        assertEquals(
            """{"type":"item-type-booster","booster":"BEAST_BOOSTER","stack":3}""",
            encoded,
        )
    }

    @Test
    fun aPotionItemSerialisesAsTheAs3ToJsonDoes() {
        val encoded = json.encodeToString<Item>(PotionItem(PotionType.MGP))

        assertEquals("""{"type":"item-type-potion","potion":"MGP_BOOST","stack":1}""", encoded)
    }

    /** `Item.itemize` (`Item.as:161-178`) is what this replaces: dispatch on the `type` string. */
    @Test
    fun aBagWrittenByTheAs3BuildStillParses() {
        val bag = """
            [
              {"type":"item-type-card","card":13,"stack":2},
              {"type":"item-type-booster","booster":"BRONZE_BOOSTER","stack":1},
              {"type":"item-type-potion","potion":"SMALL_XP_BOOST","stack":5},
              {"type":"item-type-misc","stack":1}
            ]
        """.trimIndent()

        val items = json.decodeFromString<List<Item>>(bag)

        assertEquals(4, items.size)
        assertEquals(CardItem(13, 2), items[0])
        assertEquals(BoosterItem(BoosterType.BRONZE, 1), items[1])
        assertEquals(PotionItem(PotionType.SMALL_XP, 5), items[2])
        assertEquals(MiscItem(1), items[3])
    }

    @Test
    fun everyItemRoundTrips() {
        val items: List<Item> = listOf(
            CardItem(1),
            BoosterItem(BoosterType.GARLEAN, 7),
            PotionItem(PotionType.BIG_MGP, 2),
            MiscItem(4),
            CardItem(9, 1, CardOrigin.AUCTION_UNSOLD),
            PouchItem(mgp = 4200, cardId = 9, lotId = "lot-7"),
        )

        assertEquals(items, json.decodeFromString<List<Item>>(json.encodeToString(items)))
    }

    /**
     * A card item no longer prices itself, and says so with a zero.
     *
     * `CardItem.as:25` was `value = _cardId * 4`, and this test used to pin it. That arithmetic was
     * a rarity proxy while an id indexed one ascending table, and global ids made it absurd — an
     * FFVIII common outsold every FFXIV rare. Worth is a function of rarity, which only the card
     * table knows, so the price moved to `CardValue` and `Inventory.sell` is handed a catalogue.
     */
    @Test
    fun aCardItemCannotPriceItself() {
        assertEquals(0, CardItem(1).value)
        assertEquals(0, CardItem(100).value)
        assertTrue(CardItem(1).sellable, "it is still sellable — just not at a price it knows")
    }

    /**
     * The flags are assigned in each AS3 constructor and never changed, which is why they are
     * derived here. This pins them against the source so the derivation cannot drift.
     */
    @Test
    fun theFlagsMatchEachAs3Constructor() {
        val card = CardItem(1)
        assertTrue(card.sellable && card.stackable && card.useable && card.dropable)

        val booster = BoosterItem(BoosterType.BRONZE)
        assertFalse(booster.sellable, "BoosterItem.as:54")
        assertTrue(booster.stackable && booster.useable)
        assertFalse(booster.dropable, "BoosterItem.as:57")
        assertEquals(0, booster.value, "BoosterItem.as:52")

        val potion = PotionItem(PotionType.XP)
        assertFalse(potion.sellable, "PotionItem.as:40")
        assertTrue(potion.stackable && potion.useable)
        assertFalse(potion.dropable, "PotionItem.as:43")

        val misc = MiscItem()
        assertFalse(misc.useable, "Item.as:45 — the base class is the one that is not useable")
        assertEquals(1, misc.value, "Item.as:42 — the base default is 1, not 0")
    }

    @Test
    fun iconsAndKeysFollowTheAs3Names() {
        assertEquals("booster_pack_icon", BoosterItem(BoosterType.BRONZE).iconId)
        assertEquals("beast_booster", BoosterItem(BoosterType.BEAST).iconId)
        assertEquals("STR_BEAST_BOOSTER", BoosterType.BEAST.nameKey)
        // `APP_`, not `STR_`: `BoosterItem.as:51` asks for `STR_BEAST_BOOSTER_DESC` and no bundle
        // in the original defines it, so the sentence is the port's — see `descriptionKey`.
        assertEquals("APP_BEAST_BOOSTER_DESC", BoosterType.BEAST.descriptionKey)
        assertEquals("potionItem", PotionItem(PotionType.XP).iconId)
        assertEquals("STR_XP_BOOST", PotionType.XP.nameKey)
        assertEquals("APP_SMALL_MGP_BOOST_DESC", PotionType.SMALL_MGP.descriptionKey)
        assertEquals("APP_CARD_ITEM_DESC", CardItem(1).descriptionKey)
        assertEquals(
            "APP_CARD_ITEM_UNSOLD_DESC",
            CardItem(1, origin = CardOrigin.AUCTION_UNSOLD).descriptionKey,
        )
        // The icon needs the card's rarity, which only the catalog knows.
        val card = Card(257, "STR_FF14_CARD_1", "Dodo", 4, 4, 4, 4, rarity = 3)
        assertEquals("card_r3_icon", CardItem(1).iconFor(card))
    }

    /** `PotionItem.as:18-23`. The three tiers are 2, 5 and 10 on each of two boons. */
    @Test
    fun potionModifiersMatchTheAs3Constants() {
        assertEquals(BoonModifier(BoonType.XP, 2), PotionType.SMALL_XP.modifier)
        assertEquals(BoonModifier(BoonType.MGP, 2), PotionType.SMALL_MGP.modifier)
        assertEquals(BoonModifier(BoonType.XP, 5), PotionType.XP.modifier)
        assertEquals(BoonModifier(BoonType.MGP, 5), PotionType.MGP.modifier)
        assertEquals(BoonModifier(BoonType.XP, 10), PotionType.BIG_XP.modifier)
        assertEquals(BoonModifier(BoonType.MGP, 10), PotionType.BIG_MGP.modifier)
    }

    /**
     * `BoosterItem.as:19-27`, transcribed. Spot-checked rather than restated in full.
     *
     * Nine of the fifteen are the AS3's. The other six are the FFVIII packs, which that shelf never
     * had at all — see [BoosterType].
     */
    @Test
    fun boosterPoolsMatchTheAs3Constants() {
        assertEquals(15, BoosterType.entries.size)
        assertEquals(listOf(4, 5, 8, 12, 27, 38).map(::ff14), BoosterType.BRONZE.pool)
        assertEquals(listOf(31, 32, 47, 51, 64, 119).map(::ff14), BoosterType.GARLEAN.pool)
        assertEquals(13, BoosterType.BEAST.pool.size)
        for (type in BoosterType.entries) {
            assertTrue(type.pool.isNotEmpty(), "$type has no pool")
            assertTrue(type.pool.all { it >= Card.FIRST_ID }, "$type has a legacy card id")
        }
    }

    /**
     * No shipped [BoosterType] draws more than one card today — see that class's KDoc — but
     * [drawCards], the function [BoosterItem.open] delegates to, is kept general on purpose: a
     * future pack drawing several cards should not need this mechanism rewritten, only a
     * `cardCount` above 1. This pins the shape such a pack could rely on directly, independent of
     * any shipped [BoosterType].
     */
    @Test
    fun theDrawCanBeAskedForSeveralCardsAtOnce() {
        val pool = listOf(1, 2, 3)
        val weights = listOf(1.0, 1.0, 1.0)

        val drawn = drawCards(pool, weights, count = FIVE, Random(3))

        assertEquals(FIVE, drawn.size, "asked for $FIVE cards")
        assertTrue(drawn.all { it in pool }, "every card must come from the pool: $drawn")
    }

    @Test
    fun theDrawAllowsDuplicatesAcrossSeveralCards() {
        val pool = listOf(9)
        val weights = listOf(1.0)

        val drawn = drawCards(pool, weights, count = FIVE, Random(1))

        assertEquals(List(FIVE) { 9 }, drawn, "a one-card pool can only ever repeat")
    }

    @Test
    fun aBoosterTypeCannotDrawFewerThanOneCard() {
        // No enum entry can be constructed with a bad cardCount directly — this pins the guard on
        // the general path instead, the same way `drawCards`'s own tests do.
        assertTrue(BoosterType.entries.all { it.cardCount >= 1 })
    }

    @Test
    fun openingABoosterAlwaysYieldsExactlyOneCardFromItsOwnPool() {
        for (type in BoosterType.entries) {
            val booster = BoosterItem(type)
            repeat(200) { seed ->
                val drawn = booster.open(Random(seed))
                assertEquals(1, drawn.size, "$type should deal exactly one card")
                assertTrue(drawn.all { it in type.pool }, "$type drew $drawn, outside ${type.pool}")
            }
        }
    }

    /**
     * `BoosterItem.open()` multiplies two uniform draws, so the distribution leans hard on index 0
     * and the last entry is nearly unreachable. That is the rarity curve, so it is pinned rather
     * than merely tolerated — a "fix" to uniform would silently change every drop rate in the game.
     */
    @Test
    fun theBoosterDrawIsBiasedTowardsTheStartOfThePool() {
        val booster = BoosterItem(BoosterType.BEAST)
        val pool = BoosterType.BEAST.pool
        val draws = (0 until 4_000).flatMap { booster.open(Random(it)) }

        val firstThird = draws.count { pool.indexOf(it) < pool.size / 3 }
        assertTrue(
            firstThird > draws.size / 2,
            "expected most draws in the first third of the pool, got $firstThird of ${draws.size}",
        )
        assertTrue(
            draws.count { it == pool.last() } * 20 < draws.size,
            "the last entry should be rare",
        )
    }

    @Test
    fun openingIsReproducibleForAGivenSeed() {
        val booster = BoosterItem(BoosterType.PRIMAL)

        assertEquals(booster.open(Random(42)), booster.open(Random(42)))
    }

    /**
     * A card the auction handed back is a different row from one out of a pack.
     *
     * Not cosmetic: `Inventory.add` merges on identity-minus-stack, so if these compared equal the
     * two would share one row and that row would carry one of the two descriptions — telling half
     * the players the wrong story about where their card came from.
     */
    @Test
    fun anUnsoldAuctionCardIsNotTheSameItemAsAPlainOne() {
        val plain = CardItem(13)
        val unsold = CardItem(13, origin = CardOrigin.AUCTION_UNSOLD)

        assertNotEquals(plain, unsold)
        assertEquals(CardOrigin.PLAIN, plain.origin, "the default is the old behaviour")
        assertEquals("APP_CARD_ITEM_UNSOLD_DESC", unsold.descriptionKey)
    }

    @Test
    fun aPouchSerialisesWithItsLot() {
        val encoded = json.encodeToString<Item>(PouchItem(mgp = 4200, cardId = 9, lotId = "lot-7"))

        assertEquals(
            """{"type":"item-type-pouch","mgp":4200,"card":9,"lot":"lot-7"}""",
            encoded,
        )
    }

    /**
     * Two payouts stay two rows.
     *
     * The failure this prevents is not a display bug: `Inventory.remove` finds the *first* entry
     * that stacks with the one named, so a merged pouch would be opened once and pay out once for
     * two sales. [PouchItem.lotId] is what keeps them distinct even when the same card sold twice
     * for the same amount.
     */
    @Test
    fun twoPouchesNeverBecomeOne() {
        val first = PouchItem(mgp = 4200, cardId = 9, lotId = "lot-7")
        val second = PouchItem(mgp = 4200, cardId = 9, lotId = "lot-8")

        assertFalse(first.stackable, "if this were true `Inventory.add` would try to merge them")
        assertNotEquals(first, second)
        assertEquals(first, first.withStack(1), "the identity `Inventory` compares on")
    }

    @Test
    fun aPouchIsOpenedAndNothingElse() {
        val pouch = PouchItem(mgp = 100, cardId = 1, lotId = "lot-1")

        assertTrue(pouch.useable)
        assertFalse(pouch.sellable, "selling a purse for a fraction of itself is a misclick")
        assertFalse(pouch.dropable, "throwing away money somebody owed you is a support ticket")
        assertEquals(0, pouch.value, "`mgp` is what it holds, not what a shop pays for it")
        assertEquals(1, pouch.stack)
    }

    @Test
    fun anEmptyOrUnattributedPouchIsAProgrammingError() {
        assertFailsWith<IllegalArgumentException> { PouchItem(0, 1, "lot-1") }
        assertFailsWith<IllegalArgumentException> { PouchItem(100, 0, "lot-1") }
        assertFailsWith<IllegalArgumentException> { PouchItem(100, 1, " ") }
    }

    @Test
    fun withStackReplacesTheCountAndNothingElse() {
        assertEquals(CardItem(13, 9), CardItem(13, 1).withStack(9))
        assertEquals(
            BoosterItem(BoosterType.GOLD, 2),
            BoosterItem(BoosterType.GOLD, 1).withStack(2),
        )
    }

    @Test
    fun aNegativeStackIsAProgrammingError() {
        assertFailsWith<IllegalArgumentException> { CardItem(1, stack = -1) }
        assertFailsWith<IllegalArgumentException> { CardItem(0) }
    }

    @Test
    fun itemRewardsResolveToItems() {
        assertEquals(CardItem(13), ItemReward("card", 0.25, cardId = 13).item())
        assertEquals(
            PotionItem(PotionType.MGP),
            ItemReward("potion", 0.02, potion = PotionType.MGP).item(),
        )
        assertEquals(
            BoosterItem(BoosterType.BRONZE),
            ItemReward("booster", 0.1, booster = BoosterType.BRONZE).item(),
        )
        assertNotNull(ItemReward("card", 1.0, cardId = 1).item())
        // A type this build does not understand yields nothing rather than throwing.
        assertEquals(null, ItemReward("accessory", 1.0).item())
        assertEquals(null, ItemReward("card", 1.0).item())
    }

    private companion object {
        const val FIVE = 5
    }
}
