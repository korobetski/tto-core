package com.tripletriad.data

import com.tripletriad.model.Boons
import com.tripletriad.model.Card
import com.tripletriad.model.DailyQuestCatalog
import com.tripletriad.model.GameRules
import com.tripletriad.model.GameSave
import com.tripletriad.model.MatchResult
import com.tripletriad.model.Objective
import com.tripletriad.model.OpenRule
import com.tripletriad.model.XpTable
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [MatchRewards.creditPvp] — what a match against a person pays, and what it costs.
 *
 * **The wager is the reason this file is careful.** Every other reward in the game only ever adds
 * to a profile; this is the one path that removes something, it is irreversible, and it acts on a
 * possession the player spent real time acquiring. So the tests below pin not only that the card
 * moves on a win and a loss, but that it moves on **neither** of the two cases that look like they
 * might — a draw, and a win with no wager.
 */
class PvpRewardsTest {
    private val profile = GameSave.new(username = "Tester", createdAt = AT)

    private val wagered = Card.idFor(block = 1, number = 42)
    private val theirs = Card.idFor(block = 1, number = 77)

    private val seeds = 0 until 40

    private fun credit(
        result: MatchResult,
        save: GameSave = profile,
        stakeLost: Int? = null,
        stakeWon: Int? = null,
        seed: Int = 1,
    ) = MatchRewards.creditPvp(save, result, GameRules(), AT, stakeLost, stakeWon, Random(seed))

    /**
     * A win pays into the **rank**, not the level.
     *
     * `PVP_XP` and `RANK` are `Save.DATAS` fields the AS3 never fed. Paying level XP here would
     * merge two ladders the original kept apart, and there would be no way back once saves carried
     * the merged number.
     */
    @Test
    fun aWinFeedsTheRankLadderAndNotTheLevelOne() {
        val credited = credit(MatchResult.WIN)

        assertTrue(credited.save.pvpXp > profile.pvpXp, "rank XP did not move")
        assertEquals(profile.xp, credited.save.xp, "level XP moved and should not have")
        assertEquals(profile.level, credited.save.level)
    }

    /** Enough wins raise the rank, which is the only thing that ladder is for. */
    @Test
    fun enoughWinsRaiseTheRank() {
        var save = profile
        repeat(RANK_TWO_WINS) { save = credit(MatchResult.WIN, save = save).save }

        assertEquals(XpTable.rankFor(save.pvpXp), save.rank)
        assertTrue(save.rank > profile.rank, "rank stuck at ${save.rank} on ${save.pvpXp} XP")
    }

    /** The payout is ordered win > draw > loss, at every seed, and never negative. */
    @Test
    fun theThreeResultsPayInOrderAtEverySeed() {
        for (seed in seeds) {
            val win = credit(MatchResult.WIN, seed = seed).reward
            val draw = credit(MatchResult.DRAW, seed = seed).reward
            val lose = credit(MatchResult.LOSE, seed = seed).reward

            assertTrue(win.mgp > draw.mgp, "seed $seed: win ${win.mgp} vs draw ${draw.mgp}")
            assertTrue(draw.mgp > lose.mgp, "seed $seed: draw ${draw.mgp} vs lose ${lose.mgp}")
            assertTrue(lose.mgp > 0, "seed $seed: a loss still pays something")
            assertTrue(win.xp > draw.xp && draw.xp > lose.xp, "seed $seed: XP out of order")
        }
    }

    /** A win takes the loser's card, and the profile keeps its own. */
    @Test
    fun aWinTakesTheStake() {
        val credited = credit(MatchResult.WIN, stakeLost = wagered, stakeWon = theirs)

        assertEquals(1, credited.save.copiesOf(theirs), "the won card did not arrive")
        assertEquals(
            profile.copiesOf(wagered),
            credited.save.copiesOf(wagered),
            "the winner's own wager was taken too",
        )
    }

    /** A loss hands the card over, and does not also collect one. */
    @Test
    fun aLossGivesUpTheStake() {
        val holding = profile.withCard(wagered)

        val credited =
            credit(MatchResult.LOSE, save = holding, stakeLost = wagered, stakeWon = theirs)

        assertEquals(0, credited.save.copiesOf(wagered), "the wager was not taken")
        assertEquals(0, credited.save.copiesOf(theirs), "a loss collected the opponent's card")
    }

    /**
     * A draw moves no card at all.
     *
     * The case worth stating: any other reading makes a draw a loss for somebody, and a wager that
     * changed hands on a 5-5 would be the kind of rule players discover by being robbed.
     */
    @Test
    fun aDrawMovesNoCard() {
        val holding = profile.withCard(wagered)

        val credited =
            credit(MatchResult.DRAW, save = holding, stakeLost = wagered, stakeWon = theirs)

        assertEquals(holding.cards, credited.save.cards)
    }

    /** With no wager, a win collects nothing — the MGP-only match is the default. */
    @Test
    fun anUnwageredWinCollectsNothing() {
        val credited = credit(MatchResult.WIN)

        assertEquals(profile.cards, credited.save.cards)
    }

    /** Losing the last copy drops the card, leaving no zero behind pretending to own it. */
    @Test
    fun losingTheLastCopyLeavesNoGhostEntry() {
        val holding = profile.withCard(wagered)

        val credited = credit(MatchResult.LOSE, save = holding, stakeLost = wagered)

        assertFalse(wagered in credited.save.cards, "a zero-copy entry survived")
        assertFalse(credited.save.ownsCard(wagered))
    }

    /** Losing one of several copies keeps the rest. */
    @Test
    fun losingOneOfSeveralCopiesKeepsTheRest() {
        val holding = profile.withCard(wagered, copies = 3)

        val credited = credit(MatchResult.LOSE, save = holding, stakeLost = wagered)

        assertEquals(2, credited.save.copiesOf(wagered))
    }

    /**
     * A deck naming the lost card is **left as it was**.
     *
     * Deliberate, and the opposite of tidy: `PveMatch.playableDecks` refuses an unaffordable deck
     * live, so the deck is unplayable until another copy arrives and then works again. Pruning it
     * here would rewrite something the player built as a side effect of losing a match.
     */
    @Test
    fun aDeckNamingTheLostCardIsNotRewritten() {
        val holding = profile.withCard(wagered)
        val withDeck = holding.copy(
            decks = listOf(
                holding.decks.first()
                    .copy(cards = listOf(wagered) + holding.cards.keys.take(4)),
            ),
        )

        val credited = credit(MatchResult.LOSE, save = withDeck, stakeLost = wagered)

        assertEquals(withDeck.decks, credited.save.decks, "the deck was edited")
        assertFalse(
            credited.save.decks.first().isAffordable(credited.save.cards),
            "the deck should now be unaffordable, which is what makes leaving it safe",
        )
    }

    /** Rule wins are recorded — `RULES_W` is about the rule, not about who was across the table. */
    @Test
    fun aWinUnderARuleIsRecordedAsInPve() {
        val rules = GameRules(open = OpenRule.ALL_OPEN)

        val credited =
            MatchRewards.creditPvp(profile, MatchResult.WIN, rules, AT, random = Random(1))

        assertNotEquals(profile.rulesWins, credited.save.rulesWins)
    }

    /** No NPC win is recorded: there was no NPC, and the Triple Team ladder is about opponents. */
    @Test
    fun noNpcWinIsRecorded() {
        val credited = credit(MatchResult.WIN)

        assertEquals(profile.npcWins, credited.save.npcWins)
    }

    /**
     * A PvP match advances "play a match" quests and can never advance "beat a named opponent".
     *
     * The empty icon id is what guarantees the second half, and it is guaranteed rather than
     * unlikely: no `Npc` has an empty `iconId`, so no `BeatOpponent` can match one.
     */
    @Test
    fun aPvpMatchCannotSatisfyANamedOpponentQuest() {
        var save = profile
        // Enough matches that any drawn "play N" quest would certainly have completed.
        repeat(QUEST_MATCHES) { save = credit(MatchResult.WIN, save = save).save }

        val beaten = save.quests.completed.keys
            .mapNotNull { DailyQuestCatalog[it] }
            .filter { it.objective is Objective.BeatOpponent }

        assertTrue(beaten.isEmpty(), "a PvP win completed $beaten")
        assertTrue(save.quests.day.isNotEmpty(), "no quest was credited at all")
    }

    /** The boons apply as they do in PvE, and are spent once. */
    @Test
    fun theBoonsRaiseThePayoutAndAreSpent() {
        val boosted = profile.copy(boons = Boons(mgp = 1, xp = 1))

        val credited = credit(MatchResult.WIN, save = boosted)
        val plain = credit(MatchResult.WIN)

        assertTrue(credited.reward.mgpBoonSpent && credited.reward.xpBoonSpent)
        assertTrue(credited.reward.mgp > plain.reward.mgp)
        assertTrue(credited.reward.xp > plain.reward.xp)
        assertEquals(0, credited.save.boons.mgp)
        assertEquals(0, credited.save.boons.xp)
    }

    /** The match is counted as ended, and the win/loss record moves. */
    @Test
    fun theRecordMoves() {
        val credited = credit(MatchResult.WIN)

        assertEquals(profile.endedMatches + 1, credited.save.endedMatches)
        assertEquals(profile.stats.wins + 1, credited.save.stats.wins)
    }

    private companion object {
        const val AT = 1_767_268_800_000L

        /** 250 rank XP is rank 2, and a win pays 60 before boons. */
        const val RANK_TWO_WINS = 5

        /** Comfortably more than any quest's target. */
        const val QUEST_MATCHES = 6
    }
}
