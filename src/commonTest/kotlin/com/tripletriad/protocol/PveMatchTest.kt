package com.tripletriad.protocol

import com.tripletriad.model.Capture
import com.tripletriad.model.CaptureKind
import com.tripletriad.model.Card
import com.tripletriad.model.CardColor
import com.tripletriad.model.CardType
import com.tripletriad.model.HAND_SIZE
import com.tripletriad.model.HandVisibility
import com.tripletriad.model.MatchResult
import com.tripletriad.model.MatchState
import com.tripletriad.model.MatchView
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wire format of a refereed match against an opponent.
 *
 * **The test that justifies the file is [theOpponentsHiddenCardsAreNotOnTheWireAtAll]**, for the
 * reason [PvpMatchTest] gives about its own twin: "the field is null" and "the number is nowhere in
 * the payload" are different guarantees, and only the second survives a client that reads the raw
 * body rather than the parsed object.
 *
 * It matters more here than it does there. Against another person the client never held the
 * opponent's cards, so the guarantee was new. Against a program it held all five, and every move
 * they were going to make — this is the assertion that says that stopped being true.
 */
class PveMatchTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun card(number: Int, power: Int = 5) = Card(
        id = Card.idFor(block = 1, number = number),
        nameKey = "STR_TEST_$number",
        name = "Test $number",
        top = power,
        right = power,
        bottom = power,
        left = power,
        rarity = 1,
    )

    private fun hand(from: Int, power: Int) =
        (from until from + HAND_SIZE).map { card(it, power) }

    private val state = MatchState.start(
        blueHand = hand(from = 1, power = 8),
        redHand = hand(from = 11, power = 2),
        first = CardColor.BLUE,
    )

    /** Every card either side holds — what a real client resolves ids against. */
    private val catalogue: Map<Int, Card> = state.hands.values.flatten().associateBy { it.id }

    private fun wire(
        state: MatchState = this.state,
        visibility: HandVisibility = HandVisibility.HIDDEN,
        plays: List<Placement> = emptyList(),
        outcome: PveOutcome? = null,
        status: PveMatchStatus = PveMatchStatus.PLAYING,
    ): PveMatchView = PveMatchView.of(
        view = MatchView.of(state, CardColor.BLUE, visibility),
        matchId = MATCH_ID,
        opponentIconId = OPPONENT,
        formatId = FORMAT,
        status = status,
        outcome = outcome,
        plays = plays,
    )

    // ---- What may leave the server ----------------------------------------

    /**
     * **The opponent's hidden cards are not in the payload.**
     *
     * Asserted against the encoded text, and against the *ids* rather than the names: a client that
     * wanted to cheat would read the body, not the model, and an id is what it would look for.
     */
    @Test
    fun theOpponentsHiddenCardsAreNotOnTheWireAtAll() {
        val body = json.encodeToString(PveMatchView.serializer(), wire())

        for (card in state.hands.getValue(CardColor.RED)) {
            assertFalse(
                body.contains(card.id.toString()),
                "card ${card.id} is in the payload; the opponent's hand travelled",
            )
        }
        assertTrue(
            body.contains(state.hands.getValue(CardColor.BLUE).first().id.toString()),
            "the fixture proves nothing: the player's own hand is missing too",
        )
    }

    /** The count is public even when the cards are not — a player can see how many are left. */
    @Test
    fun theOpponentsSlotsTravelAsNullsSoTheCountIsStillTold() {
        val sent = wire()

        assertEquals(HAND_SIZE, sent.opponentHand.size)
        assertTrue(sent.opponentHand.all { it == null })
    }

    /**
     * Three Open reveals slots, not a shorter hand.
     *
     * The positions matter: `HandVisibility` is indexed, so filtering the list would turn "five
     * cards, three of them face up" into "three cards" and move the two that are hidden.
     */
    @Test
    fun anOpenRuleRevealsExactlyTheSlotsItNames() {
        val open = HandVisibility(setOf(0, 2))
        val sent = wire(visibility = open)
        val red = state.hands.getValue(CardColor.RED)

        assertEquals(HAND_SIZE, sent.opponentHand.size, "the hidden slots are still there")
        assertEquals(red[0].id, sent.opponentHand[0])
        assertNull(sent.opponentHand[1])
        assertEquals(red[2].id, sent.opponentHand[2])
        assertNull(sent.opponentHand[3])
    }

    // ---- Reading it back --------------------------------------------------

    /** The round trip: what the referee projected is what the screen gets back. */
    @Test
    fun aViewSurvivesTheWireAndComesBackTheSame() {
        val original = MatchView.of(state, CardColor.BLUE, HandVisibility.HIDDEN)
        val sent = json.encodeToString(PveMatchView.serializer(), wire())

        val back = assertNotNull(
            json.decodeFromString(PveMatchView.serializer(), sent).toMatchView(catalogue),
        )

        assertEquals(original.rules, back.rules)
        assertEquals(original.board, back.board)
        assertEquals(original.placement, back.placement)
        assertEquals(original.order, back.order)
        assertContentEquals(original.ownHand.map { it.id }, back.ownHand.map { it.id })
        assertContentEquals(original.opponentHand, back.opponentHand)
        assertContentEquals(original.playableHandIndices, back.playableHandIndices)
    }

    /**
     * A card the catalogue does not know refuses the whole view rather than leaving a hole in it.
     *
     * A board with a missing cell is a match the player cannot reason about; a client whose
     * catalogue disagrees with the server's has a version problem to report, not a frame to render.
     */
    @Test
    fun anUnknownCardRefusesTheViewInsteadOfDroppingIt() {
        val sent = wire()

        assertNull(sent.toMatchView(catalogue - sent.hand.first()))
        assertNull(sent.toMatchView(emptyMap()))
        assertNotNull(sent.toMatchView(catalogue), "the fixture must otherwise resolve")
    }

    /**
     * Hands arrive as integers and come back **stamped** with an owner.
     *
     * A catalogue card carries `owner = BLUE` as a default rather than as a fact, and `CardFace`
     * fills its colour from exactly this field — so an unstamped red hand would render blue.
     */
    @Test
    fun bothHandsComeBackStampedWithTheirOwner() {
        val everything = HandVisibility(state.hands.getValue(CardColor.RED).indices.toSet())
        val back = assertNotNull(wire(visibility = everything).toMatchView(catalogue))

        assertTrue(back.ownHand.all { it.owner == CardColor.BLUE })
        assertTrue(back.opponentHand.filterNotNull().all { it.owner == CardColor.RED })
    }

    // ---- The exchange -----------------------------------------------------

    /**
     * One response carries **both** placements, and the model type keeps the last of them.
     *
     * `MatchView` models one placement, so `lastPlay` is the one that produced the board it holds.
     * A caller that wants to animate the exchange reads [PveMatchView.plays] — see the client's
     * `MatchView.after`, which is why the list is on the wire at all.
     */
    @Test
    fun aResponseCarriesBothPlacementsAndLastPlayIsTheSecond() {
        val mine = state.hands.getValue(CardColor.BLUE).first()
        val theirs = state.hands.getValue(CardColor.RED).first()
        val played = state.play(mine, position = 0)
        val answered = played.play(theirs, position = 1)

        val sent = wire(
            state = answered,
            plays = listOf(
                Placement(CardColor.BLUE, mine.id, position = 0),
                Placement(
                    player = CardColor.RED,
                    cardId = theirs.id,
                    position = 1,
                    captures = listOf(Capture(0, CaptureKind.BASIC, wave = 0)),
                ),
            ),
        )
        val back = assertNotNull(sent.toMatchView(catalogue))

        assertEquals(2, sent.plays.size, "the player's move and the reply, in that order")
        assertEquals(CardColor.RED, assertNotNull(back.lastPlay).player)
        assertEquals(1, back.lastPlay?.position)
        assertEquals(theirs.id, back.lastPlay?.card?.id)
        assertEquals(CardColor.RED, back.lastPlay?.card?.owner, "the played card is stamped too")
    }

    /** A plain read announces nothing: resuming is not a story to replay at somebody. */
    @Test
    fun aPlainReadCarriesNoPlacementsAndSoNoLastPlay() {
        val played = state.play(state.hands.getValue(CardColor.BLUE).first(), position = 4)
        val sent = wire(state = played)

        assertTrue(sent.plays.isEmpty())
        assertNull(assertNotNull(sent.toMatchView(catalogue)).lastPlay)
        assertNotNull(sent.cells[4], "the board still says what happened")
    }

    // ---- Settlement -------------------------------------------------------

    /**
     * The payout travels with the outcome, and so does the credited profile.
     *
     * One write, on the server, and the profile that comes back **is** the answer — a client does
     * not add anything up. Two copies of a profile and a window in which they disagree is what an
     * item that never reaches the bag looks like from the inside.
     */
    @Test
    fun aSettledMatchCarriesItsPayoutAndTheProfileItWrote() {
        val reward = RewardSummary(result = MatchResult.WIN, mgp = 120, xp = 30)
        val sent = wire(
            status = PveMatchStatus.FINISHED,
            outcome = PveOutcome(result = MatchResult.WIN, blue = 6, red = 4, reward = reward),
        )

        val back = json.decodeFromString(
            PveMatchView.serializer(),
            json.encodeToString(PveMatchView.serializer(), sent),
        )

        assertEquals(PveMatchStatus.FINISHED, back.status)
        assertEquals(MatchResult.WIN, back.outcome?.result)
        assertEquals(120, back.outcome?.reward?.mgp)
    }

    /** Elements and the Ascension tally are the referee's arithmetic, not the client's. */
    @Test
    fun theElementsAndTheTallyTravelRatherThanBeingRecounted() {
        val elemental = MatchState.start(
            blueHand = hand(from = 1, power = 8),
            redHand = hand(from = 11, power = 2),
            first = CardColor.BLUE,
            elements = List(9) { if (it == 0) CardType.FIRE else null },
        )
        val sent = wire(state = elemental)
        val back = assertNotNull(sent.toMatchView(catalogue))

        assertEquals(CardType.FIRE, sent.elements[0])
        assertEquals(CardType.FIRE, back.board.elements[0])
        assertEquals(elemental.tally.counts, sent.ascension)
    }

    /** A request names a deck **slot**, so a client cannot name a card it does not own. */
    @Test
    fun aRequestCarriesADeckSlotAndDefaultsToNoChoice() {
        val asked = PveMatchRequest(opponentIconId = OPPONENT, formatId = FORMAT)

        assertEquals(ANY_DECK, asked.deck)
        assertEquals(
            asked,
            json.decodeFromString(
                PveMatchRequest.serializer(),
                json.encodeToString(PveMatchRequest.serializer(), asked),
            ),
        )
    }

    /** A refusal is a code a client acts on, so the wording beside it is free to change. */
    @Test
    fun aRefusalTravelsAsACodeAndASentence() {
        val refused = PveFailure(PveRefusal.NOT_YOUR_TURN, "the board is waiting on the opponent")

        val back = json.decodeFromString(
            PveFailure.serializer(),
            json.encodeToString(PveFailure.serializer(), refused),
        )

        assertEquals(PveRefusal.NOT_YOUR_TURN, back.code)
        assertEquals(refused.detail, back.detail)
    }

    /**
     * A rematch is announced, because a fresh board is otherwise indistinguishable from the same
     * match starting over — both read `placement = 0` on an empty grid.
     */
    @Test
    fun aRematchIsCountedSoAFreshBoardCanBeToldFromTheSameOne() {
        val sent = PveMatchView.of(
            view = MatchView.of(state, CardColor.BLUE, HandVisibility.HIDDEN),
            matchId = MATCH_ID,
            opponentIconId = OPPONENT,
            formatId = FORMAT,
            rematch = 1,
        )

        assertEquals(1, sent.rematch)
        assertEquals(0, sent.placement)
    }

    private companion object {
        const val MATCH_ID = "a-match"
        const val OPPONENT = "an-opponent"
        const val FORMAT = "test"
    }
}
