package com.tripletriad.data

import com.tripletriad.model.Achievement
import com.tripletriad.model.BoonType
import com.tripletriad.model.DailyQuest
import com.tripletriad.model.GameRules
import com.tripletriad.model.GameSave
import com.tripletriad.model.Item
import com.tripletriad.model.MatchEvent
import com.tripletriad.model.MatchResult
import com.tripletriad.model.Npc
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * What a finished match paid out — the argument to the end-of-match panel.
 *
 * `PVEMatchScreen.endGame` passes the same set through `setTimeout(rematch, …, {label, MGP, XP,
 * items, achievements, NPC, RULES})`, which is why these five fields travel together.
 *
 * @property mgpBoonSpent whether a stored MGP boon was consumed to raise [mgp] by 20%. The panel
 *   says so, because a boon disappearing silently looks like it did nothing.
 */
data class MatchReward(
    val result: MatchResult,
    val mgp: Int,
    val xp: Int,
    val items: List<Item> = emptyList(),
    val achievements: List<Achievement> = emptyList(),
    /**
     * The daily quests this match finished.
     *
     * Beside [achievements] and for the same reason: the panel that announces a match's result is
     * the only place a player is told, and a quest that completes silently is a reward nobody
     * connects to what they just did. Their MGP is **not** folded into [mgp], which stays "what the
     * match paid" — a caller that wants the total reads it off the quests.
     */
    val quests: List<DailyQuest> = emptyList(),
    val mgpBoonSpent: Boolean = false,
    val xpBoonSpent: Boolean = false,
    /**
     * What the wager moved, and only ever non-empty for a player-versus-player match.
     *
     * Apart from [mgp] and [items] rather than folded into them, because they are different facts
     * to put in front of a player: [mgp] is what the match *paid*, [items] is what dropped, and
     * this is what was **risked**. A panel that added a lost wager into the payout would show a win
     * that looks like a loss.
     *
     * [stakeMgp] is signed — positive won, negative paid. Both card lists can be non-empty at once,
     * under `TradeRule.DIRECT`, where each side keeps whatever it captured.
     */
    val stakeMgp: Int = 0,
    val cardsWon: List<Int> = emptyList(),
    val cardsLost: List<Int> = emptyList(),
)

/** The profile after a match, and what the match paid. */
data class MatchCredit(val save: GameSave, val reward: MatchReward)

/**
 * How a match's payout departs from the opponent's own table.
 *
 * [NONE] is every ordinary match, and it is the default, so the free-play economy is exactly
 * what it was. Tournaments are what this exists for: a run boosts what its rungs drop and what
 * they pay in XP, and it pays **nothing at all** for a rung that ended level.
 *
 * ### Why a drawn rung pays nothing
 *
 * A draw inside a run settles nothing — the rung is played again, as often as the player likes. A
 * draw that paid would therefore be an unbounded source of MGP and XP for a player content to keep
 * drawing, which is the one thing a tournament's entry fee is meant to price. So the round pays on
 * resolution and only on resolution.
 *
 * The boons are not spent when [pays] is false, for the same reason: a boon is bought to multiply a
 * payout, and multiplying nothing must not consume it.
 *
 * @property drop what the opponent's [ItemReward.rate]s are multiplied by. Bounded at 1 when
 *   applied — a rate is a probability, and a doubled 0.6 is a certainty, not a 1.2.
 * @property xp what the opponent's XP is multiplied by.
 * @property pays whether this match pays MGP and XP at all.
 */
data class RewardBoost(
    val drop: Double = 1.0,
    val xp: Double = 1.0,
    val pays: Boolean = true,
) {
    init {
        require(drop >= 0.0) { "a drop multiplier cannot be negative: $drop" }
        require(xp >= 0.0) { "an XP multiplier cannot be negative: $xp" }
    }

    companion object {
        /** What an ordinary match is credited with: the opponent's table, unaltered. */
        val NONE: RewardBoost = RewardBoost()
    }
}

/**
 * End-of-match crediting — `PVEMatchScreen.endGame` (`:45-165`), as a pure function.
 *
 * The original is one 120-line method with the three results as three near-identical branches, each
 * reading and writing the global `Game.PROFILE_DATAS` and each calling `Save.save` itself. The
 * duplication is why the branches disagree: only the **win** branch records rule wins and NPC wins,
 * and only it rolls the drop table. That asymmetry is reproduced — it is the rule, not a slip,
 * since `RULES_W` feeds the "win N matches with rule X" achievements — but it is now stated once
 * instead of being implied by which of three blocks a line sits in.
 *
 * ### The match fee is not charged
 *
 * `NPC.matchFee` is declared for all 85 opponents, exposed by a getter, and **never read**. Nothing
 * in `endGame` or anywhere else deducts it: a loss against a 30 MGP opponent still pays out. It is
 * clearly *meant* to be an entry cost — it rises with difficulty — and charging it would change the
 * economy from one that only ever grows into one with a real risk, which is a game-design change
 * rather than a migration. So the fee is carried as data and displayed in the opponent list, and
 * [Npc.mgpFor] no longer pretends it is deducted.
 */
object MatchRewards {
    /** `tools.rand(20)` / `rand(10)` / `rand(5)` — the random top-up per result (`:114`, `:76`). */
    private const val WIN_BONUS_MAX = 20
    private const val DRAW_BONUS_MAX = 10
    private const val LOSE_BONUS_MAX = 5

    /** `Math.round(reward * 20 / 100)` — what one boon is worth. */
    private const val BOON_PERCENT = 20
    private const val PERCENT = 100

    /** The flat player-versus-player payout. See [creditPvp] for how these were chosen. */
    private const val PVP_WIN_MGP = 100
    private const val PVP_DRAW_MGP = 40
    private const val PVP_LOSE_MGP = 15

    /** Rank XP, on the ladder `PVP_XP` and `RANK` have always described. */
    private const val PVP_WIN_XP = 60
    private const val PVP_DRAW_XP = 25
    private const val PVP_LOSE_XP = 10

    /**
     * Credits [save] with the result of a match against [npc].
     *
     * @param result the profile's result. There is deliberately no overload taking a
     *   [com.tripletriad.model.MatchOutcome]: a sudden-death draw is *not* a result — the rematch
     *   decides it — and `PVEMatchScreen.as:63-68` likewise dispatches the rematch without touching
     *   stats or rewards. [MatchResult.of] returns null for exactly that case, so a caller that
     *   forwards its null cannot credit an undecided match by accident.
     * @param rules what was in force. Read only on a win, for `RULES_W`.
     * @param at epoch millis for the achievement timestamps.
     * @param random the MGP top-up and the drop table. Uniform, where the original's
     *   `Math.round(Math.random() * n)` gives half weight to 0 and to n.
     * @param boost how a tournament run departs from [npc]'s own table. [RewardBoost.NONE] — the
     *   default — is every match outside one, so free play is unaffected by its existing.
     */
    @Suppress("LongParameterList")
    fun credit(
        save: GameSave,
        npc: Npc,
        result: MatchResult,
        rules: GameRules,
        at: Long,
        random: Random = Random.Default,
        boost: RewardBoost = RewardBoost.NONE,
    ): MatchCredit {
        // Read before the payout is worked out, and false when nothing is being paid: a boon
        // multiplies a reward, so an unpaid match must not eat one. See [RewardBoost].
        val mgpBoon = boost.pays && save.boons.mgp > 0
        val xpBoon = boost.pays && save.boons.xp > 0

        val baseMgp = if (boost.pays) npc.mgpFor(result) else 0
        val mgp = if (!boost.pays) {
            0
        } else {
            baseMgp + random.nextInt(bonusMax(result) + 1) + boost(baseMgp, mgpBoon)
        }
        val baseXp = if (boost.pays) scaled(npc.xpFor(result), boost.xp) else 0
        val xp = baseXp + boost(baseXp, xpBoon)

        // The drop roll happens whether or not the match pays: a win is a win, and the run's own
        // multiplier is what a tournament raises here. Rolled off the boosted table rather than
        // re-rolled afterwards, so one draw per entry is consumed either way.
        val items = if (result == MatchResult.WIN) {
            npc.copy(itemRewards = npc.itemRewards.map { it.boostedBy(boost.drop) })
                .rollRewards(random)
        } else {
            emptyList()
        }

        var updated = save
            .endingMatch()
            .copy(stats = save.stats.recordingStats(result))
            .withMgp(mgp)
            .withXp(xp.toLong())
        if (mgpBoon) updated = updated.copy(boons = updated.boons.spending(BoonType.MGP))
        if (xpBoon) updated = updated.copy(boons = updated.boons.spending(BoonType.XP))

        if (result == MatchResult.WIN) {
            updated = updated.withRulesWin(rules).withNpcWin(npc.iconId)
            updated = Inventory.addAll(updated, items)
        }

        val award = AchievementRepository().credit(updated, at)

        // Quests **after** achievements, and the order is load-bearing. A quest pays MGP, and
        // `Requirement.MgpHeld` reads MGP — so crediting quests first would settle an MGP Pot tier
        // one match earlier than it settles today. Running achievements first leaves the existing
        // semantics exactly as they were; the quest's MGP lands the tier one match later, which is
        // the same lag a shop purchase already has.
        //
        // `isPvp = false` unconditionally: this function takes an `Npc`, so it is player-versus-
        // environment by construction. Player versus player builds its own `MatchEvent`.
        val quests = DailyQuestRepository().credit(
            save = award.save,
            event = MatchEvent(
                result = result,
                opponentIconId = npc.iconId,
                ruleKeys = rules.activeRuleKeys(),
                isPvp = false,
            ),
            at = at,
        )

        return MatchCredit(
            save = quests.save,
            reward = MatchReward(
                result = result,
                mgp = mgp,
                xp = xp,
                items = items,
                achievements = award.earned,
                quests = quests.completed,
                mgpBoonSpent = mgpBoon,
                xpBoonSpent = xpBoon,
            ),
        )
    }

    /**
     * Credits [save] with a match against another player.
     *
     * ### Why this is a second function and not a nullable `Npc`
     *
     * Almost everything [credit] does is *about* the opponent: the payout table is `npc.mgpFor`,
     * the XP is the opponent's level band, the drops are its reward table, and a win is recorded
     * under its icon for the Triple Team achievements. A person has none of those. Threading a
     * null through would leave five branches inside one function, each meaning "not this one", and
     * the two behaviours would drift the first time either changed.
     *
     * ### The AS3 already had this ladder, and it is a different one
     *
     * `PVP_XP` and `RANK` are `Save.DATAS` fields — the original modelled a separate PvP ladder and
     * never shipped a way to feed it. So a PvP win raises the **rank**, not the level: it pays
     * through [GameSave.withPvpXp], and `PVP_MATCHES` counts it. Level XP stays what beating the
     * environment pays. That is the original's own division, honoured rather than invented.
     *
     * ### The stake is applied, not decided
     *
     * This function used to hold the policy: a win took the opponent's card, a loss gave one up, a
     * draw moved nothing. That policy has moved out, to the referee, and it had to — under
     * `TradeRule.DIRECT` the **loser** can win cards too, so "what moves" is no longer a function
     * of the result. What arrives here is the settlement already worked out, and it is applied
     * unconditionally.
     *
     * Two things this deliberately does not do, both because it cannot:
     *
     * **It does not check that the cards were owned.** The server does, before the match is
     * created, because that is the only moment at which refusing costs nobody a played game.
     *
     * **There is no escrow.** [GameSave.withMgp] floors at zero, so a player whose purse emptied
     * mid-match pays what they have and the winner is still credited in full. Affordability is
     * checked when a table is opened and again when it is joined; between those and settlement
     * there is a window, and it is accepted rather than closed — holding MGP aside would mean a
     * balance that is not the number on screen.
     *
     * @param stakeMgp signed, from this profile's side: positive won, negative paid.
     * @param cardsLost the ids this profile gives up. Empty is the common case.
     * @param cardsWon the ids this profile takes. Can be non-empty alongside [cardsLost] under
     *   Direct, where both sides keep what they captured.
     */
    @Suppress("LongParameterList")
    fun creditPvp(
        save: GameSave,
        result: MatchResult,
        rules: GameRules,
        at: Long,
        stakeMgp: Int = 0,
        cardsLost: List<Int> = emptyList(),
        cardsWon: List<Int> = emptyList(),
        random: Random = Random.Default,
    ): MatchCredit {
        val mgpBoon = save.boons.mgp > 0
        val xpBoon = save.boons.xp > 0

        val baseMgp = pvpMgpFor(result)
        val mgp = baseMgp + random.nextInt(bonusMax(result) + 1) + boost(baseMgp, mgpBoon)
        val baseXp = pvpXpFor(result)
        val xp = baseXp + boost(baseXp, xpBoon)

        var updated = save
            .endingMatch()
            .copy(stats = save.stats.recordingStats(result))
            // The payout and the wager in one call, because they are one movement of one purse.
            // Applying them separately would floor at zero twice, and a player who could not cover
            // the bet would keep the difference.
            .withMgp(mgp + stakeMgp)
            .withPvpXp(xp.toLong())
        if (mgpBoon) updated = updated.copy(boons = updated.boons.spending(BoonType.MGP))
        if (xpBoon) updated = updated.copy(boons = updated.boons.spending(BoonType.XP))

        // Rule wins are recorded as they are in PvE — `RULES_W` is about the rule, not about who
        // was on the other side of it. There is no NPC win to record.
        if (result == MatchResult.WIN) updated = updated.withRulesWin(rules)

        // Unconditional, and not inside the branches above: under Direct the loser wins cards too,
        // so what moves is the settlement's business, not the result's. See the KDoc.
        for (id in cardsWon) updated = updated.withCard(id)
        for (id in cardsLost) updated = updated.withoutCard(id)

        val award = AchievementRepository().credit(updated, at)
        val quests = DailyQuestRepository().credit(
            save = award.save,
            event = MatchEvent(
                result = result,
                // No icon, and no sentinel that could collide with one: every `Objective`
                // that names an opponent names an `Npc.iconId`, and no NPC has an empty one.
                // So a PvP win advances "win a match" and never "beat tt-master".
                opponentIconId = "",
                ruleKeys = rules.activeRuleKeys(),
                isPvp = true,
            ),
            at = at,
        )

        return MatchCredit(
            save = quests.save,
            reward = MatchReward(
                result = result,
                mgp = mgp,
                xp = xp,
                achievements = award.earned,
                quests = quests.completed,
                mgpBoonSpent = mgpBoon,
                xpBoonSpent = xpBoon,
                stakeMgp = stakeMgp,
                cardsWon = cardsWon,
                cardsLost = cardsLost,
            ),
        )
    }

    /**
     * The PvP payout, which is flat where PvE's is per-opponent.
     *
     * There is nothing to scale it by: an opponent's difficulty is what sets a PvE reward, and a
     * person has no difficulty rating. Rank would be one, and paying more for beating a higher
     * rank is a matchmaking incentive to build later, not a number to guess now.
     *
     * Calibrated against the PvE table it sits beside: a win there is 64 MGP at the FFXIV median,
     * 48 for FFVIII, 182 at the very top. A hundred puts a PvP win above the ordinary opponent and
     * below the best one, so playing people is worth the wait without making the ladder pointless.
     */
    private fun pvpMgpFor(result: MatchResult): Int = when (result) {
        MatchResult.WIN -> PVP_WIN_MGP
        MatchResult.DRAW -> PVP_DRAW_MGP
        MatchResult.LOSE -> PVP_LOSE_MGP
    }

    /** Rank XP. [com.tripletriad.model.XpTable] wants 250 for rank 2, so four or five wins. */
    private fun pvpXpFor(result: MatchResult): Int = when (result) {
        MatchResult.WIN -> PVP_WIN_XP
        MatchResult.DRAW -> PVP_DRAW_XP
        MatchResult.LOSE -> PVP_LOSE_XP
    }

    private fun bonusMax(result: MatchResult): Int = when (result) {
        MatchResult.WIN -> WIN_BONUS_MAX
        MatchResult.DRAW -> DRAW_BONUS_MAX
        MatchResult.LOSE -> LOSE_BONUS_MAX
    }

    private fun boost(base: Int, active: Boolean): Int =
        if (active) (base * BOON_PERCENT / PERCENT.toDouble()).roundToInt() else 0

    /** A multiplied payout, rounded rather than truncated so a ×1.5 of 5 is 8 and not 7. */
    private fun scaled(base: Int, by: Double): Int =
        if (by == 1.0) base else (base * by).roundToInt()
}
