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
 * possession the player spent real time acquiring.
 *
 * ### What moved out of here, and where the missing tests went
 *
 * This file used to assert a *policy*: a win takes the loser's card, a loss gives one up, a draw
 * moves nothing. That policy is no longer this function's, because `TradeRule.DIRECT` breaks it —
 * under Direct the loser keeps whatever they captured, so cards move towards a player who lost.
 * "What moves" is now worked out by the referee and handed in already decided.
 *
 * So what is pinned below is the narrower and more honest claim: **the lists move exactly what they
 * name, whatever the result says.** That the right lists are computed is a server test, in
 * `PvpFlowTest`, where there is a board to compute them from.
 */
class PvpRewardsTest {
    private val profile = GameSave.new(username = "Tester", createdAt = AT)

    private val wagered = Card.idFor(block = 1, number = 42)
    private val theirs = Card.idFor(block = 1, number = 77)

    private val seeds = 0 until 40

    @Suppress("LongParameterList")
    private fun credit(
        result: MatchResult,
        save: GameSave = profile,
        stakeMgp: Int = 0,
        cardsLost: List<Int> = emptyList(),
        cardsWon: List<Int> = emptyList(),
        seed: Int = 1,
    ) = MatchRewards.creditPvp(
        save = save,
        result = result,
        rules = GameRules(),
        at = AT,
        stakeMgp = stakeMgp,
        cardsLost = cardsLost,
        cardsWon = cardsWon,
        random = Random(seed),
    )

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

    /** The won cards arrive, and the profile's own are left alone. */
    @Test
    fun theWonCardsArrive() {
        val credited = credit(MatchResult.WIN, cardsWon = listOf(theirs))

        assertEquals(1, credited.save.copiesOf(theirs), "the won card did not arrive")
        assertEquals(
            profile.copiesOf(wagered),
            credited.save.copiesOf(wagered),
            "the winner's own cards were touched",
        )
    }

    /** The lost cards go, and nothing arrives that was not named. */
    @Test
    fun theLostCardsGo() {
        val holding = profile.withCard(wagered)

        val credited = credit(MatchResult.LOSE, save = holding, cardsLost = listOf(wagered))

        assertEquals(0, credited.save.copiesOf(wagered), "the wager was not taken")
        assertEquals(0, credited.save.copiesOf(theirs), "a card nobody named arrived")
    }

    /**
     * Empty lists move nothing, whatever the result was.
     *
     * The claim that used to be "a draw moves no card". It is stated over all three results now,
     * because the function no longer knows which of them is supposed to be free — a draw with an
     * empty settlement and a Direct loss with an empty one are the same instruction here.
     */
    @Test
    fun emptyListsMoveNothingAtAnyResult() {
        val holding = profile.withCard(wagered)

        for (result in MatchResult.entries) {
            val credited = credit(result, save = holding)

            assertEquals(holding.cards, credited.save.cards, "$result moved a card")
        }
    }

    /**
     * Both lists can be non-empty at once, which is what Direct does.
     *
     * The case the old shape could not express: a player who lost the match on points still keeps
     * what they captured, so cards move in both directions in one settlement.
     */
    @Test
    fun bothDirectionsCanMoveInOneSettlement() {
        val holding = profile.withCard(wagered)

        val credited = credit(
            MatchResult.LOSE,
            save = holding,
            cardsLost = listOf(wagered),
            cardsWon = listOf(theirs),
        )

        assertEquals(0, credited.save.copiesOf(wagered), "the captured card was not given up")
        assertEquals(1, credited.save.copiesOf(theirs), "the loser did not keep what they took")
    }

    /** The MGP wager is added to the payout on a win and taken out of it on a loss. */
    @Test
    fun theMgpWagerMovesBothWays() {
        val won = credit(MatchResult.WIN, stakeMgp = WAGER)
        val paid = credit(MatchResult.WIN, stakeMgp = -WAGER)

        assertEquals(WAGER, won.reward.stakeMgp)
        assertEquals(-WAGER, paid.reward.stakeMgp)
        assertEquals(
            WAGER * 2,
            won.save.mgp - paid.save.mgp,
            "the two sides of the same wager did not differ by twice it",
        )
    }

    /**
     * A wager bigger than the purse empties it and stops there.
     *
     * There is no escrow, and [GameSave.withMgp] floors at zero — so this is what "cannot cover the
     * bet" actually does. Stated rather than left to be discovered, because the thing that keeps it
     * from happening is a check on the *server*, and a reader needs to know what is behind it.
     */
    @Test
    fun aWagerBiggerThanThePurseLeavesItAtZero() {
        val credited = credit(MatchResult.LOSE, stakeMgp = -BIG_WAGER)

        assertEquals(0, credited.save.mgp)
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

        val credited = credit(MatchResult.LOSE, save = holding, cardsLost = listOf(wagered))

        assertFalse(wagered in credited.save.cards, "a zero-copy entry survived")
        assertFalse(credited.save.ownsCard(wagered))
    }

    /** Losing one of several copies keeps the rest. */
    @Test
    fun losingOneOfSeveralCopiesKeepsTheRest() {
        val holding = profile.withCard(wagered, copies = 3)

        val credited = credit(MatchResult.LOSE, save = holding, cardsLost = listOf(wagered))

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

        val credited = credit(MatchResult.LOSE, save = withDeck, cardsLost = listOf(wagered))

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

        /** Small enough that a starting purse covers it, and big enough to see. */
        const val WAGER = 50

        /** More than `GameSave.STARTING_MGP`, so the floor is what is being tested. */
        const val BIG_WAGER = 100_000

        /** 250 rank XP is rank 2, and a win pays 60 before boons. */
        const val RANK_TWO_WINS = 5

        /** Comfortably more than any quest's target. */
        const val QUEST_MATCHES = 6
    }
}
