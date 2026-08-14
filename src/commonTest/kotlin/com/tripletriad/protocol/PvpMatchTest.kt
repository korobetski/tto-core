package com.tripletriad.protocol

import com.tripletriad.model.Capture
import com.tripletriad.model.Card
import com.tripletriad.model.CardColor
import com.tripletriad.model.GameRules
import com.tripletriad.model.HAND_SIZE
import com.tripletriad.model.HandVisibility
import com.tripletriad.model.MatchState
import com.tripletriad.model.MatchView
import com.tripletriad.model.OpenRule
import com.tripletriad.model.TradeRule
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The PvP wire format.
 *
 * **The test that justifies the file is [theOpponentsHiddenCardsAreNotOnTheWireAtAll].** Everything
 * else here is a round trip; that one is the security claim the whole design rests on, and it is
 * asserted against the encoded JSON text rather than against the object — because "the field is
 * null" and "the number is nowhere in the payload" are different guarantees, and only the second
 * one survives a client that reads the raw body.
 */
class PvpMatchTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun card(id: Int, power: Int = 5) = Card(
        id = Card.idFor(block = 1, number = id),
        nameKey = "STR_TEST_$id",
        name = "Test $id",
        top = power,
        right = power,
        bottom = power,
        left = power,
        rarity = 1,
    )

    private fun hand(from: Int, power: Int = 5) =
        (from until from + HAND_SIZE).map { card(it, power) }

    private val state = MatchState.start(
        blueHand = hand(from = 1, power = 8),
        redHand = hand(from = 11, power = 2),
        first = CardColor.BLUE,
    )

    /** Every card either side holds, which is what a real client resolves ids against. */
    private val catalogue: Map<Int, Card> =
        state.hands.values.flatten().associateBy { it.id }

    private fun wire(
        state: MatchState = this.state,
        side: CardColor = CardColor.BLUE,
        visibility: HandVisibility = HandVisibility.HIDDEN,
    ) = PvpMatchView.of(
        view = MatchView.of(state, side, visibility),
        matchId = "m-1",
        opponentName = "Kuplu",
        formatId = "ff14-standard",
    )

    /**
     * A hidden card's id appears nowhere in the encoded payload.
     *
     * Not `assertNull(view.opponentHand[0])` — that would pass just as well if the id were also
     * carried in some other field "for convenience". The claim is about the bytes.
     */
    @Test
    fun theOpponentsHiddenCardsAreNotOnTheWireAtAll() {
        val encoded = json.encodeToString(PvpMatchView.serializer(), wire())

        for (hidden in state.hands.getValue(CardColor.RED)) {
            assertFalse(
                "${hidden.id}" in encoded,
                "card ${hidden.id} leaked into the payload: $encoded",
            )
        }
        // And the payload is not empty of ids in general, or the assertion above would be vacuous.
        for (own in state.hands.getValue(CardColor.BLUE)) {
            assertTrue("${own.id}" in encoded, "own card ${own.id} is missing")
        }
    }

    /** Three Open puts three ids on the wire and withholds two, in their own slots. */
    @Test
    fun anOpenRuleRevealsExactlyTheSlotsItNames() {
        val visible = setOf(0, 2, 4)
        val red = state.hands.getValue(CardColor.RED)

        val view = wire(visibility = HandVisibility(visible))

        assertEquals(HAND_SIZE, view.opponentHand.size)
        for (index in view.opponentHand.indices) {
            if (index in visible) {
                assertEquals(red[index].id, view.opponentHand[index], "slot $index")
            } else {
                assertNull(view.opponentHand[index], "slot $index leaked")
            }
        }
    }

    /** Encoded and decoded, a view is the same view. */
    @Test
    fun aViewSurvivesTheRoundTrip() {
        val sent = wire(visibility = HandVisibility(setOf(1, 3)))

        val received = json.decodeFromString(
            PvpMatchView.serializer(),
            json.encodeToString(PvpMatchView.serializer(), sent),
        )

        assertEquals(sent, received)
    }

    /**
     * And it rebuilds into the model the screen renders — the same one the server projected.
     *
     * The two directions are asserted against each other rather than each against a fixture,
     * because what has to hold is that they agree, not that either matches something written by
     * hand in this file.
     */
    @Test
    fun theProjectionAndTheReconstructionAgree() {
        val original = MatchView.of(state, CardColor.BLUE, HandVisibility(setOf(0, 1)))

        val rebuilt = PvpMatchView
            .of(original, "m-1", "Kuplu", "ff14-standard")
            .toMatchView(catalogue)

        assertNotNull(rebuilt)
        assertEquals(original.side, rebuilt.side)
        assertContentEquals(original.ownHand, rebuilt.ownHand)
        assertContentEquals(original.opponentHand, rebuilt.opponentHand)
        assertEquals(original.board, rebuilt.board)
        assertEquals(original.score, rebuilt.score)
        assertEquals(original.playableHandIndices, rebuilt.playableHandIndices)
        assertEquals(original.isMyTurn, rebuilt.isMyTurn)
    }

    /** A played card is on the board for both sides, with its new owner. */
    @Test
    fun theBoardCrossesTheWireWithOwnership() {
        val played = state.let { it.play(it.currentHand.first(), position = 4) }
        val blue = state.hands.getValue(CardColor.BLUE).first()

        val view = wire(played, CardColor.RED)

        assertEquals(PvpCell(blue.id, CardColor.BLUE), view.cells[4])
        assertEquals(8, view.cells.count { it == null })
    }

    /**
     * An id the catalogue does not know refuses the whole view rather than dropping a card.
     *
     * A board with a hole in it is a match a player cannot reason about. Refusing is what turns a
     * catalogue mismatch into something reportable instead of a strange-looking game.
     */
    @Test
    fun anUnknownCardRefusesTheView() {
        val view = wire().copy(hand = listOf(999_999))

        assertNull(view.toMatchView(catalogue))
    }

    /**
     * Every stake survives the wire — both halves, over all four trade rules and none.
     *
     * Swept rather than sampled because [TradeRule] is what the settlement `when`s exhaust: a
     * member added later that nobody encoded would be caught here, rather than at the moment
     * somebody lost a card under it.
     */
    @Test
    fun everyStakeRoundTrips() {
        val stakes = TradeRule.entries.flatMap { trade ->
            listOf(PvpStake(mgp = 0, trade = trade), PvpStake(mgp = WAGER, trade = trade))
        }

        for (stake in stakes) {
            val sent = wire().copy(stake = stake)

            val received = json.decodeFromString(
                PvpMatchView.serializer(),
                json.encodeToString(PvpMatchView.serializer(), sent),
            )

            assertEquals(stake, received.stake)
        }
    }

    /** The stake that risks nothing says so, and is what an unstated wager means. */
    @Test
    fun theFreeStakeIsTheDefault() {
        assertTrue(PvpStake.None.isFree)
        assertEquals(PvpStake.None, wire().stake)
        assertFalse(PvpStake(mgp = WAGER).isFree)
        assertFalse(PvpStake(trade = TradeRule.ONE).isFree)
    }

    /**
     * A hand comes back in its own side's colour, not the catalogue's.
     *
     * `Card.owner` defaults to blue on a catalogue card, so a red player's own five would arrive
     * stamped as the opponent's — and `CardFace` fills from exactly that field. The catalogue here
     * is deliberately built at the default so the stamping is what is under test rather than an
     * accident of the fixture.
     */
    @Test
    fun aHandIsStampedWithTheSideThatHoldsIt() {
        val defaults = catalogue.mapValues { (_, card) -> card.copy(owner = CardColor.BLUE) }

        val view = wire(side = CardColor.RED, visibility = HandVisibility(ALL_SLOTS))
            .toMatchView(defaults)

        assertNotNull(view)
        assertTrue(view.ownHand.all { it.owner == CardColor.RED }, "red's own hand came back blue")
        assertTrue(
            view.opponentHand.filterNotNull().all { it.owner == CardColor.BLUE },
            "the opponent's revealed cards were not their own colour",
        )
    }

    /** A live match carries no pick list: there is nothing to claim until one has ended. */
    @Test
    fun aLiveViewCarriesNoPickList() {
        val view = wire()

        assertEquals(PvpMatchStatus.PLAYING, view.status)
        assertNull(view.outcome)
    }

    /** The waiting side is sent no playable slots, so a client cannot offer a move out of turn. */
    @Test
    fun theWaitingSideIsSentNothingToPlay() {
        val blue = wire(side = CardColor.BLUE)
        val red = wire(side = CardColor.RED)

        assertEquals(HAND_SIZE, blue.playable.size)
        assertTrue(red.playable.isEmpty())
    }

    /** All Open is the one case where the opponent's whole hand is legitimately on the wire. */
    @Test
    fun allOpenSendsTheWholeHandOnPurpose() {
        val red = state.hands.getValue(CardColor.RED)
        val open = state.copy(rules = GameRules(open = OpenRule.ALL_OPEN))

        val view = wire(open, visibility = HandVisibility(red.indices.toSet()))

        assertContentEquals(red.map { it.id }, view.opponentHand)
    }

    /**
     * A capture survives the round trip with its kind and its wave — the point of sending it.
     *
     * The board alone would say the card at that position changed hands. Only [Capture.kind]
     * says it was a Combo, and a Combo is a caption the player is owed.
     */
    @Test
    fun whatTheLastMoveFlippedTravelsWithIt() {
        val played = state.hands.getValue(CardColor.BLUE).first()
        val after = state.play(played, position = 4)
        val rebuilt = wire(after).toMatchView(catalogue)

        val original = assertNotNull(after.lastPlay)
        val arrived = assertNotNull(rebuilt?.lastPlay)
        assertEquals(original.player, arrived.player)
        assertEquals(original.card.id, arrived.card.id)
        assertEquals(original.position, arrived.position)
        assertEquals(original.captures, arrived.captures)
        assertEquals(original.handIndex, arrived.handIndex)
    }

    /** A board nothing has been played on says so, rather than inventing one to announce. */
    @Test
    fun anUntouchedBoardHasNoLastPlay() {
        assertNull(wire().lastPlay)
        assertNull(wire().toMatchView(catalogue)?.lastPlay)
    }

    /**
     * The placed card arrives stamped with whoever played it, not with the catalogue's default.
     *
     * The same claim the hands make, for the same reason: `CardFace` fills from `Card.owner`, and a
     * catalogue card carries BLUE whether or not blue played it.
     */
    @Test
    fun theCardJustPlayedIsStampedWithWhoPlayedIt() {
        val opening = state.play(state.hands.getValue(CardColor.BLUE).first(), position = 0)
        val reply = opening.play(opening.hands.getValue(CardColor.RED).first(), position = 8)

        // Read as blue, deliberately: the last play is the same fact for both sides, and the card
        // it names belongs to whoever played it rather than to whoever is looking.
        val arrived = assertNotNull(wire(reply).toMatchView(catalogue)?.lastPlay)
        assertEquals(CardColor.RED, arrived.player)
        assertEquals(CardColor.RED, arrived.card.owner)
    }

    /** A view whose last play names an unknown card is refused whole, as an unknown cell is. */
    @Test
    fun aLastPlayNamingAnUnknownCardIsRefused() {
        val after = state.play(state.hands.getValue(CardColor.BLUE).first(), position = 4)
        val played = assertNotNull(after.lastPlay).card.id

        assertNull(wire(after).toMatchView(catalogue - played))
    }

    /** Nothing chosen is not slot zero: the two are different requests and must encode apart. */
    @Test
    fun anUnchosenDeckIsNotTheFirstDeck() {
        assertEquals(ANY_DECK, PvpTableRequest(formatId = "ff14-standard").deck)
        assertEquals(ANY_DECK, PvpJoinRequest().deck)
        assertTrue(ANY_DECK !in 0..4)
    }

    /** Joining says only which deck, and may say nothing at all — an older client says nothing. */
    @Test
    fun joiningWithNoDeckDecodesFromAnEmptyObject() {
        assertEquals(PvpJoinRequest(), json.decodeFromString(PvpJoinRequest.serializer(), "{}"))
        assertEquals(2, json.decodeFromString(PvpJoinRequest.serializer(), """{"deck":2}""").deck)
    }

    private companion object {
        /** A wager big enough to tell apart from the default of nothing. */
        const val WAGER = 50

        /** Every slot revealed, which is what All Open produces. */
        val ALL_SLOTS: Set<Int> = (0 until HAND_SIZE).toSet()
    }
}
