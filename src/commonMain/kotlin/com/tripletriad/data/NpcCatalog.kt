package com.tripletriad.data

import com.tripletriad.model.Npc
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Every opponent, as extracted from `sources/src/tto/datas/NPCs.as` by `tools/extract_npcs.py`.
 *
 * ### One list, not two
 *
 * This was `{ff14: […], ff8: […]}`, and the profile's `MODE` picked an array — `NPCs.LIST` returns
 * `NPCs[MODE.toUpperCase() + 'NPCS']`, so a profile saw one table and never the other. **That shape
 * was `MODE`**, in the data rather than in code, and it is the reason the character had to carry a
 * collection at all.
 *
 * Now there is one roster and each opponent declares the formats it plays ([Npc.formats]). Which
 * opponents a player meets is a property of the *match they are looking for*, which is what a
 * format is. See `docs/migration/19-CARD-SETS-AND-FORMATS.md`.
 */
@Serializable
data class NpcCatalog(
    val npcs: List<Npc>,
) {
    /** Every opponent, in authored order — ff14's first, as the two arrays used to be. */
    val all: List<Npc> get() = npcs

    /** The opponents that play [formatId]. */
    fun playing(formatId: String): List<Npc> = npcs.filter { formatId in it.formats }

    /**
     * One opponent by its `iconID`, within [formatId].
     *
     * Keyed by icon rather than by `id` because ids are **not unique** — what used to be the ff8
     * table repeats 2 and 13 — and because `NPC_W` records wins under the icon id anyway. The
     * format is still part of the question for the same reason it always was: two opponents in
     * different formats may share neither ids nor nothing else.
     */
    fun byIcon(iconId: String, formatId: String): Npc? =
        playing(formatId).firstOrNull { it.iconId == iconId }

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
     * ### The achievement gate, which the original does not have either
     *
     * One opponent carries [Npc.requiresAchievement] — see it for which, and why. It is a **door**
     * rather than a clock or a weight class: unlike the hour and unlike the level, no amount of
     * waiting or playing opens it. So it is counted apart, by [lockedByAchievement], and never
     * folded into [lockedByLevel], whose sentence to the player promises the opposite.
     *
     * [earned] defaults to empty, which means *locked*. A caller that forgets to pass a profile
     * therefore under-lists rather than over-lists, and the failure shows up as a missing opponent
     * instead of a match the server refuses.
     *
     * @param hour wall-clock hour, 0..23. Injected rather than read from a clock so the list is
     *   testable, which is the same reason nothing else in this module reads one.
     * @param level the character's level. See [com.tripletriad.model.GameSave.level].
     * @param earned the achievement ids the character holds — `GameSave.achievements.keys`.
     */
    fun available(
        formatId: String,
        hour: Int,
        level: Int,
        earned: Set<String> = emptySet(),
    ): List<Npc> =
        playing(formatId)
            .filter {
                it.availability.isOpenAtHour(hour) &&
                    it.isOpenAtLevel(level) &&
                    it.isEarnedBy(earned)
            }
            .sortedWith(compareBy({ it.difficulty }, { it.matchFee }, { it.nameKey.lowercase() }))

    /**
     * How many of [formatId]'s opponents are open at [hour] but held back by [level].
     *
     * What the opponent list says under itself, so a filtered list reads as filtered rather than as
     * a short table. Counted over the same availability test as [available] — an opponent who is
     * simply not around at this hour is not "locked", and saying so would send the player looking
     * for a level that would not produce them.
     *
     * An opponent behind an achievement is not counted either, for the same reason and more
     * sharply: levelling would never produce them.
     */
    fun lockedByLevel(formatId: String, hour: Int, level: Int): Int =
        playing(formatId).count {
            it.availability.isOpenAtHour(hour) && !it.isOpenAtLevel(level)
        }

    /**
     * How many of [formatId]'s opponents are behind an achievement [earned] does not hold.
     *
     * Its own count rather than a share of [lockedByLevel]'s, because the two are different
     * promises: one says "keep playing and they arrive", this one says "there is a thing to win
     * first". Told apart in the data by [Npc.requiresAchievement] and told apart on screen by
     * having its own line.
     *
     * The hour is not consulted. A door is shut whatever time it is, and an opponent who is both
     * unearned and out of hours is better reported as unearned — that is the fact the player can
     * act on.
     */
    fun lockedByAchievement(formatId: String, earned: Set<String>): Int =
        playing(formatId).count { !it.isEarnedBy(earned) }

    private fun Npc.isOpenAtLevel(level: Int): Boolean = difficulty <= level + LEVEL_REACH

    private fun Npc.isEarnedBy(earned: Set<String>): Boolean =
        requiresAchievement?.let { it in earned } != false
}

/** How far above their own level a character may reach. See [NpcCatalog.available]. */
private const val LEVEL_REACH = 1

/** Parses the NPC catalog. Split from its loader for the same reason [CardCatalogParser] is. */
object NpcCatalogParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): NpcCatalog = json.decodeFromString(text)
}
