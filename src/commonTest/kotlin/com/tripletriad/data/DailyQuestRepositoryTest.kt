package com.tripletriad.data

import com.tripletriad.model.DailyQuestCatalog
import com.tripletriad.model.GameSave
import com.tripletriad.model.MatchEvent
import com.tripletriad.model.MatchResult
import com.tripletriad.model.Objective
import com.tripletriad.model.questDayOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Rolling the day, counting a match, and paying once.
 *
 * The catalogue is the shipped one rather than a fixture: the draw is deterministic in the day and
 * the character's creation date, so a test that wants a particular quest **pins the seed** by
 * choosing those two numbers. That has the merit of exercising the content players actually get —
 * a fixture would pass against a catalogue with nothing in it.
 */
class DailyQuestRepositoryTest {
    private val repository = DailyQuestRepository()

    /** A character whose draw is known, found by searching the seed space rather than asserted. */
    private fun characterDrawing(objective: (Objective) -> Boolean): Pair<GameSave, Long> {
        for (created in 1L..2_000L) {
            val drawn = DailyQuestCatalog.forDay(DAY_ONE, created)
            if (drawn.any { objective(it.objective) }) {
                return GameSave.new(createdAt = created) to created
            }
        }
        error("no creation date in range draws such a quest — the catalogue must have shrunk")
    }

    private fun event(
        result: MatchResult = MatchResult.WIN,
        opponent: String = "tt-master",
        rules: List<String> = emptyList(),
        isPvp: Boolean = false,
    ) = MatchEvent(result, opponent, rules, isPvp)

    @Test
    fun aProfileThatHasNeverPlayedIsShownTheDaysDrawAtZero() {
        val save = GameSave.new(createdAt = 1L)

        val statuses = repository.statuses(save, DAY_ONE)

        assertEquals(DailyQuestCatalog.PER_DAY, statuses.size)
        assertTrue(statuses.all { it.progress.current == 0 }, "nothing has been played yet")
        assertTrue(statuses.none { it.isCompleted })
        assertEquals("", save.quests.day, "and reading wrote nothing")
    }

    /** The draw is a function of the day and the character, and of nothing else. */
    @Test
    fun theDrawIsDeterministicAndDiffersByDayAndByCharacter() {
        val first = DailyQuestCatalog.idsForDay(DAY_ONE, 1L)

        assertContentEquals(first, DailyQuestCatalog.idsForDay(DAY_ONE, 1L))
        assertContentEquals(first, DailyQuestCatalog.idsForDay(DAY_ONE + HOUR, 1L))
        assertNotEquals(first, DailyQuestCatalog.idsForDay(DAY_TWO, 1L))
        assertNotEquals(first, DailyQuestCatalog.idsForDay(DAY_ONE, 2L))
    }

    @Test
    fun theFirstCreditOfTheDayPinsTheDraw() {
        val save = GameSave.new(createdAt = 7L)

        val award = repository.credit(save, event(), DAY_ONE)

        assertEquals(questDayOf(DAY_ONE), award.save.quests.day)
        assertContentEquals(
            DailyQuestCatalog.idsForDay(DAY_ONE, 7L),
            award.save.quests.questIds,
        )
    }

    /**
     * A new day clears yesterday, progress and completions together.
     *
     * Carrying either across is how a quest pays twice, and keeping progress would make "win three
     * today" mean "win three ever" one day at a time.
     */
    @Test
    fun aMatchOnTheNextDayStartsTheDayOver() {
        val save = GameSave.new(createdAt = 7L)
        val yesterday = repository.credit(save, event(), DAY_ONE).save
        assertTrue(yesterday.quests.progress.isNotEmpty(), "the fixture must have progressed")

        val today = repository.credit(yesterday, event(), DAY_TWO).save

        assertEquals(questDayOf(DAY_TWO), today.quests.day)
        assertContentEquals(DailyQuestCatalog.idsForDay(DAY_TWO, 7L), today.quests.questIds)
        // Not "completed is empty": the same call rolls the day over **and** credits the match, so
        // a one-match quest drawn today is legitimately finished by the time this returns. What
        // must not survive is yesterday's — so every completion is stamped with today's credit.
        assertTrue(
            today.quests.completed.values.all { it == DAY_TWO },
            "a completion from another day survived the rollover: ${today.quests.completed}",
        )
        // And progress restarted rather than accumulating: two matches over two days is one unit
        // today, not two.
        assertTrue(
            today.quests.progress.values.all { it <= 1 },
            "progress carried across midnight: ${today.quests.progress}",
        )
    }

    @Test
    fun playingAMatchCountsTowardsThePlayObjectiveAndPaysOnTheThird() {
        val (save, _) = characterDrawing { it is Objective.MatchesPlayed }
        val quest = DailyQuestCatalog.forDay(DAY_ONE, save.creationDate)
            .first { it.objective is Objective.MatchesPlayed }

        var current = save
        val paid = mutableListOf<String>()
        repeat(quest.objective.target) {
            val award = repository.credit(current, event(result = MatchResult.LOSE), DAY_ONE)
            current = award.save
            paid += award.completed.map { it.id }
        }

        assertEquals(listOf(quest.id), paid, "it should pay exactly once, on the last match")
        assertEquals(save.mgp + quest.reward.mgp, current.mgp)
        assertTrue(current.hasCompletedQuest(quest.id))
    }

    /** A loss does not advance a win objective, which is the only thing separating the two. */
    @Test
    fun aLossDoesNotCountTowardsAWinObjective() {
        val (save, _) = characterDrawing { it is Objective.MatchesWon }
        val quest = DailyQuestCatalog.forDay(DAY_ONE, save.creationDate)
            .first { it.objective is Objective.MatchesWon }

        val award = repository.credit(save, event(result = MatchResult.LOSE), DAY_ONE)

        assertEquals(0, award.save.quests.progressOf(quest.id))
        assertTrue(award.completed.isEmpty())
    }

    @Test
    fun beatingTheNamedOpponentIsWhatCountsForThatObjective() {
        val (save, _) = characterDrawing { it is Objective.BeatOpponent }
        val quest = DailyQuestCatalog.forDay(DAY_ONE, save.creationDate)
            .first { it.objective is Objective.BeatOpponent }
        val wanted = (quest.objective as Objective.BeatOpponent).iconId

        val wrongOne = repository.credit(save, event(opponent = "somebody-else"), DAY_ONE)
        assertEquals(0, wrongOne.save.quests.progressOf(quest.id))

        val rightOne = repository.credit(save, event(opponent = wanted), DAY_ONE)
        assertTrue(rightOne.completed.any { it.id == quest.id })
    }

    @Test
    fun winningUnderTheNamedRuleIsWhatCountsForThatObjective() {
        val (save, _) = characterDrawing { it is Objective.WinWithRule }
        val quest = DailyQuestCatalog.forDay(DAY_ONE, save.creationDate)
            .first { it.objective is Objective.WinWithRule }
        val wanted = (quest.objective as Objective.WinWithRule).ruleKey

        val plainWin = repository.credit(save, event(rules = emptyList()), DAY_ONE)
        assertEquals(0, plainWin.save.quests.progressOf(quest.id))

        val ruledWin = repository.credit(save, event(rules = listOf(wanted)), DAY_ONE)
        assertTrue(ruledWin.completed.any { it.id == quest.id })
    }

    /**
     * A quest pays once, however many matches follow.
     *
     * The property a draining offline queue depends on: it can submit the same day's matches more
     * than once after a lost acknowledgement, and a second payment would be free MGP.
     */
    @Test
    fun aCompletedQuestDoesNotPayAgain() {
        val (save, _) = characterDrawing { it is Objective.MatchesWon && it.target == 1 }
        val quest = DailyQuestCatalog.forDay(DAY_ONE, save.creationDate)
            .first { it.objective == Objective.MatchesWon(1) }

        val first = repository.credit(save, event(), DAY_ONE)
        val second = repository.credit(first.save, event(), DAY_ONE)

        assertTrue(first.completed.any { it.id == quest.id })
        assertFalse(second.completed.any { it.id == quest.id }, "it paid twice")
        assertEquals(first.save.mgp, second.save.mgp - mgpOfOtherQuestsIn(second, quest.id))
    }

    /**
     * No objective reads the save, so paying one cannot complete another.
     *
     * The assumption behind crediting in a single pass. If a reward-reading objective is ever
     * added this fails, which is the point — the alternative is a quest that pays one match late
     * and nobody noticing for a release.
     */
    @Test
    fun noObjectiveReadsTheProfileSoOnePassIsEnough() {
        val rich = GameSave.new(createdAt = 3L).copy(mgp = 1_000_000)
        val poor = GameSave.new(createdAt = 3L).copy(mgp = 0)

        val fromRich = repository.credit(rich, event(), DAY_ONE)
        val fromPoor = repository.credit(poor, event(), DAY_ONE)

        assertEquals(
            fromRich.completed.map { it.id },
            fromPoor.completed.map { it.id },
            "a quest's outcome must not depend on the purse it is credited against",
        )
    }

    /** The PvP objective is never drawn, so a PvE-only build offers nothing impossible. */
    @Test
    fun thePvpObjectiveIsNeverDrawn() {
        for (created in 1L..500L) {
            val drawn = DailyQuestCatalog.forDay(DAY_ONE, created)
            assertTrue(
                drawn.none { it.objective == Objective.PlayPvpMatch },
                "creation date $created drew the PvP quest",
            )
        }
    }

    private fun mgpOfOtherQuestsIn(award: QuestAward, exclude: String): Int =
        award.completed.filterNot { it.id == exclude }.sumOf { it.reward.mgp }

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 86_400_000L

        /** 2026-01-01T12:00Z, and the next day at the same hour. */
        const val DAY_ONE = 1_767_268_800_000L
        const val DAY_TWO = DAY_ONE + DAY
    }
}
