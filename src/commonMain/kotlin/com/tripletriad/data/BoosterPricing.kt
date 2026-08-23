package com.tripletriad.data

import com.tripletriad.model.BoosterType
import com.tripletriad.model.Card
import kotlin.math.pow
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
 * So a booster's price is **computed from its contents**. Change a pool, reweight a card, and the
 * shop reprices itself. The one thing authored anywhere is [CardValue.MGP_BY_RARITY] — what a star
 * is worth — which is a design decision and belongs in exactly one place.
 *
 * ### The draw distribution reads the pack's own [BoosterType.weights]
 *
 * A pack's odds used to come from one formula shared by every pack; now each [BoosterType] carries
 * its own [BoosterType.weights], real drop-report numbers for the five real FFXIV packs and a
 * frozen formula output for the rest — see that class's KDoc. [oddsOf] only normalises them (they
 * are not required to already sum to 1), so pricing here and the draw in `BoosterItem.open` read
 * the exact same numbers and cannot silently disagree about what a pack contains.
 *
 * ### The ladder, and one number it is deliberately not
 *
 * [CardValue.MGP_BY_RARITY] rises with each star, which is what makes a five-star pack cost real
 * money and a starter pack cost a couple of matches. It is read backwards from
 * arrtripletriad.com's own published card sale prices — see that property's KDoc — rather than
 * calibrated against an AS3 shelf price, so a pack's cost is one step removed from a real number
 * rather than an invented one.
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
     * [type]'s [BoosterType.weights], normalised into a distribution that sums to 1.
     *
     * The weights themselves are authored per pack and are never required to already sum to
     * anything in particular — a scraped drop-report percentage, an inverse-rarity ratio, and a
     * frozen formula output all arrive at different scales, so this is where they all become
     * comparable probabilities, in the same order as [BoosterType.pool].
     */
    fun oddsOf(type: BoosterType): List<Double> = oddsFor(type.weights)

    /**
     * The chance a pack of [type] draws a five-star card.
     *
     * What the shop shows next to the price, and the only honest way to sell a gamble: a pack that
     * says only "one card" and nothing else is asking the player to guess its odds, and they will
     * guess wrong in whichever direction disappoints them.
     */
    fun fiveStarChance(type: BoosterType, cards: Map<Int, Card>): Double =
        chanceOfFiveStar(type, cards)

    /** What a pack of [type] is expected to be worth, in MGP, before the shop's [MARKUP]. */
    fun expectedValue(type: BoosterType, cards: Map<Int, Card>): Double =
        expectedValueOf(type, cards)

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

    private fun expectedValueOf(type: BoosterType, cards: Map<Int, Card>): Double =
        expectedValueFor(type.pool, type.weights, type.cardCount, cards)

    private fun chanceOfFiveStar(type: BoosterType, cards: Map<Int, Card>): Double =
        fiveStarChanceFor(type.pool, type.weights, type.cardCount, cards)

    /**
     * [expectedValueOf], as a pure function of a pool and its weights.
     *
     * Draws are independent, so [count] cards are worth [count] times what one is — the reason
     * this is split out from [expectedValueOf] rather than inlined is so a `cardCount` above the 1
     * every shipped pack uses today can be exercised in a test without a shipped multi-card pack to
     * carry it.
     */
    internal fun expectedValueFor(
        pool: List<Int>,
        weights: List<Double>,
        count: Int,
        cards: Map<Int, Card>,
    ): Double {
        val odds = oddsFor(weights)
        val perDraw = odds.withIndex().sumOf { (index, odd) -> odd * worthOf(pool[index], cards) }
        return count * perDraw
    }

    /**
     * [chanceOfFiveStar], as a pure function of a pool and its weights.
     *
     * Not `count * perDrawChance`: that overshoots past 1 once a pack draws enough cards at a high
     * enough rate, because it does not account for a pack landing a five-star on more than one of
     * its draws. The right question for a shop line is "does this pack contain at least one
     * five-star", which is `1 - P(none of the count draws is one)` — `1 - (1 - perDraw)^count`.
     * At `count == 1` this is exactly `perDraw`, so every shipped pack is unaffected.
     */
    internal fun fiveStarChanceFor(
        pool: List<Int>,
        weights: List<Double>,
        count: Int,
        cards: Map<Int, Card>,
    ): Double {
        val perDraw = oddsFor(weights).withIndex().sumOf { (index, odd) ->
            if (cards[pool[index]]?.rarity == TOP_RARITY) odd else 0.0
        }
        return 1.0 - (1.0 - perDraw).pow(count)
    }

    private fun oddsFor(weights: List<Double>): List<Double> {
        val total = weights.sum()
        return weights.map { it / total }
    }

    private fun worthOf(cardId: Int, cards: Map<Int, Card>): Int =
        CardValue.worthOf(cardId, cards)

    private const val TOP_RARITY = 5
    private const val ROUND_TO = 10
}
