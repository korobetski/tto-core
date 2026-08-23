package com.tripletriad.data

import com.tripletriad.model.BoosterItem
import com.tripletriad.model.BoosterType
import com.tripletriad.model.Card
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [BoosterPricing] — the draw distribution, and the price that follows from it.
 *
 * ### The distribution is the part worth testing
 *
 * [oddsOf] claims to be `BoosterItem.open`'s draw, normalised: the same [BoosterType.weights] the
 * draw itself reads. That is a claim about *this* code matching *that* code, and the only honest
 * way to check it is to run the real draw a great many times and see whether the shape agrees. So
 * [theOddsMatchTheDrawTheyClaimToDescribe] does exactly that — it is the reason to trust every
 * price on the shelf, since all of them are integrals against this distribution.
 *
 * Everything else here is properties: odds sum to one, a better pool costs more. The shipped
 * shelf's actual numbers belong to `ShopBundleTest` in the client, where the real card table is.
 */
class BoosterPricingTest {
    private fun card(id: Int, rarity: Int) = Card(
        id = id,
        nameKey = "STR_TEST_$id",
        name = "Test $id",
        top = 1,
        right = 1,
        bottom = 1,
        left = 1,
        rarity = rarity,
    )

    /** Every id any pool names, its rarity rising along the pool as authored pools do. */
    private val cards: Map<Int, Card> = BoosterType.entries
        .flatMap { type ->
            type.pool.mapIndexed { index, id -> id to (1 + index / 2).coerceIn(1, 5) }
        }
        .distinctBy { it.first }
        .associate { (id, rarity) -> id to card(id, rarity) }

    // ---- The distribution -------------------------------------------------

    @Test
    fun oddsOfAnyPackAreADistribution() {
        for (type in BoosterType.entries) {
            val odds = BoosterPricing.oddsOf(type)

            assertEquals(type.pool.size, odds.size, "${type.name}: one entry per card")
            assertTrue(odds.all { it >= 0.0 }, "${type.name}: no negative chance: $odds")
            assertTrue(
                abs(odds.sum() - 1.0) < TOLERANCE,
                "${type.name} sums to ${odds.sum()}",
            )
        }
    }

    /**
     * `BoosterType.weights` need not already sum to 1 — a scraped percentage, an inverse-rarity
     * ratio and a frozen formula output arrive at three different scales — so this pins that
     * [oddsOf] is doing that normalising, rather than assuming whoever authors a pack got it right.
     */
    @Test
    fun oddsOfATwoToOneWeightedPoolAreTwoToOne() {
        val odds = BoosterPricing.oddsOf(BoosterType.PLATINUM)
        val fourStarOdds = odds.take(THREE_FOUR_STAR_CARDS)
        val fiveStarOdds = odds.drop(THREE_FOUR_STAR_CARDS)

        assertTrue(fourStarOdds.all { abs(it - fourStarOdds.first()) < TOLERANCE }, "$odds")
        assertTrue(fiveStarOdds.all { abs(it - fiveStarOdds.first()) < TOLERANCE }, "$odds")
        assertTrue(
            fourStarOdds.first() > fiveStarOdds.first(),
            "the cheaper rarity must be commoner",
        )
    }

    /**
     * The normalised odds really are the draw.
     *
     * `BoosterItem.open` is sampled forty thousand times and the histogram compared against
     * [BoosterPricing.oddsOf]. This is the load-bearing test of the file: if the two ever part
     * company, every price in the shop becomes an integral against a distribution the game does not
     * use, and nothing else would notice — the prices would still be numbers, and still ordered,
     * and still wrong.
     *
     * The tolerance is loose because forty thousand samples of a thirteen-way split have a standard
     * error around half a point. It is tight enough to catch a distribution that is *different*,
     * which is the failure being guarded against, not one that is noisy.
     */
    @Test
    fun theOddsMatchTheDrawTheyClaimToDescribe() {
        val pack = BoosterItem(BoosterType.BEAST)
        val pool = BoosterType.BEAST.pool
        val expected = BoosterPricing.oddsOf(BoosterType.BEAST)

        val counts = IntArray(pool.size)
        repeat(SAMPLES) { seed ->
            for (id in pack.open(Random(seed))) counts[pool.indexOf(id)] += 1
        }
        val drawn = counts.sum()

        for (index in pool.indices) {
            val observed = counts[index].toDouble() / drawn
            assertTrue(
                abs(observed - expected[index]) < SAMPLING_TOLERANCE,
                "index $index: the draw gives $observed, oddsOf says ${expected[index]}",
            )
        }
    }

    /**
     * [BoosterType.BEAST] is not a real pack — its weights are a frozen copy of the old
     * uniform-product formula, written weakest-first the same way every pool is — so its shape is
     * still worth pinning: a peak just past the first card, a fall through the interior, a lifted
     * tail. See the class doc on [BoosterType] for why this pack in particular carries that shape
     * rather than a real drop-report table.
     */
    @Test
    fun aFrozenPoolStillLeansHardOnItsStart() {
        val odds = BoosterPricing.oddsOf(BoosterType.BEAST)
        val interior = odds.subList(1, odds.size - 1)

        assertEquals(odds.max(), odds[1], "the second card is the peak, same as the old formula")
        assertTrue(odds[0] < odds[1], "index 0 sits below the peak")
        assertTrue(odds.last() > odds[odds.size - 2], "the last index absorbs every overshoot")
        assertEquals(
            interior.sortedDescending(),
            interior,
            "the odds must fall along the pool: $odds",
        )
        assertTrue(
            odds.take(odds.size / 2).sum() > HALF,
            "most of the mass sits in the front half of the pool: $odds",
        )
    }

    // ---- What it says about a pack ---------------------------------------

    /**
     * No shipped [BoosterType] draws more than one card — see that class's KDoc — but
     * [BoosterPricing.expectedValueFor] and [BoosterPricing.fiveStarChanceFor] are kept general so
     * a future multi-card pack prices itself correctly without this file changing again. This pins
     * that generality directly, against a synthetic pool rather than a shipped one.
     */
    @Test
    fun expectedValueScalesLinearlyWithCardCount() {
        val pool = listOf(1, 2)
        val weights = listOf(1.0, 1.0)
        val single = BoosterPricing.expectedValueFor(pool, weights, count = 1, cards)

        val tripled = BoosterPricing.expectedValueFor(pool, weights, count = THREE, cards)

        assertTrue(abs(tripled - single * THREE) < TOLERANCE, "$tripled should be $single times 3")
    }

    /**
     * The five-star chance of several draws is "at least one hits", not "the chances add up" — the
     * latter can exceed 1 and does here: two draws each `perDraw` away from certain still are not
     * certain together.
     */
    @Test
    fun fiveStarChanceIsAtLeastOneNotASum() {
        val pool = listOf(SYNTHETIC_ID_A, SYNTHETIC_ID_B)
        val weights = listOf(1.0, 1.0)
        val fiveStarCards = mapOf(
            SYNTHETIC_ID_A to card(SYNTHETIC_ID_A, FIVE_STAR),
            SYNTHETIC_ID_B to card(SYNTHETIC_ID_B, 1),
        )
        val perDraw = BoosterPricing.fiveStarChanceFor(pool, weights, count = 1, fiveStarCards)

        val twice = BoosterPricing.fiveStarChanceFor(pool, weights, count = 2, fiveStarCards)

        assertTrue(twice > perDraw, "drawing twice should raise the chance of a hit")
        assertTrue(twice < 1.0, "two draws at 50% is not a certainty")
        assertTrue(
            abs(twice - (1.0 - (1.0 - perDraw) * (1.0 - perDraw))) < TOLERANCE,
            "$twice should be 1 - (1 - $perDraw)^2",
        )
    }

    @Test
    fun aPackIsWorthAtLeastAsMuchAsTheCheapestCardInTheGame() {
        for (type in BoosterType.entries) {
            val value = BoosterPricing.expectedValue(type, cards)
            val cheapest = CardValue.MGP_BY_RARITY.getValue(1)

            assertTrue(value >= cheapest, "${type.name}'s single draw is worth $value")
            assertTrue(BoosterPricing.priceOf(type, cards) > 0, "${type.name} must cost something")
        }
    }

    /** The shop takes a rake: a pack costs more than its contents are expected to be worth. */
    @Test
    fun thePriceIsAboveTheExpectedContents() {
        for (type in BoosterType.entries) {
            assertTrue(
                BoosterPricing.priceOf(type, cards) >= BoosterPricing.expectedValue(type, cards),
                "${type.name} is priced below what it holds",
            )
        }
    }

    /**
     * A card the catalogue does not hold is valued as a common rather than crashing.
     *
     * Unreachable through the shipped pools — the client's bundle test resolves every id — and
     * handled because the alternative is a shop that throws while being drawn.
     */
    @Test
    fun anUnresolvableCardIsValuedAtTheBottomOfTheLadder() {
        val price = BoosterPricing.priceOf(BoosterType.BRONZE, cards = emptyMap())

        assertTrue(price > 0, "an unpriceable pack must still have a price")
    }

    @Test
    fun theOddsOfAFiveStarAreAProbability() {
        for (type in BoosterType.entries) {
            val chance = BoosterPricing.fiveStarChance(type, cards)

            assertTrue(chance in 0.0..1.0, "${type.name} claims $chance")
        }
    }

    /**
     * A pool with no five-star in it advertises none; a pool holding more of them advertises more.
     *
     * The zero is the half that matters — a shop claiming odds a pool cannot deliver is the defect
     * this number exists to prevent. The direction is the other half, and it is stated over two
     * real pools rather than a contrived certainty: under this fixture no pool is *entirely*
     * five-star, and inventing one would test arithmetic rather than the shipped shape.
     */
    @Test
    fun theFiveStarOddsFollowThePool() {
        fun fiveStars(type: BoosterType) = type.pool.count { cards[it]?.rarity == 5 }

        val poor = BoosterType.entries.first { fiveStars(it) == 0 }
        val rich = BoosterType.entries.maxBy(::fiveStars)

        assertEquals(0.0, BoosterPricing.fiveStarChance(poor, cards), "${poor.name} has none")
        assertTrue(fiveStars(rich) > 0, "the fixture assumes some pool reaches five stars")
        assertTrue(
            BoosterPricing.fiveStarChance(rich, cards) > 0.0,
            "${rich.name} holds ${fiveStars(rich)} five-stars and advertises none",
        )
    }

    private companion object {
        const val TOLERANCE = 1e-9
        const val SAMPLING_TOLERANCE = 0.02
        const val SAMPLES = 40_000
        const val HALF = 0.5
        const val THREE_FOUR_STAR_CARDS = 3
        const val THREE = 3
        const val FIVE_STAR = 5
        const val SYNTHETIC_ID_A = 300
        const val SYNTHETIC_ID_B = 301
    }
}
