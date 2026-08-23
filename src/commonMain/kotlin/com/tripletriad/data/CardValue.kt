package com.tripletriad.data

import com.tripletriad.model.Card
import kotlin.math.roundToInt

/**
 * What a card is worth, and what a shop will pay for it.
 *
 * ### One ladder, read two ways
 *
 * [MGP_BY_RARITY] is what a card is *worth*. [BoosterPricing] integrates it over a pool to price a
 * pack; [resaleOf] takes a fraction of it to say what a shop pays. One authored ladder, so a shop
 * that buys for more than it sells cannot be expressed — which is the property the old code could
 * not state at all, because pack prices and card values came from unrelated arithmetic.
 *
 * ### Why a shop pays less than it charges
 *
 * [RESALE_RATE] is 0.4 against [BoosterPricing.MARKUP] of 1.2, so anything bought and immediately
 * resold returns a third of its price. That gap is not greed, it is the thing that closes the loop:
 * as long as resale is below the cheapest way to acquire a card, no sequence of purchases and sales
 * makes money, and `CardValueTest` asserts exactly that against the shipped shelf.
 *
 * Selling is still worth doing — a duplicate five-star returns 1 500 MGP, several matches' worth —
 * which is what the feature is for. It is a way to turn cards you will not play into cards you
 * will, not a second income.
 */
object CardValue {
    /**
     * What one card of each star count is worth, in MGP.
     *
     * The single authored economic ladder. Set so that [resaleOf] — worth times [RESALE_RATE] —
     * lands exactly on arrtripletriad.com's own published "sells for" price per rarity (100 / 300 /
     * 500 / 800 / 1 500 MGP, scraped from the individual card pages of the real FFXIV Triple Triad
     * trader, one value per star and no exceptions across 142 sampled cards). Worth is therefore
     * `sale price / RESALE_RATE`, so this is a real-game number read backwards through the shop's
     * markup rather than an invented ladder.
     *
     * Lives here rather than in [BoosterPricing] because pack pricing is one *reader* of it and
     * resale is another. Putting it in either would make the other look derived from packs, or from
     * selling, when both are derived from worth.
     */
    val MGP_BY_RARITY: Map<Int, Int> = mapOf(1 to 250, 2 to 750, 3 to 1_250, 4 to 2_000, 5 to 3_750)

    /** What a shop pays, as a fraction of worth. This object's KDoc says why it is well under 1. */
    const val RESALE_RATE: Double = 0.4

    /** What [card] is worth, before anyone buys or sells it. */
    fun worthOf(card: Card): Int = MGP_BY_RARITY[card.rarity] ?: MGP_BY_RARITY.getValue(1)

    /**
     * What [cardId] is worth, or a common's worth when the catalogue does not hold it.
     *
     * The fallback is the bottom of the ladder rather than a throw: an id no catalogue resolves is
     * a save written by a newer build, and the honest answer is "the least it could be" rather than
     * a crash on the bag screen.
     */
    fun worthOf(cardId: Int, cards: Map<Int, Card>): Int =
        cards[cardId]?.let(::worthOf) ?: MGP_BY_RARITY.getValue(1)

    /** What a shop pays for one [cardId]. Never zero, so selling is never a waste of a tap. */
    fun resaleOf(cardId: Int, cards: Map<Int, Card>): Int =
        (worthOf(cardId, cards) * RESALE_RATE).roundToInt().coerceAtLeast(1)
}
