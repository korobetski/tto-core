package com.tripletriad.data

import com.tripletriad.model.CampaignRun
import com.tripletriad.model.CardItem
import com.tripletriad.model.GameRules
import com.tripletriad.model.GameSave
import com.tripletriad.model.ItemReward
import com.tripletriad.model.MatchResult
import com.tripletriad.model.Npc
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [CampaignRewards] — entering a ladder, climbing it, and what finishing one pays.
 *
 * Everything here is arithmetic on a profile, so the fixtures are hand-written: the shipped ladders
 * are `CampaignBundleTest`'s subject in the client, and pinning a payout against data that is meant
 * to be re-balanced would make this file fail every time a number moved.
 */
class CampaignRewardsTest {
    private val rung = Npc(
        id = 1,
        nameKey = "STR_NPC_Rung",
        iconId = "rung",
        // The top of the scale: EXPERT, 250/100/37 MGP, 45/25/15 XP. Written as the one number
        // the others are read off, since a rung that named a band and a payout separately could
        // name two that disagree.
        difficulty = 10,
        itemRewards = listOf(ItemReward(type = "card", rate = 0.4, cardId = CARD)),
    )

    private val ladder = Campaign(
        key = "test",
        nameKey = "APP_CAMPAIGN_TEST",
        format = "ff8-standard",
        fee = 200,
        steps = listOf(CampaignStep(rung), CampaignStep(rung.copy(id = 2, iconId = "top"))),
        dropMultiplier = 2.0,
        xpMultiplier = 2.0,
        payoutMultiplier = 2.0,
        finalReward = listOf(ItemReward(type = "card", rate = 1.0, cardId = PRIZE)),
    )

    private val rich = GameSave.new(username = "Tester", createdAt = AT).withMgp(1_000)

    // --- entering ---------------------------------------------------------------------------

    @Test
    fun enteringPaysTheFeeAndOpensTheRun() {
        val before = rich.mgp
        val entered = assertIs<CampaignEntry.Entered>(
            CampaignRewards.enter(rich, ladder, TODAY, AT),
        )

        assertEquals(before - ladder.fee, entered.save.mgp)
        assertEquals("test", entered.run.campaignKey)
        assertEquals(0, entered.run.step)
        assertEquals(entered.run, entered.save.campaignRun)
    }

    /**
     * The stamp goes down at **entry**, not at resolution.
     *
     * The whole of the daily limit rests on this. Stamping when a run ends would leave a player who
     * loses on the first rung free to pay and try again all day, which is the case the limit exists
     * for — and the one a settlement-time stamp would never see.
     */
    @Test
    fun theDaysEntryIsSpentTheMomentItIsPaidFor() {
        val entered = assertIs<CampaignEntry.Entered>(
            CampaignRewards.enter(rich, ladder, TODAY, AT),
        )

        assertTrue(entered.save.hasEnteredToday("test", TODAY))
        assertFalse(entered.save.hasEnteredToday("test", TOMORROW), "it reopens at 00:00 UTC")
        assertFalse(entered.save.hasEnteredToday("other", TODAY), "one ladder, not all of them")
    }

    @Test
    fun aSecondEntryTheSameDayIsRefusedEvenAfterTheRunIsOver() {
        val entered = assertIs<CampaignEntry.Entered>(
            CampaignRewards.enter(rich, ladder, TODAY, AT),
        )
        val knockedOut = CampaignRewards.forfeit(entered.save)

        assertNull(knockedOut.campaignRun, "the defeat closed the run")
        assertEquals(
            CampaignEntry.EnteredToday,
            CampaignRewards.enter(knockedOut, ladder, TODAY, AT),
        )
        assertIs<CampaignEntry.Entered>(
            CampaignRewards.enter(knockedOut, ladder, TOMORROW, AT),
            "tomorrow is a new attempt",
        )
    }

    @Test
    fun anOpenRunBlocksEnteringAnything() {
        val running = rich.copy(campaignRun = CampaignRun(campaignKey = "elsewhere"))

        assertEquals(
            CampaignEntry.AlreadyRunning,
            CampaignRewards.enter(running, ladder, TODAY, AT),
        )
    }

    /** `withMgp` floors at zero, so without this check being broke would be the cheapest way in. */
    @Test
    fun anUnaffordableLadderTakesNothingAtAll() {
        val broke = GameSave.new(username = "Tester", createdAt = AT)

        assertEquals(CampaignEntry.CannotAfford, CampaignRewards.enter(broke, ladder, TODAY, AT))
    }

    @Test
    fun aGatedLadderIsRefusedUntilItsAchievementIsHeld() {
        val gated = ladder.copy(requiresAchievement = "ac-cmp-balamb")

        assertEquals(CampaignEntry.Locked, CampaignRewards.enter(rich, gated, TODAY, AT))
        assertIs<CampaignEntry.Entered>(
            CampaignRewards.enter(rich.withAchievement("ac-cmp-balamb", AT), gated, TODAY, AT),
        )
    }

    @Test
    fun aLadderThisBuildDoesNotKnowChargesNothing() {
        assertEquals(
            CampaignEntry.NoSuchLadder,
            CampaignRewards.enter(rich, campaign = null, day = TODAY, at = AT),
        )
    }

    // --- climbing ---------------------------------------------------------------------------

    /**
     * A drawn rung pays nothing, and that is what makes unlimited replays safe.
     *
     * Over many seeds, because the MGP carries a random top-up: a draw that paid "only" the top-up
     * would still be an unbounded income for a player content to keep drawing.
     */
    @Test
    fun aDrawnRungPaysNoMgpAndNoXpAtAll() {
        for (seed in 0 until SEEDS) {
            val credit = MatchRewards.credit(
                save = rich,
                npc = rung,
                result = MatchResult.DRAW,
                rules = GameRules(),
                at = AT,
                random = Random(seed),
                boost = CampaignRewards.rungBoost(ladder, MatchResult.DRAW),
            )

            assertEquals(0, credit.reward.mgp, "seed $seed")
            assertEquals(0, credit.reward.xp, "seed $seed")
            assertEquals(rich.mgp, credit.save.mgp, "seed $seed: the purse did not move")
        }
    }

    /** A boon multiplies a payout. There is no payout, so it must still be there afterwards. */
    @Test
    fun aDrawnRungDoesNotEatABoon() {
        val boosted = rich.copy(boons = rich.boons.copy(mgp = 1, xp = 1))
        val credit = MatchRewards.credit(
            save = boosted,
            npc = rung,
            result = MatchResult.DRAW,
            rules = GameRules(),
            at = AT,
            random = Random(1),
            boost = CampaignRewards.rungBoost(ladder, MatchResult.DRAW),
        )

        assertFalse(credit.reward.mgpBoonSpent)
        assertFalse(credit.reward.xpBoonSpent)
        assertEquals(1, credit.save.boons.mgp)
        assertEquals(1, credit.save.boons.xp)
    }

    @Test
    fun aWonRungPaysTheLaddersMultipleOfTheOpponentsXp() {
        val plain = xpFor(RewardBoost.NONE)
        val inRun = xpFor(CampaignRewards.rungBoost(ladder, MatchResult.WIN))

        assertTrue(plain > 0, "the fixture pays something to multiply")
        assertEquals(plain * 2, inRun, "×2 is what the ladder declares")
    }

    /**
     * The rung's drop rate is multiplied, and the product is a probability rather than a ratio.
     *
     * Counted over seeds rather than asserted on one: a rate is compared against a uniform draw, so
     * the only honest statement about it is how often it lands.
     */
    @Test
    fun aWonRungDropsAtTheLaddersMultipleOfTheOpponentsRate() {
        val plain = dropsIn(RewardBoost.NONE)
        val inRun = dropsIn(CampaignRewards.rungBoost(ladder, MatchResult.WIN))

        assertTrue(inRun > plain, "0.4 doubled should land more often than 0.4: $inRun vs $plain")
        assertTrue(inRun > SEEDS / 2, "0.8 of $SEEDS should land well over half: $inRun")
    }

    @Test
    fun aDoubledRateNeverExceedsCertainty() {
        val certain = ItemReward(type = "card", rate = 0.6, cardId = CARD).boostedBy(2.0)

        assertEquals(1.0, certain.rate)
    }

    // --- finishing --------------------------------------------------------------------------

    @Test
    fun finishingPaysTheLaddersMultipleOfItsOwnEntryFee() {
        val entered = assertIs<CampaignEntry.Entered>(
            CampaignRewards.enter(rich, ladder, TODAY, AT),
        )
        val paid = CampaignRewards.finish(entered.save, ladder, AT, Random(1))

        assertEquals(FEE * 2, paid.mgp, "a tournament won returns more than it cost")
        assertEquals(entered.save.mgp + FEE * 2, paid.save.mgp)
        assertTrue(paid.mgp > FEE, "otherwise the stake is a fine, not a wager")
    }

    @Test
    fun finishingClosesTheRunAndRecordsTheLadderAsWon() {
        val entered = assertIs<CampaignEntry.Entered>(
            CampaignRewards.enter(rich, ladder, TODAY, AT),
        )
        val paid = CampaignRewards.finish(entered.save, ladder, AT, Random(1))

        assertNull(paid.save.campaignRun, "the run is over")
        assertEquals(1, paid.save.campaignWins["test"], "and it is remembered after it is gone")
    }

    @Test
    fun finishingHandsOverExactlyOneItemFromTheLot() {
        val paid = CampaignRewards.finish(rich, ladder, AT, Random(1))

        assertEquals(listOf(CardItem(PRIZE)), paid.items)
        // Into the bag, exactly where a rung's own drop lands — see `MatchRewards.credit`. A prize
        // that was reported and not stored is the failure this line is here for.
        assertEquals(1, Inventory.count(paid.save, CardItem(PRIZE)))
    }

    /**
     * The lot is a weighted **choice**, not a per-entry roll: exactly one item, always.
     *
     * With a ten-to-one split both entries must be reachable, and the common one must dominate —
     * which is what "a 10% chance of the rarer card instead" means.
     */
    @Test
    fun theLotDrawsOneItemWeightedByItsRates() {
        val split = ladder.copy(
            finalReward = listOf(
                ItemReward(type = "card", rate = 0.9, cardId = PRIZE),
                ItemReward(type = "card", rate = 0.1, cardId = RARE),
            ),
        )
        val drawn = (0 until SEEDS).map { CampaignRewards.drawLot(split, Random(it)) }

        assertTrue(drawn.all { it.size == 1 }, "every draw pays exactly one thing")
        assertTrue(drawn.any { it == listOf(CardItem(RARE)) }, "the rare half is reachable")
        assertTrue(
            drawn.count { it == listOf(CardItem(PRIZE)) } > drawn.size / 2,
            "the common half dominates",
        )
    }

    /** A ladder with no lot pays only MGP. Not an error — it is a ladder that names no prize. */
    @Test
    fun aLadderWithNoLotPaysNothingFromIt() {
        val paid = CampaignRewards.finish(rich, ladder.copy(finalReward = emptyList()), AT)

        assertEquals(emptyList(), paid.items)
        assertEquals(FEE * 2, paid.mgp)
    }

    /**
     * The ladder's multiplier does not reach its own lot.
     *
     * The prize belongs to the tournament rather than to the last opponent, so a rate in the lot is
     * a share of a whole and there is nothing for a drop multiplier to mean. Stated by giving the
     * ladder an absurd multiplier and showing the split is unmoved.
     */
    @Test
    fun theDropMultiplierDoesNotReachTheFinalLot() {
        val split = listOf(
            ItemReward(type = "card", rate = 0.9, cardId = PRIZE),
            ItemReward(type = "card", rate = 0.1, cardId = RARE),
        )
        val plain = ladder.copy(dropMultiplier = 1.0, finalReward = split)
        val boosted = ladder.copy(dropMultiplier = 10.0, finalReward = split)

        for (seed in 0 until SEEDS) {
            assertEquals(
                CampaignRewards.drawLot(plain, Random(seed)),
                CampaignRewards.drawLot(boosted, Random(seed)),
                "seed $seed",
            )
        }
    }

    // --- the run's own bookkeeping ----------------------------------------------------------

    @Test
    fun aWinAdvancesAndADrawHoldsTheSameRung() {
        val run = CampaignRun(campaignKey = "test")

        assertEquals(1, CampaignRewards.advance(run, MatchResult.WIN).step)
        assertEquals(0, CampaignRewards.advance(run, MatchResult.DRAW).step)
        assertEquals(0, CampaignRewards.advance(run, MatchResult.LOSE).step)
    }

    /** Two rungs, so the run is complete only once the second is behind it. */
    @Test
    fun theRunIsCompleteWhenItHasWalkedOffTheTopOfTheLadder() {
        var run = CampaignRun(campaignKey = "test")
        run = CampaignRewards.advance(run, MatchResult.WIN)
        assertFalse(run.hasCompleted(ladder.steps.size), "one of two")

        run = CampaignRewards.advance(run, MatchResult.WIN)
        assertTrue(run.hasCompleted(ladder.steps.size), "two of two")
    }

    /** A defeat closes the run and pays nothing — the stake is what was at risk. */
    @Test
    fun aDefeatEndsTheRunWithoutPayingForIt() {
        val entered = assertIs<CampaignEntry.Entered>(
            CampaignRewards.enter(rich, ladder, TODAY, AT),
        )
        val out = CampaignRewards.forfeit(entered.save)

        assertNull(out.campaignRun)
        assertEquals(entered.save.mgp, out.mgp, "nothing is refunded")
        assertNull(out.campaignWins["test"])
    }

    private fun xpFor(boost: RewardBoost): Int = MatchRewards.credit(
        save = rich,
        npc = rung,
        result = MatchResult.WIN,
        rules = GameRules(),
        at = AT,
        random = Random(1),
        boost = boost,
    ).reward.xp

    private fun dropsIn(boost: RewardBoost): Int = (0 until SEEDS).count { seed ->
        MatchRewards.credit(
            save = rich,
            npc = rung,
            result = MatchResult.WIN,
            rules = GameRules(),
            at = AT,
            random = Random(seed),
            boost = boost,
        ).reward.items.isNotEmpty()
    }

    private companion object {
        const val AT = 1_700_000_000_000L
        const val TODAY = "2023-11-14"
        const val TOMORROW = "2023-11-15"
        const val FEE = 200

        // FFVIII cards, which live in block 8 now — see `CardSet`.
        const val CARD = 2049
        const val PRIZE = 2063
        const val RARE = 2077
        const val SEEDS = 200
    }
}
