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
)

/** The profile after a match, and what the match paid. */
data class MatchCredit(val save: GameSave, val reward: MatchReward)

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
     */
    @Suppress("LongParameterList")
    fun credit(
        save: GameSave,
        npc: Npc,
        result: MatchResult,
        rules: GameRules,
        at: Long,
        random: Random = Random.Default,
    ): MatchCredit {
        val mgpBoon = save.boons.mgp > 0
        val xpBoon = save.boons.xp > 0

        val baseMgp = npc.mgpFor(result)
        val mgp = baseMgp + random.nextInt(bonusMax(result) + 1) + boost(baseMgp, mgpBoon)
        val xp = npc.xpFor(result) + boost(npc.xpFor(result), xpBoon)
        val items = if (result == MatchResult.WIN) npc.rollRewards(random) else emptyList()

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
     * ### The stake
     *
     * Losing hands one card to the winner, who gains a copy — see [GameSave.withoutCard] for what
     * that does and does not touch. A draw moves nothing: both players keep what they put up, which
     * is the only reading that does not make a draw a loss for somebody.
     *
     * **This function does not check that the wager was owned.** The server does, before the match
     * is created, because that is the only moment at which refusing costs nobody a played game.
     *
     * @param opponentName who was played. Recorded on the match, not on the profile: there is no
     *   `PVP_W` counter and no achievement keyed by opponent, so nothing on the save wants it.
     * @param stakeLost the card this profile put up, or null when the match was played for MGP
     *   only. Non-null on both sides of a wagered match — each is the other's [stakeWon].
     * @param stakeWon the card this profile takes, on a win.
     */
    @Suppress("LongParameterList")
    fun creditPvp(
        save: GameSave,
        result: MatchResult,
        rules: GameRules,
        at: Long,
        stakeLost: Int? = null,
        stakeWon: Int? = null,
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
            .withMgp(mgp)
            .withPvpXp(xp.toLong())
        if (mgpBoon) updated = updated.copy(boons = updated.boons.spending(BoonType.MGP))
        if (xpBoon) updated = updated.copy(boons = updated.boons.spending(BoonType.XP))

        // Rule wins are recorded as they are in PvE — `RULES_W` is about the rule, not about who
        // was on the other side of it. There is no NPC win to record.
        if (result == MatchResult.WIN) {
            updated = updated.withRulesWin(rules)
            stakeWon?.let { updated = updated.withCard(it) }
        }
        if (result == MatchResult.LOSE) {
            stakeLost?.let { updated = updated.withoutCard(it) }
        }

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
}
