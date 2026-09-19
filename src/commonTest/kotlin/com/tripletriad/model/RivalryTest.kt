package com.tripletriad.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * [Rivalry] — an opponent beaten often enough comes back one band sharper, and pays for it.
 *
 * The property worth pinning is that a stage is **always felt**: it moves [Npc.level], which is
 * what `MatchAiOptions.forLevel` reads, and never raises the band without raising the payout.
 */
class RivalryTest {
    private fun npc(difficulty: Int) =
        Npc(id = 1, nameKey = "STR_NPC_Test", iconId = ICON, difficulty = difficulty)

    private fun beaten(times: Int) =
        GameSave.new(username = "Tester", createdAt = 0L).copy(npcWins = mapOf(ICON to times))

    @Test
    fun aStageIsThreeWinsAndThereAreTwo() {
        assertEquals(listOf(0, 0, 0, 1, 1, 1, 2, 2, 2, 2), (0..9).map(Rivalry::stageFor))
        assertEquals(Rivalry.MAX_STAGE, Rivalry.stageFor(1_000), "a rivalry tops out")
        assertEquals(0, Rivalry.stageFor(-4), "a negative count is no count")
    }

    @Test
    fun theSheetCanSayHowCloseTheNextStageIs() {
        assertEquals(listOf(3, 2, 1, 3, 2, 1), (0..5).map { Rivalry.winsToNextStage(it) })
        assertNull(Rivalry.winsToNextStage(6), "nothing is next once the rivalry has topped out")
    }

    @Test
    fun eachStageIsOneBandHigher() {
        val novice = npc(difficulty = 1)

        assertEquals(NpcLevel.NOVICE, novice.asRivalOf(beaten(0)).level)
        assertEquals(NpcLevel.INITIATE, novice.asRivalOf(beaten(3)).level)
        assertEquals(NpcLevel.AVERAGE, novice.asRivalOf(beaten(6)).level)
        assertEquals(NpcLevel.AVERAGE, novice.asRivalOf(beaten(60)).level, "two stages at most")
    }

    /** The band and the payout come off one number, so a sharper opponent always pays more. */
    @Test
    fun aHarderOpponentPaysMore() {
        val base = npc(difficulty = 3)
        val rival = base.asRivalOf(beaten(3))

        assertEquals(5, rival.difficulty)
        for (result in MatchResult.entries) {
            assertEquals(xpRewardFor(5)[result], rival.xpFor(result), "$result")
        }
        assertEquals(mgpRewardFor(5), rival.mgpReward)
    }

    @Test
    fun theTopOfTheScaleIsACeiling() {
        assertEquals(10, npc(difficulty = 9).asRivalOf(beaten(6)).difficulty)
        assertEquals(NpcLevel.EXPERT, npc(difficulty = 10).asRivalOf(beaten(6)).level)
    }

    @Test
    fun anUnratedOrUnbeatenOpponentIsReturnedAsItIs() {
        val unrated = npc(difficulty = 0)
        assertSame(unrated, unrated.asRivalOf(beaten(9)), "an unrated row must not start paying")

        val fresh = npc(difficulty = 4)
        assertSame(fresh, fresh.asRivalOf(beaten(2)))
        assertSame(fresh, fresh.asRivalOf(GameSave.new(username = "Tester", createdAt = 0L)))
    }

    /** Wins are keyed by icon, so beating somebody else does nothing to this opponent. */
    @Test
    fun onlyWinsAgainstThisOpponentCount() {
        val save = GameSave.new(username = "Tester", createdAt = 0L)
            .copy(npcWins = mapOf("someone-else" to 9))
        assertEquals(0, npc(difficulty = 2).rivalStageFor(save))
    }

    private companion object {
        const val ICON = "rival"
    }
}
