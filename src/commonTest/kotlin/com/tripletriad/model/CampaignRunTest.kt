package com.tripletriad.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CampaignRunTest {
    private val run = CampaignRun(campaignKey = "balamb", enteredAt = 1_000L)

    @Test
    fun aFreshRunStandsOnTheFirstRungWithNothingBehindIt() {
        assertEquals(0, run.step)
        assertEquals(emptyList(), run.outcomes)
    }

    @Test
    fun winningARungRecordsItAndMovesUp() {
        val next = run.advanced(MatchResult.WIN)

        assertEquals(1, next.step)
        assertEquals(listOf(MatchResult.WIN), next.outcomes)
        assertEquals("balamb", next.campaignKey, "the ladder does not change under way")
    }

    /**
     * A draw settles nothing, so the rung is played again — and the record must not grow a row per
     * attempt. Three draws then a win is one rung that was won, not four rungs.
     */
    @Test
    fun aDrawnRungIsRecordedInPlaceAndReplacedWhenItFinallyResolves() {
        val drawnTwice = run.held(MatchResult.DRAW).held(MatchResult.DRAW)

        assertEquals(0, drawnTwice.step, "a draw does not advance")
        assertEquals(listOf(MatchResult.DRAW), drawnTwice.outcomes, "one row for one rung")

        val won = drawnTwice.advanced(MatchResult.WIN)
        assertEquals(listOf(MatchResult.WIN), won.outcomes, "the rung's last word is what stands")
        assertEquals(1, won.step)
    }

    @Test
    fun theOutcomesReadBackInLadderOrder() {
        val climbed = run
            .advanced(MatchResult.WIN)
            .held(MatchResult.DRAW)
            .advanced(MatchResult.WIN)
            .advanced(MatchResult.LOSE)

        assertEquals(
            listOf(MatchResult.WIN, MatchResult.WIN, MatchResult.LOSE),
            climbed.outcomes,
        )
    }

    @Test
    fun aLadderIsCompleteOnlyOnceItsLastRungIsBehindYou() {
        val threeRungs = 3
        val onTheLast = run.advanced(MatchResult.WIN).advanced(MatchResult.WIN)

        assertFalse(onTheLast.hasCompleted(threeRungs), "standing on a rung is not winning it")
        assertTrue(onTheLast.advanced(MatchResult.WIN).hasCompleted(threeRungs))
    }

    @Test
    fun aRunMustNameItsCampaign() {
        assertFailsWith<IllegalArgumentException> { CampaignRun(campaignKey = " ") }
    }

    @Test
    fun aRunCannotStandOnANegativeRung() {
        assertFailsWith<IllegalArgumentException> { CampaignRun(campaignKey = "cc", step = -1) }
    }
}
