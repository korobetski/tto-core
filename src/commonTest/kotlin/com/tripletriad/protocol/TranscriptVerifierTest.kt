package com.tripletriad.protocol

import com.tripletriad.data.CardCatalog
import com.tripletriad.data.NpcCatalog
import com.tripletriad.data.PveMatches
import com.tripletriad.data.TEST_SETS
import com.tripletriad.data.TestFormats
import com.tripletriad.model.Card
import com.tripletriad.model.CardColor
import com.tripletriad.model.Deck
import com.tripletriad.model.GameSave
import com.tripletriad.model.MatchAi
import com.tripletriad.model.Npc
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * A transcript is checkable, and only a real match passes.
 *
 * ### What these tests are actually about
 *
 * Not "does the function work". The claim under test is the one the whole network design rests on:
 * **a player who controls their own device cannot invent a result.** Each rejection case below is a
 * specific way somebody would try — play a card they do not hold, stop before a losing position,
 * append a move after the board is full, name an opponent that does not exist — and the point is
 * that all of them fail without the server having watched anything.
 *
 * The acceptance case matters just as much and for the opposite reason: an honest offline match
 * must still count, or the design has bought integrity by taking the game away.
 */
class TranscriptVerifierTest {
    /** Fixtures live in block 1; ids are global, so a bare number is not one. */
    private val testBlock = 1

    // ---- The honest case --------------------------------------------------

    @Test
    fun anHonestMatchIsAcceptedAndScoredByTheServer() {
        val transcript = playHonestly(SEED)

        val verdict = TranscriptVerifier.verify(transcript, cards, npcs, TestFormats.catalog)

        val accepted = assertIs<MatchVerdict.Accepted>(verdict, "rejected: $verdict")
        assertEquals(
            TOTAL_CARDS,
            accepted.blue + accepted.red,
            "the two sides must account for all nine cards plus the one in hand",
        )
    }

    /**
     * The property that makes the server's answer worth anything: it is a function of the
     * transcript alone, so two servers — or the same one after a restart — agree.
     */
    @Test
    fun verifyingTheSameTranscriptTwiceGivesTheSameVerdict() {
        val transcript = playHonestly(SEED)

        assertEquals(
            TranscriptVerifier.verify(transcript, cards, npcs, TestFormats.catalog),
            TranscriptVerifier.verify(transcript, cards, npcs, TestFormats.catalog),
        )
    }

    /**
     * The seed is load-bearing, not decoration.
     *
     * Without this the suite would pass just as happily if the verifier ignored the seed and
     * accepted any legal-looking sequence — which would accept a transcript replayed against a
     * friendlier deal than the one that was played.
     */
    @Test
    fun theSameMovesAgainstADifferentSeedDoNotReplay() {
        val honest = playHonestly(SEED)
        val relabelled = honest.copy(seed = SEED + 1)

        assertNotEquals(
            TranscriptVerifier.verify(honest, cards, npcs, TestFormats.catalog),
            TranscriptVerifier.verify(relabelled, cards, npcs, TestFormats.catalog),
            "the deal is different, so the same nine placements cannot reach the same result",
        )
    }

    // ---- The ways somebody would try it on --------------------------------

    @Test
    fun playingACardThatWasNeverInHandIsRejected() {
        val honest = playHonestly(SEED)
        val forged = honest.copy(
            moves = honest.moves.mapIndexed { index, move ->
                if (index == 0) move.copy(cardId = CARD_NOT_IN_ANY_HAND) else move
            },
        )

        assertRejected(RejectionReason.ILLEGAL_MOVE, forged)
    }

    @Test
    fun playingTwiceIntoTheSameCellIsRejected() {
        val honest = playHonestly(SEED)
        val forged = honest.copy(
            moves = honest.moves.mapIndexed { index, move ->
                if (index == 1) move.copy(position = honest.moves[0].position) else move
            },
        )

        assertRejected(RejectionReason.ILLEGAL_MOVE, forged)
    }

    /** Abandoning a match that is going badly must not read as a match. */
    @Test
    fun stoppingBeforeTheBoardIsFullIsRejected() {
        val honest = playHonestly(SEED)

        assertRejected(RejectionReason.TRUNCATED, honest.copy(moves = honest.moves.dropLast(1)))
    }

    @Test
    fun appendingAMoveAfterTheBoardIsFullIsRejected() {
        val honest = playHonestly(SEED)
        val padded = honest.copy(moves = honest.moves + honest.moves.last())

        assertRejected(RejectionReason.TRAILING_MOVES, padded)
    }

    @Test
    fun anOpponentThatDoesNotExistIsRejected() {
        val honest = playHonestly(SEED)

        assertRejected(
            RejectionReason.UNKNOWN_OPPONENT,
            honest.copy(opponentIconId = "not-an-opponent"),
        )
    }

    /**
     * The check a peer-to-peer design could not make at all, and that a server-held profile makes
     * free — see [MatchTranscript.ownedCards] for why today's version of it is still weak.
     */
    @Test
    fun aDeckHoldingCardsTheProfileDoesNotOwnIsRejected() {
        val honest = playHonestly(SEED)

        assertRejected(
            RejectionReason.DECK_NOT_OWNED,
            honest.copy(ownedCards = honest.ownedCards - honest.deck.first()),
        )
    }

    /**
     * A deck the profile owns every card of, and still may not bring.
     *
     * The deck-building caps, put to a transcript. It is the only place they can be put to an
     * *offline* match at all: nothing else in the transcript names a deck slot, so the server has
     * no saved deck to re-check and must ask the question of the five cards as declared. Distinct
     * from [RejectionReason.DECK_NOT_OWNED] because the two accusations are different and only one
     * of them is answered in the deck editor — see the enum's own KDoc.
     */
    @Test
    fun aDeckOverAStarRankCapIsRejectedEvenWhenEveryCardIsOwned() {
        val honest = playHonestly(SEED)
        val aces = (FIRST_FIVE_STAR..FIRST_FIVE_STAR + 1).map { Card.idFor(testBlock, it) }

        assertRejected(
            RejectionReason.DECK_ILLEGAL,
            honest.copy(
                deck = aces + honest.deck.take(3),
                ownedCards = honest.ownedCards + aces.associateWith { 1 },
            ),
        )
    }

    @Test
    fun aTranscriptFromAFormatThisBuildDoesNotReadIsRefusedRatherThanMisread() {
        val honest = playHonestly(SEED)

        assertRejected(
            RejectionReason.UNSUPPORTED_VERSION,
            honest.copy(version = TRANSCRIPT_VERSION + 1),
        )
    }

    // ---- The wire ---------------------------------------------------------

    /**
     * The transcript survives a round trip through JSON.
     *
     * Cheap, and it is the one thing between the client and the server that nothing else covers:
     * every test above verifies an object that never left the process.
     */
    @Test
    fun aTranscriptSurvivesJson() {
        val honest = playHonestly(SEED)

        val restored = json.decodeFromString<MatchTranscript>(json.encodeToString(honest))

        assertEquals(honest, restored)
        assertEquals(
            TranscriptVerifier.verify(honest, cards, npcs, TestFormats.catalog),
            TranscriptVerifier.verify(restored, cards, npcs, TestFormats.catalog),
        )
    }

    @Test
    fun aVerdictSurvivesJson() {
        val verdict = TranscriptVerifier.verify(
            playHonestly(SEED),
            cards,
            npcs,
            TestFormats.catalog,
        )

        assertEquals(verdict, json.decodeFromString<MatchVerdict>(json.encodeToString(verdict)))
    }

    /**
     * A draw survives an encoder that drops nulls.
     *
     * Regression, and a real one: the server encodes with `explicitNulls = false`, so a drawn match
     * put no `winner` on the wire at all and every client failed to decode a legitimate verdict.
     * A draw is not an edge case — it is one of three outcomes — and it was the only one nothing
     * exercised.
     */
    @Test
    fun aDrawnVerdictSurvivesAnEncoderThatOmitsNulls() {
        val terse = Json { explicitNulls = false }
        val draw = MatchVerdict.Accepted(blue = DRAWN_SCORE, red = DRAWN_SCORE, winner = null)

        val encoded = terse.encodeToString<MatchVerdict>(draw)

        assertEquals(draw, json.decodeFromString<MatchVerdict>(encoded))
    }

    // ---- Fixtures ---------------------------------------------------------

    private fun assertRejected(expected: RejectionReason, transcript: MatchTranscript) {
        val verdict = TranscriptVerifier.verify(transcript, cards, npcs, TestFormats.catalog)

        val rejected = assertIs<MatchVerdict.Rejected>(verdict, "accepted: $verdict")
        assertEquals(expected, rejected.reason, rejected.detail)
    }

    /**
     * Plays a whole match honestly and writes down what the player did.
     *
     * This is the client's half of the protocol, in miniature — and it has to obey the invariant
     * documented on [TranscriptVerifier]: **the player's turn may not draw from the match
     * generator.** So the moves here are chosen by taking the first card in hand and the first
     * empty cell, which needs no randomness at all. Using [MatchAi] for the player as well would be
     * the natural-looking shortcut and would consume the stream on blue's turns, making every
     * honest transcript fail to replay.
     */
    private fun playHonestly(
        seed: Int,
        deck: List<Int> = DECK,
        owned: Map<Int, Int> = OWNED,
    ): MatchTranscript {
        val random = Random(seed)
        val profile = GameSave(
            cards = owned,
            decks = listOf(Deck("test", deck)),
        )
        val match = PveMatches.assemble(profile, opponent, cards, TestFormats.ff14, random)

        val ai = MatchAi()
        var state = match.setup.state
        val moves = mutableListOf<TranscriptMove>()

        while (!state.isFinished) {
            state = if (state.currentPlayer == CardColor.BLUE) {
                val card = state.currentHand.first()
                val position = state.playablePositions().first()
                moves += TranscriptMove(card.id, position)
                state.play(card, position)
            } else {
                ai.play(state, random)
            }
        }

        return MatchTranscript(
            seed = seed,
            formatId = TestFormats.ff14.id,
            opponentIconId = opponent.iconId,
            deck = deck,
            ownedCards = owned,
            moves = moves,
        )
    }

    private fun card(id: Int) = Card(
        // Fixtures number their cards from 1; ids are global.
        id = Card.idFor(testBlock, id),
        nameKey = "STR_TEST_$id",
        name = "Test $id",
        top = (id % 9) + 1,
        right = ((id + 3) % 9) + 1,
        bottom = ((id + 5) % 9) + 1,
        left = ((id + 7) % 9) + 1,
        // Rank 1 but for the last two, which exist so a fixture can name two five-stars in one
        // deck — the only thing in this file that cares about a rank at all. See `DeckLimits`.
        rarity = if (id >= FIRST_FIVE_STAR) 5 else 1,
    )

    private val cards = CardCatalog(
        sets = TEST_SETS,
        cards = (1..40).map { card(it) },
    )

    private val opponent = Npc(
        id = 1,
        nameKey = "STR_NPC_Test",
        iconId = "test-npc",
        fetishCards = listOf(11, 12, 13).map { Card.idFor(block = 1, number = it) },
        cards = listOf(20, 21, 22, 23).map { Card.idFor(block = 1, number = it) },
    )

    // The opponent declares the format the fixture's transcripts are played in — the verifier
    // resolves it from the transcript's collection and looks the opponent up within it.
    private val npcs = NpcCatalog(listOf(opponent.copy(formats = listOf(TestFormats.ff14.id))))

    private val json = Json

    private companion object {
        const val SEED = 20260807

        /** The first of the two five-stars in this file's card table. See `card`. */
        const val FIRST_FIVE_STAR = 39

        // Fixtures number their cards from 1; the ids they resolve to are global.
        val DECK = (1..5).map { Card.idFor(block = 1, number = it) }
        val OWNED = (1..12).associate { Card.idFor(block = 1, number = it) to 1 }

        /** Outside every hand: the deck is 1..5 and the opponent draws from 11..13 and 20..23. */
        val CARD_NOT_IN_ANY_HAND = Card.idFor(block = 1, number = 39)

        /** Nine cells, plus the one card left in the winner's hand. */
        const val TOTAL_CARDS = 10

        /** Five apiece. */
        const val DRAWN_SCORE = 5
    }

    /**
     * Membership was not enough once a card could be owned twice: a deck naming it twice needs two
     * copies, and checking only that the id appears somewhere would leave the rule to the client.
     */
    @Test
    fun aDeckUsingMoreCopiesThanAreOwnedIsRejected() {
        val honest = playHonestly(SEED)
        val doubled = honest.deck.first()

        assertRejected(
            RejectionReason.DECK_NOT_OWNED,
            honest.copy(deck = listOf(doubled, doubled) + honest.deck.drop(2)),
        )
    }

    /** And is accepted once the second copy is actually held. */
    @Test
    fun aDeckUsingTwoCopiesIsAcceptedWhenTwoAreOwned() {
        val doubled = DECK.first()
        val transcript = playHonestly(
            seed = SEED + 1,
            deck = listOf(doubled, doubled) + DECK.drop(2),
            owned = OWNED + (doubled to 2),
        )
        val profile = GameSave(
            cards = transcript.ownedCards,
            decks = listOf(Deck("test", transcript.deck)),
        )

        assertIs<MatchVerdict.Accepted>(
            TranscriptVerifier.verify(transcript, cards, npcs, TestFormats.catalog, profile),
        )
    }
}
