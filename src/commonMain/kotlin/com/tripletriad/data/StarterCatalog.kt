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
 * **Nine cards: the authored five that fill the first deck, and four more drawn from the set's
 * commons.** Written down and enforced ([StarterCatalog.violations]) because it is what keeps every
 * set's starter worth the same: a player choosing between two starters is choosing a flavour, not a
 * power level. The moment one set ships two rarity-2s in its deck the choice collapses into "take
 * the strongest".
 *
 * Only the five are authored. The four are [StarterPack.drawn] — commons of the same block, one
 * copy each, never a duplicate of the five — so two players who opened the same box do not own the
 * same collection, and the deck they were both handed is still the same deck. They are **drawn
 * where the grant happens**: on the server for an account, in the client for a local profile, the
 * same way a booster is rolled by whoever owns the profile it lands in.
 *
 * A rarity-2 in the authored five, because it is the card the starter is *about*, and a first deck
 * that left it in the collection would be one the player has to discover and fix without being told
 * there was anything to fix. Nothing above rarity 2 at all: a starter is a starting point.
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
 *   worth when replaying a match. The `starterId` on `ClaimStarterRequest` is this.
 * @property block the set it opens. What makes "offer the starters of released sets" fall out for
 *   free — the released flag is [CardSet.released] and there is no second one to keep in step. It
 *   is also the pool the four drawn cards come out of.
 * @property deck the authored five: the character's first deck, and five of the nine it owns.
 */
@Serializable
data class Starter(
    val id: String,
    val block: Int,
    val nameKey: String,
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
        val open = sets.filter { it.released }.flatMapTo(mutableSetOf()) { it.blocks }
        return starters.filter { it.block in open }
    }

    /** The starter that opens [block], or null. */
    fun forBlock(block: Int): Starter? = starters.firstOrNull { it.block == block }

    /**
     * Everything wrong with this catalogue, as sentences, or empty when it is sound.
     *
     * The ways a starter can be wrong. They are content bugs, and every one of them reaches a
     * player as something worse than an error message — a starter whose block cannot fill the draw
     * is a character that begins with fewer cards than every other, and a released set with no
     * starter is a set nobody can begin with.
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
            val resolved = starter.deck.map { it to cards[it] }
            val missing = resolved.filter { it.second == null }.map { it.first }
            if (missing.isNotEmpty()) {
                add("${starter.id} names cards that do not exist: $missing")
                // Nothing below can be judged without them, so this starter stops here.
                continue
            }
            addAll(starter.compositionProblems(resolved.mapNotNull { it.second }, cards))
        }

        // A *set* needs a starter, not each of its blocks: a set spanning two blocks is one
        // collection to the player and opens with one box, whose cards all sit in whichever
        // block holds the set's commons.
        val opened = starters.mapTo(mutableSetOf()) { it.block }
        for (set in sets.filter { it.released }) {
            if (set.blocks.none { it in opened }) {
                add("set ${set.slug} is released and has no starter")
            }
        }
    }

    /** What is wrong with one starter's authored five, and with the block behind them. */
    private fun Starter.compositionProblems(held: List<Card>, cards: CardCatalog): List<String> =
        buildList {
            val foreign = held.filter { it.block != block }.map { it.id }
            if (foreign.isNotEmpty()) add("$id holds cards from another block: $foreign")

            if (held.size != HAND_SIZE) add("$id has a deck of ${held.size}, not $HAND_SIZE")
            if (deck.size != deck.toSet().size) add("$id names a card twice: $deck")

            val overRare = held.filter { it.rarity > RARE_RARITY }.map { it.id }
            if (overRare.isNotEmpty()) {
                add("$id holds cards above rarity $RARE_RARITY: $overRare")
            }
            if (held.none { it.rarity == RARE_RARITY }) {
                add("$id has no rarity-$RARE_RARITY card in its deck")
            }

            // The draw is not authored, so this is the only place its feasibility can be stated:
            // a block with three spare commons deals an eight-card box and nothing says so.
            val pool = StarterPack.pool(this@compositionProblems, cards.byId)
            if (pool.size < StarterPack.DRAWN) {
                add(
                    "$id draws ${StarterPack.DRAWN} from block $block, which has only " +
                        "${pool.size} rarity-$COMMON_RARITY cards outside its deck",
                )
            }
        }

    companion object {
        /** Nine cards: [Starter.deck] plus [StarterPack.DRAWN]. See this file's KDoc. */
        const val SIZE: Int = HAND_SIZE + StarterPack.DRAWN
        const val COMMON_RARITY: Int = 1
        const val RARE_RARITY: Int = 2
    }
}

/**
 * Parses the starter catalogue.
 *
 * Split from the loader for the reason [CardCatalogParser] is: this module must stay free of any
 * way to *obtain* the text. Document 19 asks for both ends to read starters "from the same parser",
 * and they do — the client from its Compose resources, the server from its classpath.
 */
object StarterCatalogParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): StarterCatalog = json.decodeFromString(text)
}
