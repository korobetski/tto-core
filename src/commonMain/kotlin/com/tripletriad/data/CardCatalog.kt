package com.tripletriad.data

import com.tripletriad.model.Card
import com.tripletriad.model.CardCollection
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One released set of cards — a **block**, and everything about it that is not a card.
 *
 * `docs/migration/19-CARD-SETS-AND-FORMATS.md` § The data model gives this a table; this is the
 * shipped half of it. [block] is the key rather than [slug] because it is what an id carries: a
 * card names its set by arithmetic, and the slug is for paths and for anything a human types.
 *
 * @property released whether the set is out. Authored content can exist before it is playable, and
 *   this is the flag a starter offer and a default format read rather than each keeping their own.
 */
@Serializable
data class CardSet(
    val block: Int,
    val slug: String,
    val nameKey: String,
    val sortOrder: Int,
    val released: Boolean = false,
)

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
     * The cards of one shipped table.
     *
     * The bridge from the enum that is on its way out — see [CardCollection]. Kept while the four
     * per-table things it still names (opponents, shop, rule pool, campaign) have no format to
     * belong to yet.
     */
    fun collection(collection: CardCollection): List<Card> = block(collection.block)

    /** The card with [id], or null. */
    operator fun get(id: Int): Card? = byId[id]
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
