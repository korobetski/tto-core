package com.tripletriad.model

import kotlin.random.Random

/** The best [cover] a placement can have: four sides, each contributing at most [ACE_POWER]. */
const val MAX_COVER: Int = 4 * ACE_POWER

/**
 * One candidate placement, with the two numbers the AI ranks by.
 *
 * @property captures how many cards the placement would flip, combos included. The AS3
 *   `applyRules(tile, color, checking = true)` return value.
 * @property cover how well the placed card defends itself, [0][MIN_EFFECTIVE_POWER]..[MAX_COVER].
 *   Higher is safer.
 */
data class ScoredMove(
    val card: Card,
    val position: Int,
    val captures: Int,
    val cover: Int,
)

/**
 * The one place this AI departs from `PVEMatchScreen.AI`.
 *
 * @property coverBreaksTies whether a tie on [ScoredMove.captures] is settled by [ScoredMove.cover]
 *   before the random pick. The original sorts by both — `powers.sortOn(['power', 'cover'],
 *   [DESCENDING, DESCENDING])` (`:218`) — and then builds `bestMoves` from every entry whose
 *   `power` equals the best (`:224-227`) and picks one at random, so **the cover sort has no
 *   effect on the move actually chosen**. It is dead work: the AI computes a defensive score for
 *   45 candidate placements and then throws it away except in the branch where nothing captures.
 *
 *   Set to `false` to reproduce that. The default settles the tie, which is what sorting by cover
 *   was evidently meant to do and makes the opponent stop volunteering an ace into an open corner
 *   when a safer square captures the same card.
 * @property depth how many half-moves [MatchSearch] looks ahead. **1 is the original**: no search
 *   at all, [MatchAi.choose] as written, and the only value that reproduces `PVEMatchScreen.AI`.
 *   Two is the first value at which the opponent notices that the placement capturing the most
 *   also offers the reply.
 * @property solveFrom how few free cells are left before the match is solved to the end instead of
 *   cut off at [depth]. The tree below five free cells is small enough to walk exhaustively, and a
 *   solved endgame is played exactly right rather than nearly right. Ignored when [depth] is 1.
 * @property nodeBudget a hard ceiling on nodes per search. Not an optimisation but a guarantee:
 *   the referee runs one of these per placement and a pathological position must not be able to
 *   turn a request into one that never answers.
 * @property blunderRate how often the opponent plays the *worst* move it found instead of the
 *   best, 0.0..1.0. **This is what makes a novice beatable rather than merely short-sighted.**
 *   Depth alone produces an opponent that is weak and utterly consistent, which is a different and
 *   worse experience than one that is mostly sensible and occasionally throws a card away — the
 *   second is what a person on the other side of the table feels like.
 * @property cautiousOpening whether to keep the AS3 coin toss that sends the opponent into the
 *   *most* exposed square before the sixth placement (`PVEMatchScreen.as:236-242`). Faithful, and
 *   the only thing that stops the original's opening from being deterministic; false plays the
 *   safest square from the first move, which is what a searching opponent should do.
 */
data class MatchAiOptions(
    val coverBreaksTies: Boolean = true,
    val playsWorst: Boolean = false,
    val depth: Int = 1,
    val solveFrom: Int = 0,
    val nodeBudget: Int = DEFAULT_NODE_BUDGET,
    val blunderRate: Double = 0.0,
    val cautiousOpening: Boolean = true,
) {
    init {
        require(depth >= 1) { "depth must be at least one half-move, was $depth" }
        require(blunderRate in 0.0..1.0) { "blunderRate must be a probability, was $blunderRate" }
        require(nodeBudget > 0) { "nodeBudget must be positive, was $nodeBudget" }
    }

    companion object {
        /**
         * How hard an opponent in [band] plays.
         *
         * ### `level` is an input now
         *
         * It used to be an output: `NpcRating.rated` wrote `level = levelFor(difficulty)`, where
         * `difficulty` was *measured* from the opponent's hand. Reading it here would have closed
         * the loop — measured strength deciding played strength deciding measured strength — so the
         * rating stopped writing it. An opponent's band is authored, which is what lets a
         * character with a poor hand still be sharp, and `difficulty` remains what it always
         * claimed to be: the measured answer to "how hard is this to beat".
         *
         * ### The shape of the ladder
         *
         * Two things separate the bands and they do different work. **Depth** is how far the
         * opponent sees; **blunder** is how reliably it acts on what it saw. Every rung moves both,
         * and that is a measured decision rather than a stylistic one: depth on its own stops
         * paying above three half-moves, because what limits the search there is not how far it
         * looks but that it is looking at a stand-in for a hand it cannot see. Blunder keeps
         * paying, and it is also the better half of the difference lower down — an opponent that is
         * merely short-sighted plays consistently badly and reads as a machine, while one that
         * mostly plays well and occasionally gives a card away reads as a beginner.
         *
         * [NONE] is the AS3 opponent exactly: no search, the dead cover sort, the opening coin
         * toss. It is what every opponent in the shipped roster used to be.
         *
         * The numbers are a starting point to be measured, not a claim — `MatchAiLadderTest` holds
         * the only property that matters, which is that each band beats the one below it more
         * often than it loses.
         */
        fun forLevel(band: NpcLevel): MatchAiOptions = when (band) {
            NpcLevel.NONE -> FAITHFUL
            NpcLevel.NOVICE -> MatchAiOptions(
                coverBreaksTies = false,
                blunderRate = NOVICE_BLUNDER,
            )
            NpcLevel.INITIATE -> MatchAiOptions(blunderRate = INITIATE_BLUNDER)
            NpcLevel.AVERAGE -> MatchAiOptions(
                depth = 2,
                blunderRate = AVERAGE_BLUNDER,
                cautiousOpening = false,
            )
            NpcLevel.ADVANCED -> MatchAiOptions(
                depth = 3,
                solveFrom = 5,
                blunderRate = ADVANCED_BLUNDER,
                cautiousOpening = false,
            )
            NpcLevel.EXPERT -> MatchAiOptions(
                depth = 5,
                solveFrom = 6,
                cautiousOpening = false,
            )
        }

        /**
         * Enough nodes for a depth-5 search of a full board, and far more than one ever needs.
         *
         * The widest real tree is the first placement: five cards into nine cells is 45 children,
         * and the substitute card takes the reply to nine. Alpha-beta cuts most of it; this is the
         * ceiling for the case where it cuts none of it.
         */
        const val DEFAULT_NODE_BUDGET: Int = 400_000

        // Measured, over a hundred and twenty deals with both colours and both openings
        // rotated. Depth alone does not build a ladder: past three half-moves the search runs into
        // the hidden hand it is guessing at, and five plies beat three only 42 to 41. So each rung
        // sees one step further *and* drops one step of unreliability, and the two together
        // separate the bands — 59:29, 46:34, 47:37, 51:35 up the ladder, 59:25 top against
        // [FAITHFUL].
        private const val NOVICE_BLUNDER = 0.30
        private const val INITIATE_BLUNDER = 0.15
        private const val AVERAGE_BLUNDER = 0.07
        private const val ADVANCED_BLUNDER = 0.03

        /** Reproduces `PVEMatchScreen.AI` exactly, dead cover sort included. */
        val FAITHFUL = MatchAiOptions(coverBreaksTies = false)

        /**
         * The tutorial opponent, which loses on purpose — `TutorialScreen.AI` (`:246-249`).
         *
         * It scores every placement exactly as the real AI does and then takes
         * `powers[powers.length - 1]`, the **last** entry of a list sorted best first:
         *
         * ```actionscript
         * // c'est le tuto, le pnj jour toujours la pire solution
         * card = powers[powers.length -1].card;
         * ```
         *
         * So the lesson is winnable by a player who has just been told what a card is. Note it is
         * the *worst* move rather than a random one: a random opponent would occasionally capture
         * three cards in a row and teach the opposite of what the script is saying at the time.
         */
        val TUTOR = MatchAiOptions(playsWorst = true)
    }
}

/**
 * The opponent — `PVEMatchScreen.AI` (`:182-254`), as a pure function of the match state.
 *
 * Two nested loops over every remaining card and every free cell, scoring each pair by how many
 * cards it captures and how exposed it leaves the placed card, then a random pick among the best.
 * It looks exactly one move ahead and models nothing the player might do next.
 *
 * **Evaluation is now side-effect free, which it was not.** The original scores a candidate by
 * assigning `tile.card = card` and calling `applyRules(…, checking = true)`, which overwrites
 * `tile.color`, recomputes the tile's powers and — under Elemental — mutates `card.modifier`. It
 * then has to undo the damage by hand: `if (RULES.TYPE_RULE == RULE_ELEMENTAL) tile.card.modifier =
 * 0;` and a `tile.card = null; // VERY IMPORTANT` (`:212-213`). Here [RulesEngine.resolve] returns
 * a new board and touches nothing, so there is no damage and no reset
 * ([game-rules.md](../../../../../../../docs/analysis/game-rules.md) § 14).
 *
 * **There are no difficulty levels.** `NPC.difficulty` is read only to order the opponent list
 * (`NpcCatalog.available`); every opponent from the tutorial dummy to the Queen of Cards plays with
 * this same function. Giving the field its apparent meaning would be new game design rather than
 * migration, so it is left alone.
 */
class MatchAi(private val options: MatchAiOptions = MatchAiOptions()) {
    /**
     * How exposed [card] would be at [position] — `PVEMatchScreen.as:202-210`.
     *
     * Each of the four sides contributes:
     *
     * - a neighbouring cell that exists and is **empty**: the placed card's own effective power on
     *   that side, or `10 - power` under Reverse, since Reverse makes a low edge the strong one;
     * - a wall, or a cell already occupied: [ACE_POWER], the maximum. Neither can attack — a wall
     *   has nothing to place on it, and a card already on the board never initiates a capture.
     *
     * So cover measures only the *open* flanks, and a placement walled in on three sides scores
     * high whatever it holds.
     *
     * The tally counts this card, because the question is how exposed it would be **once played**
     * — the same reason `RulesEngine.resolve` counts it, and the same call. An AI scoring its
     * candidates against a tally the board will never be in would systematically undervalue
     * playing into its own type under Bonus, which is the move that rule exists to reward.
     */
    fun cover(state: MatchState, card: Card, position: Int): Int = Side.entries.sumOf { side ->
        val neighbour = state.board.neighbour(position, side)
        if (neighbour == null || state.board[neighbour] != null) {
            ACE_POWER
        } else {
            val power = effectivePower(
                card,
                side,
                state.rules,
                state.board.elements[position],
                state.tally.including(card, state.rules),
            )
            if (state.rules.reverse) ACE_POWER - power else power
        }
    }

    /** Scores playing [card] at [position], without touching [state]. */
    fun evaluate(state: MatchState, card: Card, position: Int): ScoredMove {
        val player = checkNotNull(state.currentPlayer) { "the match is over" }
        val resolution = RulesEngine(state.rules, state.options)
            .resolve(state.board, position, card, player, state.tally)
        return ScoredMove(
            card = card,
            position = position,
            captures = resolution.captures.size,
            cover = cover(state, card, position),
        )
    }

    /**
     * Every legal placement, best first.
     *
     * Ranked by captures then cover, descending on both, as `sortOn` does. Order and Chaos are
     * applied first through [MatchState.playableCards], matching the original, which narrows its
     * own candidate list before scoring (`:185-188`) — so under Order the AI considers only its
     * first card, and under Chaos only the one the rule picked.
     */
    fun candidates(state: MatchState, random: Random = Random.Default): List<ScoredMove> {
        if (state.isFinished) return emptyList()
        val cards = state.playableCards(random)
        val positions = state.playablePositions()
        val ranking = compareByDescending<ScoredMove> { it.captures }
            .thenByDescending { it.cover }
        return cards
            .flatMap { card -> positions.map { position -> evaluate(state, card, position) } }
            .sortedWith(ranking)
    }

    /**
     * The move to play, or `null` when the match is over.
     *
     * Two branches, as in the original:
     *
     * - **something captures.** Pick at random among the placements that capture the most
     *   (`:221-230`), narrowed by cover unless [MatchAiOptions.coverBreaksTies] says otherwise.
     * - **nothing captures.** Then every candidate scores 0 and the list is ordered by cover alone.
     *   From the sixth placement on, take the safest square (`:232-234`). Before that, toss a coin
     *   between the safest and the *most* exposed (`:236-242`) — an even chance of walking into the
     *   worst square on the board. Reproduced: it is the only thing that stops the opening from
     *   being deterministic, and a one-move-lookahead opponent that always plays safe early is both
     *   duller and not much stronger.
     *
     * The original's threshold is `this.turn > 5` on a counter incremented before use, so it is the
     * sixth placement onward — placement index 5 and up. See [TurnOrder].
     *
     * Under [MatchAiOptions.playsWorst] neither branch is taken and the bottom of the same ranking
     * is played instead — see [MatchAiOptions.TUTOR].
     */
    fun choose(state: MatchState, random: Random = Random.Default): ScoredMove? {
        val ranked = candidates(state, random)
        val best = ranked.firstOrNull() ?: return null
        return when {
            options.playsWorst -> ranked.last()
            best.captures > 0 -> bestCapture(ranked, best, random)
            else -> bestPosition(ranked, state.placement, random)
        }
    }

    /** One of the placements capturing the most, at random. */
    private fun bestCapture(
        ranked: List<ScoredMove>,
        best: ScoredMove,
        random: Random,
    ): ScoredMove {
        val tied = ranked.filter {
            it.captures == best.captures && (!options.coverBreaksTies || it.cover == best.cover)
        }
        return tied[random.nextInt(tied.size)]
    }

    /** Nothing captures, so [ranked] is ordered by cover alone and only the extremes are played. */
    private fun bestPosition(
        ranked: List<ScoredMove>,
        placement: Int,
        random: Random,
    ): ScoredMove = when {
        // Off, the toss never happens and the safest square is always taken. An opponent that
        // searches has a reason to prefer the safe square from the first placement; the original
        // had none, and the toss was the only thing keeping its openings from repeating.
        !options.cautiousOpening -> ranked.first()
        placement >= CAUTIOUS_FROM_PLACEMENT -> ranked.first()
        random.nextBoolean() -> ranked.first()
        else -> ranked.last()
    }

    /** Plays [choose]'s move. Returns [state] unchanged when there is nothing to play. */
    fun play(state: MatchState, random: Random = Random.Default): MatchState {
        val move = choose(state, random) ?: return state
        return state.play(move.card, move.position)
    }

    private companion object {
        /**
         * `if (this.turn > 5)` (`PVEMatchScreen.as:232`), translated out of the AS3's 1-based
         * pre-incremented turn counter: its turn 6 is placement index 5.
         */
        const val CAUTIOUS_FROM_PLACEMENT = 5
    }
}
