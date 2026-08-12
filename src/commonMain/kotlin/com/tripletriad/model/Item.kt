package com.tripletriad.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Which of a potion's two boons it raises. `PotionItem.as:18-23` writes `{type:'XP'|'MGP',
 * value:n}`.
 *
 * `Save.DATAS.BOONS` has a third slot, `LUCK`, that **no potion grants** — no `LUCK_BOOST_MOD`
 * exists and nothing writes it. It is modelled on [Boons] because the save file has the field, but
 * it has no member here because nothing can produce it.
 */
@Serializable
enum class BoonType {
    @SerialName("XP")
    XP,

    @SerialName("MGP")
    MGP,
}

/** What using a potion does: raise [type] by [value]. `PotionItem.modifier`. */
data class BoonModifier(val type: BoonType, val value: Int)

/** The five metal packs share one icon; only the four tribe packs have their own. */
private const val PACK_ICON = "booster_pack_icon"

/**
 * The six potions of `datas/PotionItem.as`.
 *
 * The serial names are the raw AS3 strings, which are also the i18n key stems (`STR_XP_BOOST`,
 * `STR_XP_BOOST_DESC`) and the values stored in `Save.DATAS.BAG` — so a bag written by the original
 * still parses. `NPCs.as` names them directly in reward tables (`potion:'MGP_BOOST'`), which is the
 * other reason they cannot be renamed.
 */
@Serializable
enum class PotionType(val modifier: BoonModifier) {
    @SerialName("SMALL_XP_BOOST")
    SMALL_XP(BoonModifier(BoonType.XP, 2)),

    @SerialName("SMALL_MGP_BOOST")
    SMALL_MGP(BoonModifier(BoonType.MGP, 2)),

    @SerialName("XP_BOOST")
    XP(BoonModifier(BoonType.XP, 5)),

    @SerialName("MGP_BOOST")
    MGP(BoonModifier(BoonType.MGP, 5)),

    @SerialName("BIG_XP_BOOST")
    BIG_XP(BoonModifier(BoonType.XP, 10)),

    @SerialName("BIG_MGP_BOOST")
    BIG_MGP(BoonModifier(BoonType.MGP, 10)),
    ;

    /** `PotionItem.as:35` — `i18n.gettext('STR_' + _potionType)`. */
    val nameKey: String get() = "STR_$as3Name"

    /**
     * `PotionItem.as:37` asks for `STR_<type>_DESC` and **no bundle in the original defines one** —
     * not `en_US`, not any of the other three. The AS3 shop therefore drew the raw key under every
     * potion it sold, and so did this port until the sentences were written for it.
     *
     * `APP_` rather than `STR_`, for the reason `APP_MATCHES` carries: a key the port authors is
     * app-owned whatever the original happened to ask for it by.
     */
    val descriptionKey: String get() = "APP_${as3Name}_DESC"

    /** The AS3 constant's value, which the serial name also uses. */
    private val as3Name: String
        get() = when (this) {
            SMALL_XP -> "SMALL_XP_BOOST"
            SMALL_MGP -> "SMALL_MGP_BOOST"
            XP -> "XP_BOOST"
            MGP -> "MGP_BOOST"
            BIG_XP -> "BIG_XP_BOOST"
            BIG_MGP -> "BIG_MGP_BOOST"
        }
}

/**
 * The booster packs, each with its fixed card pool and its size.
 *
 * ### The nine FFXIV packs
 *
 * `pool` is transcribed verbatim from the `*_BOOSTER_CARDS` constants of `datas/BoosterItem.as`
 * (`:19-27`), not re-derived from rarity or type — the lists are hand-picked and several are
 * inconsistent with any rule you might infer (card 51 appears in Gold, Platinum *and* Garlean;
 * Silver and Scion overlap on 19, 50 and 56).
 *
 * The AS3 pools named **ids within whichever table `MODE` selected**, so opening a bronze booster
 * on an `ff8_` profile yielded ff8 card 4 rather than ff14 card 4. Ids are global now and `MODE` is
 * gone, so a pool names exactly the cards it names, and the three FFVIII packs below are the packs
 * the FFVIII shelf never had.
 *
 * ### The three FFVIII packs
 *
 * New, and authored rather than transcribed: `shopScreen.as`'s FFVIII shelf sells five single cards
 * and no packs at all. They follow the structure the FFVIII card table already has, which is the
 * source game's — a hundred and ten cards in ten levels, the top two of which are the Guardian
 * Forces and the cast:
 *
 * - [GALBADIAN] — the Galbadian military and its machines, drawn from levels 3 to 8.
 * - [GUARDIAN_FORCE] — the sixteen GF cards, level 9. `Boko` and `Angelo` are left out: they are
 *   level 8 companions rather than Guardian Forces, and a pack that promises GFs should hold GFs.
 * - [CHARACTER] — the eleven level-10 character cards, Squall last. The most expensive thing in
 *   the shop, and the only pack whose every card is a five-star.
 *
 * ### What a pack holds
 *
 * [size] cards, not one. The AS3 pack was a single draw — you paid 520 MGP, you got one card, and
 * whether it was worth it was decided before the animation finished. A pack is a ritual in every
 * game that sells one, and a ritual needs more than one beat.
 *
 * The last card is **always** drawn from the top of the pool ([rareFrom] onwards), which is the
 * mechanic that makes opening one worth doing: the first cards are what you expected and the last
 * is the question. See [BoosterItem.open].
 */
@Serializable
enum class BoosterType(
    val pool: List<Int>,
    val iconId: String,
    val size: Int = BOOSTER_STANDARD_SIZE,
) {
    @SerialName("BRONZE_BOOSTER")
    BRONZE(listOf(260, 261, 264, 268, 283, 294), PACK_ICON),

    @SerialName("SILVER_BOOSTER")
    SILVER(listOf(270, 271, 272, 273, 275, 306, 312, 313), PACK_ICON),

    @SerialName("GOLD_BOOSTER")
    GOLD(listOf(284, 285, 286, 290, 307, 314, 324, 332), PACK_ICON),

    @SerialName("MITHRIL_BOOSTER")
    MITHRIL(listOf(295, 364, 365, 369, 379, 308, 326, 328, 329), PACK_ICON),

    @SerialName("PLATINUM_BOOSTER")
    PLATINUM(listOf(307, 311, 313, 319, 325, 327, 333, 336), PACK_ICON),

    @SerialName("BEAST_BOOSTER")
    BEAST(listOf(270, 271, 272, 273, 274, 283, 291, 292, 293, 338, 339, 373, 384), "beast_booster"),

    @SerialName("PRIMAL_BOOSTER")
    PRIMAL(listOf(296, 297, 298, 299, 308, 309, 310, 311, 317, 353, 354, 393), "primal_booster"),

    @SerialName("SCION_BOOSTER")
    SCION(listOf(275, 378, 302, 304, 305, 306, 312, 315, 316, 394), "scion_booster"),

    @SerialName("GARLEAN_BOOSTER")
    GARLEAN(listOf(287, 288, 303, 307, 320, 375), "garlean_booster"),

    /**
     * Galbadia's army: two robots, two soldiers, and the machines sent after Squall's party.
     *
     * Written weakest-first like every pool above, so [BoosterItem.open]'s bias reads the same way.
     */
    @SerialName("GALBADIAN_BOOSTER")
    GALBADIAN(
        listOf(561, 562, 567, 568, 569, 573, 575, 570, 583, 586),
        PACK_ICON,
    ),

    /** The sixteen Guardian Forces, Eden last: the strongest cards in the set bar the cast. */
    @SerialName("GUARDIAN_FORCE_BOOSTER")
    GUARDIAN_FORCE(
        listOf(595, 596, 597, 598, 599, 600, 601, 602, 603, 604, 605, 606, 607, 608, 609, 610, 611),
        PACK_ICON,
        size = BOOSTER_PREMIUM_SIZE,
    ),

    /** The eleven level-10 cards. Squall last, because there is nothing above him. */
    @SerialName("CHARACTER_BOOSTER")
    CHARACTER(
        listOf(612, 613, 614, 615, 616, 617, 618, 619, 620, 621, 622),
        PACK_ICON,
        size = BOOSTER_PREMIUM_SIZE,
    ),
    ;

    init {
        require(size in 1..pool.size) { "$name holds $size cards from a pool of ${pool.size}" }
    }

    /**
     * Where the guaranteed slot starts drawing from — the top third of the pool, rounded up.
     *
     * A third rather than a fixed count so it scales with the pool: the six-card Bronze pack
     * guarantees one of its best two, the seventeen-card Guardian Force pack one of its best six.
     * Fixing it at "the last two" would make a long pool's guarantee vanishingly narrow and a short
     * pool's guarantee cover a third of it.
     */
    val rareFrom: Int get() = pool.size - ((pool.size + 2) / 3)

    /** `BoosterItem.as:49` — `i18n.gettext('STR_' + _boosterType)`. */
    val nameKey: String get() = "STR_$as3Name"

    /** `BoosterItem.as:51`, app-owned for the reason [PotionType.descriptionKey] gives. */
    val descriptionKey: String get() = "APP_${as3Name}_DESC"

    private val as3Name: String get() = "${name}_BOOSTER"

    companion object {
        /** What an ordinary pack holds. Five is a hand, which is the unit this game thinks in. */
        const val STANDARD_SIZE: Int = BOOSTER_STANDARD_SIZE

        /** The packs whose every card is worth having; a bigger one would be a giveaway. */
        const val PREMIUM_SIZE: Int = BOOSTER_PREMIUM_SIZE
    }
}

// File-private, because an enum entry's constructor runs before its own companion exists and the
// three FFVIII packs name their size in that constructor. Re-exposed on the companion above so a
// caller still reads `BoosterType.STANDARD_SIZE`.
private const val BOOSTER_STANDARD_SIZE = 5
private const val BOOSTER_PREMIUM_SIZE = 3

/**
 * Something in the player's bag.
 *
 * ### Display split out
 *
 * `datas/Item.as` **extends `starling.display.Sprite`**: it owns an `ItemIcon` child and a
 * `TouchEvent` listener, and dispatches `Event.TRIGGERED` when tapped. None of that is here. What
 * remains is the state the original persisted plus the constants its constructors assigned, and the
 * icon is named rather than held — a composable resolves [iconId] to a drawable.
 *
 * ### The flags are derived, not stored
 *
 * `_sellable`, `_stackable`, `_useable`, `_dropable` and `_value` look like fields but every
 * subclass assigns them **fixed values in its constructor** and nothing ever changes them
 * afterwards. So they are computed properties per subtype here. This is not a simplification that
 * loses information: it makes it impossible to construct a booster that is sellable, which the AS3
 * type system allowed and the AS3 code never did.
 *
 * ### Wire format
 *
 * The discriminator is `type` and the subclass serial names are the `ITEM_TYPE_*` constants, so
 * [Item] round-trips exactly the objects `__toJSON()` produced and `Item.itemize` consumed:
 * `{"type":"item-type-card","card":13,"stack":2}`. That is what sits in `Save.DATAS.BAG`.
 *
 * `ITEM_TYPE_ACCESSORY` (`Item.as:19`) has no subclass here because it has none there either — the
 * constant is declared and never used. `ITEM_TYPE_MISC` is [MiscItem], the fallback `itemize`
 * returns for an unrecognised type.
 */
@Serializable
sealed class Item {
    /** How many are held. `Item.as:41` defaults it to 1. */
    abstract val stack: Int

    /** Texture name for the bag icon. */
    abstract val iconId: String

    /** i18n key for the tooltip text. */
    abstract val descriptionKey: String

    /** MGP the shop pays, per unit. Only [CardItem] has a non-zero one. */
    abstract val value: Int

    abstract val sellable: Boolean
    abstract val stackable: Boolean
    abstract val useable: Boolean
    abstract val dropable: Boolean

    /** The same entry with [stack] set to [count]. */
    abstract fun withStack(count: Int): Item

    protected fun requireValidStack() {
        require(stack >= 0) { "stack must not be negative, was $stack" }
    }
}

/**
 * A card, held as an inventory entry rather than in the collection. `datas/CardItem.as`.
 *
 * Using one adds [cardId] to `Save.DATAS.CARDS`; that is the shop and reward path by which a card
 * enters a collection.
 *
 * [iconId] and the display name both need the card's own record — the icon is `card_r{rarity}_icon`
 * and the name is the card's name composed with `STR_CARD` — so neither is available from this
 * object alone. [iconFor] takes the card; the name is left to the UI, because `CardItem.as:19-22`
 * orders the two words differently in French and word order is not a data-layer concern.
 */
@Serializable
@SerialName("item-type-card")
data class CardItem(
    @SerialName("card") val cardId: Int,
    override val stack: Int = 1,
) : Item() {
    init {
        requireValidStack()
        require(cardId > 0) { "card id must be positive, was $cardId" }
    }

    /** `CardItem.as:23` needs the card's rarity, which only the catalog knows. */
    fun iconFor(card: Card): String = "card_r${card.rarity}_icon"

    /**
     * A placeholder, used when the card is not resolvable. The real icon is [iconFor].
     *
     * Rarity 1 rather than an "unknown" texture: there is no such texture, and a wrong star count
     * on an unresolvable card is a better failure than a missing image.
     */
    override val iconId: String get() = "card_r1_icon"

    override val descriptionKey: String get() = "APP_CARD_ITEM_DESC"

    /** `CardItem.as:25` — `value = _cardId * 4`. Later cards are worth more because ids ascend. */
    override val value: Int get() = cardId * MGP_PER_ID

    override val sellable: Boolean get() = true
    override val stackable: Boolean get() = true
    override val useable: Boolean get() = true
    override val dropable: Boolean get() = true

    override fun withStack(count: Int): CardItem = copy(stack = count)

    private companion object {
        const val MGP_PER_ID = 4
    }
}

/**
 * A booster pack. `datas/BoosterItem.as`.
 */
@Serializable
@SerialName("item-type-booster")
data class BoosterItem(
    @SerialName("booster") val boosterType: BoosterType,
    override val stack: Int = 1,
) : Item() {
    init {
        requireValidStack()
    }

    override val iconId: String get() = boosterType.iconId
    override val descriptionKey: String get() = boosterType.descriptionKey

    /** `BoosterItem.as:52` — `value = 0`. Boosters cannot be sold, so the price is unused. */
    override val value: Int get() = 0

    override val sellable: Boolean get() = false
    override val stackable: Boolean get() = true
    override val useable: Boolean get() = true
    override val dropable: Boolean get() = false

    override fun withStack(count: Int): BoosterItem = copy(stack = count)

    /**
     * Opens the pack: [BoosterType.size] card ids, the last of them the good one.
     *
     * ### The draw itself is the AS3's, bias included
     *
     * `BoosterItem.open()` (`:60-65`) verbatim:
     *
     * ```actionscript
     * var regularRand:Number = Math.random() * uint(this._boosterCards.length - 1);
     * var downgrade:Number    = Math.random() * 1.25;
     * var fin:uint = Math.min(Math.round(regularRand * downgrade), this._boosterCards.length - 1);
     * ```
     *
     * Two uniform draws multiplied together, so the result is heavily weighted towards **index 0**
     * and the last entry is nearly unreachable — with a 6-card pool the mean index is about 1.6 of
     * 5. Since the pools are written best-last, that is the rarity curve, and it is intentional in
     * the original however oddly expressed. Reproduced rather than replaced.
     *
     * ### What is new is how many times it runs
     *
     * The AS3 pack was **one** draw. You paid 520 MGP, one card came out, and whether the purchase
     * was worth making was settled before the animation finished — which is why nothing in the
     * original made opening a pack an event. A pack is a ritual in every game that sells one.
     *
     * So: `size - 1` ordinary draws over the whole pool, then **one draw restricted to
     * [BoosterType.rareFrom] onwards**. Returned in that order, worst prospect first, which is the
     * order the reveal flips them in — the first cards are what you expected and the last is the
     * question. Take the list as given; shuffling it would throw away the only structure it has.
     *
     * Duplicates are possible and are not a defect: a second copy of a card is a card a player
     * wants, since `GameSave` counts copies and a deck may field them.
     *
     * @param random injected so drops are reproducible in tests.
     */
    fun open(random: Random = Random.Default): List<Int> {
        val ordinary = List(boosterType.size - 1) { drawFrom(boosterType.pool, random) }
        return ordinary + drawFrom(boosterType.pool.drop(boosterType.rareFrom), random)
    }

    private fun drawFrom(pool: List<Int>, random: Random): Int {
        val last = pool.lastIndex
        val regular = random.nextDouble() * last
        val downgrade = random.nextDouble() * DOWNGRADE_CEILING
        return pool[min((regular * downgrade).roundToInt(), last)]
    }

    private companion object {
        const val DOWNGRADE_CEILING = 1.25
    }
}

/**
 * A boon potion. `datas/PotionItem.as`.
 */
@Serializable
@SerialName("item-type-potion")
data class PotionItem(
    @SerialName("potion") val potionType: PotionType,
    override val stack: Int = 1,
) : Item() {
    init {
        requireValidStack()
    }

    /** `PotionItem.as:36`. Note the AS3 texture name is camelCase where every other one is not. */
    override val iconId: String get() = "potionItem"

    override val descriptionKey: String get() = potionType.descriptionKey
    override val value: Int get() = 0

    override val sellable: Boolean get() = false
    override val stackable: Boolean get() = true
    override val useable: Boolean get() = true
    override val dropable: Boolean get() = false

    override fun withStack(count: Int): PotionItem = copy(stack = count)

    /** What using it raises. `PotionItem.modifier`. */
    val modifier: BoonModifier get() = potionType.modifier
}

/**
 * Anything else. The `else` branch of `Item.itemize` (`Item.as:174`), which returns a bare `Item`
 * with the base class's defaults.
 *
 * Reachable in practice from a bag entry whose `type` this build does not know — a save written by
 * a newer version, or the unused `item-type-accessory`. Keeping it as a real member means such an
 * entry survives a load/save cycle as an inert row instead of being dropped.
 */
@Serializable
@SerialName("item-type-misc")
data class MiscItem(
    override val stack: Int = 1,
) : Item() {
    init {
        requireValidStack()
    }

    /** `Item.as:36` constructs its icon with the booster texture, whatever the item is. */
    override val iconId: String get() = PACK_ICON

    override val descriptionKey: String get() = ""

    /** `Item.as:42` — the base default is 1, not 0. */
    override val value: Int get() = 1

    override val sellable: Boolean get() = true
    override val stackable: Boolean get() = true

    /** `Item.as:45` — the base class is the one thing that is *not* useable. */
    override val useable: Boolean get() = false
    override val dropable: Boolean get() = true

    override fun withStack(count: Int): MiscItem = copy(stack = count)
}
