package com.tripletriad.data

import com.tripletriad.model.BoosterItem
import com.tripletriad.model.BoosterType
import com.tripletriad.model.Card
import com.tripletriad.model.CardItem
import com.tripletriad.model.GameSave
import com.tripletriad.model.Item
import com.tripletriad.model.PotionItem
import com.tripletriad.model.PotionType

/**
 * One thing the shop sells.
 *
 * @property item what lands in the bag. The [Item] itself carries no price — nothing in the game
 *   sells at what [Item.value] says, which is the *resale* figure — so the price belongs to the
 *   offer and not to the item.
 */
data class ShopOffer(val item: Item, val price: Int) {
    init {
        require(price > 0) { "an offer must cost something, was $price" }
    }

    /** True when [save] can pay for it. */
    fun isAffordableBy(save: GameSave): Boolean = save.mgp >= price
}

/**
 * The two shop tables of `screens/shopScreen.as:42-70`, transcribed verbatim.
 *
 * ### What the transcription preserves
 *
 * - **The prices**, which follow no formula. Silver and Scion are both 1152; Gold and Garlean are
 *   both 2160; card 74 costs a million MGP and card 63 costs 400 000 while card 118 — a later id —
 *   costs 200 000. These are hand-set numbers and re-deriving them from rarity would change the
 *   game's economy.
 * - **The order**, which is the order the list showed: potions, then packs, then cards ascending in
 *   price.
 * - **The asymmetry between the two collections.** The `ff8_` shop has *five* entries against the
 *   `ff14_` shop's twenty, and sells **no booster packs at all** — which is right rather than an
 *   omission: every `*_BOOSTER_CARDS` pool in `BoosterItem.as` names ff14 ids, and an id opened on
 *   an ff8 profile resolves against the ff8 table (see [BoosterType]). A pack on the ff8 shelf
 *   would deal a different card than its own pool describes.
 *
 * ### Two things in the original that are not reproduced
 *
 * - **A purchase was never saved.** `buyButton_triggeredHandler` ends on a commented-out
 *   `//Save.save(Game.PROFILE_DATAS)` (`:149`), so MGP spent and items bought were both lost on
 *   quit. Here the shop screen persists through `ProfileSession`, like every other mutation.
 * - **The bag grew a new row per purchase.** It `push`ed unconditionally, so buying two of the same
 *   potion showed two rows of "1". [Inventory.add] stacks. See there.
 *
 * [BoosterType.PLATINUM] is declared, priced nowhere and sold nowhere — it is in the pack table and
 * absent from both shop tables. Transcribed as such: it can only enter a bag as an achievement or
 * opponent reward, and neither grants one either.
 */
object ShopCatalog {
    /** `FF14_SHOP` (`shopScreen.as:42-63`). */
    val ff14: List<ShopOffer> = listOf(
        ShopOffer(PotionItem(PotionType.MGP), price = 50),
        ShopOffer(PotionItem(PotionType.XP), price = 50),
        ShopOffer(CardItem(258), price = 120),
        ShopOffer(CardItem(269), price = 150),
        ShopOffer(CardItem(276), price = 200),
        ShopOffer(CardItem(300), price = 1_000),
        ShopOffer(CardItem(301), price = 1_200),
        ShopOffer(CardItem(370), price = 14_400),
        ShopOffer(CardItem(394), price = 20_000),
        ShopOffer(CardItem(319), price = 400_000),
        ShopOffer(CardItem(374), price = 200_000),
        ShopOffer(CardItem(330), price = 1_000_000),
    )

    /** `FF8_SHOP` (`shopScreen.as:64-70`). */
    val ff8: List<ShopOffer> = listOf(
        ShopOffer(PotionItem(PotionType.MGP), price = 50),
        ShopOffer(PotionItem(PotionType.XP), price = 50),
        ShopOffer(CardItem(2080), price = 350),
        ShopOffer(CardItem(2085), price = 420),
        ShopOffer(CardItem(2093), price = 600),
    )

    /**
     * Every pack, at a price [BoosterPricing] works out from what it holds.
     *
     * Separate from the two authored lists above, and priceless — literally: a [ShopOffer] needs a
     * number and the number is not knowable without the card table, so [boosterOffers] takes one
     * and the shelf is assembled per call. That is the cost of pricing a pack by its contents, and
     * it is worth paying: the eight AS3 prices bore no relation to the pools (Beast and Bronze at
     * 360 and 520 for comparable pools; Scion and Silver both at 1 152 three stars apart), and any
     * hand-typed replacement would drift the first time a pool changed.
     *
     * The three FFVIII packs are new — see [BoosterType]. The FFVIII shelf sold no packs at all,
     * which was correct while every pool named ids that resolved against whichever table `MODE`
     * selected, and is simply a gap now that ids are global.
     */
    fun boosterOffers(cards: Map<Int, Card>): List<ShopOffer> =
        BoosterType.entries.map { type ->
            ShopOffer(BoosterItem(type), price = BoosterPricing.priceOf(type, cards))
        }

    /**
     * Everything the shop sells, both shelves at once and each potion only once.
     *
     * The two lists above are the AS3's, kept as written so they stay diffable against
     * `shopScreen.as`. This is what a caller reads, and [offers] is how it is filtered.
     */
    val shelf: List<ShopOffer> = (ff14 + ff8).distinct()

    /** [shelf] with the priced packs on it, in shelf order: potions, packs, then single cards. */
    fun shelf(cards: Map<Int, Card>): List<ShopOffer> {
        val (potions, singles) = shelf.partition { it.item is PotionItem }
        return potions + boosterOffers(cards) + singles
    }

    /**
     * What is on sale in [format].
     *
     * `shopScreen[MODE.toUpperCase() + 'SHOP']` (`:100`) — a string-built static lookup in the
     * original, which is why a typo in `MODE` produced an empty list rather than an error. It is a
     * **filter** now rather than a lookup: an offer belongs to the block of the cards it yields,
     * and the format decides which blocks are in play.
     *
     * A potion belongs to no block and is always on the shelf. That is not a special case bolted
     * on: an XP boon does not come from a set, and a shop that hid it in half the formats would be
     * hiding it for no reason anyone could state.
     */
    fun offers(format: Format, cards: Map<Int, Card>): List<ShopOffer> =
        shelf(cards).filter { offer -> offer.block?.let(format::admits) ?: true }

    /**
     * Buys [offer] for [save], or returns the profile unchanged when it cannot be paid for.
     *
     * One function rather than "deduct, then add" at the call site: those two steps have to either
     * both happen or neither, and the AS3 got that wrong in the direction that matters — it
     * subtracted the price and *then* checked (`:144-146`), so the check only ever decided whether
     * the button stayed enabled.
     */
    fun buy(save: GameSave, offer: ShopOffer): GameSave {
        if (!offer.isAffordableBy(save)) return save
        return Inventory.add(save.withMgp(-offer.price), offer.item)
    }
}

/**
 * The block an offer's cards come from, or null when it sells nothing set-specific.
 *
 * Derived rather than authored, because it is already written down twice over: a card id encodes
 * its block, and a booster's pool is a list of card ids. A third statement of the same fact would
 * be the one that goes stale.
 *
 * A booster whose pool somehow spanned two blocks would answer with the first, which no shipped
 * booster does — `ShopCatalogTest` holds them to it, so the day one does the test says so rather
 * than the shop quietly hiding it.
 */
val ShopOffer.block: Int?
    get() = when (val bought = item) {
        is CardItem -> bought.cardId shr Card.BLOCK_SHIFT
        is BoosterItem -> bought.boosterType.pool.firstOrNull()?.shr(Card.BLOCK_SHIFT)
        else -> null
    }
