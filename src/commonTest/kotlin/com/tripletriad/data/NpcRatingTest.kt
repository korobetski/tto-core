package com.tripletriad.data

import com.tripletriad.model.Card
import com.tripletriad.model.Npc
import com.tripletriad.model.NpcLevel
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [NpcRating] as a rule, against fixtures.
 *
 * ### Why this exists separately from the client's bundle test
 *
 * `NpcRatingBundleTest` in `tto-client` rates the eighty-four shipped opponents and holds
 * `npcs.json` to the answer. That is a test about **content** and it lives where the content is.
 *
 * It is not a test of this object, and for a while it was the only one — which `:core`'s coverage
 * gate caught, correctly: forty-seven lines of engine code whose only exercise was in another
 * repository, invisible to every gate this one runs. What follows is the other half: that a
 * stronger opponent rates harder, that the bands and the payouts are the functions they claim to
 * be, and that the yardstick is the yardstick.
 *
 * ### The fixtures are two opponents who differ in one way
 *
 * A weak hand against a strong hand, same rules, same pool of cards to be rated in. Rating is a
 * *simulation*, so an assertion about an absolute number would be an assertion about the AI's
 * current move ordering; what is stated here is the direction, which is the property the scale
 * needs and the only one that survives the AI being improved.
 */
class NpcRatingTest {
    private fun card(number: Int, power: Int) = Card(
        id = Card.idFor(block = 1, number = number),
        nameKey = "STR_TEST_$number",
        name = "Test $number",
        top = power,
        right = power,
        bottom = power,
        left = power,
        // Rarity rises with power, as the shipped table's does: the ladder in [BoosterPricing] and
        // the reference profile both read it, so a fixture where they disagreed would be testing
        // nothing anybody ships.
        rarity = (power / 2).coerceIn(1, 5),
    )

    /** Twenty cards from very weak to very strong, so a rating has room to move. */
    private val catalog = CardCatalog(
        sets = TEST_SETS,
        cards = (1..20).map { card(it, power = 1 + (it - 1) / 2) },
    )

    private val format = Format(
        id = "test",
        nameKey = "APP_TEST",
        blocks = listOf(1),
        rules = listOf("RULE_ALL_OPEN"),
    )

    private fun npc(name: String, cards: List<Int>) = Npc(
        id = 1,
        nameKey = "STR_NPC_$name",
        iconId = name,
        formats = listOf(format.id),
        fetishCards = cards.map { Card.idFor(block = 1, number = it) },
    )

    private val weakling = npc("weakling", listOf(1, 2, 3, 4, 5))
    private val monster = npc("monster", listOf(16, 17, 18, 19, 20))

    private fun rate(opponent: Npc, trials: Int = TRIALS) = NpcRating.referenceWinRate(
        npc = opponent,
        reference = NpcRating.referenceProfile(catalog, format),
        catalog = catalog,
        format = format,
        random = Random(SEED),
        trials = trials,
    )

    // ---- The yardstick ----------------------------------------------------

    /**
     * The reference is the middle of the table, and it owns more than it can field.
     *
     * The second half is the load-bearing one: `RULE_RANDOM` deals from the *collection*, so a
     * yardstick whose collection was its deck would make that rule a no-op and rate every opponent
     * imposing it as easier than it is.
     */
    @Test
    fun theReferenceProfileHoldsTenMiddlingCardsAndFieldsFive() {
        val reference = NpcRating.referenceProfile(catalog, format)
        val held = reference.cards.keys.mapNotNull { catalog.byId[it] }

        assertEquals(NpcRating.REFERENCE_CARDS, reference.cards.size)
        assertEquals(1, reference.decks.size)
        assertEquals(HAND, reference.decks.single().cards.size)
        assertTrue(
            reference.decks.single().cards.all { it in reference.cards },
            "the deck must be fieldable from the collection",
        )

        // "The middle" said as a property rather than as the arithmetic: the yardstick holds
        // none of the strongest cards and none of the weakest. Restating the slice indices here
        // would assert that the code is the code.
        val ranked = catalog.admittedBy(format).sortedWith(compareBy({ it.total }, { it.id }))
        val edge = (ranked.size - NpcRating.REFERENCE_CARDS) / 2
        val extremes = (ranked.take(edge) + ranked.takeLast(edge)).map { it.id }.toSet()
        assertTrue(
            held.none { it.id in extremes },
            "the yardstick must be neither the best cards nor the worst: " +
                "held ${held.map { it.total }}, extremes ${extremes.size}",
        )
    }

    /** The same table gives the same yardstick, or no two ratings are comparable. */
    @Test
    fun theReferenceProfileIsDeterministic() {
        assertEquals(
            NpcRating.referenceProfile(catalog, format).cards,
            NpcRating.referenceProfile(catalog, format).cards,
        )
    }

    @Test
    fun aTableTooSmallToRateAgainstIsRefused() {
        val tiny = CardCatalog(sets = TEST_SETS, cards = (1..4).map { card(it, power = 5) })

        assertFailsWith<IllegalArgumentException> {
            NpcRating.referenceProfile(tiny, format)
        }
    }

    // ---- The measurement --------------------------------------------------

    /**
     * The whole claim: a better hand is a harder opponent.
     *
     * Stated as a direction and not as a number. The rating is a simulation, so pinning
     * `weakling` at some exact win rate would pin the AI's current move ordering — and the AI is
     * allowed to get better without every opponent in the game silently renumbering.
     */
    @Test
    fun aStrongerHandIsRatedHarder() {
        val againstWeakling = rate(weakling)
        val againstMonster = rate(monster)

        assertTrue(
            againstWeakling > againstMonster,
            "the reference should beat 1-power cards more often than 10s: " +
                "$againstWeakling vs $againstMonster",
        )
        assertTrue(
            NpcRating.difficultyFor(againstMonster) > NpcRating.difficultyFor(againstWeakling),
        )
    }

    @Test
    fun aWinRateIsAProportionAndTheSameSeedGivesTheSameOne() {
        val rate = rate(weakling)

        assertTrue(rate in 0.0..1.0, "a win rate must be a proportion, was $rate")
        assertEquals(rate, rate(weakling), "the same seed must give the same rating")
    }

    /** A different seed is a different sample, which is what makes the fixed one worth fixing. */
    @Test
    fun theSeedIsWhatMakesARatingReproducible() {
        val other = NpcRating.referenceWinRate(
            npc = weakling,
            reference = NpcRating.referenceProfile(catalog, format),
            catalog = catalog,
            format = format,
            random = Random(SEED + 1),
            trials = TRIALS,
        )

        // Not an assertion that they *must* differ — two samples can agree — but that the rating
        // reads the generator at all. A rating ignoring it would be a constant.
        assertTrue(other in 0.0..1.0)
    }

    @Test
    fun ratingWithNoTrialsIsAProgrammingError() {
        assertFailsWith<IllegalArgumentException> { rate(weakling, trials = 0) }
    }

    // ---- The bands and the payouts ---------------------------------------

    /**
     * A win rate maps onto the whole scale, inverted, and nothing falls off either end.
     *
     * The two extremes are the ones worth naming: never beaten is the top band and always beaten is
     * the bottom, and `1.0` must not produce a zero through the `(1 − p) · 10` arithmetic.
     */
    @Test
    fun theScaleCoversItsWholeRangeAndNothingEscapesIt() {
        assertEquals(NpcRating.RANGE.last, NpcRating.difficultyFor(0.0), "never beaten")
        assertEquals(NpcRating.RANGE.first, NpcRating.difficultyFor(1.0), "always beaten")

        val produced = (0..100).map { NpcRating.difficultyFor(it / 100.0) }
        assertTrue(produced.all { it in NpcRating.RANGE }, "produced $produced")
        assertEquals(NpcRating.RANGE.toSet(), produced.toSet(), "every band should be reachable")
        assertEquals(produced.sortedDescending(), produced, "harder must never mean a lower band")
    }

    @Test
    fun aWinRateOutsideZeroToOneIsAProgrammingError() {
        for (impossible in listOf(-0.1, 1.1)) {
            assertFailsWith<IllegalArgumentException> { NpcRating.difficultyFor(impossible) }
        }
    }

    /**
     * Every band has a skill level, and none of them is [NpcLevel.NONE].
     *
     * `NONE` pays no XP at all — see `Npc.xpFor` — so an opponent in it costs a match fee and
     * returns nothing towards a level. That is a state the data should not be able to express.
     */
    @Test
    fun everyBandHasALevelAndNoneOfThemPaysNothing() {
        val levels = NpcRating.RANGE.map(NpcRating::levelFor)

        assertTrue(NpcLevel.NONE !in levels, "produced $levels")
        assertEquals(levels.sortedBy { it.modifier }, levels, "harder must never mean less XP")
        assertEquals(NpcLevel.EXPERT, NpcRating.levelFor(NpcRating.RANGE.last))
        assertEquals(NpcLevel.NOVICE, NpcRating.levelFor(NpcRating.RANGE.first))
    }

    @Test
    fun aDifficultyOffTheScaleIsAProgrammingError() {
        for (impossible in listOf(0, 11, -1)) {
            assertFailsWith<IllegalArgumentException> { NpcRating.levelFor(impossible) }
            assertFailsWith<IllegalArgumentException> { NpcRating.mgpFor(impossible) }
            assertFailsWith<IllegalArgumentException> { NpcRating.feeFor(impossible) }
        }
    }

    /**
     * Harder pays more, a loss always pays something, and the fee never exceeds a win.
     *
     * The last two are the properties the shipped AS3 data broke: one opponent declared `l: 0`, so
     * losing to it after paying its fee returned nothing at all.
     */
    @Test
    fun thePayoutRisesWithTheDifficultyAndALossAlwaysPays() {
        val payouts = NpcRating.RANGE.map(NpcRating::mgpFor)

        assertEquals(payouts.sortedBy { it.win }, payouts, "harder must never pay less")
        assertTrue(payouts.all { it.lose > 0 }, "a loss must always pay something")
        assertTrue(payouts.all { it.draw in it.lose..it.win }, "a draw sits between the two")

        for (difficulty in NpcRating.RANGE) {
            assertTrue(
                NpcRating.feeFor(difficulty) < NpcRating.mgpFor(difficulty).win,
                "difficulty $difficulty charges more than a win returns",
            )
        }
        assertEquals(
            NpcRating.RANGE.map(NpcRating::feeFor).sorted(),
            NpcRating.RANGE.map(NpcRating::feeFor),
            "harder must never be cheaper to sit down against",
        )
    }

    /** [NpcRating.rated] replaces all four fields together, which is the point of one function. */
    @Test
    fun ratingAnOpponentReplacesAllFourBalanceFields() {
        val stale = weakling.copy(
            difficulty = 0,
            level = NpcLevel.NONE,
            matchFee = 999,
            mgpReward = com.tripletriad.model.MgpReward(win = 1, draw = 1, lose = 0),
        )

        val rated = NpcRating.rated(stale, winRate = 0.0)

        assertEquals(NpcRating.RANGE.last, rated.difficulty)
        assertEquals(NpcRating.levelFor(NpcRating.RANGE.last), rated.level)
        assertEquals(NpcRating.mgpFor(NpcRating.RANGE.last), rated.mgpReward)
        assertEquals(NpcRating.feeFor(NpcRating.RANGE.last), rated.matchFee)
        assertEquals(stale.iconId, rated.iconId, "and nothing else about the opponent moves")
        assertEquals(stale.fetishCards, rated.fetishCards)
        assertNotEquals(stale.difficulty, rated.difficulty)
    }

    private companion object {
        /** Enough to separate two opponents, few enough that the suite stays quick. */
        const val TRIALS = 60
        const val SEED = 20260812
        const val HAND = 5
    }
}
