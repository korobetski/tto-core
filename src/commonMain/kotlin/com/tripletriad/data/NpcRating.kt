package com.tripletriad.data

import com.tripletriad.model.CardColor
import com.tripletriad.model.DIFFICULTY_RANGE
import com.tripletriad.model.Deck
import com.tripletriad.model.GameSave
import com.tripletriad.model.HAND_SIZE
import com.tripletriad.model.MatchAi
import com.tripletriad.model.MatchResult
import com.tripletriad.model.Npc
import kotlin.random.Random

/**
 * How hard an opponent is, measured rather than asserted — and what it should therefore pay.
 *
 * ### The problem this exists for
 *
 * `NPCs.as` ships a `difficulty` field, and it is not a scale. The FFXIV table runs 1..19 with gaps
 * (there is no 13, 14, 15 or 17) against a `Npc.difficulty` KDoc that claimed 1..10, and **all
 * twenty-five FFVIII entries declare 0** — a field the FFVIII data never filled in. That was
 * harmless while `MODE` kept the two rosters apart: each was sorted against itself, and a column of
 * zeroes sorts fine. With `MODE` gone the opponent list is one list sorted by difficulty, so the
 * twenty-five zeroes sort ahead of everybody and a level-1 character meets the FFVIII cast first.
 *
 * The same is true of what they pay. A win is worth 0 MGP against one FFXIV opponent and 182
 * against another; the FFVIII table tops out at 128. The numbers were authored per table, by hand,
 * over years, and there is no reason the two scales should agree — they never had to meet.
 *
 * ### Measured, not weighted
 *
 * The obvious repair is a formula over hand power plus a hand-authored weight per rule. It was
 * rejected. Those weights would be fifteen numbers invented by whoever wrote them, and they get the
 * interactions wrong in ways nobody would notice: Fallen Ace only punishes a deck that holds aces,
 * Elemental only matters if the board's elements meet the hand, and Random is free against an
 * opponent whose collection is its deck.
 *
 * So this **plays the matches**. [referenceWinRate] runs a fixed reference profile against the
 * opponent, both sides driven by [MatchAi], under the rules that opponent actually imposes, and
 * returns how often the reference side wins. Every input the two-term formula wanted — card power,
 * rule burden, hand size, the roulette — is in that number because it is in the game.
 *
 * What it measures is the **matchup**, not a human. Both sides are the same AI, so the reference
 * side is neither clever nor stupid, and the only thing varying between two ratings is the
 * opponent. That is the property a difficulty scale needs; it is not a claim about how a person
 * would do.
 *
 * ### Why it is data and not a runtime call
 *
 * A rating costs a few hundred simulated matches. That is milliseconds, but it is not free and it
 * is not something an opponent list should do while a player waits. The numbers are computed once,
 * written into `npcs.json`, and held to this function by a test — so the file stays the thing the
 * app reads, and the reasoning behind every number stays executable.
 */
object NpcRating {
    /**
     * Matches per rating.
     *
     * The standard error of a proportion is `sqrt(p(1-p)/n)`, so at 400 trials a win rate near
     * 50% is good to about ±2.5 points. The difficulty bands below are ten points wide, so a
     * rating sits in the wrong band only when it lands within a standard error of a boundary — and
     * the seed is fixed, so whatever it does it does the same way every time. Raising this buys
     * precision nobody can see and costs the bundle test its running time.
     */
    const val TRIALS: Int = 400

    /**
     * The scale [difficultyFor] produces.
     *
     * [DIFFICULTY_RANGE] itself, not a copy of it: the scale is declared next to the arithmetic
     * that reads it — `Npc` computes its band, fee and payout from a difficulty and cannot import
     * `data` to learn what one is — and named here because this is what produces it.
     */
    val RANGE: IntRange = DIFFICULTY_RANGE

    /** How many cards the yardstick owns. See [referenceProfile]. */
    const val REFERENCE_CARDS: Int = 10

    /**
     * The yardstick: a profile holding the [REFERENCE_CARDS] cards closest to the middle of what
     * [format] admits, with the first five of them as its deck.
     *
     * ### Why the middle of the pool and not a starter
     *
     * The first version of this used the authored FFXIV starter, on the reasoning that a fresh
     * character is who difficulty is *for*. It rated forty-five of the eighty-five opponents into
     * the top two bands, which is true — a starter deck loses to most of the roster — and useless:
     * the opponent list sorts on this field, and twenty-five opponents tied at 10 sort by name.
     * A scale that cannot separate a third of its subjects has the same defect as the column of
     * zeroes it replaced.
     *
     * The middle of the card pool is the fix and is the better yardstick anyway, for a reason that
     * has nothing to do with the spread: **it does not depend on `starters.json`**. Rating against
     * an authored starter means swapping one card in a starter renumbers all eighty-five opponents
     * and moves the whole list. The median of the card table moves only when the card table does.
     *
     * ### Ten cards, not five
     *
     * `RULE_RANDOM` — twenty-eight opponents impose it — deals from the *collection* rather than
     * from the deck. A yardstick whose collection was its deck would make Random a no-op and rate
     * those twenty-eight easier than they are.
     *
     * Ties are broken by id so the selection is stable: the middle of the table is a plateau — over
     * two hundred cards share the median total — and `sortedBy` alone would leave the choice to the
     * catalogue's order.
     */
    fun referenceProfile(catalog: CardCatalog, format: Format): GameSave {
        val admitted = catalog.admittedBy(format)
        require(admitted.size >= REFERENCE_CARDS) {
            "${format.id} admits ${admitted.size} cards, too few to rate against"
        }
        val ranked = admitted.sortedWith(compareBy({ it.total }, { it.id }))
        val middle = ranked.subList(
            (ranked.size - REFERENCE_CARDS) / 2,
            (ranked.size - REFERENCE_CARDS) / 2 + REFERENCE_CARDS,
        )
        return GameSave.new(username = "Reference", createdAt = 0L).copy(
            cards = middle.associate { it.id to 1 },
            decks = listOf(Deck("Reference", middle.take(HAND_SIZE).map { it.id })),
        )
    }

    /**
     * How often [reference] beats [npc], over [TRIALS] matches.
     *
     * A draw counts as half a win. Triple Triad draws often enough that scoring them as losses
     * would make a defensive opponent look stronger than an aggressive one that loses as often as
     * it wins, and calling a draw half of each is the ordinary convention for a rating.
     *
     * The rules come from [PveMatches.rulesFor], per trial rather than once, so an opponent that
     * declares the roulette is rated across the pool it can draw — which is the thing that makes it
     * hard. Who starts is also drawn per trial, so the first-player advantage averages out instead
     * of being handed to one side.
     *
     * @param reference the profile doing the beating — [referenceProfile]. A **fixed** one across
     *   the whole roster: that two opponents were rated against the same yardstick is the entire
     *   reason their numbers can be compared, and it is what the two tables never had.
     * @param format what the match is played under. The widest one, so an opponent is not rated in
     *   a format the reference profile could not bring its cards to.
     * @param random seed it, and mean it: an unseeded rating is a number that changes when nothing
     *   changed.
     */
    // LongParameterList: six inputs and a trial count, and every one of them is a term of the
    // measurement — who, against whom, with which cards, under what, from which seed, how many
    // times. Bundling them behind a `RatingRequest` would name the same six things one indirection
    // further away, for a counter.
    @Suppress("LongParameterList")
    fun referenceWinRate(
        npc: Npc,
        reference: GameSave,
        catalog: CardCatalog,
        format: Format,
        random: Random,
        trials: Int = TRIALS,
    ): Double {
        require(trials > 0) { "trials must be positive, was $trials" }
        val ai = MatchAi()
        var score = 0.0

        repeat(trials) {
            val match = PveMatches.assemble(reference, npc, catalog, format, random)
            var state = match.setup.state
            while (!state.isFinished) state = ai.play(state, random)

            val outcome = checkNotNull(state.outcome()) { "a finished match has an outcome" }
            score += when (MatchResult.of(outcome, self = CardColor.BLUE)) {
                MatchResult.WIN -> 1.0
                MatchResult.LOSE -> 0.0
                // A sudden-death rematch is not replayed, and `MatchResult.of` returns null for
                // exactly that case: the *first* match drew, and that is the fact being measured.
                // Replaying would rate the rule rather than the opponent.
                MatchResult.DRAW, null -> DRAW_CREDIT
            }
        }
        return score / trials
    }

    /**
     * 1..10, from a win rate.
     *
     * Ten equal bands over the whole 0..1 range, inverted: an opponent the reference profile always
     * beats is a 1, one it never beats is a 10. Equal bands rather than a curve fitted to the
     * shipped roster, because a curve would have to be refitted the day an opponent is added and
     * would silently renumber the other eighty-four.
     *
     * The scale is therefore **absolute**. Nothing guarantees the roster uses all ten bands, and it
     * should not: if every shipped opponent is beatable half the time, the honest reading is that
     * the game has no hard opponents, not that the hardest of them is a 10.
     */
    fun difficultyFor(winRate: Double): Int {
        require(winRate in 0.0..1.0) { "a win rate must be in 0..1, was $winRate" }
        val band = ((1.0 - winRate) * RANGE.last).toInt() + 1
        return band.coerceIn(RANGE)
    }

    /**
     * [npc] rated: its difficulty replaced by what [winRate] says.
     *
     * One field, where this used to write four. `Npc.level`, `Npc.matchFee` and `Npc.mgpReward`
     * are computed from the difficulty now (`model/NpcBalance.kt`), so rating an opponent moves all
     * four numbers by writing one, and `npcs.json` has no way to carry a band that disagrees with
     * the strength it was measured at.
     */
    fun rated(npc: Npc, winRate: Double): Npc = npc.copy(difficulty = difficultyFor(winRate))

    private const val DRAW_CREDIT = 0.5
}
