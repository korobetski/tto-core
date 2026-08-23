package com.tripletriad.data

import com.tripletriad.model.Card
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One released set of cards — a list of **blocks**, and everything about it that is not a card.
 *
 * `docs/migration/19-CARD-SETS-AND-FORMATS.md` § The data model gives this a table; this is the
 * shipped half of it. [blocks] is the key rather than [slug] because it is what an id carries: a
 * card names its set by arithmetic, and the slug is for paths and for anything a human types.
 *
 * ### Why a list, when it was one block
 *
 * A block holds 255 cards ([Card.NUMBER_RANGE]), and FFXIV has more than that — 454 of them. So a
 * set that outgrows a block **takes another one**, which is the answer [Card]'s own KDoc committed
 * to before there was a set large enough to need it: "a set is a *list* of blocks and not one
 * block". The alternative was widening the id, which would have re-issued every card id in every
 * save, deck and stored match for a limit only one set has ever reached.
 *
 * The blocks are **ordered**, and that order is the set's own numbering: a card's place in the set
 * is `index of its block × 255 + its number`, which is what keeps a card's published number
 * recoverable once a set spans two blocks. That only holds while every block but the last is
 * *full*, so a set fills a block to 255 before opening the next one — [violations] says so.
 *
 * @property released whether the set is out. Authored content can exist before it is playable, and
 *   this is the flag a starter offer and a default format read rather than each keeping their own.
 */
@Serializable
data class CardSet(
    val blocks: List<Int>,
    val slug: String,
    val nameKey: String,
    val sortOrder: Int,
    val released: Boolean = false,
) {
    init {
        require(blocks.isNotEmpty()) { "set '$slug' names no block" }
        require(blocks.all { it >= 1 }) { "set '$slug' names a non-positive block: $blocks" }
        require(blocks.size == blocks.toSet().size) { "set '$slug' names a block twice: $blocks" }
    }

    /** Whether a card of [block] belongs to this set. */
    fun holds(block: Int): Boolean = block in blocks

    /**
     * Where [card] sits in this set's own numbering, 1-based, or null if it is not in this set.
     *
     * The published number — what arrtripletriad.com calls `#N` and what a player reads on the
     * card — which stops being [Card.number] the moment a set spans two blocks. See this class's
     * KDoc for why this is arithmetic rather than a lookup, and what it assumes.
     */
    fun numberOf(card: Card): Int? {
        val index = blocks.indexOf(card.block)
        return if (index < 0) null else index * Card.NUMBER_MASK + card.number
    }
}

/**
 * Every shipped card, and the sets they belong to.
 *
 * ### Why this stopped being two named fields
 *
 * It was `CardCatalog(ff14, ff8)`, with `collection(prefix)` picking one — the shape the AS3 forced
 * by keying two tables off `Game.PROFILE_DATAS.MODE`. A third set could not be added without a
 * third field, a third `when` branch, and a third of everything downstream.
 *
 * Card ids are global now ([Card.id]), so the tables no longer have to be kept apart to stay
 * readable: one list, indexed by id and grouped by block, answers both "what is card 318" and
 * "what is in set 1" without either question knowing how many sets there are.
 */
@Serializable
data class CardCatalog(
    val sets: List<CardSet>,
    val cards: List<Card>,
) {
    /** Every card, ascending by id — which is set order, then number, for free. */
    val all: List<Card> get() = cards

    /**
     * Card by id, built once.
     *
     * Every caller that resolves a deck or a hand does it through here, and the AS3's answer — walk
     * the table — was tolerable at 153 entries and is not the shape to keep as sets accumulate.
     */
    val byId: Map<Int, Card> by lazy { cards.associateBy { it.id } }

    private val byBlock: Map<Int, List<Card>> by lazy { cards.groupBy { it.block } }

    /** The sets that are out, in their authored order. */
    val releasedSets: List<CardSet> get() = sets.filter { it.released }.sortedBy { it.sortOrder }

    /** The cards of one set, ascending by number. Empty for a block nothing ships. */
    fun block(block: Int): List<Card> = byBlock[block].orEmpty()

    /**
     * Every card [format] admits, in block order.
     *
     * What replaced "the cards of this profile's collection". A format may admit several blocks —
     * free play admits all of them — so this is a flatMap rather than a lookup, and that is the
     * whole difference: a player is no longer confined to one table.
     */
    fun admittedBy(format: Format): List<Card> = format.blocks.flatMap(::block)

    /** The card with [id], or null. */
    operator fun get(id: Int): Card? = byId[id]

    /**
     * Everything wrong with this catalogue, as sentences, or empty when it is sound.
     *
     * The same shape as [FormatCatalog.violations] and [StarterCatalog.violations], and for the
     * same reason: these are content bugs, an authoring pass wants to see all of them at once, and
     * the rule belongs here rather than restated in whichever test happens to check it.
     */
    fun violations(): List<String> = buildList {
        val ids = cards.map { it.id }
        if (ids.size != ids.toSet().size) {
            add("two cards share an id: ${ids.groupBy { it }.filterValues { it.size > 1 }.keys}")
        }

        val slugs = sets.map { it.slug }
        if (slugs.size != slugs.toSet().size) add("set slugs are not unique: $slugs")

        // A block may belong to at most one set, or a card's id would name two collections.
        val owner = mutableMapOf<Int, String>()
        for (set in sets) {
            for (block in set.blocks) {
                val taken = owner.put(block, set.slug)
                if (taken != null) add("block $block is claimed by both $taken and ${set.slug}")
            }
        }

        for (block in byBlock.keys.sorted()) {
            if (block !in owner) add("block $block holds cards and belongs to no set")
        }

        for (set in sets) addAll(set.numberingProblems(::block))
    }
}

/**
 * What is wrong with how one set's cards are spread across its blocks.
 *
 * [CardSet.numberOf] is arithmetic rather than a lookup, and it is only correct while every block
 * but the last is *full*: a gap in the middle would silently shift every published number after it.
 * So the shape that arithmetic assumes is checked rather than trusted.
 */
private fun CardSet.numberingProblems(cardsOf: (Int) -> List<Card>): List<String> = buildList {
    for (block in blocks.dropLast(1)) {
        val held = cardsOf(block).size
        if (held != Card.NUMBER_MASK) {
            add(
                "set $slug opened a block after $block, which holds $held cards and not " +
                    "${Card.NUMBER_MASK} — see CardSet's KDoc on why a block fills before the next",
            )
        }
    }
}

/**
 * Parses the card catalog.
 *
 * Split from the loader — which lives in `:shared`, because reading the bytes needs Compose
 * resources — so that this module stays free of any way to obtain them. The server has the same
 * catalog to parse and a completely different way of getting hold of it.
 */
object CardCatalogParser {
    // The extractor emits every field, but being lenient about unknown keys means a
    // later field addition does not break older clients.
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): CardCatalog = json.decodeFromString(text)
}
