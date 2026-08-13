package com.tripletriad.data

import com.tripletriad.model.BoosterItem
import com.tripletriad.model.BoosterType
import com.tripletriad.model.Card
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [BoosterPricing] — the draw distribution, and the price that follows from it.
 *
 * ### The distribution is the part worth testing
 *
 * `indexOdds` claims to be `BoosterItem.open`'s draw in closed form: two uniforms multiplied, which
 * has CDF `t − t·ln t`. That is a claim about *this* code matching *that* code, and the only honest
 * way to check it is to run the real draw a great many times and see whether the shape agrees. So
 * [theClosedFormMatchesTheDrawItClaimsToDescribe] does exactly that — it is the reason to trust
 * every price on the shelf, since all of them are integrals against this distribution.
 *
 * Everything else here is properties: odds sum to one, a better pool costs more, a guarantee is a
 * guarantee. The shipped shelf's actual numbers belong to `ShopBundleTest` in the client, where the
 * real card table is.
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
    fun theOddsOverAPoolAreADistribution() {
        for (size in 1..20) {
            val odds = BoosterPricing.indexOdds(size)

            assertEquals(size, odds.size, "one entry per card")
            assertTrue(odds.all { it >= 0.0 }, "no negative chance: $odds")
            assertTrue(abs(odds.sum() - 1.0) < TOLERANCE, "size $size sums to ${odds.sum()}")
        }
    }

    /** A one-card pool has no `last` to divide by, and answers with certainty instead. */
    @Test
    fun aSingleCardPoolIsCertain() {
        assertEquals(listOf(1.0), BoosterPricing.indexOdds(1))
    }

    @Test
    fun anEmptyPoolIsAProgrammingError() {
        assertFailsWith<IllegalArgumentException> { BoosterPricing.indexOdds(0) }
    }

    /**
     * The closed form really is the draw.
     *
     * `BoosterItem.open` is sampled forty thousand times and the histogram compared against
     * [BoosterPricing.indexOdds]. This is the load-bearing test of the file: if the two ever part
     * company, every price in the shop becomes an integral against a distribution the game does not
     * use, and nothing else would notice — the prices would still be numbers, and still ordered,
     * and still wrong.
     *
     * The tolerance is loose because forty thousand samples of a ten-way split have a standard
     * error around half a point. It is tight enough to catch a distribution that is *different*,
     * which is the failure being guarded against, not one that is noisy.
     */
    @Test
    fun theClosedFormMatchesTheDrawItClaimsToDescribe() {
        val pack = BoosterItem(BoosterType.BEAST)
        val pool = BoosterType.BEAST.pool
        val expected = BoosterPricing.indexOdds(pool.size)

        // The ordinary slots only. The guaranteed one draws from a sub-list by design, and mixing
        // it in would be comparing one distribution against a blend of two.
        val counts = IntArray(pool.size)
        repeat(SAMPLES) { seed ->
            for (id in pack.open(Random(seed)).dropLast(1)) counts[pool.indexOf(id)] += 1
        }
        val drawn = counts.sum()

        for (index in pool.indices) {
            val observed = counts[index].toDouble() / drawn
            assertTrue(
                abs(observed - expected[index]) < SAMPLING_TOLERANCE,
                "index $index: the draw gives $observed, the closed form says ${expected[index]}",
            )
        }
    }

    /**
     * Written best-last, drawn front-heavy — that asymmetry *is* the rarity curve.
     *
     * ### Both ends of the pool are rounding artefacts, and the second card wins
     *
     * The obvious assertion — "index 0 is the likeliest" — is **false**, which this test found. The
     * draw rounds, so index 0 owns the half-width bin `[0, ½)` while index 1 owns a full-width
     * `[½, 1½)`: the second card of a pool comes out **more often than the first**. The last index
     * is the mirror image, absorbing everything that overshot through the `min`, so it sits above
     * its neighbour too.
     *
     * That is the same rounding fault `docs/analysis/game-rules.md` § 15.6 records for the rule
     * roulette, where `Math.round(Math.random() * (n - 1))` halves the odds of the first and last
     * entries — here it is the booster draw, and it survives for the same reason: it *is* the
     * shipped drop rate, and levelling it would change every pack in the game.
     *
     * So what is asserted is the true shape: a peak at index 1, a monotone fall through the
     * interior, and a lifted tail.
     */
    @Test
    fun theOddsLeanHardOnTheStartOfThePool() {
        val odds = BoosterPricing.indexOdds(10)
        val interior = odds.subList(1, odds.size - 1)

        assertEquals(
            odds.max(),
            odds[1],
            "the second card owns a full-width bin and the first does not",
        )
        assertTrue(odds[0] < odds[1], "index 0 is rounded into from one side only")
        assertTrue(odds.last() > odds[odds.size - 2], "the last index absorbs every overshoot")
        assertEquals(
            interior.sortedDescending(),
            interior,
            "the odds must fall along the pool: $odds",
        )
        assertTrue(odds.take(3).sum() > HALF, "most of the mass sits at the front: $odds")
    }

    // ---- What it says about a pack ---------------------------------------

    @Test
    fun aPackIsWorthMoreThanOneOfItsCards() {
        for (type in BoosterType.entries) {
            val value = BoosterPricing.expectedValue(type, cards)
            val cheapest = CardValue.MGP_BY_RARITY.getValue(1)

            assertTrue(
                value > cheapest * type.size,
                "${type.name} of ${type.size} cards is worth $value",
            )
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

    /**
     * The guaranteed slot cannot fall below the floor the shop advertises.
     *
     * Both halves matter and they are the same fact from two directions: the floor is *derived*
     * from the guaranteed range, and the draw never leaves that range — so the shop's promise is
     * true by construction rather than by somebody remembering to update a sentence.
     */
    @Test
    fun theAdvertisedFloorIsOneTheDrawCannotBreak() {
        for (type in BoosterType.entries) {
            val floor = BoosterPricing.guaranteedFloor(type, cards)
            val pack = BoosterItem(type)

            repeat(GUARANTEE_SAMPLES) { seed ->
                val prize = pack.open(Random(seed)).last()
                assertTrue(
                    (cards[prize]?.rarity ?: 1) >= floor,
                    "${type.name} promised $floor★ and dealt ${cards[prize]?.rarity}",
                )
            }
        }
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
        const val GUARANTEE_SAMPLES = 300
        const val HALF = 0.5
    }
}
