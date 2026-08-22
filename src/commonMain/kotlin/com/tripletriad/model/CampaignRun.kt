package com.tripletriad.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A tournament in progress: which ladder, how far up it, and how each rung went.
 *
 * ## Why a tournament needs state at all
 *
 * Until this existed, a ladder was a walk through screens and nothing more. The rung a player had
 * reached lived in a `remember` inside the client's `CampaignMatchScreen`, so it did not survive
 * leaving the screen; and the referee, which is handed only an opponent and a format
 * (`PveMatchRequest`), could not tell a tournament match from a free one. Every rule that makes a
 * tournament a tournament — the entry fee bought once, the opponent's own stake waived, the
 * boosted drops, the prize for finishing — needs a server that knows a run is under way, and this
 * is what it knows it by.
 *
 * ## One at a time, and server-owned
 *
 * A profile holds at most one open run: [GameSave.campaignRun] is nullable and null is the ordinary
 * state. Entering a ladder while a run is open is refused rather than queued — two runs would have
 * to disagree about which match the referee is settling.
 *
 * It is listed in [GameSave.withServerOwnedFrom], so a client cannot assert one. That is not
 * bureaucracy: a forged run is a forged entry fee, waived opponent stakes and multiplied drop
 * rates, which is most of what a client could want to help itself to.
 *
 * ## What closes it
 *
 * A run is closed by being resolved, never by aging: a defeat at any rung ends it — including the
 * first, which is the whole of what the entry fee buys against — and winning the last rung
 * completes it. Nothing expires it, so a player who stops between two opponents comes back to the
 * rung they had reached. Stopping *during* a match is the exception, and is a forfeit: the live
 * match is what carries that, not this record.
 *
 * @property campaignKey the ladder being climbed, `Campaign.key`.
 * @property step the rung now being played, counted from zero as a ladder's own steps are.
 * @property outcomes how each rung already played ended, in ladder order. A drawn rung is replayed
 *   rather than advanced past, so the entry for a rung is replaced on the replay and this stays one
 *   entry per rung reached rather than one per match played.
 * @property enteredAt when the entry fee was paid, epoch millis. Kept for the bilan and for
 *   support, not for expiry — see above.
 */
@Serializable
data class CampaignRun(
    @SerialName("KEY") val campaignKey: String,
    @SerialName("STEP") val step: Int = 0,
    @SerialName("OUTCOMES") val outcomes: List<MatchResult> = emptyList(),
    @SerialName("ENTERED_AT") val enteredAt: Long = 0L,
) {
    init {
        require(campaignKey.isNotBlank()) { "a run must name its campaign" }
        require(step >= 0) { "a run cannot be on a negative rung: $step" }
    }

    /** The same run one rung higher, with [result] recorded against the rung just played. */
    fun advanced(result: MatchResult): CampaignRun =
        copy(step = step + 1, outcomes = outcomes.recording(step, result))

    /**
     * The same run with [result] recorded against the current rung, still on that rung.
     *
     * What a draw does: it settles nothing, so the rung is played again. The outcome is recorded
     * anyway so a bilan can say the rung was drawn before it was won.
     */
    fun held(result: MatchResult): CampaignRun = copy(outcomes = outcomes.recording(step, result))

    /**
     * Whether a ladder of [rungs] opponents has been climbed to the top, which is what completing
     * it means.
     *
     * Takes a count rather than the `Campaign` itself: that lives in `com.tripletriad.data`, which
     * already depends on this package, and reaching back for it would close a cycle between the
     * two.
     */
    fun hasCompleted(rungs: Int): Boolean = step >= rungs

    /**
     * One outcome per rung reached: replaces the rung's entry when it already has one, appends
     * when it does not. A rung drawn three times then won leaves `WIN`, not four entries.
     */
    private fun List<MatchResult>.recording(rung: Int, result: MatchResult): List<MatchResult> =
        if (rung < size) toMutableList().also { it[rung] = result } else this + result
}
