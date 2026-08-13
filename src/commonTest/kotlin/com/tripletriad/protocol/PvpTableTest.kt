package com.tripletriad.protocol

import com.tripletriad.model.GameRules
import com.tripletriad.model.TradeRule
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lobby's wire format: a table, the request that opens one, and why one is refused.
 *
 * ### What is worth asserting about a set of data classes
 *
 * Not that `kotlinx.serialization` works. Two things that are decisions rather than plumbing:
 *
 * **Every [PvpRefusal] survives the wire.** The client switches on this enum to choose a sentence,
 * and an unknown member is not a value it can ignore — it is a parse failure at the moment a player
 * is being told why something did not work. A member added later and never encoded would be found
 * here rather than by somebody staring at a screen that did nothing.
 *
 * **[PvpTable.roulette] is not [GameRules.roulette].** The two are one word apart and mean opposite
 * things: the first is a request for a draw, the second is the record of one having happened. The
 * round trip below keeps them distinguishable, which is the whole point of their being separate
 * fields.
 */
class PvpTableTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun table() = PvpTable(
        id = "t-1",
        hostName = "Kuplu",
        formatId = "free-play",
        rules = GameRules(same = true, plus = true),
        roulette = true,
        stake = PvpStake(mgp = WAGER, trade = TradeRule.DIFF),
        openedAt = OPENED,
        expiresAt = OPENED + LIFETIME,
    )

    /** A table crosses the wire with its terms intact — the reason a lobby beats a queue. */
    @Test
    fun aTableSurvivesTheRoundTrip() {
        val sent = table()

        val received = json.decodeFromString(
            PvpTable.serializer(),
            json.encodeToString(PvpTable.serializer(), sent),
        )

        assertEquals(sent, received)
    }

    /**
     * The host's pending draw and the engine's record of one are different fields.
     *
     * A table asks for a roulette; the rules it carries have not been through one yet. Collapsing
     * them would credit the Wheel of Fortune achievements for a match that never drew — see
     * `Format.choosableRuleKeys`.
     */
    @Test
    fun theRequestedDrawIsNotTheRecordedOne() {
        val sent = table()

        assertTrue(sent.roulette, "the table did not ask for a draw")
        assertTrue(!sent.rules.roulette, "the rules claim a draw that has not happened")
    }

    /** An unopened table names no match, which is how a host's client knows to keep waiting. */
    @Test
    fun anOpenTableNamesNoMatch() {
        assertNull(table().matchId)
    }

    /** A request round trips, defaults and all — an omitted field is "no rules, no wager". */
    @Test
    fun aRequestSurvivesTheRoundTrip() {
        val bare = json.decodeFromString(
            PvpTableRequest.serializer(),
            """{"formatId":"free-play"}""",
        )

        assertEquals(GameRules(), bare.rules)
        assertEquals(PvpStake.None, bare.stake)
        assertTrue(!bare.roulette)
    }

    /** A claim is a list, because Diff takes as many as the margin. */
    @Test
    fun aClaimSurvivesTheRoundTrip() {
        val sent = PvpClaim(listOf(257, 258))

        val received = json.decodeFromString(
            PvpClaim.serializer(),
            json.encodeToString(PvpClaim.serializer(), sent),
        )

        assertEquals(sent, received)
    }

    /**
     * Every refusal code survives the wire.
     *
     * Swept rather than sampled: the client has a `when` over this enum, so a member nobody encoded
     * is a parse failure at exactly the moment a player is owed an explanation.
     */
    @Test
    fun everyRefusalCodeRoundTrips() {
        for (code in PvpRefusal.entries) {
            val received = json.decodeFromString(
                PvpRefusal.serializer(),
                json.encodeToString(PvpRefusal.serializer(), code),
            )

            assertEquals(code, received, "$code did not survive")
        }
    }

    private companion object {
        const val WAGER = 50
        const val OPENED = 1_767_268_800_000L
        const val LIFETIME = 300_000L
    }
}
