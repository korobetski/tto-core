package com.tripletriad.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The balance assertion: **a band beats the band below it.**
 *
 * `NpcLevel` has been a label since the port began — five values that ordered the opponent list and
 * decided nothing else, because every opponent from the tutorial dummy to the Queen of Cards played
 * the same one-move function. [MatchAiOptions.forLevel] gives them meaning, and a ladder whose
 * rungs are not in order would be worse than no ladder at all: a player who beat an Advanced
 * opponent and then lost to an Initiate has been told something false about their own progress.
 *
 * ### What is asserted, and what is not
 *
 * The **direction**, not the number. This is the repository's habit for anything measured — see
 * `NpcRatingTest` — and here it is doubly right: the numbers in [MatchAiOptions.forLevel] are meant
 * to be tuned, and a test pinning a win rate would have to be rewritten every time somebody
 * improved the opponent. What must not change is that improving it keeps the rungs in order.
 *
 * ### Why the hands are drawn rather than fixed
 *
 * A single dealt hand measures one position, and a band that happened to suit it. Each trial deals
 * afresh from a seeded generator and alternates who opens, so the coin toss — worth roughly a card
 * on its own, since the opener places five — falls evenly on both sides.
 */
class MatchAiLadderTest {

    @Test
    fun anInitiateBeatsANovice() {
        assertLadder(NpcLevel.INITIATE, NpcLevel.NOVICE)
    }

    @Test
    fun anAverageOpponentBeatsAnInitiate() {
        assertLadder(NpcLevel.AVERAGE, NpcLevel.INITIATE)
    }

    @Test
    fun anAdvancedOpponentBeatsAnAverageOne() {
        assertLadder(NpcLevel.ADVANCED, NpcLevel.AVERAGE)
    }

    @Test
    fun anExpertBeatsAnAdvancedOpponent() {
        assertLadder(NpcLevel.EXPERT, NpcLevel.ADVANCED)
    }

    /**
     * And the whole ladder end to end, which is the claim a player actually experiences.
     *
     * Adjacent bands differ by one tuning step and can be close; the top and the bottom must not
     * be. If this ever fails, the bands have stopped being a difficulty setting.
     */
    @Test
    fun anExpertOutclassesTheOriginalOpponent() {
        val (expert, none) = duel(NpcLevel.EXPERT, NpcLevel.NONE)

        assertTrue(
            expert > none * 2,
            "an expert should not merely edge out the AS3 opponent: $expert to $none",
        )
    }

    /** [NpcLevel.NONE] is the AS3 opponent exactly — no search, whatever else the ladder does. */
    @Test
    fun theBottomOfTheLadderIsTheOriginalOpponentUntouched() {
        val plain = MatchAiOptions.forLevel(NpcLevel.NONE)

        assertTrue(plain == MatchAiOptions.FAITHFUL, "NONE must stay the faithful port: $plain")
    }

    // ---- Harness ----------------------------------------------------------

    private fun assertLadder(stronger: NpcLevel, weaker: NpcLevel) {
        val (strongerWins, weakerWins) = duel(stronger, weaker)

        assertTrue(
            strongerWins > weakerWins,
            "$stronger should beat $weaker more often than it loses: " +
                "$strongerWins to $weakerWins over $TRIALS matches",
        )
    }

    /**
     * [stronger] against [weaker] over [TRIALS] deals, alternating who opens.
     *
     * Returns the two win counts; draws are counted for nobody, which is the honest reading — a
     * draw says the two bands could not separate on that deal.
     */
    private fun duel(stronger: NpcLevel, weaker: NpcLevel): Pair<Int, Int> {
        val strong = MatchSearch(MatchAiOptions.forLevel(stronger))
        val weak = MatchSearch(MatchAiOptions.forLevel(weaker))
        var strongWins = 0
        var weakWins = 0

        repeat(TRIALS) { trial ->
            val deal = Random(SEED + trial)
            val cards = List(2 * HAND_SIZE) { drawCard(it, deal) }
            // Blue is the stronger side. Who opens alternates, because opening places five cards
            // to four and is worth about that much.
            var state = MatchState.start(
                blueHand = cards.take(HAND_SIZE).map { it.copy(owner = CardColor.BLUE) },
                redHand = cards.drop(HAND_SIZE).map { it.copy(owner = CardColor.RED) },
                first = if (trial % 2 == 0) CardColor.BLUE else CardColor.RED,
            )

            // One generator per side, so a blunder drawn by one does not shift the other's rolls.
            val forBlue = Random(SEED - trial)
            val forRed = Random(SEED + TRIALS + trial)
            var move = state.moveFrom(strong, weak, forBlue, forRed)
            while (move != null) {
                state = state.play(move.card, move.position)
                move = state.moveFrom(strong, weak, forBlue, forRed)
            }

            when (state.score.winner()) {
                CardColor.BLUE -> strongWins++
                CardColor.RED -> weakWins++
                null -> Unit
            }
        }
        return strongWins to weakWins
    }

    /**
     * Whose turn it is, which band plays it, and what it chooses — `null` when the match is over.
     *
     * Written as a function rather than inline so the loop has one exit instead of three.
     */
    private fun MatchState.moveFrom(
        strong: MatchSearch,
        weak: MatchSearch,
        forBlue: Random,
        forRed: Random,
    ): ScoredMove? {
        val onMove = currentPlayer ?: return null
        val searcher = if (onMove == CardColor.BLUE) strong else weak
        return searcher.choose(this, random = if (onMove == CardColor.BLUE) forBlue else forRed)
    }

    /** A card with four drawn faces. No catalogue: what is being measured is the play, not data. */
    private fun drawCard(index: Int, random: Random) = Card(
        id = Card.idFor(block = 1, number = index + 1),
        nameKey = "STR_TEST_$index",
        name = "Test $index",
        top = random.nextInt(1, ACE_POWER + 1),
        right = random.nextInt(1, ACE_POWER + 1),
        bottom = random.nextInt(1, ACE_POWER + 1),
        left = random.nextInt(1, ACE_POWER + 1),
        rarity = 1,
    )

    private companion object {
        /**
         * Enough deals to separate one tuning step, and few enough that the deep bands are
         * affordable — an expert searches five half-moves per placement and there are five of them
         * per match. Raise it when a rung is close rather than when it is failing.
         *
         * It was forty, and forty was not enough. Adjacent rungs settle around 55:45 with a third
         * of the deals drawn, and at forty trials that is inside the noise: the `AVERAGE` rung
         * failed here while measuring 44:36 over a hundred and twenty of the same kind of deal.
         * Eighty is the point where the measured margins stop being coin flips.
         */
        const val TRIALS = 80

        const val SEED = 20260821
    }
}
