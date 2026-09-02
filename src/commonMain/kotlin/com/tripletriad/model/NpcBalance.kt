package com.tripletriad.model

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The scale a rated opponent sits on.
 *
 * 0 is deliberately outside it and means **unrated**: every function here answers 0 with the
 * nothing-at-all row — no band, no fee, no payout — so an [Npc] that has never been through
 * `NpcRating` reads as inert rather than as an easy opponent worth 25 MGP.
 */
val DIFFICULTY_RANGE: IntRange = 1..10

/**
 * XP for beating an opponent of this [difficulty], on the AS3 curve read one step finer.
 *
 * The AS3 formula is `{w: 25 + 2m, d: 10 + 1.5m, l: 5 + m}` and it is kept exactly — what changes
 * is what `m` is. It was [NpcLevel.modifier], one of five band ordinals, so ten difficulties paid
 * five distinct amounts and a win was worth 27 XP at the bottom and 35 at the top: an eight-point
 * spread across the whole roster, which is not a reason to seek out a harder opponent.
 *
 * Reading `m` as the difficulty itself keeps the curve's shape and its footing — difficulty 1 pays
 * 27/12/6, exactly what the first band paid — and lets the top of the scale rise to 45/25/15. It
 * is also monotone now: every step up pays more than the step below, where the band staircase paid
 * two difficulties the same.
 *
 * [NpcLevel.xpReward] is left alone. It is still the AS3 formula over the AS3 bands, `NpcTest`
 * still pins it, and the band is still what the opponent list *labels* an opponent with; it simply
 * is not what settles the XP any more.
 */
fun xpRewardFor(difficulty: Int): XpReward = XpReward(
    win = 25 + (difficulty * 2.0).roundToInt(),
    draw = 10 + (difficulty * 1.5).roundToInt(),
    lose = 5 + difficulty,
)

/**
 * The skill band [difficulty] belongs to, which is what the opponent list labels a row with.
 *
 * XP is **not** re-derived from it — see [xpRewardFor], which took that job. What the band is still
 * for is the label, and what was wrong with the shipped one is *which band an opponent was in*:
 * `level` was authored per table alongside a difficulty it disagreed with. Deriving it from
 * measured strength puts the two back in agreement by making them one number.
 *
 * Five bands over ten points, so two difficulties to a band. [NpcLevel.NONE] is produced only by
 * the unrated 0: it pays no XP at all (see [Npc.xpFor]), and a rated opponent that costs a fee and
 * pays nothing is a row the data should not be able to express.
 */
fun npcLevelFor(difficulty: Int): NpcLevel {
    require(difficulty in UNRATED..DIFFICULTY_RANGE.last) {
        "difficulty must be in $UNRATED..${DIFFICULTY_RANGE.last}, was $difficulty"
    }
    val ordinal = (difficulty + 1) / 2
    return NpcLevel.entries.first { it.modifier == ordinal }
}

/**
 * What a win, a draw and a loss pay against an opponent of this [difficulty].
 *
 * A straight line rather than the authored table, and the line is fitted to what the authored
 * table *meant*: a win against the easiest opponent pays [WIN_BASE], and each difficulty step adds
 * [WIN_STEP], so the hardest pays 250. The old FFXIV spread was 0..182 and the FFVIII one 15..128 —
 * the top of the new scale is close to the old FFXIV top, so a player's expectations about what a
 * hard opponent is worth survive, and the bottom stops being zero.
 *
 * ### The draw and the loss are not chosen — they are the authored table's own arithmetic
 *
 * The pre-rating FFXIV roster (60 opponents, `npcs.json` as it stood at `d5ce059`) turns out to
 * derive both from the win, exactly:
 *
 * - `lose == floor(0.15 * win)` for **59 of 59** opponents that pay anything at all.
 * - `draw == floor(0.40 * win)` for **54 of 57**, the other three one MGP high.
 *
 * That is not a fit, it is the rule somebody applied. So [DRAW_SHARE] and [LOSE_SHARE] are those
 * two fractions and the rounding is [floor][Int], where earlier revisions of this used 0.35 and
 * 0.12 rounded to nearest — numbers picked here rather than read off the data.
 *
 * The three opponents that pay `w > 0, d = 0` (`ruhtwyda`, `gegeruju`) and the one that pays
 * nothing at all (`linu-vali`) are data faults, not exceptions to the rule: at `d = 0` a draw pays
 * less than the loss. They are excluded from the counts above rather than explained.
 *
 * **A loss always pays something.** `PVEMatchScreen.endGame` pays `MGPReward.l + rand(5)` on a
 * defeat, and one shipped opponent declares `l: 0` — so losing to it, having paid a fee, could
 * return nothing at all. A floor of [LOSE_FLOOR] is a deliberate change: a beginner who loses their
 * first five matches should still be able to afford the sixth. At 0.15 the floor is no longer
 * load-bearing — the easiest opponent's `floor(0.15 * 25)` is already 3 — and it is kept as the
 * guarantee it states rather than as arithmetic anything depends on.
 */
fun mgpRewardFor(difficulty: Int): MgpReward {
    require(difficulty in UNRATED..DIFFICULTY_RANGE.last) {
        "difficulty must be in $UNRATED..${DIFFICULTY_RANGE.last}, was $difficulty"
    }
    if (difficulty == UNRATED) return MgpReward()
    val win = WIN_BASE + (difficulty - 1) * WIN_STEP
    return MgpReward(
        win = win,
        draw = (win * DRAW_SHARE).toInt(),
        lose = (win * LOSE_SHARE).toInt().coerceAtLeast(LOSE_FLOOR),
    )
}

/**
 * What it costs to sit down.
 *
 * Carried as data and **not deducted** — see [Npc.mgpFor], which explains at length why the AS3
 * declares this field for all 85 opponents and never reads it. It is scaled with the rest so the
 * opponent list stays coherent: a row that says "fee 40, pays 30" reads as a bad deal whether or
 * not the fee is charged, and the old tables produced several of those.
 *
 * ### Concave, because the authored table is
 *
 * The fee was a straight line here and the authored FFXIV fees are not one: they run
 * 5, 10, 15, 15, 20, 20, 25, 25, 25, 30 over the same span the win rises tenfold across, and they
 * stop at 40 while the win goes on to 182. A search over that table's 59 usable rows puts the best
 * rule at `5 * round(0.55 * sqrt(win))`, right for 46 of them and one 5-step out on the rest — a
 * square root, not a line.
 *
 * The coefficient is **not** reused, because it was fitted against a win scale of 10..182 and this
 * one runs 25..250: applied literally it would open at a fee of 15 against an opponent who pays 25,
 * which is the "bad deal" row this field exists to avoid. What is kept is the *shape*. The curve is
 * anchored instead — [FEE_MIN] at the bottom of [DIFFICULTY_RANGE], [FEE_MAX] at the top — and
 * interpolated on the square root in whole [FEE_STEP]s, giving 5, 10, 15, 20, 25, 30, 30, 35, 35,
 * 40. The fee is then a roughly flat share of the win (20% at the bottom, 16% at the top) instead
 * of a rising one.
 */
fun matchFeeFor(difficulty: Int): Int {
    require(difficulty in UNRATED..DIFFICULTY_RANGE.last) {
        "difficulty must be in $UNRATED..${DIFFICULTY_RANGE.last}, was $difficulty"
    }
    if (difficulty == UNRATED) return 0
    val steps = (FEE_MAX - FEE_MIN) / FEE_STEP
    val curve = (sqrt(difficulty.toDouble()) - 1) / (sqrt(DIFFICULTY_RANGE.last.toDouble()) - 1)
    return FEE_MIN + FEE_STEP * (curve * steps).roundToInt()
}

private const val UNRATED = 0

private const val WIN_BASE = 25
private const val WIN_STEP = 25
private const val DRAW_SHARE = 0.40
private const val LOSE_SHARE = 0.15
private const val LOSE_FLOOR = 3

private const val FEE_MIN = 5
private const val FEE_MAX = 40
private const val FEE_STEP = 5
