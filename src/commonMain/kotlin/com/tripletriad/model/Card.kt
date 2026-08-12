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
 * It replaces the AS3 array index, which was unique only *within* a table and is why a
 * `CardCollection` enum had to exist at all — it named which of the two tables an index belonged
 * to, and it is deleted. Three properties follow, and each is the reason to prefer this to a plain
 * sequence:
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

    /**
     * The four edges added up, 4..40 — how strong this card is, in one number.
     *
     * A card's strength is not one of its edges but all four: a 1-1-1-A is an ace that loses on
     * three sides. Nothing in the AS3 computes this — the deck-power figure the original shows is
     * a sum of *rarities*, which is a proxy for price rather than for strength — and the reason it
     * is here is [com.tripletriad.data.NpcRating], which needs a total order over the card table to
     * pick a neutral yardstick.
     *
     * Deliberately unweighted. Which edge matters depends on where the card is played and on
     * whether Reverse or Fallen Ace is up, so any weighting would be a claim about a board that has
     * not been dealt yet.
     */
    val total: Int get() = top + right + bottom + left

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
