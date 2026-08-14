package com.tripletriad.protocol

import kotlinx.serialization.Serializable

/**
 * Seeds the server has issued, for matches not yet played.
 *
 * ### Why a stock and not one seed per match
 *
 * Because the obvious design — ask the server for a seed when a match starts — ends offline play,
 * and offline play is a property this game has deliberately kept. `MatchTranscript`'s whole
 * argument is that "a match played on a plane still counts": the transcript is unforgeable as a
 * game, so it can be queued and credited later. A round trip at match start would take that away
 * to close a hole that a stock closes just as well.
 *
 * So the client holds unspent seeds, plays from them offline, and tops up when it can. What it
 * cannot do is **mint** one, which is the whole of the fix.
 *
 * ### Spending one voids the ones before it
 *
 * A stock reintroduces a smaller version of the problem: a client holding fifty seeds could compute
 * the deal for each and spend whichever it liked best. So the server treats a spend as a
 * *position*: crediting a match voids every ticket issued to that account before the one used.
 * Skipping ahead is allowed — a player may abandon a match and start another, which must keep
 * working — but it costs the skipped seeds, so the window shrinks as it is exploited rather than
 * being free to search.
 *
 * That, plus [MAX_UNSPENT], is what bounds the search. Neither is a proof; together they turn an
 * unlimited free re-roll into a cost per attempt, which is the honest thing this design can buy
 * without giving up matches played on a plane.
 *
 * @property seeds unspent seeds, oldest first — the order the client is expected to use them in.
 */
@Serializable
data class SeedTickets(val seeds: List<Int> = emptyList()) {
    companion object {
        /**
         * The most unspent tickets an account may hold.
         *
         * Two numbers in one. It is the **offline reserve** — how many matches a player can play
         * with no network before the game has to say no — and it is the **search window**, the most
         * deals a modified client could choose between. Those pull in opposite directions, which is
         * why it is stated once here rather than guessed at each end.
         *
         * Fifty is generous as a reserve: it is a long flight's worth of matches. It is poor as a
         * search window only if the seeds are free, and voiding-on-spend is what stops them being.
         */
        const val MAX_UNSPENT: Int = 50

        /** Below this, an online client tops up. Leaves room to play while the request runs. */
        const val TOP_UP_AT: Int = 20
    }
}
