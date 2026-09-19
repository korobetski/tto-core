package com.tripletriad.model

/**
 * How an opponent answers being beaten again and again.
 *
 * ### Why an opponent needs a memory
 *
 * [GameSave.npcWins] has counted every win per opponent since the AS3, and until this only the
 * Triple Team badges read it: the fiftieth match against Jonas was the first one again, same band,
 * same payout. Reading the count back turns each opponent into a short curve — beat them
 * [WINS_PER_STAGE] times and they come back sharper, and pay for it.
 *
 * ### Raising difficulty rather than the band
 *
 * The stage is added to [Npc.difficulty], two points a stage, and everything else follows from that
 * one number the way it already does for a rated opponent: [Npc.level] (so the search depth
 * and blunder rate of `MatchAiOptions.forLevel`), [Npc.matchFee], [Npc.mgpReward] and
 * [Npc.xpFor].
 * Two points is exactly one band — [npcLevelFor] folds pairs — so a stage is always felt at the
 * board, and a harder game can never pay the same as an easier one.
 *
 * Capped at [MAX_STAGE] and at the top of [DIFFICULTY_RANGE], so an opponent a player keeps
 * returning to tops out rather than growing without end, and an Expert has nowhere left to go.
 * An unrated opponent (difficulty 0) stays unrated: raising it would invent a payout for a row the
 * data says pays nothing.
 *
 * ### Read from the profile before the match counts
 *
 * Both ends ask with the profile as it was when the match was dealt — the server when it picks the
 * opponent's options and again when it pays, `MatchRewards.credit` before it records the win. So
 * the win that crosses a threshold is paid at the old stage, and the next match is the first one
 * played at the new stage. Nothing here touches a transcript: a PvE match's moves are written by
 * the referee as they are made, so the options can change without a protocol version.
 */
object Rivalry {
    /** Wins against one opponent that raise it one stage. */
    const val WINS_PER_STAGE: Int = 3

    /** How far an opponent can rise above its measured difficulty, in stages. */
    const val MAX_STAGE: Int = 2

    /** Difficulty points per stage — one band, see [npcLevelFor]. */
    const val DIFFICULTY_PER_STAGE: Int = 2

    /** The stage an opponent beaten [wins] times plays at. */
    fun stageFor(wins: Int): Int = (wins.coerceAtLeast(0) / WINS_PER_STAGE).coerceAtMost(MAX_STAGE)

    /**
     * Wins still needed before the next stage, or null once [wins] has reached [MAX_STAGE] — for
     * the opponent sheet, which says how close a rematch is to getting harder.
     */
    fun winsToNextStage(wins: Int): Int? =
        if (stageFor(wins) >= MAX_STAGE) {
            null
        } else {
            WINS_PER_STAGE - wins.coerceAtLeast(0) % WINS_PER_STAGE
        }
}

/** The stage this opponent plays at against [save]. See [Rivalry]. */
fun Npc.rivalStageFor(save: GameSave): Int = Rivalry.stageFor(save.npcWins[iconId] ?: 0)

/**
 * This opponent as [save] meets it: the same row with its difficulty raised by its rivalry stage,
 * so [Npc.level], the fee and the payouts all read the raised value. Returns this row unchanged at
 * stage 0 and for an unrated opponent. See [Rivalry].
 */
fun Npc.asRivalOf(save: GameSave): Npc {
    val stage = rivalStageFor(save)
    if (stage == 0 || difficulty == 0) return this
    val raised = (difficulty + stage * Rivalry.DIFFICULTY_PER_STAGE)
        .coerceAtMost(DIFFICULTY_RANGE.last)
    return if (raised == difficulty) this else copy(difficulty = raised)
}
