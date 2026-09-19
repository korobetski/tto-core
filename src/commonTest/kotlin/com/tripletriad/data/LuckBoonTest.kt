package com.tripletriad.data

import com.tripletriad.model.BoonType
import com.tripletriad.model.Boons
import com.tripletriad.model.BoosterType
import com.tripletriad.model.CardItem
import com.tripletriad.model.GameRules
import com.tripletriad.model.GameSave
import com.tripletriad.model.ItemReward
import com.tripletriad.model.MatchResult
import com.tripletriad.model.Npc
import com.tripletriad.model.PotionItem
import com.tripletriad.model.PotionType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The luck potion: what it raises, what it does to a drop table, and when it is spent. */
class LuckBoonTest {
    private val table = Npc(
        id = 1,
        nameKey = "STR_NPC_Test",
        iconId = "lucky-npc",
        difficulty = 5,
        itemRewards = listOf(
            ItemReward(type = "potion", rate = COMMON, potion = PotionType.MGP),
            ItemReward(type = "potion", rate = COMMON, potion = PotionType.XP),
            ItemReward(type = "booster", rate = COMMON, booster = BoosterType.BRONZE),
            ItemReward(type = "card", rate = RARE, cardId = CARD),
        ),
    )

    private val profile = GameSave.new(username = "Tester", createdAt = 0L)

    @Test
    fun usingThePotionBuysThreeLuckyWins() {
        val used = Inventory.use(
            Inventory.add(profile, PotionItem(PotionType.LUCK)),
            PotionItem(PotionType.LUCK),
            Random(0),
        )
        assertEquals(LUCKY_WINS, used.save.boons.luck)
        assertEquals(Boons(luck = 1), Boons(luck = 2).spending(BoonType.LUCK))
        assertEquals(Boons(), Boons().spending(BoonType.LUCK), "never below none")
    }

    /**
     * The rarer haul wins, and rarity is `1 / rate`: one 5 % card (20) beats three 25 % items
     * (12), though the second roll holds fewer things.
     */
    @Test
    fun theRarerOfTwoRollsIsKept() {
        // First roll: the three common entries, not the card. Second: the card alone.
        val commonsThenCard = Scripted(HIT, HIT, HIT, MISS, MISS, MISS, MISS, HIT)
        assertEquals(listOf(CardItem(CARD)), table.rollLuckyRewards(commonsThenCard))

        // The same two rolls the other way round: the first is kept, not the last.
        val cardThenCommons = Scripted(MISS, MISS, MISS, HIT, HIT, HIT, HIT, MISS)
        assertEquals(listOf(CardItem(CARD)), table.rollLuckyRewards(cardThenCommons))
    }

    @Test
    fun aTieKeepsTheFirstRoll() {
        val mgpThenXp = Scripted(HIT, MISS, MISS, MISS, MISS, HIT, MISS, MISS)
        assertEquals(listOf(PotionItem(PotionType.MGP)), table.rollLuckyRewards(mgpThenXp))
    }

    @Test
    fun aWinAgainstATableSpendsOne() {
        val credited = credit(MatchResult.WIN, profile.copy(boons = Boons(luck = 2)))
        assertTrue(credited.reward.luckBoonSpent)
        assertEquals(1, credited.save.boons.luck)
    }

    /** Nothing is rolled on a defeat or against an empty table, so nothing is spent either. */
    @Test
    fun luckIsKeptWhenThereIsNothingToRoll() {
        val lucky = profile.copy(boons = Boons(luck = 1))

        val lost = credit(MatchResult.LOSE, lucky)
        assertFalse(lost.reward.luckBoonSpent)
        assertEquals(1, lost.save.boons.luck)

        val bare = credit(MatchResult.WIN, lucky, table.copy(itemRewards = emptyList()))
        assertFalse(bare.reward.luckBoonSpent)
        assertEquals(1, bare.save.boons.luck)
    }

    /** Over many wins, a lucky one finds the rare card clearly more often than a plain one. */
    @Test
    fun luckRaisesTheRareDropRate() {
        fun cards(boons: Boons): Int = SEEDS.count { seed ->
            MatchRewards.credit(
                profile.copy(boons = boons),
                table,
                MatchResult.WIN,
                GameRules(),
                0L,
                Random(seed),
            ).reward.items.any { it is CardItem }
        }
        val plain = cards(Boons())
        val lucky = cards(Boons(luck = 1))
        assertTrue(lucky > plain * LIFT, "lucky $lucky against plain $plain")
    }

    @Test
    fun theShopSellsItInEveryFormat() {
        for (shelf in listOf(ShopCatalog.ff14, ShopCatalog.ff8)) {
            assertTrue(shelf.any { it.item == PotionItem(PotionType.LUCK) })
        }
    }

    private fun credit(result: MatchResult, save: GameSave, npc: Npc = table) =
        MatchRewards.credit(save, npc, result, GameRules(), 0L, Random(1))

    /** A generator whose uniform draws are written out, one per table entry. */
    private class Scripted(vararg draws: Double) : Random() {
        private val queue = ArrayDeque(draws.toList())

        override fun nextBits(bitCount: Int): Int = error("only doubles are scripted")

        override fun nextDouble(): Double = queue.removeFirst()
    }

    private companion object {
        const val COMMON = 0.25
        const val RARE = 0.05
        const val CARD = 42
        const val HIT = 0.01
        const val MISS = 0.99
        const val LUCKY_WINS = 3

        /** Two rolls at 5 % find the card 9.75 % of the time; asking for 1.5x leaves room. */
        const val LIFT = 1.5
        val SEEDS = 0 until 4_000
    }
}
