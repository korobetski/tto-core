package com.tripletriad.data

import com.tripletriad.model.GameSave
import com.tripletriad.model.MatchEvent
import com.tripletriad.model.MatchResult
import com.tripletriad.model.WeeklyQuestCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The week's log, which is the day's log with a longer period — and the two things that have to be
 * true of *both* halves once one repository serves them: they roll independently, and neither pays
 * out of the other's purse.
 *
 * `DailyQuestRepositoryTest` owns the crediting rule itself. What is here is the period.
 */
class WeeklyQuestRepositoryTest {
    @Test
    fun theWeeksDrawIsPinnedOnTheFirstCredit() {
        val credited = WeeklyQuestRepository().credit(profile, win, MONDAY)

        assertEquals("2026-08-31", credited.save.weekly.period)
        assertEquals(WeeklyQuestCatalog.PER_WEEK, credited.save.weekly.questIds.size)
        assertTrue(credited.save.quests.questIds.isEmpty(), "it wrote the daily log as well")
    }

    @Test
    fun progressAccumulatesAcrossTheWholeWeek() {
        var save = profile
        // Four wins spread over four days of one week. A log that rolled on the day would show one.
        for (day in 0 until 4) {
            save = WeeklyQuestRepository().credit(save, win, MONDAY + day * DAY).save
        }

        val quest = save.weekly.questIds.single()
        assertEquals(4, save.weekly.progressOf(quest), "the week counted a day")
    }

    @Test
    fun theNextMondayStartsAgain() {
        val within = WeeklyQuestRepository().credit(profile, win, MONDAY).save
        val after = WeeklyQuestRepository().credit(within, win, MONDAY + 7 * DAY).save

        assertEquals("2026-09-07", after.weekly.period)
        assertEquals(
            1,
            after.weekly.progressOf(after.weekly.questIds.single()),
            "progress carried across the rollover, which is how a quest pays twice",
        )
    }

    @Test
    fun theSundayNightBeforeItDoesNot() {
        // The boundary that matters, and the one a day-based week would get wrong: 23:59 on the
        // Sunday is still the same week.
        val sunday = MONDAY + 6 * DAY + DAY - 1

        val credited = WeeklyQuestRepository().credit(profile, win, sunday)

        assertEquals("2026-08-31", credited.save.weekly.period)
    }

    @Test
    fun theTwoLogsRollOnTheirOwnClocks() {
        var save = profile
        save = DailyQuestRepository().credit(save, win, MONDAY).save
        save = WeeklyQuestRepository().credit(save, win, MONDAY).save

        // A day later: the daily log has turned over and the weekly has not.
        val next = MONDAY + DAY
        save = DailyQuestRepository().credit(save, win, next).save
        save = WeeklyQuestRepository().credit(save, win, next).save

        assertEquals("2026-09-01", save.quests.period)
        assertEquals("2026-08-31", save.weekly.period)
        assertEquals(2, save.weekly.progressOf(save.weekly.questIds.single()))
    }

    @Test
    fun aFinishedWeekPaysOnceAndThenStopsCounting() {
        var save = profile
        val target = WeeklyQuestCatalog
            .all
            .first { it.id == WeeklyQuestCatalog.idsForWeek(MONDAY, save.creationDate).single() }

        var paid: Int? = null
        repeat(target.objective.target + 3) { step ->
            val before = save.mgp
            val credited = WeeklyQuestRepository().credit(save, win, MONDAY + step % 7 * DAY)
            save = credited.save
            if (credited.completed.isNotEmpty()) paid = save.mgp - before
        }

        assertEquals(target.reward.mgp, paid, "the week paid something other than its reward")
        assertTrue(save.weekly.isCompleted(target.id))
    }

    private val profile = GameSave.new(username = "Weekly", createdAt = 1_700_000_000_000L)

    private val win = MatchEvent(
        result = MatchResult.WIN,
        opponentIconId = "tt-master",
        ruleKeys = listOf("RULE_SAME", "RULE_PLUS"),
        isPvp = true,
    )

    private companion object {
        const val DAY = 86_400_000L

        /** 2026-08-31, a Monday. */
        const val MONDAY = 1_788_134_400_000L
    }
}
