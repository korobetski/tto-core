package com.tripletriad.data

import com.tripletriad.model.CardCollection
import com.tripletriad.model.Npc
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The two NPC tables of `sources/src/tto/datas/NPCs.as`, as extracted by
 * `tools/extract_npcs.py`.
 *
 * Selected by the profile's mode exactly as the AS3 does — `NPCs.LIST` returns
 * `NPCs[MODE.toUpperCase() + 'NPCS']` — so a profile sees one table's opponents and never the
 * other's.
 */
@Serializable
data class NpcCatalog(
    val ff14: List<Npc>,
    val ff8: List<Npc>,
) {
    /** All opponents of both collections, ff14 first, in AS3 array order. */
    val all: List<Npc> get() = ff14 + ff8

    fun collection(collection: CardCollection): List<Npc> = when (collection) {
        CardCollection.FF14 -> ff14
        CardCollection.FF8 -> ff8
    }

    /**
     * One opponent by its `iconID`, searched within [collection].
     *
     * Keyed by icon rather than by `id` because ids are **not unique** — the ff8 table repeats 2
     * and 13 — and because `NPC_W` records wins under the icon id anyway. See [Npc.id].
     */
    fun byIcon(iconId: String, collection: CardCollection): Npc? =
        collection(collection).firstOrNull { it.iconId == iconId }

    /**
     * The opponents challengeable at [hour] by a character of [level], in list order.
     *
     * `NPCs.toListCollection()` sorts on `difficulty`, then `matchFee`, then `name`
     * (`NPCs.as:1141`) and then filters by availability. Both are reproduced; the availability test
     * is not — see [com.tripletriad.model.Availability] for why the original's cannot work.
     *
     * ### The level gate, which the original does not have
     *
     * `PVEScreen` lists every opponent in the table from the first match onward, and the ff14 table
     * runs from difficulty 1 to **19**. A new character is shown sixty opponents, has no way to
     * tell which of them are worth attempting, and the fee is charged either way — so the honest
     * reading of that list is that it is unsorted advice at best.
     *
     * The rule is [LEVEL_REACH] above the character's own level: at level 1 the five easiest are
     * open, and each level opens what the last one made plausible. **One** ahead rather than none,
     * because a list with nothing above your weight is a list with nothing to aim at.
     *
     * Note the ff8 table declares `difficulty` **0 for all twenty-five of its opponents** — it is a
     * field the FF8 data never filled in — so this gate is inert there and that collection behaves
     * exactly as it did. That is the right outcome by accident rather than by design, and it is
     * recorded here so a later pass that fills those numbers in knows it is turning a gate on.
     *
     * @param hour wall-clock hour, 0..23. Injected rather than read from a clock so the list is
     *   testable, which is the same reason nothing else in this module reads one.
     * @param level the character's level. See [com.tripletriad.model.GameSave.level].
     */
    fun available(collection: CardCollection, hour: Int, level: Int): List<Npc> =
        collection(collection)
            .filter { it.availability.isOpenAtHour(hour) && it.isOpenAtLevel(level) }
            .sortedWith(compareBy({ it.difficulty }, { it.matchFee }, { it.nameKey.lowercase() }))

    /**
     * How many of [collection] are open at [hour] but held back by [level].
     *
     * What the opponent list says under itself, so a filtered list reads as filtered rather than as
     * a short table. Counted over the same availability test as [available] — an opponent who is
     * simply not around at this hour is not "locked", and saying so would send the player looking
     * for a level that would not produce them.
     */
    fun lockedByLevel(collection: CardCollection, hour: Int, level: Int): Int =
        collection(collection)
            .count { it.availability.isOpenAtHour(hour) && !it.isOpenAtLevel(level) }

    private fun Npc.isOpenAtLevel(level: Int): Boolean = difficulty <= level + LEVEL_REACH
}

/** How far above their own level a character may reach. See [NpcCatalog.available]. */
private const val LEVEL_REACH = 1

/** Parses the NPC catalog. Split from its loader for the same reason [CardCatalogParser] is. */
object NpcCatalogParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): NpcCatalog = json.decodeFromString(text)
}
