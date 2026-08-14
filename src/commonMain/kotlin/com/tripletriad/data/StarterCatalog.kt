package com.tripletriad.data

import com.tripletriad.model.Card
import com.tripletriad.model.HAND_SIZE
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The box a character opens with.
 *
 * `docs/migration/19-CARD-SETS-AND-FORMATS.md` § The starter pack, which is **decided** rather than
 * proposed. It replaces `GameSave.DEFAULT_CARDS` — the AS3's hard-coded five, `Save.as:30` — whose
 * ids named cards in whichever table `MODE` pointed at and which global card ids therefore left
 * naming block 1 outright.
 *
 * ### The composition is a rule, not a per-starter decision
 *
 * **Ten cards: nine of rarity 1 and one of rarity 2, and the rare one is in the deck.** Written
 * down and enforced ([StarterCatalog.violations]) because it is what keeps every set's starter
 * worth the same: a player choosing between three starters is choosing a flavour, not a power
 * level. The moment one set ships two rarity-2s the choice collapses into "take the strongest".
 *
 * The rare belongs in the five-card deck because it is the card the starter is *about*, and a first
 * deck that left it in the collection would be one the player has to discover and fix without being
 * told there was anything to fix.
 *
 * ### Choosing a starter is not choosing a side
 *
 * This is the trap the document spends a section on, and it is worth repeating where the code is:
 * **taking the starter of set A does not restrict you to set A.** It is the box you open first, not
 * the half of the game you are assigned to. The old `MODE` was irreversible and partitioned the
 * shop, the opponents and the campaign; a starter grants cards once and then has no further
 * existence — nothing reads [Starter.block] after the grant.
 *
 * @property id the key the client sends and the server resolves. Never the cards: under document 19
 *   the grant is the server's, exactly as it refuses to take the client's word for what a card is
 *   worth when replaying a match.
 * @property block the set it opens. What makes "offer the starters of released sets" fall out for
 *   free — the released flag is [CardSet.released] and there is no second one to keep in step.
 * @property cards what the character owns on its first frame.
 * @property deck the five of them that fill its first deck slot.
 */
@Serializable
data class Starter(
    val id: String,
    val block: Int,
    val nameKey: String,
    val cards: List<Int>,
    val deck: List<Int>,
)

/** Every authored starter. */
@Serializable
data class StarterCatalog(val starters: List<Starter>) {

    /** The starter with [id], or null. */
    operator fun get(id: String): Starter? = starters.firstOrNull { it.id == id }

    /**
     * The starters a character may be created with, in authored order.
     *
     * Filtered on the **set's** released flag rather than on a flag of the starter's own, so an
     * unreleased set cannot be opened by the one door that does not go through the shop.
     */
    fun released(sets: List<CardSet>): List<Starter> {
        val open = sets.filter { it.released }.mapTo(mutableSetOf()) { it.block }
        return starters.filter { it.block in open }
    }

    /** The starter that opens [block], or null. */
    fun forBlock(block: Int): Starter? = starters.firstOrNull { it.block == block }

    /**
     * Everything wrong with this catalogue, as sentences, or empty when it is sound.
     *
     * The five refusals of § What an importer should refuse. They are content bugs, and every one
     * of them reaches a player as something worse than an error message — a starter of the wrong
     * size is a character that begins stronger or weaker than every other, and a released set with
     * no starter is a set nobody can begin with.
     *
     * A list rather than a throw: an authoring pass wants to see all of it at once, and the one
     * caller is a test. Returning the problems also means the *rule* is stated once, here, rather
     * than restated in the assertions of whichever test happens to check it.
     *
     * @param cards the card table, for rarities and blocks.
     * @param sets the shipped sets, so a released one with no starter is caught.
     */
    fun violations(cards: CardCatalog, sets: List<CardSet>): List<String> = buildList {
        for (starter in starters) {
            val resolved = starter.cards.map { it to cards[it] }
            val missing = resolved.filter { it.second == null }.map { it.first }
            if (missing.isNotEmpty()) {
                add("${starter.id} names cards that do not exist: $missing")
                // Nothing below can be judged without them, so this starter stops here.
                continue
            }
            val held = resolved.mapNotNull { it.second }
            addAll(starter.compositionProblems(held))
        }

        val opened = starters.mapTo(mutableSetOf()) { it.block }
        for (set in sets.filter { it.released }) {
            if (set.block !in opened) {
                add("set ${set.slug} is released and has no starter")
            }
        }
    }

    /** What is wrong with one starter's ten cards and five-card deck. */
    private fun Starter.compositionProblems(held: List<Card>): List<String> = buildList {
        val foreign = held.filter { it.block != block }.map { it.id }
        if (foreign.isNotEmpty()) add("$id holds cards from another block: $foreign")

        val commons = held.count { it.rarity == COMMON_RARITY }
        val rares = held.filter { it.rarity == RARE_RARITY }
        if (held.size != SIZE || commons != COMMONS || rares.size != RARES) {
            add(
                "$id must be $COMMONS rarity-$COMMON_RARITY cards and $RARES " +
                    "rarity-$RARE_RARITY, was ${held.size} cards " +
                    "($commons and ${rares.size})",
            )
        }

        if (!cards.containsAll(deck)) add("$id has a deck that is not a subset of its cards")
        if (deck.size != HAND_SIZE) add("$id has a deck of ${deck.size}, not $HAND_SIZE")
        if (rares.isNotEmpty() && rares.none { it.id in deck }) {
            add("$id leaves its rare card out of its deck")
        }
    }

    companion object {
        /** Ten cards, and the split that makes every set's starter worth the same. */
        const val SIZE: Int = 10
        const val COMMONS: Int = 9
        const val RARES: Int = 1
        const val COMMON_RARITY: Int = 1
        const val RARE_RARITY: Int = 2
    }
}

/**
 * Parses the starter catalogue.
 *
 * Split from the loader for the reason [CardCatalogParser] is: this module must stay free of any
 * way to *obtain* the text. Document 19 asks for both ends to read starters "from the same parser",
 * which they will — this type is pure Kotlin and moves to `:core` verbatim the day the server needs
 * it. It is here rather than there today only because `:core` is a published artifact of another
 * repository, and adding to it costs a release the client would then have to wait for.
 */
object StarterCatalogParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): StarterCatalog = json.decodeFromString(text)
}
