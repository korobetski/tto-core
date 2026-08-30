package com.tripletriad.protocol

import com.tripletriad.data.ItemUse
import com.tripletriad.model.Item
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Naming an item in the bag — to use it, to sell it, or to throw it away.
 *
 * ### Why this endpoint exists at all
 *
 * Because opening a booster is a **dice roll**, and until now the client rolled it.
 * `Inventory.use(save, item, random)` takes a generator, and the inventory screen passed its own —
 * so a modified client could open a pack, dislike the cards, and open it again from the same save
 * until a legend fell out. Nothing about the resulting profile was detectably wrong: the pack was
 * really owned, the cards really are in that pack's pool, and the arithmetic really adds up. There
 * is no server-side check that catches it, which is exactly why the roll has to move rather than be
 * audited.
 *
 * The same argument the refereed match makes, one screen along: whoever holds the randomness
 * decides the outcome, so it must not be the party the outcome is worth something to.
 *
 * ### Why the request names an `Item` and not a bag index
 *
 * An index is a claim about the server's bag that the client cannot make safely — the two can
 * disagree, and "slot 3" resolving to a different item on each side is how a request to open a
 * common pack opens a metal one. The item names itself.
 *
 * It is also safe to take at its word, which is not obvious and worth stating: `Inventory.remove`
 * takes its count as a **separate parameter**, and `stacksWith` compares items with the stack
 * normalised to one. So `stack` on an arriving item is ignored entirely — it identifies *which*
 * item, never how many. A client sending `stack: 999` consumes one pack, and one sending `stack:
 * -1` cannot add anything.
 *
 * Possession is still checked, by the same guard that has always checked it: `Inventory.use`
 * returns [ItemEffect.NotUseable] for an item the bag does not hold.
 *
 * ### One type for the three things you can do to a bag item
 *
 * Use it, sell it, discard it. The payload is identical for all three — *which* item — and the verb
 * is the URL, which is what a verb is for. Three types differing in nothing but their name would be
 * three places to fix the day an item gains a field.
 *
 * @property operationId the client's own id for this attempt, so a retry does not open a second
 *   pack or sell a second card. See [Idempotent].
 */
@Serializable
data class BagItemRequest(
    val item: Item,
    override val operationId: String,
) : Idempotent

/**
 * A request that must not happen twice, however many times it arrives.
 *
 * ### Why every intent endpoint needs one
 *
 * Because the failure this design introduces is worse than the one it fixes. Moving the shop and
 * the bag server-side means the client no longer computes the result — it asks for it — and an ask
 * that times out has an unknown outcome. Retrying it is the only sensible thing a client can do,
 * and without an id that retry buys a second booster with money the player only meant to spend
 * once. Replacing a cheat with a way to lose a purchase in a tunnel is not an improvement.
 *
 * So the client mints an id per *intent* — not per attempt — and the server records it against the
 * account with the answer it gave. A repeat of a known id returns **the original answer**, cards
 * and all, rather than doing the work again. That is what makes a retry safe and what makes the
 * reveal animation show the same pack twice instead of two different ones.
 *
 * An interface rather than a field repeated on eight request types, so that the server can take
 * "anything idempotent" and a new endpoint cannot forget.
 */
interface Idempotent {
    /** Unique per intent, opaque to the server, and stable across retries of that intent. */
    val operationId: String
}

/**
 * What using an item did — [ItemUse] with the profile taken out.
 *
 * A parallel hierarchy rather than making [ItemUse] itself serializable, for one reason: every
 * `ItemUse` carries the whole resulting `GameSave`, and the response already carries it once inside
 * [ItemUsed.player]. Sending a profile twice to say one thing is the sort of payload that is
 * cheaper to fix now than after two clients have shipped reading it.
 *
 * The mapping is stated once, in [effect], so the two cannot drift.
 */
@Serializable
sealed interface ItemEffect {
    /**
     * A pack was opened.
     *
     * @property cardIds every card it held, **in reveal order** — the guaranteed one last, as
     *   `BoosterItem.open` decides. The order is the animation's, so it has to survive the wire.
     * @property newCardIds those the profile did not already own. A set, so a pack holding two
     *   copies of a card the player lacked reports one new card and not two.
     */
    @Serializable
    @SerialName("pack-opened")
    data class PackOpened(
        val cardIds: List<Int>,
        val newCardIds: Set<Int> = emptySet(),
    ) : ItemEffect

    /** A card item was used and entered the collection. */
    @Serializable
    @SerialName("card-drawn")
    data class CardDrawn(val cardId: Int, val wasNew: Boolean = false) : ItemEffect

    /** A potion was drunk and raised a boon. */
    @Serializable
    @SerialName("boon-raised")
    data object BoonRaised : ItemEffect

    /**
     * An auction pouch was opened and paid out.
     *
     * @property mgp what went into the purse. On the wire even though the profile beside it
     *   already shows the new balance: a client cannot tell a payout from any other change by
     *   diffing two purses, and "+4 200 MGP" is the whole of what the player opened it to see.
     * @property cardId the card that was sold, so the confirmation can name it.
     */
    @Serializable
    @SerialName("pouch-opened")
    data class PouchOpened(val mgp: Int, val cardId: Int) : ItemEffect

    /**
     * Nothing happened: the item does nothing, or the bag does not hold it.
     *
     * The two are deliberately one answer, because they are one answer to the *client*: it asked
     * for something the server will not do, and the bag it is about to be sent says which. A
     * separate refusal would be a second way to say "your bag is not what you thought", and the
     * profile in the response already says it better.
     */
    @Serializable
    @SerialName("nothing")
    data object NotUseable : ItemEffect
}

/** This outcome as it travels, dropping the profile the response carries separately. */
fun ItemUse.effect(): ItemEffect = when (this) {
    is ItemUse.PackOpened -> ItemEffect.PackOpened(cardIds, newCardIds)
    is ItemUse.CardDrawn -> ItemEffect.CardDrawn(cardId, wasNew)
    is ItemUse.BoonRaised -> ItemEffect.BoonRaised
    is ItemUse.PouchOpened -> ItemEffect.PouchOpened(mgp, cardId)
    is ItemUse.NotUseable -> ItemEffect.NotUseable
}

/**
 * What the server did, and the profile it wrote.
 *
 * The profile is sent back rather than left for the client to recompute — the same bargain
 * `MatchReceipt` strikes. A client that recomputed would be a client that could disagree, and the
 * point of moving the roll was to end that.
 */
@Serializable
data class ItemUsed(val player: PlayerState, val effect: ItemEffect)

/**
 * Buying from the shop.
 *
 * ### The price is not on the wire, and that is the whole point
 *
 * A client names **what** it wants and the format it is shopping in; the server looks the offer up
 * in its own `ShopCatalog` and charges what *it* says. Sending a price would be asking the buyer
 * what they feel like paying, and validating a sent price against the catalogue would be the same
 * thing with an extra step — the catalogue is the answer either way, so it may as well be the only
 * one consulted.
 *
 * @property formatId which shelf. Offers are filtered by format — a pack of cards a format does not
 *   admit is not for sale in it — so the same item can be on one shelf and not another.
 * @property item the offer's item, matched against the shelf with its stack normalised. `stack` is
 *   an identity here as it is everywhere else on this wire, never a quantity: one tap buys one.
 */
@Serializable
data class BuyRequest(
    val item: Item,
    val formatId: String,
    override val operationId: String,
) : Idempotent

/**
 * Selling a card out of the collection.
 *
 * By id, and the price is the server's — `CardValue.resaleOf` reads it off the card table, because
 * a card is worth what its rarity says. Selling a card the profile does not hold changes nothing.
 */
@Serializable
data class SellCardRequest(
    val cardId: Int,
    override val operationId: String,
) : Idempotent

/**
 * Claiming the box a profile is owed.
 *
 * No payload beyond the id. Whether anything is owed is `StarterPack.isOwedBy`'s answer and not the
 * client's, and which box it is comes from the server's own catalogue — a client that could name
 * the pack could name a better one.
 *
 * ### Why a repair needs an endpoint at all
 *
 * Because it grants **cards**, and `GameSave.cards` stopped being the client's to write. Everything
 * else on that list closed the moment its last client-side writer became an intent; this was the
 * last one, and until it moved, closing `cards` would have meant a profile that sold everything
 * could no longer repair itself — silently, since the write would simply be discarded. A forgery
 * left open is bad; a repair that quietly does nothing is worse.
 */
@Serializable
data class ClaimStarterRequest(override val operationId: String) : Idempotent

/**
 * Paying to enter a campaign ladder.
 *
 * The fee is **taken on the way in and never given back** — a defeat costs another entry to try
 * again, which is the whole of what makes a ladder a stake. So it is a spend like any other and has
 * to be the server's, or the ladder is free.
 *
 * @property campaignKey which ladder. The fee comes from the server's campaign catalogue.
 */
@Serializable
data class EnterCampaignRequest(
    val campaignKey: String,
    override val operationId: String,
) : Idempotent
