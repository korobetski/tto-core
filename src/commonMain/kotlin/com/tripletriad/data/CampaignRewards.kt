package com.tripletriad.data

import com.tripletriad.model.Achievement
import com.tripletriad.model.CampaignRun
import com.tripletriad.model.GameSave
import com.tripletriad.model.Item
import com.tripletriad.model.MatchResult
import com.tripletriad.model.questDayOf
import kotlin.random.Random

/**
 * Why a tournament could not be entered, or [Entered] and the profile that paid for it.
 *
 * Every refusal is a *state of the player*, not an error: they have a run going, they already spent
 * today's attempt, they cannot afford it, or they have not earned the ladder yet. So none of them
 * throws and none of them is a status code — the caller answers with the profile unchanged, and the
 * client can see for itself which of the four it was.
 */
sealed interface CampaignEntry {
    /** Paid, and [save] holds the open run. */
    data class Entered(val save: GameSave, val run: CampaignRun) : CampaignEntry

    /** No such ladder in this build's catalogue — a version disagreement, not a payment problem. */
    data object NoSuchLadder : CampaignEntry

    /** The ladder is gated behind an achievement this profile has not earned. */
    data object Locked : CampaignEntry

    /**
     * A run in this ladder — or another — is already open.
     *
     * One at a time, and deliberately: a player who could hold two runs could keep a losing one
     * parked forever and never spend the defeat it is heading for.
     */
    data object AlreadyRunning : CampaignEntry

    /** Today's entry to this ladder is spent. Opens again at 00:00 UTC. */
    data object EnteredToday : CampaignEntry

    /** The purse does not cover [Campaign.fee]. */
    data object CannotAfford : CampaignEntry
}

/** A finished tournament: the profile it wrote, what it paid, and what it dropped. */
data class CampaignPayout(
    val save: GameSave,
    val mgp: Int,
    val items: List<Item>,
    val achievements: List<Achievement> = emptyList(),
)

/**
 * Entering, climbing and finishing a tournament — the arithmetic, with no I/O and no HTTP.
 *
 * ### What a run changes about a match
 *
 * A rung is an ordinary refereed match against an ordinary opponent, with three differences, and
 * [rungBoost] is all three:
 *
 * - **the opponent's own stake is not collected.** It never was — `Npc.matchFee` is data nothing
 *   deducts, see [MatchRewards] — so inside a run this is true by construction rather than by a
 *   branch, and the entry fee is the only thing the player pays.
 * - **drops and XP are multiplied** by the ladder's own rates.
 * - **a drawn rung pays nothing**, because it settles nothing and may be replayed without limit.
 *
 * ### The prize belongs to the tournament, not to the last opponent
 *
 * [finish] pays [Campaign.payout] and draws one item from [Campaign.finalReward]. Beating the final
 * rung is what triggers it, but it does not pass through that opponent's drop table and
 * [Campaign.dropMultiplier] does **not** apply to it: a lot a multiplier could inflate would have
 * to be authored small enough to survive being multiplied, which is a worse way to say what it is
 * worth.
 */
object CampaignRewards {

    /**
     * Pays for a place in [campaign], or says why the player cannot have one.
     *
     * @param day the UTC day key, from [questDayOf]. Passed in rather than read from a clock so
     *   that this stays a pure function and a test can name the day it is asking about.
     */
    fun enter(save: GameSave, campaign: Campaign?, day: String, at: Long): CampaignEntry = when {
        campaign == null -> CampaignEntry.NoSuchLadder
        !campaign.isUnlockedFor(save) -> CampaignEntry.Locked
        save.campaignRun != null -> CampaignEntry.AlreadyRunning
        save.hasEnteredToday(campaign.key, day) -> CampaignEntry.EnteredToday
        save.mgp < campaign.fee -> CampaignEntry.CannotAfford
        else -> {
            val run = CampaignRun(campaignKey = campaign.key, enteredAt = at)
            CampaignEntry.Entered(
                // The fee and the stamp in one step. `GameSave.withMgp` floors at zero, so the
                // affordability check above is the only thing standing between a broke player and a
                // free entry — see `AccountRoutes`, which learned that the hard way.
                save = save.withMgp(-campaign.fee).enteringCampaign(run, day),
                run = run,
            )
        }
    }

    /** How [campaign] departs from a rung's own reward table, once [result] is known. */
    fun rungBoost(campaign: Campaign, result: MatchResult): RewardBoost = RewardBoost(
        drop = campaign.dropMultiplier,
        xp = campaign.xpMultiplier,
        pays = result != MatchResult.DRAW,
    )

    /**
     * The run with [result] written into it — one step on if it was a win, the same rung otherwise.
     *
     * It does **not** decide whether the run is over. Whether the advanced run has run out of
     * ladder is [CampaignRun.hasCompleted], which needs the catalogue, and whether a defeat ends it
     * is [forfeit]; keeping both out of here leaves this function about the run alone.
     *
     * A defeat is recorded rather than discarded because the summary screen shows it: the player
     * is told which rung they fell at, and that is the rung's own outcome.
     */
    fun advance(run: CampaignRun, result: MatchResult): CampaignRun =
        if (result == MatchResult.WIN) run.advanced(result) else run.held(result)

    /**
     * Closes a run that ended in defeat. The stake is gone and nothing is paid.
     *
     * Its own function rather than a branch in [finish] because it does something [finish] must
     * never do by accident: it is the only path that ends a run without paying for it.
     */
    fun forfeit(save: GameSave): GameSave = save.leavingCampaign()

    /**
     * Closes a run that reached the top: the payout, the lot, and the ladder's achievement.
     *
     * The MGP is [Campaign.payout] — a multiple of what entering cost, above 1 by intent, because a
     * tournament won has to return more than it took or its stake is a fine rather than a wager.
     *
     * @param random draws the lot. One item, weighted by [com.tripletriad.model.ItemReward.rate].
     */
    fun finish(
        save: GameSave,
        campaign: Campaign,
        at: Long,
        random: Random = Random.Default,
    ): CampaignPayout {
        val drawn = drawLot(campaign, random)
        val credited = Inventory.addAll(
            save.withCampaignWin(campaign.key).withMgp(campaign.payout),
            drawn,
        )
        val award = AchievementRepository().credit(credited, at)

        return CampaignPayout(
            save = award.save,
            mgp = campaign.payout,
            items = drawn,
            achievements = award.earned,
        )
    }

    /**
     * One item out of [Campaign.finalReward], weighted by each entry's rate.
     *
     * A *weighted choice*, not the per-entry roll `Npc.rollRewards` does. This lot pays exactly
     * one thing, where a drop table may pay several or none. So the rates read as shares of a
     * whole rather than as independent probabilities — what "a 10% chance of Quistis instead"
     * means, and they need not sum to one: whatever they sum to is the denominator.
     *
     * An empty lot pays nothing, and so does one whose entries all name items this build does not
     * understand. Neither is an error — a ladder with no lot is simply one that pays only MGP.
     */
    fun drawLot(campaign: Campaign, random: Random = Random.Default): List<Item> {
        val lot = campaign.finalReward.filter { it.rate > 0.0 && it.item() != null }
        if (lot.isEmpty()) return emptyList()

        // The fold walks the whole lot rather than returning from inside it: `remaining` going
        // negative is what picks the entry, and once one is picked the rest subtract harmlessly.
        var remaining = random.nextDouble() * lot.sumOf { it.rate }
        // Falls back to the last entry, which is what a `remaining` left at exactly zero by
        // floating-point subtraction should draw — never nothing.
        var drawn = lot.last()
        var picked = false
        for (entry in lot) {
            remaining -= entry.rate
            if (!picked && remaining < 0.0) {
                drawn = entry
                picked = true
            }
        }
        return listOfNotNull(drawn.item())
    }
}
