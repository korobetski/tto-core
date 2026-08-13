package com.tripletriad.data

import com.tripletriad.model.Card
import com.tripletriad.model.GameRules
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a match may be played with — the cards, and the rules that may be drawn.
 *
 * `docs/migration/19-CARD-SETS-AND-FORMATS.md` § What a format is, which is **decided** rather than
 * proposed. A format is not new machinery: it is the four things that are already per-collection,
 * named once and generalised —
 *
 * | Today | Becomes |
 * |---|---|
 * | `Roulette.pools[collection]` | [rules] |
 * | `NpcCatalog.available(collection, …)` | the opponents that declare this format |
 * | `ShopCatalog.offers(collection)` | boosters yielding cards of a set |
 * | `campaigns.forCollection(mode)` | a tournament, which *is* a format plus a ladder |
 *
 * The rule pools are the evidence this is real rather than decorative: FFVIII's carries Elemental
 * and Same Wall, FFXIV's carries Ascension, Descension, Order, Chaos, Swap, Fallen Ace and Reverse.
 * Which rules may be drawn is genuinely a property of the pool of cards being played with.
 *
 * ### The switch happened here
 *
 * This type spent one release in the client as *data plus a proof*: `FormatBundleTest` held every
 * format's rules identical, in order, to `Roulette.pools` — the table compiled into `:core` that
 * the engine actually drew from. That is what made this move a **deletion** rather than a rewrite
 * nobody could check.
 *
 * `Roulette.pools` is now gone. A rule pool is a property of a format, the engine is handed one,
 * and there is no second copy left to drift.
 *
 * @property blocks the sets it admits, by [Card.block]. Blocks and not slugs, so legality is
 *   `card.id shr 8 in format.blocks` — a shift and a set lookup, with nothing to resolve. The slug
 *   stays for paths and for anything a human types.
 * @property rules the rules that may be in force. The one piece of *logic* document 19 moves into
 *   data, and the piece most likely to be tuned without a release.
 */
@Serializable
data class Format(
    val id: String,
    val nameKey: String,
    val blocks: List<Int>,
    val rules: List<String>,
) {
    /** Whether a card of [block] may be played in this format. */
    fun admits(block: Int): Boolean = block in blocks

    /** Whether [cardId] may be played in this format. */
    fun admitsCard(cardId: Int): Boolean = admits(cardId shr Card.BLOCK_SHIFT)

    /**
     * The rules a **player** may tick, which is [rules] without Roulette.
     *
     * Roulette is in the pool because it can be *drawn*, and it is off this list because it is not
     * a rule you turn on: `GameRules.roulette` is what the Wheel of Fortune achievements count, and
     * only `Roulette.augment` may set it. A host asks for a draw and the server performs one — see
     * `PvpTable.roulette`.
     */
    fun choosableRuleKeys(): List<String> = rules.filterNot { it == ROULETTE_KEY }

    /**
     * [rules] with everything this format does not allow removed.
     *
     * Built up from an empty set rather than stripped down from the argument, because
     * [GameRules.withRuleKey] cannot subtract on its own — `RULE_DEFAULT_OPEN` and its two siblings
     * are deliberately absent from the key table, being the *absence* of a rule rather than one.
     * Rebuilding is the only operation that expresses "keep exactly these".
     *
     * `roulette` is dropped with everything else: what the server draws is not what the caller
     * declared.
     */
    fun confine(rules: GameRules): GameRules =
        rules.activeRuleKeys()
            .filter { it in this.rules }
            .fold(GameRules()) { kept, key -> kept.withRuleKey(key) }

    /**
     * Whether every rule [rules] names is one this format allows.
     *
     * Compared against `roulette = false` because a caller has no business declaring it: the flag
     * means "this set went through a draw", and a set that has not been drawn yet has not.
     */
    fun admitsRules(rules: GameRules): Boolean = confine(rules) == rules.copy(roulette = false)

    private companion object {
        const val ROULETTE_KEY = "RULE_ROULETTE"
    }
}

/**
 * Every authored format.
 *
 * Three of them as of 2026-08-12: one per released set, and `free-play`, which admits every
 * released block and draws from the **union** of the two rule pools.
 *
 * ### ⚠️ Elemental is in the free-play pool, and it is lopsided there
 *
 * `Board.elements()` draws from the eight **FFVIII** elements, and an FFXIV card's `type` is a
 * *group* — `beast`, `scions`, `garlean`, `primals` — not an element. So under Elemental in a mixed
 * format an FFXIV card can only ever take the −1: it cannot match, in principle, on any tile.
 *
 * Known and accepted rather than overlooked — the alternative, an intersection of the pools,
 * shrinks every time a set ships, and a hand-kept exception list is the thing nobody updates.
 * It stops being lopsided when document 20 splits `Card.type` into a shared `element` and a per-set
 * `group`, which is the proper fix and is a different piece of work.
 */
@Serializable
data class FormatCatalog(val formats: List<Format>) {

    operator fun get(id: String): Format? = formats.firstOrNull { it.id == id }

    /**
     * The format a character plays when nothing has chosen one for them.
     *
     * The one that admits the most blocks — free play, in the shipped data — because document 19
     * asks for "one admitting every released block, so a new player has somewhere to play before
     * any tournament exists". Chosen by *width* rather than by id, so authoring a wider format
     * makes it the default without a constant here having to be remembered.
     *
     * This is what replaced `GameSave.MODE`. A character no longer belongs to a set; a **match**
     * is played in a format, and this is the one they land in.
     */
    val default: Format? get() = formats.maxByOrNull { it.blocks.size }

    /**
     * The formats admitting [block], in authored order.
     *
     * A block can appear in several — a single-set format and a free-play one that takes
     * everything — which is why this returns a list rather than the format.
     */
    fun admitting(block: Int): List<Format> = formats.filter { it.admits(block) }

    /**
     * Everything wrong with this catalogue, as sentences, or empty when it is sound.
     *
     * The same shape as [StarterCatalog.violations] and for the same reason: these are content
     * bugs, an authoring pass wants to see all of them at once, and the rule is stated here rather
     * than restated in whichever test happens to check it.
     */
    fun violations(sets: List<CardSet>): List<String> = buildList {
        val known = sets.mapTo(mutableSetOf()) { it.block }
        val released = sets.filter { it.released }.mapTo(mutableSetOf()) { it.block }

        val ids = formats.map { it.id }
        if (ids.size != ids.toSet().size) add("format ids are not unique: $ids")

        for (format in formats) {
            if (format.blocks.isEmpty()) add("${format.id} admits no set at all")
            val unknown = format.blocks.filterNot { it in known }
            if (unknown.isNotEmpty()) add("${format.id} names blocks nothing ships: $unknown")

            if (format.rules.isEmpty()) add("${format.id} has an empty rule pool")
            val notRules = format.rules.filterNot(::namesARule)
            if (notRules.isNotEmpty()) {
                add("${format.id} names things that are not rules: $notRules")
            }
            if (format.rules.size != format.rules.toSet().size) {
                add("${format.id} lists a rule twice: ${format.rules}")
            }
        }

        // A released set nothing admits is a set that ships and cannot be played.
        val admitted = formats.flatMapTo(mutableSetOf()) { it.blocks }
        for (block in released - admitted) {
            add("block $block is released and no format admits it")
        }
    }
}

/**
 * Whether [key] names a rule the engine can put in force.
 *
 * Asked by applying it, because `RuleKeys` — the table that would answer directly — is `internal`
 * to `:core`. `withRuleKey` is documented as ignoring a key it does not recognise, so a rule set
 * that comes back unchanged is the answer. It also correctly refuses `RULE_COMBO`, which is in the
 * help screen's list and is a dead constant everywhere else: combo fires whenever Same or Plus
 * captures and no flag turns it on, so a format naming it would be a format promising nothing.
 */
private fun namesARule(key: String): Boolean = GameRules().withRuleKey(key) != GameRules()

/**
 * Parses the format catalogue.
 *
 * Split from the loader for the reason [CardCatalogParser] is: this layer must stay free of any way
 * to *obtain* the text, so the same parser can serve a server that gets it from somewhere else.
 */
object FormatCatalogParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): FormatCatalog = json.decodeFromString(text)
}
