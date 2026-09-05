package com.tripletriad.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shipped quest catalogue, and the two claims that keep it honest.
 *
 * A quest a player cannot finish is a third of a day's offer wasted, every day. A quest whose
 * opponent does not exist is the same thing with a worse symptom — it looks completable.
 *
 * One exception is now deliberate and documented rather than caught here: an **offline** profile
 * can draw the PvP quest and be unable to finish it. See [DailyQuestCatalog.assignable].
 */
class DailyQuestCatalogTest {

    /**
     * Everything that exists may be drawn.
     *
     * This used to assert the opposite of its own name: the PvP objective was defined and filtered
     * out, because nothing could satisfy it. **PvP ships**, so the two lists are one again — and
     * the assertion is kept, pointing the other way, because "what exists" and "what may be
     * offered" are still separate questions and this is where they are checked to agree.
     */
    @Test
    fun everythingThatExistsMayBeDrawn() {
        assertEquals(DailyQuestCatalog.all, DailyQuestCatalog.assignable)
    }

    /** And the PvP quest is one of them, exactly once. */
    @Test
    fun thePvpQuestIsDefinedAndDrawable() {
        val pvp = DailyQuestCatalog.all.filter { it.objective is Objective.PlayPvpMatch }

        assertEquals(1, pvp.size)
        assertTrue(pvp.single() in DailyQuestCatalog.assignable)
    }

    /**
     * Every named opponent is a real one, in a table a player can reach.
     *
     * Checked against the icon id, which is what `NPC_W` keys on and what
     * [Objective.BeatOpponent] therefore uses — the numeric `id` is not unique across the two
     * tables.
     */
    @Test
    fun everyNamedOpponentExists() {
        val named = DailyQuestCatalog.all
            .mapNotNull { (it.objective as? Objective.BeatOpponent)?.iconId }
        assertTrue(named.isNotEmpty(), "the fixture assumes at least one such quest")

        // The catalogue is authored against the shipped tables, which this module cannot read —
        // `npcs.json` is a client resource. What it can assert is the shape of the key: an icon id
        // is a lowercase slug, never a number, and getting that wrong is how one would silently
        // never match. `NpcBundleTest` in the client checks the ids themselves against the table.
        for (iconId in named) {
            assertTrue(iconId.isNotBlank(), "an empty opponent id")
            assertTrue(
                iconId.none { it.isUpperCase() } && iconId.toIntOrNull() == null,
                "'$iconId' does not look like an iconID — NPC_W keys on the slug, not the number",
            )
        }
    }

    /** Every rule named is one the engine can actually put in force. */
    @Test
    fun everyNamedRuleIsARealRule() {
        val named = DailyQuestCatalog.all
            .mapNotNull { (it.objective as? Objective.WinWithRule)?.ruleKey }
        assertTrue(named.isNotEmpty(), "the fixture assumes at least one such quest")

        for (key in named) {
            assertTrue(key in RuleKeys.all, "'$key' is not a rule the engine knows")
        }
    }

    @Test
    fun idsAreUniqueAndEveryQuestPaysSomething() {
        val ids = DailyQuestCatalog.all.map { it.id }

        assertEquals(ids.size, ids.toSet().size, ids.toString())
        for (quest in DailyQuestCatalog.all) {
            assertTrue(
                quest.reward.mgp > 0 || quest.reward.xp > 0 || quest.reward.item != null,
                "${quest.id} pays nothing",
            )
            assertTrue(quest.objective.target > 0, "${quest.id} has a target of zero")
        }
    }

    /** A day always offers a full set, and never the same quest twice. */
    @Test
    fun everyDrawIsFullAndWithoutRepeats() {
        for (created in 1L..200L) {
            val drawn = DailyQuestCatalog.forDay(DAY, created)

            assertEquals(DailyQuestCatalog.PER_DAY, drawn.size, "creation date $created")
            assertEquals(drawn.size, drawn.map { it.id }.toSet().size, "a repeat on $created")
        }
    }

    /**
     * The pool is wide enough that three quests is a choice rather than the whole catalogue.
     *
     * Not a style rule: if `assignable` ever shrinks to [DailyQuestCatalog.PER_DAY] the draw stops
     * varying, every player sees the same three every day, and the seed becomes decoration.
     */
    @Test
    fun thePoolIsWiderThanADaysDraw() {
        assertTrue(
            DailyQuestCatalog.assignable.size > DailyQuestCatalog.PER_DAY,
            "only ${DailyQuestCatalog.assignable.size} drawable quests",
        )
    }

    private companion object {
        /** 2026-01-01T12:00Z. */
        const val DAY = 1_767_268_800_000L
    }
}
