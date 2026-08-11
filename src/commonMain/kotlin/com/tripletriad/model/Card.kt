package com.tripletriad.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Which side owns a card.
 *
 * The AS3 original stores this as a String on `tto.display.Card._color`
 * (`"GREY"` / `"BLUE"` / `"RED"`) and looks the background colour up by name via
 * `Card[_color + '_COLOR']`; see `sources/src/tto/display/Card.as:336`. `GREY` is
 * the unowned state used in the collection and deck-builder screens, which this
 * PoC does not have, so only the two playing sides are modelled here.
 */
enum class CardColor {
    BLUE,
    RED,
    ;

    fun opposite(): CardColor = if (this == BLUE) RED else BLUE
}

/**
 * The card's `type` field.
 *
 * One AS3 field, two meanings, depending on which collection the card belongs to:
 *
 * - the **ff14_** collection uses four FFXIV tribes ([BEAST], [GARLEAN],
 *   [PRIMALS], [SCIONS]), which drive `RULE_TYPE`;
 * - the **ff8_** collection uses the eight FF8 elements, which drive
 *   `RULE_ELEMENTAL`.
 *
 * Both are compared against `tile.element` by the same two lines of
 * `TTOCore.as:48-49`, so they share one field in the data and one enum here. The
 * serial names are the raw AS3 strings, which are also the texture names
 * (`type-beast`, `type-fire`, … in `sources/assets/card_types/`).
 */
@Serializable
enum class CardType {
    @SerialName("beast")
    BEAST,

    @SerialName("garlean")
    GARLEAN,

    @SerialName("primals")
    PRIMALS,

    @SerialName("scions")
    SCIONS,

    @SerialName("earth")
    EARTH,

    @SerialName("fire")
    FIRE,

    @SerialName("holy")
    HOLY,

    @SerialName("ice")
    ICE,

    @SerialName("lightning")
    LIGHTNING,

    @SerialName("poison")
    POISON,

    @SerialName("water")
    WATER,

    @SerialName("wind")
    WIND,
}

/**
 * Which of the two shipped card tables a card belongs to, and which a profile plays with.
 *
 * ### What is left of it, now that ids are global
 *
 * Its original job is gone. It existed because the AS3 bolted two independently numbered tables
 * together — FFXIV 1..153, FFVIII 1..110 — so every FFVIII id also named an FFXIV card, and
 * `Save.DATAS.MODE` said which table `CARDS` indexed. Card ids are now
 * `(block shl 8) or number` and unique across every set, so nothing has to be disambiguated.
 *
 * What remains is the four things that are still genuinely per-table — the opponents, the shop
 * shelf, the rule pool and the campaign — and those become a **format** in
 * `docs/migration/19-CARD-SETS-AND-FORMATS.md`, which is the next change and not this one. This
 * enum is the placeholder in between: it is keyed by [block] rather than by a texture prefix, so it
 * is already a set reference rather than an index, and the day formats land it is deleted rather
 * than rewritten.
 *
 * @property storageKey what this collection is **written as**, trailing underscore included: the
 *   save's `MODE`, the server's `matches.collection` column, and the canonical transcript digest.
 *   It used to be called `prefix`, because it was also the texture-name prefix and the key the two
 *   card tables were looked up by; ids are global now, so the only job left is being the string
 *   already on disk. Renamed rather than deleted for exactly that reason — changing the value would
 *   need a migration and would mislabel every stored match, and this column becomes `format_id` in
 *   `docs/migration/19-CARD-SETS-AND-FORMATS.md` anyway.
 * @property slug what a path, a URL and a human use. The same string a [CardSet] carries.
 */
@Serializable
enum class CardCollection(val block: Int, val slug: String, val storageKey: String) {
    @SerialName("ff14_")
    FF14(1, "ff14", "ff14_"),

    @SerialName("ff8_")
    FF8(2, "ff8", "ff8_"),
    ;

    companion object {
        /** The collection holding [block], or null if no shipped table does. */
        fun forBlock(block: Int): CardCollection? = entries.firstOrNull { it.block == block }

        /** The collection named `"ff14"`, or null. */
        fun forSlug(slug: String): CardCollection? = entries.firstOrNull { it.slug == slug }

        /** The collection a stored `"ff14_"` names, or null. See [storageKey]. */
        fun forStorageKey(key: String): CardCollection? =
            entries.firstOrNull { it.storageKey == key }
    }
}

/**
 * A Triple Triad card.
 *
 * Field-for-field the AS3 record in `sources/src/tto/datas/cards.as`, which stores
 * each card as `{name, power, rarity, type}` and identifies it by its **index in
 * that array**.
 *
 * ### The id is global, and is two numbers in one
 *
 * `id = (block shl 8) or number`, with `block >= 1` and `number` in 1..255 — the scheme
 * `docs/migration/19-CARD-SETS-AND-FORMATS.md` § Card identifiers decides. So the id reads in hex
 * at a glance (`0x013e` is card 62 of block 1) and both halves come back out with a shift and a
 * mask rather than a lookup.
 *
 * It replaces the AS3 array index, which was unique only *within* a table and is why
 * [CardCollection] had to exist at all. Three properties follow, and each is the reason to prefer
 * this to a plain sequence:
 *
 * - **No real id is below 256**, so the whole range 1..255 is poison and every legacy id is
 *   *detectably* invalid rather than silently remapped onto the first set.
 * - **`0` keeps its meaning** — `saveDeck_Handler` pushes it for an empty deck slot — because no
 *   block holds a number 0.
 * - **A set can grow after release** without touching what it has published, up to 255.
 *
 * If a set ever needs more than 255 cards it takes a second block, which costs one row and no
 * rethinking. It is why a set is a *list* of blocks and not one block.
 *
 * [top], [right], [bottom], [left] are `power[0..3]`, in that order, as stated by
 * the comment in `CardDigits.display()`. Note the AS3 reads each element with
 * `uint("0x" + power[i])` (`Card.as:316-330`) — a *hexadecimal* parse, which is how
 * the literal `'A'` in the data means 10.
 */
@Serializable
data class Card(
    val id: Int,
    /** The i18n key, e.g. `STR_FF14_CARD_1`. Kept so the real name is derivable. */
    val nameKey: String,
    /** The `en_US` name, resolved at extraction time from `datas/locales`. */
    val name: String,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val left: Int,
    /** Star count, 1..5. Drawn as the `{rarity}stars` texture at (9, 6). */
    val rarity: Int,
    val type: CardType? = null,
    val owner: CardColor = CardColor.BLUE,
) {
    /** The set this card belongs to — the high byte of [id]. */
    val block: Int get() = id shr BLOCK_SHIFT

    /** Its number within that set, 1..255 — the low byte of [id]. */
    val number: Int get() = id and NUMBER_MASK

    init {
        require(id >= FIRST_ID) {
            "card id must name a block and a number, was $id (the range 1..$NUMBER_MASK is legacy)"
        }
        require(number in NUMBER_RANGE) { "card number must be in $NUMBER_RANGE, was $number" }
        require(rarity in RARITY_RANGE) { "rarity must be in $RARITY_RANGE, was $rarity" }
        val sides = listOf(
            "top" to top,
            "right" to right,
            "bottom" to bottom,
            "left" to left,
        )
        for ((side, power) in sides) {
            require(power in POWER_RANGE) { "$side power must be in $POWER_RANGE, was $power" }
        }
    }

    /** Returns the same card owned by the other side — what a capture produces. */
    fun captured(): Card = copy(owner = owner.opposite())

    companion object {
        /** `id shr 8` is the block; `id and 0xFF` is the number. */
        const val BLOCK_SHIFT: Int = 8
        const val NUMBER_MASK: Int = 0xFF

        /** Blocks start at 1, so this is the lowest id a real card can have. */
        const val FIRST_ID: Int = 1 shl BLOCK_SHIFT

        /** Number 0 is reserved: an empty deck slot is stored as `0`. */
        val NUMBER_RANGE = 1..NUMBER_MASK

        /** `(block shl 8) or number`. The one place the id scheme is built. */
        fun idFor(block: Int, number: Int): Int {
            require(block >= 1) { "block must be positive, was $block" }
            require(number in NUMBER_RANGE) { "number must be in $NUMBER_RANGE, was $number" }
            return (block shl BLOCK_SHIFT) or number
        }

        /**
         * Every edge power is one hex digit, 1..A. There is no `cd0` in play (the
         * texture exists but no card has a 0) and no value above A.
         */
        val POWER_RANGE = 1..ACE_POWER

        /** Star counts run 1..5; `{rarity}stars` textures exist for exactly those. */
        val RARITY_RANGE = 1..5
    }
}

/** The highest edge power, written `'A'` in `cards.as` and drawn with the `cdA` texture. */
const val ACE_POWER: Int = 10

/**
 * Triple Triad renders a power of 10 as "A" (ace). The AS3 original does this in
 * `CardDigits.as` by picking the `cdA` texture instead of `cd10`, which does not
 * exist — see `sources/assets/digits/digits.xml`.
 */
fun powerLabel(power: Int): String = if (power >= ACE_POWER) "A" else power.toString()
