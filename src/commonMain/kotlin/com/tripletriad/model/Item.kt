package com.tripletriad.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
 * The booster packs, each with its fixed card pool and the per-card weight [BoosterItem.open]
 * draws from.
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
 * - [MONSTER] — the bestiary of levels 1 and 2. The cheapest pack in the game.
 * - [GALBADIAN] — the Galbadian military and its machines, drawn from levels 3 to 8.
 * - [FIEND] — the bosses of levels 5 to 7, sharing no card with [GALBADIAN].
 * - [COMPANION] — the five level-8 companions, which [GUARDIAN_FORCE] deliberately excludes.
 * - [GUARDIAN_FORCE] — the sixteen GF cards, level 9. `Boko` and `Angelo` are left out: they are
 *   level 8 companions rather than Guardian Forces, and a pack that promises GFs should hold GFs.
 * - [CHARACTER] — the eleven level-10 character cards, Squall last. The most expensive thing in
 *   the shop, and the only pack whose every card is a five-star.
 *
 * Six against the FFXIV shelf's nine, and covering the set's whole ladder rather than only its top:
 * the first three FFVIII packs were all premium, which left a new FFVIII collection nothing to buy.
 *
 * ### What a pack holds
 *
 * **One card, by default.** The AS3 pack was a single draw — you paid 520 MGP, you got one card,
 * and whether it was worth it was decided before the animation finished — and so is the real
 * game's: every "Triad Card" sold by the Manderville Gold Saucer's Triple Triad Trader hands over
 * exactly one random card from its pool, never several. Every pack shipped today draws
 * [cardCount] `1` for exactly that reason. See [BoosterItem.open].
 *
 * [cardCount] itself is not pinned to 1 — a pack that draws several cards is a mechanic this game
 * has had before (the old AS3-era "several ordinary draws plus one guaranteed-rarity draw" packs)
 * and may have again, so the capacity to draw more than one card independently stays general
 * rather than being deleted along with the formula that used to compute it.
 *
 * ### [weights]: authored, not computed
 *
 * Used to be a formula — two uniform draws multiplied, biased hard toward the front of the pool —
 * applied identically to every pack. [BRONZE], [SILVER], [GOLD], [MITHRIL] and [PLATINUM] are real
 * FFXIV packs, and arrtripletriad.com publishes the community-reported chance of drawing each of
 * their cards, so those five now carry the **real numbers** instead:
 *
 * - Where the site reports a rate for a card in this project's (smaller) pool, that rate is used
 *   as-is — [weights] need not sum to any particular total, only their *ratio* matters, so a pool
 *   that is a strict subset of the site's own renormalises itself for free the moment
 *   [BoosterItem.open] or [BoosterPricing] divides by the sum.
 * - Where the site has no report at all for a card in the pool — every entry of [PLATINUM], the
 *   most expensive pack, whose own drop reports are all still `?%` — the weight is derived from
 *   [CardValue.MGP_BY_RARITY] instead, inverted (a cheaper rarity is a commoner card) and left
 *   unnormalised for the same reason: three 4★ cards at weight 15 and five 5★ at weight 8 is the
 *   ratio `1/2000 : 1/3750` reduced to whole numbers.
 *
 * [BEAST], [PRIMAL], [SCION], [GARLEAN] and the six FFVIII packs are not real packs — no site
 * documents them, because they do not exist outside this game — so their weights are simply the
 * *old* formula's output, computed once and frozen as data rather than recomputed every draw. The
 * shape a player already knows does not change; only where the numbers live does.
 */
@Serializable
enum class BoosterType(
    val pool: List<Int>,
    val weights: List<Double>,
    val iconId: String,
    /** How many cards one pack draws. `1` for every pack currently sold — see the class doc. */
    val cardCount: Int = 1,
) {
    /** Real FFXIV rates (arrtripletriad.com), renormalised to this pool's own six cards. */
    @SerialName("BRONZE_BOOSTER")
    BRONZE(
        pool = listOf(260, 261, 264, 268, 283, 294),
        weights = listOf(16.67, 20.0, 6.67, 3.33, 10.0, 13.33),
        iconId = PACK_ICON,
    ),

    /** Real FFXIV rates, renormalised to this pool's own eight cards. */
    @SerialName("SILVER_BOOSTER")
    SILVER(
        pool = listOf(270, 271, 272, 273, 275, 306, 312, 313),
        weights = listOf(14.29, 15.24, 20.0, 21.9, 11.43, 12.38, 0.95, 3.81),
        iconId = PACK_ICON,
    ),

    /** Real FFXIV rates, renormalised to this pool's own eight cards. */
    @SerialName("GOLD_BOOSTER")
    GOLD(
        pool = listOf(284, 285, 286, 290, 307, 314, 324, 332),
        weights = listOf(22.58, 22.55, 11.99, 19.84, 4.83, 5.11, 0.32, 0.55),
        iconId = PACK_ICON,
    ),

    /** Real FFXIV rates. The one pack whose full site pool matches this one card for card. */
    @SerialName("MITHRIL_BOOSTER")
    MITHRIL(
        pool = listOf(295, 364, 365, 369, 379, 308, 326, 328, 329),
        weights = listOf(14.63, 4.88, 31.71, 14.63, 4.88, 21.95, 2.44, 2.44, 2.44),
        iconId = PACK_ICON,
    ),

    /** No site report exists for any of these eight — see the class doc's rarity fallback. */
    @SerialName("PLATINUM_BOOSTER")
    PLATINUM(
        pool = listOf(307, 311, 313, 319, 325, 327, 333, 336),
        weights = listOf(15.0, 15.0, 15.0, 8.0, 8.0, 8.0, 8.0, 8.0),
        iconId = PACK_ICON,
    ),

    /** Not a real pack — frozen output of the old front-loaded formula. */
    @SerialName("BEAST_BOOSTER")
    BEAST(
        pool = listOf(270, 271, 272, 273, 274, 283, 291, 292, 293, 338, 339, 373, 384),
        weights = listOf(
            0.14671, 0.18355, 0.13503, 0.10761, 0.08829,
            0.07335, 0.06116, 0.05087, 0.04195, 0.03409,
            0.02706, 0.0207, 0.02963,
        ),
        iconId = "beast_booster",
    ),

    /** Not a real pack — frozen output of the old front-loaded formula. */
    @SerialName("PRIMAL_BOOSTER")
    PRIMAL(
        pool = listOf(296, 297, 298, 299, 308, 309, 310, 311, 317, 353, 354, 393),
        weights = listOf(
            0.15688, 0.19391, 0.14098, 0.11106,
            0.08999, 0.07369, 0.0604, 0.04916,
            0.03944, 0.03086, 0.02319, 0.03044,
        ),
        iconId = "primal_booster",
    ),

    /** Not a real pack — frozen output of the old front-loaded formula. */
    @SerialName("SCION_BOOSTER")
    SCION(
        pool = listOf(275, 378, 302, 304, 305, 306, 312, 315, 316, 394),
        weights = listOf(
            0.18282, 0.21916, 0.15447, 0.1179, 0.09215,
            0.07223, 0.05598, 0.04225, 0.03036, 0.03266,
        ),
        iconId = "scion_booster",
    ),

    /** Not a real pack — frozen output of the old front-loaded formula. */
    @SerialName("GARLEAN_BOOSTER")
    GARLEAN(
        pool = listOf(287, 288, 303, 307, 320, 375),
        weights = listOf(0.28206, 0.30045, 0.18401, 0.11818, 0.07182, 0.04348),
        iconId = "garlean_booster",
    ),

    /**
     * Galbadia's army: two robots, two soldiers, and the machines sent after Squall's party.
     *
     * Written weakest-first like every pool above, so the frozen weights' bias reads the same way.
     * Not a real pack — frozen output of the old front-loaded formula.
     */
    @SerialName("GALBADIAN_BOOSTER")
    GALBADIAN(
        pool = listOf(2097, 2098, 2103, 2104, 2105, 2109, 2111, 2106, 2119, 2122),
        weights = listOf(
            0.18282, 0.21916, 0.15447, 0.1179, 0.09215,
            0.07223, 0.05598, 0.04225, 0.03036, 0.03266,
        ),
        iconId = PACK_ICON,
    ),

    /**
     * The sixteen Guardian Forces, Eden last: the strongest cards in the set bar the cast.
     *
     * Not a real pack — frozen output of the old front-loaded formula.
     */
    @SerialName("GUARDIAN_FORCE_BOOSTER")
    GUARDIAN_FORCE(
        pool = listOf(
            2131, 2132, 2133, 2134, 2135, 2136, 2137, 2138, 2139,
            2140, 2141, 2142, 2143, 2144, 2145, 2146, 2147,
        ),
        weights = listOf(
            0.11722, 0.15205, 0.11566, 0.09509, 0.0806, 0.0694, 0.06026, 0.05253, 0.04585,
            0.03995, 0.03468, 0.02991, 0.02556, 0.02155, 0.01784, 0.01439, 0.02746,
        ),
        iconId = PACK_ICON,
    ),

    /**
     * The bestiary: what a player fights before they fight anybody.
     *
     * Levels 1 and 2, and the cheapest thing on either shelf — the FFVIII answer to Bronze, which
     * a new character can afford after three or four matches. Not a real pack — frozen output of
     * the old front-loaded formula.
     */
    @SerialName("MONSTER_BOOSTER")
    MONSTER(
        pool = listOf(2049, 2051, 2054, 2058, 2063, 2067, 2080, 2082, 2084, 2088, 2094, 2100),
        weights = listOf(
            0.15688, 0.19391, 0.14098, 0.11106,
            0.08999, 0.07369, 0.0604, 0.04916,
            0.03944, 0.03086, 0.02319, 0.03044,
        ),
        iconId = PACK_ICON,
    ),

    /**
     * The bosses of levels 5 to 7 — the fights, rather than the wildlife or the army.
     *
     * Deliberately overlapping [GALBADIAN] on nothing: Galbadia's machines are its own pack, and a
     * player buying both should be buying two different things. Not a real pack — frozen output of
     * the old front-loaded formula.
     */
    @SerialName("FIEND_BOOSTER")
    FIEND(
        pool = listOf(
            2107, 2108, 2110, 2112, 2113, 2114, 2115,
            2116, 2117, 2118, 2120, 2121, 2123, 2124, 2125,
        ),
        weights = listOf(
            0.13015, 0.16614, 0.12455, 0.10104, 0.08449, 0.07168, 0.06123, 0.05241,
            0.04477, 0.03803, 0.032, 0.02655, 0.02158, 0.017, 0.02838,
        ),
        iconId = PACK_ICON,
    ),

    /**
     * The five level-8 companions: Chubby Chocobo, Angelo, Gilgamesh, Mini Mog and Chicobo.
     *
     * The cards [GUARDIAN_FORCE] leaves out, and the reason it does — they sit at level 8 with the
     * GFs' rarity and none of their standing. Not a real pack — frozen output of the old
     * front-loaded formula.
     */
    @SerialName("COMPANION_BOOSTER")
    COMPANION(
        pool = listOf(2126, 2127, 2128, 2129, 2130),
        weights = listOf(0.33026, 0.33093, 0.18538, 0.1031, 0.05033),
        iconId = PACK_ICON,
    ),

    /**
     * The eleven level-10 cards. Squall last, because there is nothing above him. Not a real
     * pack — frozen output of the old front-loaded formula.
     */
    @SerialName("CHARACTER_BOOSTER")
    CHARACTER(
        pool = listOf(2148, 2149, 2150, 2151, 2152, 2153, 2154, 2155, 2156, 2157, 2158),
        weights = listOf(
            0.16876, 0.20568, 0.14746, 0.11454,
            0.09136, 0.07344, 0.05881, 0.04645,
            0.03576, 0.02632, 0.03143,
        ),
        iconId = PACK_ICON,
    ),
    ;

    init {
        require(pool.isNotEmpty()) { "$name has no pool to draw from" }
        require(pool.size == weights.size) {
            "$name has ${pool.size} cards but ${weights.size} weights"
        }
        require(weights.all { it > 0.0 }) { "$name has a non-positive weight: $weights" }
        require(cardCount >= 1) { "$name draws $cardCount cards, must draw at least one" }
    }

    /** `BoosterItem.as:49` — `i18n.gettext('STR_' + _boosterType)`. */
    val nameKey: String get() = "STR_$as3Name"

    /** `BoosterItem.as:51`, app-owned for the reason [PotionType.descriptionKey] gives. */
    val descriptionKey: String get() = "APP_${as3Name}_DESC"

    private val as3Name: String get() = "${name}_BOOSTER"
}

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
 * Why a [CardItem] is in the bag, when that changes what it says.
 *
 * The entry behaves identically either way — a card to use, which puts it in the collection. What
 * differs is the sentence under it, and a card handed back by an auction that found no buyer is
 * worth telling apart from one that fell out of a pack: it is the only notice the seller gets that
 * their lot ended in nothing.
 *
 * This is part of the item's identity, so `Inventory.add` does **not** merge the two — a single row
 * carrying both descriptions could only show one of them, and it would be the wrong one half the
 * time.
 */
@Serializable
enum class CardOrigin(val descriptionKey: String) {
    /** Bought, drawn or awarded. The description every card item had before there was a second. */
    @SerialName("plain")
    PLAIN("APP_CARD_ITEM_DESC"),

    /** Handed back when a lot closed without a sale. "Invendue aux enchères". */
    @SerialName("auction-unsold")
    AUCTION_UNSOLD("APP_CARD_ITEM_UNSOLD_DESC"),
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
    @SerialName("origin") val origin: CardOrigin = CardOrigin.PLAIN,
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

    override val descriptionKey: String get() = origin.descriptionKey

    /**
     * **Not** what a card is worth. See [com.tripletriad.data.CardValue].
     *
     * `CardItem.as:25` said `value = _cardId * 4`, which was a rarity proxy while an id indexed one
     * ascending table and became nonsense the day ids went global: an FFVIII common outsold every
     * FFXIV rare because its id was a bigger number, and the shop sold several of those commons
     * for less than they resold for. Worth is a function of *rarity*, which only the card table
     * knows — which is why this cannot answer, and does not pretend to.
     *
     * Zero rather than a removal from [Item]: the field is what the three unsellable kinds say, and
     * a card's price now comes from `Inventory.sell`, which is handed the catalogue.
     */
    override val value: Int get() = 0

    override val sellable: Boolean get() = true
    override val stackable: Boolean get() = true
    override val useable: Boolean get() = true
    override val dropable: Boolean get() = true

    override fun withStack(count: Int): CardItem = copy(stack = count)
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
     * Opens the pack: [BoosterType.cardCount] card ids from [BoosterType.pool], each drawn
     * independently against [BoosterType.weights].
     *
     * ### The draw itself
     *
     * Every card is a separate weighted pick — `Random.nextDouble() * total` walked against the
     * cumulative weights until it lands, `total` being `weights.sum()` rather than a fixed 1.0,
     * since a pack's weights are card-specific numbers (a real drop-report percentage, or an
     * inverse-rarity ratio, or a frozen formula output — see [BoosterType]'s class doc) and are
     * never required to have been normalised by whoever authored them. The picks are independent
     * and with replacement: opening a pack that draws several can draw the same card twice, the
     * same as opening the same one-card pack twice in a row — a second copy is a card a player
     * wants, since `GameSave` counts copies and a deck may field them.
     *
     * ### Why every shipped pack still draws one
     *
     * A pack used to deal several cards unconditionally — `size - 1` ordinary draws over the pool
     * plus one draw restricted to a "guaranteed" top slice — a mechanic this game never actually
     * had. The AS3 pack was one draw, and so is the real FFXIV's: a Triad Card bought from the
     * Triple Triad Trader yields exactly one card, and arrtripletriad.com's own reported drop rates
     * confirm it — a flat percentage per card in a pack's pool, summing to 100%, which is only
     * sensible as a single categorical draw and not as five biased ones. Every [BoosterType] sold
     * today leaves [BoosterType.cardCount] at its default of 1 for that reason; the loop below is
     * general so a future pack can set it above 1 without this function changing again.
     *
     * @param random injected so drops are reproducible in tests.
     */
    fun open(random: Random = Random.Default): List<Int> =
        drawCards(boosterType.pool, boosterType.weights, boosterType.cardCount, random)
}

/**
 * The pure draw [BoosterItem.open] delegates to, kept free of [BoosterType] so the mechanism is
 * testable — including with a [count] above 1 — without shipping a multi-card pack to prove it
 * works. `internal` rather than `private` for exactly that: `commonTest` sits in the same module.
 */
internal fun drawCards(
    pool: List<Int>,
    weights: List<Double>,
    count: Int,
    random: Random,
): List<Int> {
    val total = weights.sum()
    return List(count) {
        val target = random.nextDouble() * total
        var cumulative = 0.0
        var picked = pool.last()
        for (index in weights.indices) {
            cumulative += weights[index]
            if (target < cumulative) {
                picked = pool[index]
                break
            }
        }
        picked
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

/**
 * The proceeds of an auction, as something to open rather than MGP already in the purse.
 *
 * ### Why the money arrives as an object
 *
 * A sale settles while the seller is asleep. Crediting the purse directly would mean a profile that
 * silently gained several thousand MGP between two launches with nothing to say where it came from
 * — and a settlement is exactly the moment worth telling somebody about. So the sweeper puts a
 * pouch in the bag, the bag screen shows it, and opening it is the player's acknowledgement. It is
 * the shape a bought card already had ([CardItem]), applied to the other half of the trade.
 *
 * ### Why it does not stack
 *
 * Two pouches are two sales, and merging them would produce one row worth the sum of both and named
 * after only one of the cards — after which `Inventory.remove` drops the single entry and one of
 * the two amounts is credited twice or not at all. [lotId] makes every pouch distinct even for two
 * sales of the same card at the same price, and [stackable] is false so nothing tries.
 *
 * @property mgp what the seller cleared: the winning bid, with the buyer's fee already taken off
 *   the buyer's side and the listing fee already spent when the lot opened. A settled figure, not
 *   one to recompute — see `AuctionRules`.
 * @property cardId the card that was sold, for the description ("...de la vente de la carte x").
 *   The card itself is long gone to the buyer; this is a label.
 * @property lotId the lot it settled. Carried so two pouches can never be the same object, and so a
 *   support question about one payout has something to look up.
 */
@Serializable
@SerialName("item-type-pouch")
data class PouchItem(
    @SerialName("mgp") val mgp: Int,
    @SerialName("card") val cardId: Int,
    @SerialName("lot") val lotId: String,
) : Item() {
    init {
        require(mgp > 0) { "a pouch must hold something, was $mgp" }
        require(cardId > 0) { "card id must be positive, was $cardId" }
        require(lotId.isNotBlank()) { "a pouch must name the lot it settled" }
    }

    /** Always one. See this class's KDoc for why there is no such thing as two of a pouch. */
    override val stack: Int get() = 1

    /**
     * The currency mark, which is the closest thing the imported sprite sheet holds to a purse.
     *
     * Named here rather than worked around in the UI: when a pouch sprite is imported this is the
     * one line that changes.
     */
    override val iconId: String get() = "PGS"

    /** Needs the card's name composed into it, the way [CardItem]'s does. Left to the UI. */
    override val descriptionKey: String get() = "APP_POUCH_ITEM_DESC"

    /** Zero, like everything the shop will not buy: [mgp] is what it holds, not what it fetches. */
    override val value: Int get() = 0

    /** Selling a purse for a fraction of what is inside it is a way to lose money by misclick. */
    override val sellable: Boolean get() = false
    override val stackable: Boolean get() = false
    override val useable: Boolean get() = true

    /** Discarding money somebody owed you is a support ticket, not a feature. */
    override val dropable: Boolean get() = false

    /**
     * Itself, whatever is asked.
     *
     * [stackable] is false, so `Inventory` never merges a pouch and never asks for a count other
     * than the one it has — the only call that reaches here is the `withStack(1)` identity check.
     */
    override fun withStack(count: Int): PouchItem = this
}
