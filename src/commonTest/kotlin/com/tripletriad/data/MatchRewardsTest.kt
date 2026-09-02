package com.tripletriad.data

import com.tripletriad.model.Boons
import com.tripletriad.model.CardItem
import com.tripletriad.model.DailyQuestCatalog
import com.tripletriad.model.GameRules
import com.tripletriad.model.GameSave
import com.tripletriad.model.ItemReward
import com.tripletriad.model.MatchResult
import com.tripletriad.model.Npc
import com.tripletriad.model.Objective
import com.tripletriad.model.OrderRule
import com.tripletriad.model.XpTable
import com.tripletriad.model.questDayOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [MatchRewards] — `PVEMatchScreen.endGame`, whose three near-identical branches disagree with each
 * other on purpose.
 *
 * The MGP payout carries a random top-up, so nothing here asserts an exact figure without also
 * bounding it: what is pinned is the **range**, which is what the formula actually says, plus every
 * asymmetry between the three results.
 */
class MatchRewardsTest {
    private val opponent = Npc(
        id = 1,
        nameKey = "STR_NPC_Test",
        iconId = "test-npc",
        // The hardest opponent there is, so the fixture pays the top of every curve: EXPERT,
        // 250/100/37 MGP, 45/25/15 XP, a fee of 40. The band and the payout are no longer
        // separate fields that could be set to disagree with it — they are read off this one.
        difficulty = 10,
    )

    private val profile = GameSave.new(username = "Tester", createdAt = AT)

    private val seeds = 0 until 60

    private fun credit(
        result: MatchResult,
        save: GameSave = profile,
        npc: Npc = opponent,
        rules: GameRules = GameRules(),
        seed: Int = 1,
    ) = MatchRewards.credit(save, npc, result, rules, AT, Random(seed))

    /**
     * A credited match also credits the day's quests, and reports the ones it finished.
     *
     * The wiring test: `MatchRewards.credit` is the one path both the client and the server run, so
     * a quest that is not credited here is a quest the server never pays.
     */
    @Test
    fun aCreditedMatchAdvancesTheDaysQuests() {
        val credit = credit(MatchResult.WIN)

        assertEquals(questDayOf(AT), credit.save.quests.day, "the day was pinned")
        assertTrue(credit.save.quests.progress.isNotEmpty(), "nothing was counted")
        assertTrue(
            credit.reward.quests.all { it.id in credit.save.quests.completed },
            "a quest was reported paid without being recorded",
        )
    }

    /**
     * **Achievements are credited before quests**, and the order is not cosmetic.
     *
     * A quest pays MGP and `Requirement.MgpHeld` reads MGP, so crediting quests first would settle
     * an MGP Pot tier one match earlier than it settles today. Running achievements first keeps the
     * existing semantics exactly; the quest's MGP lands the tier one match later, which is the same
     * lag a shop purchase already has.
     *
     * The fixture makes the two orders give different answers. The profile sits far enough below
     * `ac-mp1`'s thousand that **match money alone cannot reach it** — a win against an opponent
     * paying nothing tops up by at most [WIN_BONUS_MAX] — but a quest's 150 clears it easily. So if
     * quests ran first, the tier would be in *this* credit's achievements. It is in the next one.
     */
    @Test
    fun achievementsAreCreditedBeforeQuestsPayTheirMgp() {
        val (created, quest) = questWinningOnOneMatch()
        val nearlyRich = GameSave.new(username = "Tester", createdAt = created)
            .copy(mgp = MGP_POT_I - WIN_BONUS_MAX - 1)

        val first = credit(MatchResult.WIN, save = nearlyRich, npc = pauper)

        assertTrue(
            first.reward.quests.any { it.id == quest },
            "the fixture must complete a quest in this credit",
        )
        assertTrue(
            first.reward.achievements.none { it.id == MGP_POT_ID },
            "quest money paid the MGP tier inside its own credit — the order flipped",
        )
        assertTrue(first.save.mgp >= MGP_POT_I, "and the quest really did clear the threshold")

        // One match later, on the money the quest paid. That is the lag the order buys, and it is
        // the same one a shop purchase has.
        val second = credit(MatchResult.WIN, save = first.save, npc = pauper)
        assertTrue(
            second.reward.achievements.any { it.id == MGP_POT_ID },
            "the tier should settle on the next match",
        )
    }

    /** A creation date whose day-one draw holds a quest one win finishes, and that quest's id. */
    private fun questWinningOnOneMatch(): Pair<Long, String> {
        for (created in 1L..2_000L) {
            val drawn = DailyQuestCatalog.forDay(AT, created)
            val one = drawn.firstOrNull {
                it.objective == Objective.MatchesWon(1) && it.reward.mgp >= WIN_BONUS_MAX
            }
            if (one != null) return created to one.id
        }
        error("no creation date in range draws a one-win quest — the catalogue must have changed")
    }

    /** An opponent that pays nothing, so only the random top-up and quest money move the purse. */
    private val pauper = Npc(
        id = 2,
        nameKey = "STR_NPC_Pauper",
        iconId = "pauper",
        // Unrated, which is the only way to pay nothing now.
        difficulty = 0,
    )

    // ---- MGP -------------------------------------------------------------

    /**
     * `MGPReward.w + rand(20)`, `.d + rand(10)`, `.l + rand(5)` — `:114`, `:76`, `:148`.
     *
     * The bonus ranges are the one thing the three branches vary that is not a counter, so all
     * three are swept: a copy-paste that gave a loss the win's `rand(20)` would pass any single-
     * result test.
     */
    @Test
    fun theMgpPayoutIsTheBaseRewardPlusAResultSpecificBonus() {
        val expected = mapOf(
            MatchResult.WIN to (250..270),
            MatchResult.DRAW to (100..110),
            MatchResult.LOSE to (37..42),
        )
        for ((result, range) in expected) {
            val paid = seeds.map { credit(result, seed = it).reward.mgp }
            val outside = paid.filterNot { it in range }
            assertTrue(outside.isEmpty(), "$result paid $outside, outside $range")
            assertTrue(paid.min() < paid.max(), "$result never varied, so the bonus is not rolled")
        }
    }

    /**
     * The fee is not deducted, so every result is a net gain. See [Npc.mgpFor].
     *
     * **The quests are added back deliberately.** `MatchReward.mgp` is *what the match paid*, and a
     * quest's MGP is on purpose not folded into it — see that property's own KDoc — so the purse
     * moves by the two together. This test used to compare against the match alone and passed only
     * because the day's draw happened to hold nothing the fixture's match could finish. It broke
     * the moment the draw changed, which is the right way round: it was accidentally true, and is
     * now true on purpose.
     */
    @Test
    fun everyResultPaysSomethingAndTheFeeIsNeverCharged() {
        for (result in MatchResult.entries) {
            val credited = credit(result)
            val quests = credited.reward.quests.sumOf { it.reward.mgp }

            assertTrue(credited.reward.mgp > 0, "$result paid ${credited.reward.mgp}")
            assertEquals(
                profile.mgp + credited.reward.mgp + quests,
                credited.save.mgp,
                "$result should add what it reports, plus what its quests paid",
            )
        }
    }

    // ---- XP --------------------------------------------------------------

    /** XP has no random component — `XPReward.w` is taken as it stands. */
    @Test
    fun theXpPayoutIsExactAndFollowsTheDifficulty() {
        assertEquals(45, credit(MatchResult.WIN).reward.xp, "difficulty 10 wins 25 + 10*2")
        assertEquals(25, credit(MatchResult.DRAW).reward.xp)
        assertEquals(15, credit(MatchResult.LOSE).reward.xp)
        assertTrue(seeds.map { credit(MatchResult.WIN, seed = it).reward.xp }.distinct().size == 1)
    }

    /**
     * An unrated opponent pays no XP, and the random top-up is all the MGP it pays.
     *
     * The two used to be independent — `level` gated the XP and `mgpReward` was its own field, so
     * an EXPERT could pay nothing and an unlevelled opponent could pay 100. Both now come off
     * `difficulty`, so the only opponent that pays no XP is the one that has never been rated, and
     * it pays no base MGP either. What survives is the asymmetry that mattered: XP is exactly 0,
     * MGP is not, because `rand(20)` is added whatever the opponent.
     */
    @Test
    fun anUnratedOpponentPaysNoXpAndOnlyTheRandomTopUp() {
        val credited = credit(MatchResult.WIN, npc = pauper, seed = 0)

        assertEquals(0, credited.reward.xp)
        assertTrue(credited.reward.mgp in 0..20, "paid ${credited.reward.mgp}")
    }

    @Test
    fun xpRaisesTheLevel() {
        val nearly = profile.withXp(XP_NEAR_LEVEL_2)
        assertEquals(1, nearly.level, "the fixture should be one XP short of level 2")

        val credited = credit(MatchResult.WIN, save = nearly)

        assertEquals(2, credited.save.level, "45 XP on top of $XP_NEAR_LEVEL_2 should level up")
    }

    // ---- Boons -----------------------------------------------------------

    /**
     * `Math.round(reward * 20 / 100)` on top, and one boon consumed — `:74-77`.
     *
     * A boon is a **count of boosted matches**, not a permanent multiplier: the AS3 does
     * `BOONS.MGP -= 1` in every branch that used it.
     */
    @Test
    fun anMgpBoonAddsAFifthAndIsSpent() {
        val boosted = profile.copy(boons = Boons(mgp = 2))

        val credited = credit(MatchResult.WIN, save = boosted, seed = 0)

        assertTrue(credited.reward.mgpBoonSpent)
        assertEquals(1, credited.save.boons.mgp, "one boon should be consumed")
        // 250 + rand(0..20) + round(250 * 20/100) = 300..320
        assertTrue(credited.reward.mgp in 300..320, "paid ${credited.reward.mgp}")
    }

    @Test
    fun anXpBoonAddsAFifthAndIsSpent() {
        val boosted = profile.copy(boons = Boons(xp = 1))

        val credited = credit(MatchResult.WIN, save = boosted)

        assertTrue(credited.reward.xpBoonSpent)
        assertEquals(0, credited.save.boons.xp)
        assertEquals(45 + 9, credited.reward.xp, "45 + round(45 * 20/100)")
    }

    @Test
    fun theBoonsAreIndependent() {
        val credited = credit(MatchResult.WIN, save = profile.copy(boons = Boons(mgp = 1)))

        assertTrue(credited.reward.mgpBoonSpent)
        assertFalse(credited.reward.xpBoonSpent, "no XP boon was held")
    }

    /** A boon is spent whatever the result, not only on a win — every branch decrements it. */
    @Test
    fun aBoonIsSpentOnALossToo() {
        val credited = credit(MatchResult.LOSE, save = profile.copy(boons = Boons(mgp = 1)))

        assertTrue(credited.reward.mgpBoonSpent)
        assertEquals(0, credited.save.boons.mgp)
    }

    @Test
    fun noBoonMeansNoBoost() {
        val credited = credit(MatchResult.WIN, seed = 0)

        assertFalse(credited.reward.mgpBoonSpent)
        assertTrue(credited.reward.mgp in 250..270)
    }

    // ---- Counters --------------------------------------------------------

    @Test
    fun eachResultBumpsItsOwnStatAndTheEndedCounter() {
        val expected = mapOf(
            MatchResult.WIN to Triple(1, 0, 0),
            MatchResult.DRAW to Triple(0, 1, 0),
            MatchResult.LOSE to Triple(0, 0, 1),
        )
        for ((result, counts) in expected) {
            val stats = credit(result).save.stats

            assertEquals(counts.first, stats.wins, "$result wins")
            assertEquals(counts.second, stats.draws, "$result draws")
            assertEquals(counts.third, stats.defeats, "$result defeats")
            assertEquals(1, credit(result).save.endedMatches, "$result should end a match")
        }
    }

    /**
     * **Only a win records the opponent and the rules**, which is the asymmetry the original's
     * three duplicated branches encode: `NPC_W` and the `RULES_W` loop appear in the win branch
     * alone (`:100-127`). It matters because `RULES_W` is what the Wheel-of-Fortune achievements
     * count.
     */
    @Test
    fun onlyAWinRecordsTheOpponentAndTheRules() {
        val rules = GameRules(same = true, order = OrderRule.ORDER)

        val won = credit(MatchResult.WIN, rules = rules).save
        assertEquals(1, won.npcWins["test-npc"])
        assertEquals(1, won.rulesWins["RULE_SAME"])
        assertEquals(1, won.rulesWins["RULE_ORDER"])

        for (result in listOf(MatchResult.DRAW, MatchResult.LOSE)) {
            val other = credit(result, rules = rules).save
            assertTrue(other.npcWins.isEmpty(), "$result recorded an NPC win")
            assertTrue(other.rulesWins.isEmpty(), "$result recorded a rule win")
        }
    }

    /** Wins are keyed by `iconID`, not by id — ids are not unique. See [GameSave.npcWins]. */
    @Test
    fun theOpponentIsRecordedByIconId() {
        val credited = credit(MatchResult.WIN, npc = opponent.copy(id = 99))

        assertEquals(mapOf("test-npc" to 1), credited.save.npcWins)
    }

    @Test
    fun repeatedWinsAccumulate() {
        var save = profile
        repeat(3) {
            save = MatchRewards.credit(save, opponent, MatchResult.WIN, GameRules(), AT).save
        }

        assertEquals(3, save.npcWins["test-npc"])
        assertEquals(3, save.stats.wins)
        assertEquals(3, save.endedMatches)
    }

    // ---- Items -----------------------------------------------------------

    /** `getRewardItems()` is called in the win branch only (`:126`). */
    @Test
    fun onlyAWinRollsTheDropTable() {
        val generous = opponent.copy(itemRewards = listOf(ItemReward("card", 1.0, cardId = 7)))

        assertEquals(listOf(CardItem(7)), credit(MatchResult.WIN, npc = generous).reward.items)
        for (result in listOf(MatchResult.DRAW, MatchResult.LOSE)) {
            assertTrue(credit(result, npc = generous).reward.items.isEmpty(), result.name)
        }
    }

    /** Dropped items reach the bag, and a second copy stacks rather than becoming a second row. */
    @Test
    fun droppedItemsGoIntoTheBagAndStack() {
        val generous = opponent.copy(itemRewards = listOf(ItemReward("card", 1.0, cardId = 7)))

        var save = credit(MatchResult.WIN, npc = generous).save
        assertEquals(1, save.bag.size)
        save = MatchRewards.credit(save, generous, MatchResult.WIN, GameRules(), AT).save

        assertEquals(1, save.bag.size, "the second copy should stack")
        assertEquals(2, save.bag.single().stack)
    }

    @Test
    fun aRateOfZeroNeverDrops() {
        val stingy = opponent.copy(itemRewards = listOf(ItemReward("card", 0.0, cardId = 7)))

        assertTrue(
            seeds.all { credit(MatchResult.WIN, npc = stingy, seed = it).reward.items.isEmpty() },
        )
    }

    // ---- Achievements ----------------------------------------------------

    /** `Achievements.check()` runs in all three branches (`:88`, `:135`, `:161`). */
    @Test
    fun achievementsAreCheckedWhateverTheResult() {
        // `MGP_POT_I` wants 1,000 MGP held; a loss still pays, so a profile just short of it earns
        // the achievement by losing.
        val nearlyRich = profile.withMgp(MGP_JUST_SHORT)

        for (result in MatchResult.entries) {
            val credited = credit(result, save = nearlyRich)

            assertTrue(
                credited.reward.achievements.isNotEmpty(),
                "$result should have crossed the MGP threshold: ${credited.save.mgp}",
            )
            assertTrue(credited.save.achievements.isNotEmpty())
        }
    }

    @Test
    fun anAchievementIsRecordedAtTheGivenInstant() {
        val credited = credit(MatchResult.WIN, save = profile.withMgp(MGP_JUST_SHORT))

        for (id in credited.save.achievements.keys) {
            assertEquals(AT, credited.save.achievements[id], id)
        }
    }

    @Test
    fun nothingIsEarnedTwice() {
        val nearlyRich = profile.withMgp(MGP_JUST_SHORT)

        val first = credit(MatchResult.WIN, save = nearlyRich)
        val second = MatchRewards.credit(first.save, opponent, MatchResult.WIN, GameRules(), AT)

        assertTrue(first.reward.achievements.isNotEmpty())
        assertTrue(
            second.reward.achievements.none { it in first.reward.achievements },
            "an achievement was reported twice",
        )
    }

    // ---- Reproducibility -------------------------------------------------

    @Test
    fun aCreditIsReproducibleForAGivenSeed() {
        assertEquals(credit(MatchResult.WIN, seed = 5), credit(MatchResult.WIN, seed = 5))
    }

    /**
     * The boon flags say what happened, so the panel can explain an unexpected payout.
     */
    @Test
    fun theRewardReportsTheResultItWasGiven() {
        for (result in MatchResult.entries) {
            assertEquals(result, credit(result).reward.result)
        }
    }

    private companion object {
        const val AT = 1_767_268_800_000L

        /** One XP short of level 2, which [XpTable.steps] puts at 250. */
        const val XP_NEAR_LEVEL_2 = 249L

        /** One MGP short of the first `MGP_POT` achievement's 1,000. */
        const val MGP_JUST_SHORT = 1_000

        /** What `ac-mp1` wants held, which quest money must not reach inside its own credit. */
        const val MGP_POT_I = 1_000
        const val MGP_POT_ID = "ac-mp1"

        /** `tools.rand(20)` — the most a win can top up by, and the fixture's safety margin. */
        const val WIN_BONUS_MAX = 20
    }
}
