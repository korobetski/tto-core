package com.tripletriad.model

import com.tripletriad.time.utcWeekStart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The week's own quest: where the week starts, what may be drawn, and that a draw is stable for
 * seven days and not for eight.
 */
class WeeklyQuestCatalogTest {
    @Test
    fun theWeekStartsOnMondayAndHoldsForSevenDays() {
        // 2026-08-31 is a Monday. Every day up to the Sunday answers with it; the next Monday does
        // not — which is the whole of the rollover.
        val monday = at("2026-08-31")

        for (day in 0 until DAYS) {
            assertEquals(
                "2026-08-31",
                utcWeekStart(monday + day * DAY),
                "day $day of that week",
            )
        }
        assertEquals("2026-09-07", utcWeekStart(monday + DAYS * DAY))
    }

    @Test
    fun theBoundaryIsStillMondayBeforeTheEpoch() {
        // 1970-01-01 was a Thursday, so every naive division cuts the week on one, and dates before
        // the epoch are where a truncating division rounds the wrong way. Written as offsets from
        // zero rather than as dates, because the fixture below cannot build a negative one.
        assertEquals("1969-12-29", utcWeekStart(0L), "the epoch's own Thursday")
        assertEquals("1969-12-29", utcWeekStart(-1L), "the Wednesday before it")
        assertEquals("1969-12-29", utcWeekStart(-3 * DAY), "its Monday")
        assertEquals("1969-12-22", utcWeekStart(-4 * DAY), "the Sunday before that")
    }

    @Test
    fun aWeekDrawsExactlyOne() {
        for (week in 0 until WEEKS) {
            val drawn = WeeklyQuestCatalog.forWeek(at("2026-08-31") + week * DAYS * DAY, 0L)
            assertEquals(WeeklyQuestCatalog.PER_WEEK, drawn.size, "week $week")
        }
    }

    @Test
    fun theDrawIsTheSameAllWeekAndChangesWithIt() {
        val monday = at("2026-08-31")
        val first = WeeklyQuestCatalog.idsForWeek(monday, CREATED)

        for (day in 1 until DAYS) {
            assertEquals(
                first,
                WeeklyQuestCatalog.idsForWeek(monday + day * DAY, CREATED),
                "the draw moved on day $day",
            )
        }
        // Not asserted to be *different* — six quests and a shuffle will repeat sometimes, and a
        // test that demanded otherwise would be asserting luck. What matters is that the seed
        // moved, which the next case pins.
        assertEquals(
            WeeklyQuestCatalog.idsForWeek(monday + DAYS * DAY, CREATED),
            WeeklyQuestCatalog.idsForWeek(monday + (DAYS + 3) * DAY, CREATED),
            "the following week is not itself stable",
        )
    }

    @Test
    fun twoCharactersOnOneDeviceAreNotHandedTheSameFortnight() {
        val monday = at("2026-08-31")
        val differing = (0 until WEEKS).count { week ->
            val at = monday + week * DAYS * DAY
            WeeklyQuestCatalog.idsForWeek(at, CREATED) !=
                WeeklyQuestCatalog.idsForWeek(at, CREATED + DAY)
        }

        assertTrue(differing > WEEKS / 2, "only $differing of $WEEKS weeks differed")
    }

    @Test
    fun everyDrawnIdResolvesBackToItsQuest() {
        for (week in 0 until WEEKS) {
            for (id in WeeklyQuestCatalog.idsForWeek(at("2026-08-31") + week * DAYS * DAY, 0L)) {
                assertNotNull(WeeklyQuestCatalog[id], "the catalogue drew an id it cannot resolve")
            }
        }
    }

    @Test
    fun aDailyIdIsNotAWeeklyOne() {
        // The two catalogues share a repository and a log shape; they must not share a namespace,
        // or a stored daily draw would resolve against the weekly table after a rollover.
        for (quest in DailyQuestCatalog.all) {
            assertNull(WeeklyQuestCatalog[quest.id], "${quest.id} is in both catalogues")
        }
        for (quest in WeeklyQuestCatalog.all) {
            assertNull(DailyQuestCatalog[quest.id], "${quest.id} is in both catalogues")
        }
    }

    @Test
    fun aWeeksTargetIsBiggerThanADaysAndPaysMore() {
        val hardestDaily = DailyQuestCatalog.all.maxOf { it.objective.target }
        val richestDaily = DailyQuestCatalog.all.maxOf { it.reward.mgp }

        for (quest in WeeklyQuestCatalog.all) {
            // The reward is the claim, not the target: "win five matches with Same" asks for the
            // same number as "play five matches" and is several evenings harder, so a comparison
            // of targets across catalogues would be comparing two different things.
            assertTrue(
                quest.reward.mgp > richestDaily,
                "${quest.id} pays ${quest.reward.mgp}, no more than a daily's $richestDaily",
            )
            assertTrue(
                quest.objective.target > 1,
                "${quest.id} is a single errand, which is a daily",
            )
        }
        assertTrue(hardestDaily > 0, "the daily catalogue is empty, so this compares nothing")
    }

    private fun at(iso: String): Long {
        val (year, month, day) = iso.split("-").map { it.toInt() }
        // Days from the civil date, by the same reckoning `CivilDate` uses in reverse.
        var days = 0L
        for (y in 1970 until year) days += if (leap(y)) 366 else 365
        for (m in 1 until month) days += MONTHS[m - 1] + if (m == 2 && leap(year)) 1 else 0
        return (days + day - 1) * DAY
    }

    private fun leap(year: Int) = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

    private companion object {
        const val DAY = 86_400_000L
        const val DAYS = 7L
        const val WEEKS = 40
        const val CREATED = 1_700_000_000_000L
        val MONTHS = listOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    }
}
