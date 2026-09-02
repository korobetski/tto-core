package com.tripletriad.data

import com.tripletriad.model.Achievement
import com.tripletriad.model.AchievementCatalog
import com.tripletriad.model.GameSave
import com.tripletriad.model.Requirement

/** An achievement and where the profile stands on it, for the achievements screen. */
data class AchievementStatus(
    val achievement: Achievement,
    val progress: Requirement.Progress,
    /** Epoch millis it was earned at, or null if it has not been. */
    val earnedAt: Long?,
) {
    val isEarned: Boolean get() = earnedAt != null
}

/** The result of crediting a profile with what it has newly earned. */
data class AchievementAward(
    /** The profile with the achievements recorded and any rewards in the bag. */
    val save: GameSave,
    /**
     * What was just earned. Empty when nothing was.
     *
     * Catalogue order within each round of [AchievementRepository.credit], rounds in sequence —
     * so an achievement a reward paid for follows the one that paid for it.
     */
    val earned: List<Achievement>,
) {
    val hasAwards: Boolean get() = earned.isNotEmpty()
}

/**
 * Evaluates achievements against a profile and credits what it has earned.
 *
 * ### What this replaces
 *
 * `Achievements.check()` (`Achievements.as:75-91`) does five things in eleven lines: tests each
 * condition, writes the timestamp into the global profile, constructs a Starling `Image`, pushes
 * the reward object into `BAG`, and calls `InventoryScreen.sortBag()` — a static method on a
 * *screen*, so checking achievements requires the inventory screen to be loaded. It also cannot be
 * re-run, because its conditions were evaluated in the constructor (see [Requirement]).
 *
 * The split here: [AchievementCatalog] holds the rules, [statuses] answers questions about them,
 * and [credit] is the only thing that changes a profile. No texture is touched, nothing global is
 * mutated, and the whole thing is a pure function of a [GameSave].
 */
class AchievementRepository(
    private val catalog: List<Achievement> = AchievementCatalog.all,
) {
    /** Every achievement with the profile's progress, in catalogue order. */
    fun statuses(save: GameSave): List<AchievementStatus> = catalog.map { achievement ->
        AchievementStatus(
            achievement = achievement,
            progress = achievement.progressFor(save),
            earnedAt = save.achievements[achievement.id],
        )
    }

    /** Those already earned, newest first — what a profile screen shows. */
    fun earned(save: GameSave): List<AchievementStatus> =
        statuses(save).filter { it.isEarned }.sortedByDescending { it.earnedAt }

    /** Those not yet earned, closest to completion first. */
    fun pending(save: GameSave): List<AchievementStatus> =
        statuses(save).filterNot { it.isEarned }.sortedByDescending { it.progress.fraction }

    /**
     * Credits [save] with everything it now qualifies for.
     *
     * Rewards go into the bag through [Inventory.add], so a second copy of a reward card stacks
     * rather than becoming a second row — which is what the AS3 `BAG.push` produced.
     *
     * Idempotent by construction, not by accident: anything already recorded is filtered out, so
     * calling this after every match is correct and cheap.
     *
     * ### Why this iterates, and why the loop cannot run away
     *
     * A reward used to be unable to earn another achievement. The only rewards were `CardItem`s,
     * [Inventory.add] puts those in the **bag**, and no [Requirement] reads the bag — the closest,
     * `CardsOwned`, counts [GameSave.cards], which a card item joins only when [Inventory.use]
     * consumes it. One pass was therefore provably enough, and this said so at length.
     *
     * [Achievement.mgpReward] ended that. It pays into the purse, and `MgpHeld` reads the purse:
     * completing a tribe for 5 000 MGP can carry a profile across `ac-mp1` in the same instant.
     * One pass would leave that hanging until the next match — the player would see the MGP land
     * and the badge it plainly earned not appear.
     *
     * The loop terminates because each round *must* record at least one achievement to continue,
     * and the catalogue is finite: at most `catalog.size` rounds, and in practice one or two. The
     * cycle a loop invites — an achievement whose own reward earns it — cannot form either, since
     * a round only ever considers ids not yet recorded and an id is recorded before its reward is
     * paid. `AchievementRepositoryTest` pins both the cascade and the single-round case.
     *
     * @param at epoch millis to record as the moment each was earned, as the AS3
     *   `new Date().getTime()` does. Injected because `commonMain` has no clock. Every achievement
     *   of one cascade shares it: they were earned by one act, and dating the second a millisecond
     *   later would be a precision this does not have.
     */
    fun credit(save: GameSave, at: Long): AchievementAward {
        var updated = save
        val earned = mutableListOf<Achievement>()

        while (true) {
            val newly = catalog.filter { !updated.hasAchievement(it.id) && it.isEarnedBy(updated) }
            if (newly.isEmpty()) break
            for (achievement in newly) {
                updated = updated.withAchievement(achievement.id, at)
                achievement.reward?.let { updated = Inventory.add(updated, it) }
                if (achievement.mgpReward > 0) updated = updated.withMgp(achievement.mgpReward)
            }
            earned += newly
        }

        // The list is in catalogue order *within* each round rather than across the whole award:
        // a badge a reward paid for belongs after the badge that paid for it, whatever order the
        // catalogue happens to list them in.
        return AchievementAward(updated, earned)
    }
}
