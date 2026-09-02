package com.tripletriad.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What a difficulty pays, as arithmetic — the other half of `NpcRatingTest`.
 *
 * `NpcRating` measures how hard an opponent is; these four functions turn that one number into
 * everything the opponent list shows and the match screen pays. They live in `model` rather than
 * beside the measurement because [Npc] reads them, and an [Npc] cannot see `data`.
 *
 * Nothing here simulates anything, which is why it is a separate file: these are total functions of
 * one small integer, and they are pinned as literals rather than recomputed from their own
 * constants — a test that reapplies the formula only proves the formula was applied twice.
 */
class NpcBalanceTest {
    /**
     * The three curves, written out — the numbers a player actually sees.
     *
     * These are what the fractions read off the authored FFXIV table produce
     * (`floor(0.40 * win)`, `floor(0.15 * win)`) and what anchoring the fee's square root between 5
     * and 40 produces. Changing any of them should have to be typed here.
     */
    @Test
    fun theShippedCurvesAreTheOnesTheAuthoredTableImplies() {
        assertEquals(
            listOf(25, 50, 75, 100, 125, 150, 175, 200, 225, 250),
            DIFFICULTY_RANGE.map { mgpRewardFor(it).win },
        )
        assertEquals(
            listOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100),
            DIFFICULTY_RANGE.map { mgpRewardFor(it).draw },
            "draw is two fifths of a win, floored",
        )
        assertEquals(
            listOf(3, 7, 11, 15, 18, 22, 26, 30, 33, 37),
            DIFFICULTY_RANGE.map { mgpRewardFor(it).lose },
            "loss is 15% of a win, floored",
        )
        assertEquals(
            listOf(5, 10, 15, 20, 25, 30, 30, 35, 35, 40),
            DIFFICULTY_RANGE.map(::matchFeeFor),
            "the fee flattens where a straight line would not",
        )
        assertEquals(
            listOf(27, 29, 31, 33, 35, 37, 39, 41, 43, 45),
            DIFFICULTY_RANGE.map { xpRewardFor(it).win },
            "XP moves every step instead of every second one",
        )
        assertEquals(
            xpRewardFor(DIFFICULTY_RANGE.first),
            NpcLevel.NOVICE.xpReward,
            "and the bottom of the curve is exactly what the first band used to pay",
        )
    }

    /**
     * Harder pays more, a loss always pays something, and the fee never exceeds a win.
     *
     * The last two are the properties the shipped AS3 data broke: one opponent declared `l: 0`, so
     * losing to it after paying its fee returned nothing at all.
     */
    @Test
    fun thePayoutRisesWithTheDifficultyAndALossAlwaysPays() {
        val payouts = DIFFICULTY_RANGE.map(::mgpRewardFor)

        assertEquals(payouts.sortedBy { it.win }, payouts, "harder must never pay less")
        assertTrue(payouts.all { it.lose > 0 }, "a loss must always pay something")
        assertTrue(payouts.all { it.draw in it.lose..it.win }, "a draw sits between the two")

        for (difficulty in DIFFICULTY_RANGE) {
            assertTrue(
                matchFeeFor(difficulty) < mgpRewardFor(difficulty).win,
                "difficulty $difficulty charges more than a win returns",
            )
        }
        assertEquals(
            DIFFICULTY_RANGE.map(::matchFeeFor).sorted(),
            DIFFICULTY_RANGE.map(::matchFeeFor),
            "harder must never be cheaper to sit down against",
        )
    }

    /**
     * Every band has a skill level, and no rated one is [NpcLevel.NONE].
     *
     * `NONE` pays no XP at all — see [Npc.xpFor] — so a *rated* opponent in it would cost a match
     * fee and return nothing towards a level. That is a state the data should not be able to
     * express, and the only way to reach it is by never rating the opponent at all.
     */
    @Test
    fun everyRatedBandHasALevelAndNoneOfThemPaysNothing() {
        val levels = DIFFICULTY_RANGE.map(::npcLevelFor)

        assertTrue(NpcLevel.NONE !in levels, "produced $levels")
        assertEquals(levels.sortedBy { it.modifier }, levels, "harder must never mean less XP")
        assertEquals(NpcLevel.EXPERT, npcLevelFor(DIFFICULTY_RANGE.last))
        assertEquals(NpcLevel.NOVICE, npcLevelFor(DIFFICULTY_RANGE.first))
    }

    /**
     * 0 is not the easiest opponent, it is no opponent — every curve answers it with nothing.
     *
     * `npcs.json` stores a difficulty and nothing else, so this row is what an entry that has never
     * been through `NpcRating` reads as. Answering it with the difficulty-1 numbers would make an
     * unrated opponent look like a cheap, payable one; throwing would make an unrated opponent
     * uncrashable only as long as nobody opened the opponent list.
     */
    @Test
    fun anUnratedOpponentHasNoBandNoFeeAndNoPayout() {
        assertEquals(NpcLevel.NONE, npcLevelFor(0))
        assertEquals(0, matchFeeFor(0))
        assertEquals(MgpReward(), mgpRewardFor(0))
    }

    @Test
    fun aDifficultyOffTheScaleIsAProgrammingError() {
        for (impossible in listOf(11, -1)) {
            assertFailsWith<IllegalArgumentException> { npcLevelFor(impossible) }
            assertFailsWith<IllegalArgumentException> { mgpRewardFor(impossible) }
            assertFailsWith<IllegalArgumentException> { matchFeeFor(impossible) }
        }
    }
}
