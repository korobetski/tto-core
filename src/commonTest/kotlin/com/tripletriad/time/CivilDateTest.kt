package com.tripletriad.time

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The calendar arithmetic behind an achievement's unlock date.
 *
 * Worth its own file because it is the one piece of this app that reimplements something every
 * platform already has: a transcription of `civil_from_days` that nothing else would catch if a
 * digit moved. The cases are the ones that break a naive implementation — the epoch itself, the
 * turn of a leap year, the 1900/2000 century rule, and a day before 1970.
 */
class CivilDateTest {
    @Test
    fun theEpochIsTheFirstOfJanuary1970() {
        assertEquals("1970-01-01", isoDate(0L))
        assertEquals("1970-01-01", isoDate(DAY - 1))
        assertEquals("1970-01-02", isoDate(DAY))
    }

    /**
     * The instant `:shared`'s `FixedClock` documents as its default, spelled out.
     *
     * The literal rather than the constant: `FixedClock` is a host seam and stays in the client,
     * so this module cannot name it. The number is what the KDoc there claims, and this is still
     * the only thing in either build that checks the claim — if it moves, this fails and the
     * comment over there is what needs reading.
     */
    @Test
    fun theClientsDefaultClockInstantIsTheDateItClaims() {
        assertEquals("2026-01-01", isoDate(1_767_268_800_000L))
    }

    /**
     * [utcDayNumber] and [isoDate] are the same boundary, read two ways.
     *
     * The number is what a daily reset compares and the string is what it stores, so the day they
     * disagree is the day a quest resets at one moment and is recorded at another.
     */
    @Test
    fun theDayNumberAndTheDateAgreeOnWhereADayEnds() {
        val lastMoment = days(20_676) + DAY - 1
        val firstMoment = days(20_677)

        assertEquals(20_676L, utcDayNumber(lastMoment))
        assertEquals(20_677L, utcDayNumber(firstMoment))
        assertEquals("2026-08-11", isoDate(lastMoment))
        assertEquals("2026-08-12", isoDate(firstMoment))
    }

    /** Before the epoch it counts backwards rather than truncating towards zero. */
    @Test
    fun theDayNumberIsNegativeBeforeTheEpoch() {
        assertEquals(0L, utcDayNumber(0L))
        assertEquals(-1L, utcDayNumber(-1L))
        assertEquals(-1L, utcDayNumber(-DAY))
        assertEquals(-2L, utcDayNumber(-DAY - 1))
    }

    @Test
    fun leapDaysAreWhereTheyBelong() {
        assertEquals("2024-02-29", isoDate(days(19_782)))
        assertEquals("2024-03-01", isoDate(days(19_783)))
        // 2000 is a leap year (divisible by 400) and 1900 was not (divisible by 100).
        assertEquals("2000-02-29", isoDate(days(11_016)))
        assertEquals("2100-02-28", isoDate(days(47_540)))
        assertEquals("2100-03-01", isoDate(days(47_541)))
    }

    @Test
    fun monthEndsAreExact() {
        assertEquals("1970-12-31", isoDate(days(364)))
        assertEquals("1971-01-01", isoDate(days(365)))
        assertEquals("2026-08-11", isoDate(days(20_676)))
    }

    /**
     * A timestamp before 1970 rounds down to its own day rather than up to the next one.
     *
     * Reachable only from a save with a nonsense date in it. Asserted because truncating division
     * gets this wrong silently, and "wrong by one day, but only for negative instants" is the kind
     * of defect that survives every casual check.
     */
    @Test
    fun instantsBeforeTheEpochRoundDown() {
        assertEquals("1969-12-31", isoDate(-1L))
        assertEquals("1969-12-31", isoDate(-DAY))
        assertEquals("1969-12-30", isoDate(-DAY - 1))
    }

    @Test
    fun everyPartIsTwoDigitsExceptTheYear() {
        assertEquals("2003-04-05", isoDate(days(12_147)))
    }

    private companion object {
        const val DAY = 86_400_000L

        fun days(count: Long): Long = count * DAY
    }
}
