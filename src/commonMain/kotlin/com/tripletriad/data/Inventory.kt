package com.tripletriad.data

import com.tripletriad.model.BoosterItem
import com.tripletriad.model.Card
import com.tripletriad.model.CardItem
import com.tripletriad.model.GameSave
import com.tripletriad.model.Item
import com.tripletriad.model.MiscItem
import com.tripletriad.model.PotionItem
import com.tripletriad.model.PouchItem
import kotlin.random.Random

/**
 * What using an item did, so the caller can show it.
 *
 * A return value rather than a callback or an event, because every AS3 equivalent dispatched a
 * Starling event that a screen listened for and then mutated the global profile from — which is why
 * `InventoryScreen` and `Item` are hard to separate.
 */
sealed interface ItemUse {
    /** The profile after the item was consumed. */
    val save: GameSave

    /**
     * A pack was opened and yielded a [CardItem] for [cardId] **into the bag**, not into the
     * collection.
     *
     * That is the original's behaviour and it is deliberate rather than an oversight:
     * `useBtnHandler` (`InventoryScreen.as:252-258`) opens the pack and pushes `new
     * CardItem(cardId).__toJSON()` onto `BAG`, leaving `CARDS` alone. Opening a pack therefore
     * yields something the player can either *use* — which adds the card — or **sell**. Adding the
     * card directly would silently delete that choice, and with it the resale value of every
     * duplicate ever drawn.
     *
     * Selling used to be *the only* sink for a duplicate, and that is no longer true: a second copy
     * is kept and can be played, so Use is now a real answer for a card already owned rather than a
     * waste of it. See `GameSave.withCard` and § 1 of
     * `docs/migration/20-CARD-COPIES-AND-PLATFORM-ACCOUNTS.md`.
     */
    /**
     * @property cardIds every card the pack held, in reveal order — the guaranteed one last. See
     *   `BoosterItem.open`, which decides that order and explains why it is the only structure the
     *   list has. A pack holds several cards now; it used to hold one.
     * @property newCardIds those of [cardIds] the profile did not already own. A **set**, so a pack
     *   holding two copies of a card the player lacked reports one new card and not two.
     */
    data class PackOpened(
        override val save: GameSave,
        val cardIds: List<Int>,
        val newCardIds: Set<Int>,
    ) : ItemUse

    /** A card item was used and [cardId] entered the collection. */
    data class CardDrawn(
        override val save: GameSave,
        val cardId: Int,
        val wasNew: Boolean,
    ) : ItemUse

    /** A potion was drunk and raised a boon. */
    data class BoonRaised(override val save: GameSave) : ItemUse

    /**
     * An auction pouch was opened and its [mgp] went into the purse.
     *
     * @property cardId the card whose sale this was, so the confirmation can name it rather than
     *   announcing a sum from nowhere.
     */
    data class PouchOpened(
        override val save: GameSave,
        val mgp: Int,
        val cardId: Int,
    ) : ItemUse

    /** The item is in the bag but does nothing — [Item.useable] is false. */
    data class NotUseable(override val save: GameSave) : ItemUse
}

/**
 * Bag operations on a [GameSave].
 *
 * ### Why these are functions on the save and not a stateful repository
 *
 * The AS3 keeps the bag as `Game.PROFILE_DATAS.BAG`, an array of plain objects, and mutates it from
 * whichever screen happens to be open: `InventoryScreen.sortBag()`, `Achievements.check()` pushing
 * a reward, `shopScreen` splicing on a sale. Ownership is nowhere, so any of them can leave the bag
 * in a state the others do not expect — two entries for the same stackable item being the standard
 * result.
 *
 * Here every operation is `GameSave -> GameSave` and stacking is enforced in one place ([add]), so
 * the duplicate-entry state is unreachable. There is no `Inventory` object holding a bag, because
 * the bag is a field of the profile and a second owner for it is precisely the problem.
 *
 * `InventoryScreen.sortBag()` is [sorted], applied by [add] so the bag is always in order rather
 * than being re-sorted by whoever remembers to.
 */
// TooManyFunctions: eleven, and they are one subject — a bag, and the four things that can happen
// to what is in it. `priceOf` is the eleventh and is deliberately not folded into `sell`: the bag
// screen has to show a price before the player commits to it, and a screen computing it separately
// is how a button comes to promise one number and pay another.
@Suppress("TooManyFunctions")
object Inventory {
    /**
     * Adds [item] to the bag, merging into an existing stack when it is [Item.stackable].
     *
     * Merging is keyed on the item's **identity minus its stack**: two `CardItem(13)` merge, a
     * `CardItem(13)` and a `CardItem(14)` do not. That is what `stackable` was for, and what the
     * AS3 never actually did — `Achievements.check()` and the shop both `push` unconditionally, so
     * a second copy of a card item became a second row showing "1" rather than one row showing "2".
     */
    fun add(save: GameSave, item: Item): GameSave {
        if (item.stack <= 0) return save
        val bag = save.bag.toMutableList()
        val at = if (item.stackable) bag.indexOfFirst { stacksWith(it, item) } else -1
        if (at >= 0) {
            bag[at] = bag[at].withStack(bag[at].stack + item.stack)
        } else {
            bag += item
        }
        return save.copy(bag = sorted(bag))
    }

    /** Adds several items, one at a time, so each merges independently. */
    fun addAll(save: GameSave, items: List<Item>): GameSave =
        items.fold(save) { current, item -> add(current, item) }

    /**
     * Removes [count] of [item] from the bag, dropping the entry when the stack empties.
     *
     * A no-op if the bag holds fewer than [count]: partially fulfilling a removal is how an
     * inventory ends up disagreeing with the transaction that caused it.
     */
    fun remove(save: GameSave, item: Item, count: Int = 1): GameSave {
        require(count > 0) { "count must be positive, was $count" }
        val at = save.bag.indexOfFirst { stacksWith(it, item) }
        if (at < 0 || save.bag[at].stack < count) return save
        val bag = save.bag.toMutableList()
        val remaining = bag[at].stack - count
        if (remaining == 0) bag.removeAt(at) else bag[at] = bag[at].withStack(remaining)
        return save.copy(bag = bag)
    }

    /** How many of [item] the bag holds. */
    fun count(save: GameSave, item: Item): Int =
        save.bag.firstOrNull { stacksWith(it, item) }?.stack ?: 0

    /**
     * Sells [count] of [item] for what a shop pays.
     *
     * Refuses anything not [Item.sellable] and anything not in the bag, in both cases by returning
     * the profile unchanged.
     *
     * @param cards the card table, because **only it knows what a card is worth**. The price used
     *   to be `Item.value`, which a `CardItem` answered as `cardId * 4` — an arithmetic that made
     *   an FFVIII common worth more than an FFXIV legend and several shop rows profitable to buy
     *   and immediately resell. See [CardValue], and [priceOf] for what is paid now.
     */
    fun sell(save: GameSave, item: Item, cards: Map<Int, Card>, count: Int = 1): GameSave {
        if (!item.sellable || count(save, item) < count) return save
        return remove(save, item, count).withMgp(priceOf(item, cards) * count)
    }

    /**
     * What a shop pays for one [item], or 0 for something it will not buy.
     *
     * Split out of [sell] because the bag screen has to *show* the figure on the button before the
     * player commits to it, and a screen computing it a second way is how a button comes to promise
     * one number and pay another.
     */
    fun priceOf(item: Item, cards: Map<Int, Card>): Int = when {
        !item.sellable -> 0
        item is CardItem -> CardValue.resaleOf(item.cardId, cards)
        else -> item.value
    }

    /**
     * Uses one [item].
     *
     * - A **booster** is opened, the pack consumed, and a [CardItem] for **each** drawn card put
     *   in the bag — *not* the cards into the collection; see [ItemUse.PackOpened] for why that
     *   distinction is the whole point of a pack. `BoosterItem.open()` draws several ids from a
     *   fixed pool with a strong low-index bias, guaranteeing the last — see there.
     * - A **potion** raises its boon and is consumed.
     * - A **pouch** pays its MGP into the purse and is consumed. It is the one item whose whole
     *   purpose is to be opened; see [com.tripletriad.model.PouchItem] for why an auction's money
     *   waits in the bag rather than landing in the purse while nobody is looking.
     * - A **card item** adds its card to the collection and is consumed. `CardItem` is `useable` in
     *   the AS3, and this is what using it can only have meant.
     * - Anything else is [ItemUse.NotUseable] and the profile is untouched.
     *
     * A card already owned is still consumed, and [ItemUse.CardDrawn.wasNew] says so — it now means
     * "this is the first copy" rather than "this was not wasted". The AS3 inventory screen disables
     * Use on a card already owned (`InventoryScreen.as:111`) because a second copy did nothing;
     * that gate is removed with card copies, since a second copy is exactly what the player wants.
     *
     * @param random the booster draw. Injected so a drop is reproducible.
     */
    fun use(save: GameSave, item: Item, random: Random = Random.Default): ItemUse {
        if (!item.useable || count(save, item) < 1) return ItemUse.NotUseable(save)
        val consumed = remove(save, item)
        return when (item) {
            is BoosterItem -> {
                val drawn = item.open(random)
                ItemUse.PackOpened(
                    save = drawn.fold(consumed) { profile, id -> add(profile, CardItem(id)) },
                    cardIds = drawn,
                    newCardIds = drawn.filterNot(save::ownsCard).toSet(),
                )
            }

            is CardItem ->
                ItemUse.CardDrawn(
                    consumed.withCard(item.cardId),
                    item.cardId,
                    !save.ownsCard(item.cardId),
                )

            is PotionItem ->
                ItemUse.BoonRaised(consumed.copy(boons = consumed.boons.raised(item.modifier)))

            is PouchItem ->
                ItemUse.PouchOpened(consumed.withMgp(item.mgp), item.mgp, item.cardId)

            is MiscItem -> ItemUse.NotUseable(save)
        }
    }

    /**
     * The bag in display order: pouches, then card items by id, then boosters, potions, the rest.
     *
     * Pouches lead because they are the only entry that exists to be opened and then gone. A
     * player coming back to a sale that settled overnight should find the money first, not below
     * a column of packs.
     *
     * `InventoryScreen.sortBag()`'s job. The exact AS3 comparator is not reproduced — it sorts the
     * raw JSON objects on `type` as a string, which orders them `item-type-booster`,
     * `item-type-card`, `item-type-misc`, `item-type-potion`, i.e. alphabetically by an internal
     * constant. Cards before packs before potions is the order the inventory screen visibly groups
     * them in, and is what this produces.
     */
    fun sorted(bag: List<Item>): List<Item> = bag.sortedWith(
        compareBy(
            { section(it).ordinal },
            { withinSection(it) },
        ),
    )

    /** True when two entries are the same item and would share a stack. */
    private fun stacksWith(a: Item, b: Item): Boolean = a.withStack(1) == b.withStack(1)

    /**
     * The groups the inventory screen shows, in the order it shows them.
     *
     * An enum rather than a function returning 0..3, so the order is stated once, in one place, and
     * reordering the display means reordering these five lines.
     */
    private enum class Section { POUCHES, CARDS, BOOSTERS, POTIONS, OTHER }

    private fun section(item: Item): Section = when (item) {
        is PouchItem -> Section.POUCHES
        is CardItem -> Section.CARDS
        is BoosterItem -> Section.BOOSTERS
        is PotionItem -> Section.POTIONS
        is MiscItem -> Section.OTHER
    }

    /** Rank within a section: card id, then declaration order for the two enum-keyed kinds. */
    private fun withinSection(item: Item): Int = when (item) {
        is PouchItem -> item.cardId
        is CardItem -> item.cardId
        is BoosterItem -> item.boosterType.ordinal
        is PotionItem -> item.potionType.ordinal
        is MiscItem -> 0
    }
}
