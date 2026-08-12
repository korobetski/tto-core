package com.tripletriad.time

/**
 * Epoch milliseconds as a calendar day, in UTC.
 *
 * ### Why this is arithmetic and not a library
 *
 * `kotlinx-datetime` was tried and dropped: as of 0.6.2 its `Instant` is a deprecated typealias
 * onto the stdlib's in the metadata view and a distinct type in the platform views, so common code
 * that formats one compiles and then fails to link. This is Howard Hinnant's `civil_from_days`,
 * exact for every day a `Long` of milliseconds can name and needing nothing but integer arithmetic.
 * The trick that makes it a dozen lines rather than a table of month lengths is the shift: the era
 * is taken to begin on March 1st, so February's variable length falls at the *end* of a year and
 * every month's length comes out of the linear `(5 * dayOfYear + 2) / 153`.
 *
 * ### Why it lives here rather than in the client
 *
 * It began in `:shared`, which was the only place that needed it — the achievements screen renders
 * an unlock date. Daily quests need the same arithmetic in two more places the client cannot reach:
 * [com.tripletriad.data.MatchRewards], which is the one credit path both ends run, and the server,
 * which decides what day it is. Two implementations of a day boundary is the divergence that ends
 * with the client thinking it is a new day and the server disagreeing, so there is one, and it is
 * here.
 *
 * **This module still has no clock.** Nothing in `model/` or `data/` reads the wall time; they take
 * an `at`. A pure function from milliseconds to a date reads no clock either, which is why it can
 * live in `:core` while `Clock` — which needs a time zone for `localHour` — stays a host seam in
 * `:shared`.
 *
 * ### Everything here is UTC, and the ISO form says so
 *
 * There is no time zone in this module and none is wanted: a daily reset that happened at a
 * different instant per player would be a reset the server could not verify. `YYYY-MM-DD` is chosen
 * partly because it reads as a calendar date rather than as a local timestamp, and partly because
 * it is the one written order that is unambiguous in all four of the app's locales — `08/11/2026`
 * is two different days depending on who is reading it.
 */

/**
 * Days since 1970-01-01, UTC.
 *
 * The bucket a daily quest counts in, and the input a deterministic per-day draw is seeded from.
 * Exposed as well as [isoDate] because a number is what arithmetic wants — "is this the same day"
 * and "how many days since" are both wrong on strings — while the string is what a stored record
 * and a support ticket want.
 */
fun utcDayNumber(epochMillis: Long): Long = floorDiv(epochMillis, MILLIS_PER_DAY)

/**
 * Epoch milliseconds as `YYYY-MM-DD`, UTC.
 *
 * The readable form of [utcDayNumber], and what `DailyQuests.day` stores: a day key legible in a
 * JSONB document is worth its extra bytes the first time a support question needs answering.
 */
fun isoDate(epochMillis: Long): String {
    val (year, month, day) = civilFromDays(utcDayNumber(epochMillis))
    return "$year-${month.padded()}-${day.padded()}"
}

/**
 * Year, month `1..12` and day `1..31` for a count of days since 1970-01-01.
 *
 * Howard Hinnant's `civil_from_days`, transcribed. It is exact for every day a `Long` of
 * milliseconds can name and needs nothing but integer arithmetic. The trick that makes it a dozen
 * lines rather than a table of month lengths is the shift: the era is taken to begin on March 1st,
 * so February's variable length falls at the *end* of a year and every month's length comes out of
 * the linear `(5 * dayOfYear + 2) / 153`.
 *
 * `MagicNumber` is suppressed rather than answered with constants. The 5, the 2 and the 153 are
 * terms of one published formula, not independent quantities: naming them would invent meanings the
 * algorithm does not give them and make the transcription impossible to check against its source.
 */
@Suppress("MagicNumber")
private fun civilFromDays(days: Long): Triple<Long, Int, Int> {
    val shifted = days + DAYS_TO_MARCH_ERA
    val era = floorDiv(shifted, DAYS_PER_ERA)
    val dayOfEra = shifted - era * DAYS_PER_ERA
    val yearOfEra =
        (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val monthOfMarchYear = (5 * dayOfYear + 2) / 153
    val day = (dayOfYear - (153 * monthOfMarchYear + 2) / 5 + 1).toInt()
    // 0..9 are March..December; 10 and 11 are the January and February that follow, which belong
    // to the next calendar year.
    val month = (if (monthOfMarchYear < 10) monthOfMarchYear + 3 else monthOfMarchYear - 9).toInt()
    val year = yearOfEra + era * YEARS_PER_ERA + if (month <= 2) 1 else 0
    return Triple(year, month, day)
}

/**
 * Division that rounds towards negative infinity, which `/` does not.
 *
 * `java.lang.Math.floorDiv` is not in `commonMain`, and truncation would put every instant before
 * 1970 on the wrong day. Reachable only from a save with a nonsense timestamp in it, and handled
 * because a corrupt date should render as a wrong date rather than as an off-by-one nobody can
 * explain.
 */
private fun floorDiv(value: Long, divisor: Long): Long {
    val quotient = value / divisor
    return if (value % divisor != 0L && (value xor divisor) < 0) quotient - 1 else quotient
}

private fun Int.padded(): String = if (this < FIRST_TWO_DIGIT) "0$this" else "$this"

/** Below this a month or a day needs its leading zero. */
private const val FIRST_TWO_DIGIT = 10

private const val MILLIS_PER_DAY = 86_400_000L

/** Days from 1970-01-01 back to 0000-03-01, the epoch the era arithmetic counts from. */
private const val DAYS_TO_MARCH_ERA = 719_468L

/** A 400-year era: `400 * 365` days plus its 97 leap days. */
private const val DAYS_PER_ERA = 146_097L
private const val YEARS_PER_ERA = 400L
