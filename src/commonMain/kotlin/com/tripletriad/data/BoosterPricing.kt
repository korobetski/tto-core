package com.tripletriad.data

import com.tripletriad.model.BoosterType
import com.tripletriad.model.Card
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What a pack is worth, and therefore what it costs.
 *
 * ### Derived, because a hand-typed price stops being true
 *
 * The AS3 prices its eight packs by hand — 360 for Beast, 8 000 for Mithril — and there is no
 * relation between them and what the packs hold. Beast and Bronze cost 360 and 520 for pools of
 * comparable strength, while Scion and Silver both cost 1 152 for pools three stars apart. Nobody
 * did the arithmetic, and nobody could have kept doing it: the price of a pack changes every time a
 * card is added to its pool, and a number in a list does not know that.
 *
 * So a booster's price is **computed from its contents**. Change a pool, add a card, resize a pack,
 * and the shop reprices itself. The one thing authored anywhere is [CardValue.MGP_BY_RARITY] —
 * what a star is worth — which is a design decision and belongs in exactly one place.
 *
 * ### The draw distribution is exact, not sampled
 *
 * `BoosterItem.open` picks an index as `min(round(u · v · 1.25 · last), last)` for two independent
 * uniforms, and the product of two uniforms has a closed form: `P(UV ≤ t) = t − t·ln t` on (0, 1].
 * So the whole distribution over pool indices comes out of [indexOdds] in a few multiplications —
 * no sampling, no seed, no number that drifts between runs. A Monte Carlo estimate would have been
 * easier to write and would have made the shop's prices depend on a random generator, which is the
 * kind of thing that is fine until somebody changes a library.
 *
 * ### The ladder, and one number it is deliberately not
 *
 * [CardValue.MGP_BY_RARITY] triples with each star, which is what makes a five-star pack cost real
 * money and a starter pack cost a couple of matches. Calibrated against the one authored price that
 * was plausible: the AS3 charges 520 for Bronze, and this gives 500.
 *
 * It is **not** `CardItem.value`, the game's sale price, which is `cardId * 4`. That was a rarity
 * proxy while ids were indices into one ascending table and stopped being one when ids went global:
 * an FFVIII common now "sells" for more than the rarest FFXIV card, because its id is a bigger
 * number. Pricing packs off it produced a shop in which every pack cost about seven thousand. See
 * the note on [CardItem][com.tripletriad.model.CardItem] — the sale price is a separate decision
 * and is not changed here.
 */
object BoosterPricing {
    /** What the shop charges over the expected contents: a pack is a gamble with a rake. */
    const val MARKUP: Double = 1.2

    /**
     * The chance of drawing each index of a pool of [size], under `BoosterItem.open`'s draw.
     *
     * `index = min(round(U · V · 1.25 · last), last)`, so with `X = U·V` scaled by `1.25 · last`:
     * `P(index = k) = F((k + ½) / c) − F((k − ½) / c)` where `c = 1.25 · last` and `F` is the CDF
     * of a product of two uniforms, `F(t) = t − t·ln t`, clamped to [0, 1]. The last index absorbs
     * everything above it, which is what the `min` does.
     *
     * Returns a distribution summing to 1. A single-card pool is the degenerate case and returns
     * `[1.0]` rather than dividing by a zero `last`.
     *
     * ### The second card is likelier than the first
     *
     * Worth knowing before reading a pool, because it looks like a bug. Rounding gives index 0 the
     * half-width bin `[0, ½)` and index 1 a full-width `[½, 1½)`, so a pool's **second** entry
     * comes out most often; the last index is the mirror image, absorbing everything that overshot.
     * It is the same fault `docs/analysis/game-rules.md` § 15.6 records for the rule roulette, and
     * it is reproduced for the same reason — it is the shipped drop rate.
     */
    fun indexOdds(size: Int): List<Double> {
        require(size > 0) { "a pool needs at least one card, had $size" }
        if (size == 1) return listOf(1.0)

        val last = size - 1
        val scale = DOWNGRADE_CEILING * last
        var below = 0.0
        return List(size) { k ->
            val upTo = if (k == last) 1.0 else productCdf((k + HALF) / scale)
            (upTo - below).also { below = upTo }
        }
    }

    /**
     * The chance a pack of [type] contains at least one five-star card.
     *
     * What the shop shows next to the price, and the only honest way to sell a gamble: a pack that
     * says "five cards" and nothing else is asking the player to guess its odds, and they will
     * guess wrong in whichever direction disappoints them.
     *
     * Computed as `1 − P(no slot is a five-star)` across the [BoosterType.size] − 1 ordinary slots
     * and the guaranteed one, which are independent draws.
     */
    fun fiveStarChance(type: BoosterType, cards: Map<Int, Card>): Double {
        val ordinary = 1.0 - chanceOfFiveStar(type.pool, cards)
        val guaranteed = 1.0 - chanceOfFiveStar(type.pool.drop(type.rareFrom), cards)
        var none = guaranteed
        repeat(type.size - 1) { none *= ordinary }
        return 1.0 - none
    }

    /**
     * The lowest star count the guaranteed slot can produce — what the pack may advertise.
     *
     * Per pack rather than one global claim, because the shipped pools do not support a global one:
     * Bronze's whole pool tops out at three stars and its guaranteed range holds a two, so a shelf
     * saying "every pack guarantees a three-star" would be lying about two of its rows. Derived, so
     * it cannot lie about any of them.
     */
    fun guaranteedFloor(type: BoosterType, cards: Map<Int, Card>): Int =
        type.pool.drop(type.rareFrom).minOf { cards[it]?.rarity ?: 1 }

    /** What a pack of [type] is expected to be worth, in MGP, before the shop's [MARKUP]. */
    fun expectedValue(type: BoosterType, cards: Map<Int, Card>): Double {
        val ordinary = expectedValueOf(type.pool, cards)
        val guaranteed = expectedValueOf(type.pool.drop(type.rareFrom), cards)
        return ordinary * (type.size - 1) + guaranteed
    }

    /**
     * What the shop charges for a pack of [type], rounded to the nearest ten.
     *
     * Rounded because a price is read by a person: 5 100 is a price and 5 094 is a rounding error
     * somebody will assume is meaningful.
     */
    fun priceOf(type: BoosterType, cards: Map<Int, Card>): Int {
        val raw = expectedValue(type, cards) * MARKUP
        return ((raw / ROUND_TO).roundToInt() * ROUND_TO).coerceAtLeast(ROUND_TO)
    }

    private fun expectedValueOf(pool: List<Int>, cards: Map<Int, Card>): Double =
        indexOdds(
            pool.size,
        ).withIndex().sumOf { (index, odds) -> odds * worthOf(pool[index], cards) }

    private fun chanceOfFiveStar(pool: List<Int>, cards: Map<Int, Card>): Double =
        indexOdds(pool.size).withIndex().sumOf { (index, odds) ->
            if (cards[pool[index]]?.rarity == TOP_RARITY) odds else 0.0
        }

    private fun worthOf(cardId: Int, cards: Map<Int, Card>): Int =
        CardValue.worthOf(cardId, cards)

    /** `F(t) = t − t·ln t`, the CDF of a product of two independent uniforms, clamped to [0, 1]. */
    private fun productCdf(t: Double): Double =
        if (t <= 0.0) 0.0 else min(1.0, t - t * ln(t))

    private const val DOWNGRADE_CEILING = 1.25
    private const val HALF = 0.5
    private const val TOP_RARITY = 5
    private const val ROUND_TO = 10
}
