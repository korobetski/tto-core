package com.tripletriad.protocol

import com.tripletriad.model.GameSave
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gate on player-to-player play and trade.
 *
 * The rule is one line, so what is worth testing is not the arithmetic but the two properties the
 * design rests on: that the threshold **travels** rather than being compiled into both ends, and
 * that a deployment which says nothing still means something definite.
 */
class UnlocksTest {

    @Test
    fun aCharacterBelowTheLineIsRefusedBothDoors() {
        val unlocks = Unlocks()
        val fresh = save(level = 1)

        assertFalse(unlocks.allowsMultiplayer(fresh))
        assertFalse(unlocks.allowsAuction(fresh))
    }

    /** At the threshold, not past it: five means five. */
    @Test
    fun theThresholdItselfIsOpen() {
        val unlocks = Unlocks()

        assertTrue(unlocks.allowsMultiplayer(save(level = Unlocks.DEFAULT_MULTIPLAYER)))
        assertTrue(unlocks.allowsAuction(save(level = Unlocks.DEFAULT_AUCTION)))
    }

    /**
     * The two gate different things and are not obliged to agree.
     *
     * They hold the same number today, which is exactly why this is worth a test: a single field
     * would have been the obvious simplification and would have made tuning one of them impossible
     * without tuning the other.
     */
    @Test
    fun theTwoDoorsCanBeSetIndependently() {
        val unlocks = Unlocks(multiplayer = 5, auction = 12)
        val eight = save(level = 8)

        assertTrue(unlocks.allowsMultiplayer(eight))
        assertFalse(unlocks.allowsAuction(eight))
    }

    /**
     * A deployment that sends no thresholds means the defaults.
     *
     * This is what lets the numbers be tuned by configuration: an older server, or one that has
     * simply not set them, is not a server with the gate wide open.
     */
    @Test
    fun aServerThatNamesNoThresholdsMeansTheDefaults() {
        val body = """
            {"name":"Alpha",
             "version":{"major":3,"minor":0,"patch":0},
             "minimumClient":{"major":3,"minor":0,"patch":0}}
        """.trimIndent()

        val info = Json { ignoreUnknownKeys = true }.decodeFromString<ServerInfo>(body)

        assertEquals(Unlocks.DEFAULT_MULTIPLAYER, info.unlocks.multiplayer)
        assertEquals(Unlocks.DEFAULT_AUCTION, info.unlocks.auction)
    }

    @Test
    fun aServerThatNamesItsOwnIsBelieved() {
        val info = ServerInfo(
            name = "Alpha",
            version = CURRENT_VERSION,
            minimumClient = CURRENT_VERSION,
            unlocks = Unlocks(multiplayer = 8, auction = 8),
        )
        val round = Json.decodeFromString<ServerInfo>(Json.encodeToString(info))

        assertEquals(8, round.unlocks.multiplayer)
        assertFalse(round.unlocks.allowsMultiplayer(save(level = 7)))
    }

    private fun save(level: Int) = GameSave(username = "kuplu", level = level)
}
