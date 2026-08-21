package com.tripletriad.model

import kotlin.random.Random

/**
 * Looking further than one move — the part `PVEMatchScreen.AI` never had.
 *
 * ### Why this can exist now, and could not before
 *
 * The opponent's placements used to be **derived** rather than recorded: a solo match was verified
 * by replaying it from its seed, so `MatchAi` was load-bearing for verification and any change to
 * how it played invalidated every stored transcript. Making the opponent cleverer cost a protocol
 * version and a migration.
 *
 * A refereed match writes both sides' placements down (`protocol/PveMatch.kt`). The row replays to
 * what it replayed to yesterday whatever this file does next, so search depth is now a *tuning*
 * decision rather than a wire-format one. That is what the whole move to a referee bought.
 *
 * ### What it is
 *
 * Negamax with alpha-beta pruning over [MatchState], scoring a position by the margin the side to
 * move holds. Nothing here is clever about Triple Triad specifically — [RulesEngine] already knows
 * the rules and [MatchState.play] already applies them — so the search is the ordinary algorithm
 * with three domain decisions bolted on, each of which is written down where it happens:
 *
 * * a **win bonus** at terminal nodes, because 9-1 and 5-4 pay the same and a margin-maximiser
 *   takes risks the settlement does not reward ([valueOf]);
 * * a **substitute card** for the opponent's hidden hand, because searching the real one would be
 *   cheating and searching every possibility would be unaffordable ([substituteFor]);
 * * **move ordering** from [MatchAi.candidates], the one-move ranking that already exists, so the
 *   pruning is good from the first try rather than after an iteration.
 *
 * ### It does not cheat, and that is testable
 *
 * The search is given a [MatchState], which holds both hands — so honesty is a property of this
 * file rather than of its caller. Every opponent card the rules do not reveal is replaced before
 * the first node is expanded, so changing a hidden card cannot change the move. `MatchSearchTest`
 * asserts exactly that.
 *
 * ### It is pure
 *
 * No clock, no generator, no allocation the caller can observe. [Random] appears in one place, to
 * break ties among equally-valued moves, and it is the caller's — the same generator the referee
 * threads through everything else.
 */
class MatchSearch(private val options: MatchAiOptions = MatchAiOptions()) {
    private val oneMove = MatchAi(options)

    /**
     * The move to play from [state], or null when the board is full.
     *
     * @param visible what the side to move is entitled to see of the other hand. Anything it does
     *   not name is replaced by [substituteFor] before the search begins — see the class KDoc.
     * @param random the blunder draw, and the shuffle `MatchAi.candidates` applies within a
     *   tie group — which is where every tie in this search is now settled.
     */
    fun choose(
        state: MatchState,
        visible: HandVisibility = HandVisibility.HIDDEN,
        random: Random = Random.Default,
    ): ScoredMove? {
        if (state.isFinished) return null

        // **The blunder is drawn before anything else, and at every depth.** It used to sit at the
        // end of the search, which quietly meant a band that did not search could not blunder —
        // and the three lowest bands are exactly the ones separated by it. Measured, `NOVICE` and
        // `NONE` were then the same opponent wearing two labels.
        if (options.blunderRate > 0.0 && random.nextDouble() < options.blunderRate) {
            return oneMove.candidates(state, random).lastOrNull()
        }

        // Below the threshold there is nothing for a search to find that the one-move ranking does
        // not already know, and running one anyway would cost nodes to arrive at the same answer.
        // No blinding either: `MatchAi` scores its own placements against the *board* and never
        // reads the other hand, so one-move play is honest by construction.
        if (options.depth <= 1) return oneMove.choose(state, random)

        val player = state.currentPlayer ?: return null
        val budget = Budget(options.nodeBudget)
        val root = blinded(state, player, visible)
        val moves = oneMove.candidates(root, random)
        if (moves.isEmpty()) return null

        // The full depth once the board is nearly empty of choices: the tree below `solveFrom`
        // free cells is small enough to walk exhaustively, and a match solved to the end is played
        // exactly right rather than nearly right. `Board.SIZE` is the cap, so a solve that starts
        // early simply searches everything.
        val depth = if (root.playablePositions().size <= options.solveFrom) {
            Board.SIZE
        } else {
            options.depth
        }

        // The ranking decides every tie, and that is the load-bearing line of this class.
        //
        // It was a reservoir sample to begin with — a random pick among moves the search could not
        // separate — and that made the search *weaker than not searching at all*: two plies lost to
        // one, 35 wins to 45 over a hundred and twenty deals. The reason is that under hidden
        // information the leaf values are nearly flat, because the opponent's hand is one
        // substitute card that threatens every low face equally, so most placements tie and the
        // draw was throwing away `MatchAi`'s positional ranking on almost every move.
        //
        // `moves` arrives best-first from that ranking, and a strict `>` keeps the first of any
        // tie. So the search departs from one-move play only where it found a *strictly* better
        // reason to, and can no longer be worse than it. Measured again on the same deals: two
        // plies 41:42, three 43:35, five 45:34. The randomness the sample was protecting is still
        // there — it is in `MatchAi.candidates`, which already shuffles within a tie group.
        var best = moves.first()
        var bestValue = Int.MIN_VALUE
        for (move in moves) {
            val value = -negamax(
                state = root.play(move.card, move.position),
                depth = depth - 1,
                alpha = -MAX_VALUE,
                beta = -bestValue.coerceAtLeast(-MAX_VALUE),
                player = player.opposite(),
                budget = budget,
            )
            if (value > bestValue) {
                best = move
                bestValue = value
            }
        }

        // The move belongs to the blinded state, and the card in it is the *real* one: only the
        // opponent's hand was substituted, and this side plays from its own.
        return best
    }

    /**
     * The value of [state] to the side to move, in card margin.
     *
     * Negamax: a position is worth to me exactly what it costs my opponent, so one function serves
     * both sides and the sign flips on the way up.
     */
    // Six arguments plus the budget, and every one of them is the algorithm: the position, how
    // much further to look, the window, whose turn it is, and the ceiling. Bundling them into a
    // frame object would allocate one per node to please the count.
    @Suppress("ReturnCount", "LongParameterList")
    private fun negamax(
        state: MatchState,
        depth: Int,
        alpha: Int,
        beta: Int,
        player: CardColor,
        budget: Budget,
    ): Int {
        if (state.isFinished || depth <= 0 || budget.spent()) return valueOf(state, player)

        val positions = state.playablePositions()
        // No generator here on purpose. Under Chaos the *root* draw is the referee's and has
        // already happened; drawing again inside the search would have the opponent plan around a
        // card it will not be dealt. `Random.Default` would also make the search impure, which is
        // the property `MatchSearchTest` pins.
        val cards = state.currentHand.narrowedBy(state.rules.order)
        if (cards.isEmpty() || positions.isEmpty()) return valueOf(state, player)

        var best = -MAX_VALUE
        var window = alpha
        for (card in cards) {
            for (position in positions) {
                budget.spend()
                val value = -negamax(
                    state = state.play(card, position),
                    depth = depth - 1,
                    alpha = -beta,
                    beta = -window,
                    player = player.opposite(),
                    budget = budget,
                )
                if (value > best) best = value
                if (best > window) window = best
                // The cut: this branch is already better for the mover than the parent will allow,
                // so the parent will never choose it and the rest of it need not be looked at.
                if (window >= beta) return best
            }
        }
        return best
    }

    /**
     * What [state] is worth to [player]: the margin, and then how safely it is held.
     *
     * ### The margin alone is not enough, and measuring it proved it
     *
     * [MatchState.score] counts unplayed cards for their owner, so the margin is defined at every
     * depth and *is* the result once the board is full — which is what makes a partial search
     * comparable with a complete one. It was the whole evaluation to begin with, and a two-ply
     * search using it **lost to the one-move opponent**, 8 wins to 32 over sixty deals.
     *
     * The reason is the classic one. A search that stops after two placements and counts cards is
     * blind to everything on the third: it declines a capture because it sees the recapture, and
     * plays instead into a square whose open flanks lose two cards on a turn it never looked at.
     * `MatchAi` never had that problem because it was not looking ahead at all — it was ranking by
     * [MatchAi.cover], which is a *positional* judgement, and throwing that away in exchange for
     * one extra ply was a bad trade.
     *
     * So the leaf keeps both. [exposure] is the same notion of safety, summed over the cards each
     * side holds on the board, and it is scaled to lose every argument with the margin: a card is
     * worth more than any amount of tidiness. It only orders positions the margin cannot separate,
     * which — two plies into a nine-cell board — is most of them.
     *
     * ### The bonus separates a result from an estimate
     *
     * Among finished boards the margin already orders them correctly, so the bonus changes nothing
     * there. What it changes is the comparison the search actually makes: a finished board against
     * one the depth limit cut off. Without it a line that ends 4-6 scores the same as a cut-off
     * position two cards down with four placements left to recover in, and the search walks into
     * the lost ending because the two look alike.
     */
    private fun valueOf(state: MatchState, player: CardColor): Int {
        val score = state.score
        val mine = if (player == CardColor.BLUE) score.blue else score.red
        val theirs = if (player == CardColor.BLUE) score.red else score.blue
        val margin = mine - theirs

        if (state.isFinished) {
            return when {
                margin > 0 -> margin * MARGIN_WEIGHT + WIN_BONUS
                margin < 0 -> margin * MARGIN_WEIGHT - WIN_BONUS
                // A draw is a draw, except under Sudden Death, where it is another board and this
                // search has no business guessing at one. Zero is the honest answer to both.
                else -> 0
            }
        }
        return margin * MARGIN_WEIGHT + exposure(state, player) - exposure(state, player.opposite())
    }

    /**
     * How safely [side] holds what it has on the board — [MatchAi.cover], summed.
     *
     * Each card contributes, per side: the maximum when that flank cannot be attacked at all (a
     * wall, or a cell already taken), and its own facing power when it can. So a card wedged into a
     * corner scores high whatever it holds, and an ace sitting in the open scores about what its
     * faces say. Under Reverse the reading inverts, because a low edge is the strong one.
     *
     * The tally is the board's own: these cards are already played, so nothing is being added to
     * it. That is the one difference from [MatchAi.cover], which is asking about a card that is not
     * on the board yet and has to count it.
     */
    private fun exposure(state: MatchState, side: CardColor): Int =
        state.board.cells.withIndex().sumOf { (position, placed) ->
            if (placed == null || placed.owner != side) {
                0
            } else {
                Side.entries.sumOf { face ->
                    val neighbour = state.board.neighbour(position, face)
                    if (neighbour == null || state.board[neighbour] != null) {
                        ACE_POWER
                    } else {
                        val power = effectivePower(
                            placed.card,
                            face,
                            state.rules,
                            state.board.elements[position],
                            state.tally,
                        )
                        if (state.rules.reverse) ACE_POWER - power else power
                    }
                }
            }
        }

    /**
     * [state] with everything [player] may not see replaced by one substitute card.
     *
     * ### Why a substitute rather than the real hand
     *
     * Because searching the real hand is the cheat this whole design exists to remove. Under no
     * Open rule the opponent's five cards are simply not knowable, and a program that plans against
     * them is not playing the game the player is playing.
     *
     * ### Why one card rather than a distribution
     *
     * Arithmetic. Five distinct unknown cards branch five ways at every one of the other side's
     * turns; one substitute branches once, which takes the opponent's contribution to the tree from
     * `5 × cells` to `1 × cells` and is the difference between a depth-5 search and a depth-2 one.
     * The cost is that the search is optimistic about *which* card comes back — it plans against
     * the average rather than the worst — and that is the right trade for an opponent rather than
     * a solver.
     *
     * ### Why the average of what has been seen
     *
     * It needs no catalogue, invents no constant, and adapts to the format: a match played with
     * block 1 commons and one played with the Queen's aces produce different substitutes because
     * the cards on the table are different. The sample is everything [player] legitimately knows —
     * its own hand, every card on the board, and whatever an Open rule revealed.
     *
     * The type is left null — untyped — because the type of an unknown card is unknown, and
     * guessing one would make the search plan around an Elemental bonus nobody promised.
     */
    private fun blinded(
        state: MatchState,
        player: CardColor,
        visible: HandVisibility,
    ): MatchState {
        val opponent = player.opposite()
        val theirs = state.hands[opponent].orEmpty()
        // Nothing to hide: an empty hand, or a rule that showed all of it. Answered as one
        // condition rather than two early returns, which is also what the return-count rule is
        // asking for — two ways of saying "leave it alone" read as two different decisions.
        if (theirs.indices.all(visible::isVisible)) return state

        val substitute = substituteFor(state, player, visible)
        return state.copy(
            hands = state.hands + (
                opponent to theirs.mapIndexed { slot, card ->
                    if (visible.isVisible(slot)) card else substitute
                }
                ),
        )
    }

    /** The stand-in for a card [player] cannot see: the mean face of every card it can. */
    private fun substituteFor(
        state: MatchState,
        player: CardColor,
        visible: HandVisibility,
    ): Card {
        val seen = state.hands[player].orEmpty() +
            state.board.cells.mapNotNull { it?.card } +
            state.hands[player.opposite()].orEmpty()
                .filterIndexed { slot, _ -> visible.isVisible(slot) }

        val faces = seen.flatMap { listOf(it.top, it.right, it.bottom, it.left) }
        val mean = if (faces.isEmpty()) {
            MIDDLING_POWER
        } else {
            // Rounded rather than truncated: the faces are small integers and a systematic
            // half-point of pessimism across four sides is a whole card's worth of caution.
            (faces.sum() + faces.size / 2) / faces.size
        }

        return Card(
            id = SUBSTITUTE_ID,
            nameKey = SUBSTITUTE_KEY,
            name = "",
            top = mean,
            right = mean,
            bottom = mean,
            left = mean,
            rarity = 1,
            type = null,
            owner = player.opposite(),
        )
    }

    /** [OrderRule] applied without a generator — see the note in [negamax] about Chaos. */
    private fun List<Card>.narrowedBy(order: OrderRule): List<Card> = when {
        isEmpty() -> this
        order == OrderRule.ORDER -> listOf(first())
        // Chaos inside the search is treated as a free choice rather than as a draw. Modelling it
        // as "one card, picked now" would plan around a roll that has not happened; modelling it
        // as a free hand is optimistic in the same direction for both sides, which is the least
        // wrong of the cheap answers.
        // Deduplicated by id, which matters only for the substituted hand: blinding replaces
        // every unseen card with the *same* stand-in, and without this the search would walk five
        // identical opponent branches and spend five times the budget to reach one answer.
        else -> distinctBy { it.id }
    }

    /**
     * A hard ceiling on how much of the tree gets walked.
     *
     * Not an optimisation — a guarantee. The referee runs one search per placement, at most five
     * per match, and a pathological position must not be able to turn one of them into a request
     * that never answers. Exhausting the budget makes the search return the value it has, which is
     * a shallower answer rather than a wrong one.
     */
    private class Budget(private val limit: Int) {
        private var used = 0

        fun spend() {
            used++
        }

        fun spent(): Boolean = used >= limit
    }

    private companion object {
        /**
         * How much a card outweighs the tidiest possible board.
         *
         * Wider than the whole positional range, so a placement that wins a card is always worth
         * more than any rearrangement that does not. The positional term is a tiebreak and must
         * never become an objective — an opponent that preferred a neat board to a captured card
         * would be a worse opponent than the one that could not count past one move.
         */
        const val MARGIN_WEIGHT = 2 * MAX_COVER * Board.SIZE + 1

        /** Bigger than any margin, so a settled result outranks any estimate. */
        const val WIN_BONUS = MARGIN_WEIGHT * (TOTAL_CARDS + 1)

        /** The alpha-beta window's edge. Wider than any value [valueOf] can return. */
        const val MAX_VALUE = WIN_BONUS * 2

        /** The face of a substitute when nothing at all has been seen, which is unreachable. */
        const val MIDDLING_POWER = 5

        /**
         * An id no real card has: the last number of the last block a [Card] will accept.
         *
         * It matters that it is distinct rather than that it is out of range — `Card` validates
         * its id, so a sentinel below [Card.FIRST_ID] does not construct. `MatchState.play` finds
         * a card in the hand by id, so a substitute sharing an id with a real one would make the
         * search play the wrong card; no shipped block reaches 255.
         */
        val SUBSTITUTE_ID = Card.idFor(block = Card.NUMBER_MASK, number = Card.NUMBER_MASK)

        const val SUBSTITUTE_KEY = "APP_CARD_UNKNOWN"
    }
}
