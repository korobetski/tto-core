package com.tripletriad.protocol

import com.tripletriad.model.GameSave
import com.tripletriad.model.TradeRule
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The ceiling on a wager, and what counts as a big one.
 *
 * Like [UnlocksTest], the arithmetic is one line and is not what this is about. What is worth
 * holding down is the shape of the rule: that it is the *level* it climbs with and not the purse,
 * that it closes below zero as well as above the ceiling, and that a deployment saying nothing
 * still means something definite.
 */
class PvpStakePolicyTest {

    // ---- The ceiling ------------------------------------------------------

    @Test
    fun theCeilingClimbsWithTheLevelAndNothingElse() {
        val policy = PvpStakePolicy()

        assertEquals(PvpStakePolicy.DEFAULT_PER_LEVEL, policy.ceilingFor(save(level = 1)))
        assertEquals(PvpStakePolicy.DEFAULT_PER_LEVEL * 5, policy.ceilingFor(save(level = 5)))
        // The same level with a fortune behind it buys no more room than the same level without.
        assertEquals(
            policy.ceilingFor(save(level = 5)),
            policy.ceilingFor(save(level = 5, mgp = 500_000)),
        )
    }

    /** At the ceiling, not past it. */
    @Test
    fun theCeilingItselfIsAllowed() {
        val policy = PvpStakePolicy()
        val save = save(level = 5)

        assertTrue(policy.allows(save, PvpStake(mgp = policy.ceilingFor(save))))
        assertFalse(policy.allows(save, PvpStake(mgp = policy.ceilingFor(save) + 1)))
    }

    @Test
    fun risingALevelBuysRoomForABiggerWager() {
        val policy = PvpStakePolicy()
        val stake = PvpStake(mgp = PvpStakePolicy.DEFAULT_PER_LEVEL * 6)

        assertFalse(policy.allows(save(level = 5), stake))
        assertTrue(policy.allows(save(level = 6), stake))
    }

    /**
     * **A wager you win by losing.**
     *
     * `PvpMatchRow.spoils` pays the winner `stake.mgp`, so a negative one settles backwards, and
     * the affordability check cannot catch it: it asks whether the purse is *at least* the stake,
     * which every negative number passes. This is the only place it is refused.
     */
    @Test
    fun aNegativeWagerIsNotAWager() {
        assertFalse(PvpStakePolicy().allows(save(level = 20), PvpStake(mgp = -5_000)))
    }

    @Test
    fun wageringNothingIsAlwaysAllowedIncludingAtLevelOne() {
        assertTrue(PvpStakePolicy().allows(save(level = 1), PvpStake.None))
    }

    /** A trade-only stake is not an amount, so the ceiling has nothing to say about it. */
    @Test
    fun theCeilingIsOnTheMoneyAndNotOnTheCards() {
        val stake = PvpStake(mgp = 0, trade = TradeRule.ALL)

        assertTrue(PvpStakePolicy().allows(save(level = 1), stake))
    }

    // ---- What counts as heavy ---------------------------------------------

    @Test
    fun aQuarterOfThePurseIsHeavyAndALittleLessIsNot() {
        val policy = PvpStakePolicy()

        assertTrue(policy.isHeavy(mgp = 250, purse = 1_000))
        assertFalse(policy.isHeavy(mgp = 249, purse = 1_000))
    }

    /**
     * The same table reads differently to two players, which is why this is not on the server.
     *
     * A stake is heavy relative to the purse of whoever is looking at it. There is no answer the
     * house could compute once and send.
     */
    @Test
    fun theSameWagerIsHeavyForOnePlayerAndNotForAnother() {
        val policy = PvpStakePolicy()

        assertTrue(policy.isHeavy(mgp = 500, purse = 800))
        assertFalse(policy.isHeavy(mgp = 500, purse = 40_000))
    }

    @Test
    fun aFreeTableIsNeverHeavyEvenToAnEmptyPurse() {
        assertFalse(PvpStakePolicy().isHeavy(mgp = 0, purse = 0))
        assertTrue(PvpStakePolicy().isHeavy(mgp = 1, purse = 0))
    }

    /** The comparison is the whole purse multiplied, so it has to hold at the top of the range. */
    @Test
    fun aFortuneDoesNotOverflowTheComparison() {
        val policy = PvpStakePolicy()

        assertFalse(policy.isHeavy(mgp = 1_000, purse = Int.MAX_VALUE))
        assertTrue(policy.isHeavy(mgp = Int.MAX_VALUE, purse = Int.MAX_VALUE))
    }

    // ---- On the wire ------------------------------------------------------

    @Test
    fun aServerThatNamesNoCeilingMeansTheDefaults() {
        val body = """
            {"name":"Alpha",
             "version":{"major":3,"minor":0,"patch":0},
             "minimumClient":{"major":3,"minor":0,"patch":0}}
        """.trimIndent()

        val info = Json { ignoreUnknownKeys = true }.decodeFromString<ServerInfo>(body)

        assertEquals(PvpStakePolicy.DEFAULT_PER_LEVEL, info.stakes.perLevel)
        assertEquals(PvpStakePolicy.DEFAULT_HEAVY_PERCENT, info.stakes.heavyPercent)
    }

    @Test
    fun aServerThatNamesItsOwnIsBelieved() {
        val info = ServerInfo(
            name = "Alpha",
            version = CURRENT_VERSION,
            minimumClient = CURRENT_VERSION,
            stakes = PvpStakePolicy(perLevel = 40, heavyPercent = 60),
        )
        val round = Json.decodeFromString<ServerInfo>(Json.encodeToString(info))

        assertEquals(400, round.stakes.ceilingFor(save(level = 10)))
        assertFalse(round.stakes.isHeavy(mgp = 500, purse = 1_000))
    }

    private fun save(level: Int, mgp: Int = 0) =
        GameSave(username = "kuplu", level = level, mgp = mgp)
}
