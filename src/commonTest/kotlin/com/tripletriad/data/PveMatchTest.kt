package com.tripletriad.data

import com.tripletriad.model.Card
import com.tripletriad.model.CardColor
import com.tripletriad.model.Deck
import com.tripletriad.model.GameSave
import com.tripletriad.model.HAND_SIZE
import com.tripletriad.model.MatchIntroStep
import com.tripletriad.model.Npc
import com.tripletriad.model.OpenRule
import com.tripletriad.model.OrderRule
import com.tripletriad.model.TypeRule
import com.tripletriad.protocol.ANY_DECK
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [PveMatches] — the join between a profile and an opponent that the AS3 makes half through a
 * screen property and half through the global `Game.PROFILE_DATAS`.
 */
class PveMatchTest {
    /** A block-1 card id — the shipped `ff14` table. Ids are global; these fixtures are not. */
    private fun ff14(number: Int) = Card.idFor(block = 1, number = number)

    private fun card(block: Int, number: Int) = Card(
        id = Card.idFor(block, number),
        nameKey = "STR_TEST_$block-$number",
        name = "Test $block-$number",
        top = 5,
        right = 5,
        bottom = 5,
        left = 5,
        rarity = 1,
    )

    private val catalog = CardCatalog(
        sets = TEST_SETS,
        cards = (1..40).map { card(1, it) } + (1..30).map { card(2, it) },
    )

    private val opponent = Npc(
        id = 1,
        nameKey = "STR_NPC_Test",
        iconId = "test-npc",
        fetishCards = listOf(11, 12, 13).map(::ff14),
        cards = listOf(20, 21, 22, 23).map(::ff14),
    )

    /** A block-2 card id — the shipped `ff8` table. */
    private fun ff8(number: Int) = Card.idFor(block = 2, number = number)

    /**
     * Ids are global, so a fixture has to say which block its cards come from: an id alone no
     * longer implies a table the way an AS3 array index did.
     */
    private fun numbering(block: Int): (Int) -> Int =
        if (block == TestFormats.FF8_BLOCK) ::ff8 else ::ff14

    /** The same opponent, drawn from the other shipped table — see [numbering]. */
    private val ff8Opponent
        get() = opponent.copy(
            fetishCards = listOf(11, 12, 13).map(::ff8),
            cards = listOf(20, 21, 22, 23).map(::ff8),
        )

    /**
     * A profile holding twelve cards of one block.
     *
     * [block] is a *fixture* parameter and not a property of the profile: it chooses which block
     * the twelve cards come from. The format is passed to the call under test separately.
     */
    private fun profile(
        block: Int = TestFormats.FF14_BLOCK,
        cards: Map<Int, Int> = (1..12).associate { numbering(block)(it) to 1 },
        decks: List<Deck> = listOf(Deck("Starter", (1..5).map(numbering(block)))),
    ) = GameSave.new(createdAt = 0L).copy(cards = cards, decks = decks)

    private val seeds = 0 until 40

    @Test
    fun bothSidesGetFiveCardsFromTheProfilesCollection() {
        val match = PveMatches.assemble(profile(), opponent, catalog, TestFormats.ff14, Random(1))
        val hands = match.setup.state.hands

        assertEquals(HAND_SIZE, hands[CardColor.BLUE]?.size)
        assertEquals(HAND_SIZE, hands[CardColor.RED]?.size)
        assertTrue(
            hands.values.flatten().all { it.block == TestFormats.FF14_BLOCK },
            "an ff14_ profile must only ever see ff14_ cards",
        )
    }

    @Test
    fun anFf8ProfileGetsFf8Cards() {
        val match =
            PveMatches.assemble(
                profile(TestFormats.FF8_BLOCK),
                ff8Opponent,
                catalog,
                TestFormats.ff8,
                Random(1),
            )

        assertTrue(
            match.setup.state.hands.values.flatten()
                .all { it.block == TestFormats.FF8_BLOCK },
        )
    }

    // ---- Choosing a deck ---------------------------------------------------

    /** An explicitly chosen deck beats the "first complete one" default. */
    @Test
    fun theDeckPassedInIsTheDeckDealt() {
        val decks = listOf(
            Deck("First", (1..5).map(::ff14)),
            Deck("Second", (6..10).map(::ff14)),
        )
        val save = profile(decks = decks)
        val random = Random(1)
        val plan =
            MatchPlan(
                PveMatches.rulesFor(opponent, TestFormats.ff14, random),
                decks[1].cards,
            )

        val match = PveMatches.assemble(save, opponent, catalog, TestFormats.ff14, random, plan)

        assertEquals(decks[1].cards, match.setup.state.hands[CardColor.BLUE]?.map { it.id })
    }

    /**
     * Resolving the rules separately and passing them in gives the same match as letting
     * [PveMatches.assemble] do it.
     *
     * This is the contract the deck selector depends on: it needs the rules *before* the match is
     * assembled, in order to know whether to ask for a deck at all, and it must not cost a second
     * roulette draw. Asserted against a roulette opponent, where a second draw would show.
     */
    @Test
    fun resolvingTheRulesFirstDoesNotChangeTheMatch() {
        val roulette = opponent.copy(ruleKeys = listOf("RULE_ROULETTE"))
        val save = profile()

        val whole = PveMatches.assemble(save, roulette, catalog, TestFormats.ff14, Random(7))

        val split = Random(7).let { random ->
            val rules = PveMatches.rulesFor(roulette, TestFormats.ff14, random)
            val plan = MatchPlan(rules, PveMatches.playerDeck(save))
            PveMatches.assemble(save, roulette, catalog, TestFormats.ff14, random, plan)
        }

        assertEquals(whole.rules, split.rules, "the roulette must be drawn exactly once")
        assertEquals(whole.setup.state.hands, split.setup.state.hands)
    }

    /** Only complete decks are offered, and the index is the save slot rather than the row. */
    @Test
    fun onlyCompleteAndResolvableDecksArePlayable() {
        val save = profile(
            cards = (1..12).associate { ff14(it) to 1 },
            decks = listOf(
                Deck("Partial", listOf(1, 2).map(::ff14)),
                Deck("Full", (1..5).map(::ff14)),
                // Five ids, but 99 is in neither table, so this one would throw if it were offered.
                Deck("Broken", listOf(1, 2, 3, 4, 99).map(::ff14)),
            ),
        )

        val playable = PveMatches.playableDecks(save, catalog, TestFormats.ff14)

        assertEquals(listOf(1), playable.map { it.index }, "the slot, not the row")
        assertEquals("Full", playable.single().value.name)
    }

    @Test
    fun aProfileWithNoCompleteDeckOffersNothingToChooseFrom() {
        val save = profile(decks = listOf(Deck("Partial", listOf(1, 2).map(::ff14))))

        assertTrue(PveMatches.playableDecks(save, catalog, TestFormats.ff14).isEmpty())
        // …and still has something to play, which is what the fallback is for.
        assertEquals(HAND_SIZE, PveMatches.playerDeck(save).size)
    }

    /** A named slot is played, which is how a refereed match honours a choice made in a lobby. */
    @Test
    fun theDeckNamedIsTheDeckPlayed() {
        val second = (6..10).map(::ff14)
        val save = profile(
            cards = (1..12).associate { ff14(it) to 1 },
            decks = listOf(Deck("First", (1..5).map(::ff14)), Deck("Second", second)),
        )

        assertEquals(second, PveMatches.playerDeck(save, deck = 1))
    }

    /**
     * A slot that has stopped being playable falls back rather than refusing.
     *
     * The case is a host who opened a table naming a deck and then edited a card out of it before
     * anybody joined. Refusing there would fail the *joiner's* tap for something the host did.
     */
    @Test
    fun aDeckThatIsNoLongerCompleteFallsBackToWhatCanBePlayed() {
        val save = profile(
            cards = (1..12).associate { ff14(it) to 1 },
            decks = listOf(Deck("Full", (1..5).map(::ff14)), Deck("Gutted", listOf(ff14(6)))),
        )

        assertEquals(PveMatches.playerDeck(save), PveMatches.playerDeck(save, deck = 1))
        assertEquals(PveMatches.playerDeck(save), PveMatches.playerDeck(save, deck = ANY_DECK))
        assertEquals(PveMatches.playerDeck(save), PveMatches.playerDeck(save, deck = 99))
    }

    /**
     * A deck naming a card the profile no longer holds falls back too — **the card wager's case**.
     *
     * [GameSave.withoutCard] takes the card and leaves the decks alone on purpose, on the reading
     * that an unaffordable deck is refused rather than rewritten. [PveMatches.playableDecks] did
     * refuse it; these two did not, because [Deck.isComplete] counts to five and asks nothing about
     * ownership. So the loser of a card wager went on fielding the card they had just lost, in this
     * match and in every one after it.
     *
     * The named slot is the one that matters: it is what the referee deals a player-versus-player
     * match from, which is the same place the card was lost.
     */
    @Test
    fun aDeckNamingACardNoLongerOwnedFallsBackToWhatCanBePlayed() {
        val lost = ff14(3)
        val save = profile(
            // Twelve cards, then the third is taken by a wager — the deck still names it.
            cards = (1..12).associate { ff14(it) to 1 } - lost,
            decks = listOf(Deck("Full", (6..10).map(::ff14)), Deck("Wagered", (1..5).map(::ff14))),
        )

        assertFalse(lost in PveMatches.playerDeck(save, deck = 1), "the lost card was dealt")
        assertEquals(PveMatches.playerDeck(save), PveMatches.playerDeck(save, deck = 1))
        // And the deck itself is untouched: refused, not silently edited down to four cards.
        assertEquals(HAND_SIZE, save.decks[1].cards.size)
    }

    /** The same for the default lookup: the first complete deck must also be an affordable one. */
    @Test
    fun theDefaultDeckSkipsOneThatCannotBeAfforded() {
        val playable = (6..10).map(::ff14)
        val save = profile(
            cards = (1..12).associate { ff14(it) to 1 } - ff14(3),
            decks = listOf(Deck("Wagered", (1..5).map(::ff14)), Deck("Full", playable)),
        )

        assertEquals(playable, PveMatches.playerDeck(save))
    }

    /** The complete deck is played, not the first five cards owned. */
    @Test
    fun theFirstCompleteDeckIsPlayed() {
        val chosen = (6..10).map(::ff14)
        val decks = listOf(Deck("Partial", listOf(1, 2).map(::ff14)), Deck("Full", chosen))

        val match = PveMatches.assemble(
            profile(decks = decks),
            opponent,
            catalog,
            TestFormats.ff14,
            Random(1),
        )

        assertEquals(chosen, match.setup.state.hands[CardColor.BLUE]?.map { it.id })
    }

    /**
     * A profile whose only deck is half-built still gets a match.
     *
     * The AS3 `DeckSelector` refuses to start with a partial deck and offers no fallback, which
     * leaves such a profile with no way to play at all. See [PveMatches.playerDeck].
     */
    @Test
    fun aProfileWithNoCompleteDeckFallsBackToTheCardsItOwns() {
        val partial = profile(decks = listOf(Deck("Partial", listOf(1, 2).map(::ff14))))

        val hand = PveMatches.assemble(partial, opponent, catalog, TestFormats.ff14, Random(1))
            .setup.state.hands[CardColor.BLUE]
            .orEmpty()

        assertEquals(HAND_SIZE, hand.size)
        assertTrue(hand.all { it.id in partial.cards })
    }

    @Test
    fun theOpponentAlwaysPlaysItsFetishCards() {
        for (seed in seeds) {
            val red = PveMatches.assemble(
                profile(),
                opponent,
                catalog,
                TestFormats.ff14,
                Random(seed),
            )
                .setup.state.hands[CardColor.RED]
                .orEmpty()
                .map { it.id }

            assertTrue(red.containsAll(opponent.fetishCards), "seed $seed dropped a fetish card")
            assertEquals(HAND_SIZE, red.size, "seed $seed")
        }
    }

    // ---- Rules -----------------------------------------------------------

    @Test
    fun theOpponentsDeclaredRulesAreInForce() {
        val strict = opponent.copy(ruleKeys = listOf("RULE_REVERSE", "RULE_ORDER"))

        val match = PveMatches.assemble(profile(), strict, catalog, TestFormats.ff14, Random(1))

        assertTrue(match.rules.reverse)
        assertEquals(OrderRule.ORDER, match.rules.order)
        assertEquals(match.rules, match.setup.state.rules, "the state must play the same rules")
    }

    @Test
    fun anOpponentWithNoRulesPlaysTheBasicGame() {
        val match = PveMatches.assemble(profile(), opponent, catalog, TestFormats.ff14, Random(1))

        assertTrue(match.rules.activeRuleKeys().isEmpty())
    }

    /**
     * `RULE_ROULETTE` adds one to three more rules — and does so **here**, not in [Npc.gameRules].
     * `BaseMatchScreen.as:64-66` augments at screen construction, so an opponent's declared rules
     * are a fixed property of the opponent and what a *match* is played under is not.
     */
    @Test
    fun aRouletteOpponentGetsExtraRules() {
        val gambler = opponent.copy(ruleKeys = listOf("RULE_ROULETTE"))
        val pool = TestFormats.ff14.rules.toSet()

        val extras = seeds.map { seed ->
            PveMatches.assemble(profile(), gambler, catalog, TestFormats.ff14, Random(seed))
                .rules
                .activeRuleKeys()
                .toSet() - "RULE_ROULETTE"
        }

        assertTrue(extras.all { it.isNotEmpty() }, "the roulette must always add at least one rule")
        assertTrue(extras.all { drawn -> drawn.all { it in pool } }, "drew outside the ff14 pool")
        assertTrue(extras.distinct().size > 1, "the draw must not be the same every time")
    }

    @Test
    fun anFf8RouletteOpponentDrawsFromTheFf8Pool() {
        val gambler = ff8Opponent.copy(ruleKeys = listOf("RULE_ROULETTE"))
        val pool = TestFormats.ff8.rules.toSet()

        for (seed in seeds) {
            val drawn = PveMatches
                .assemble(
                    profile(TestFormats.FF8_BLOCK),
                    gambler,
                    catalog,
                    TestFormats.ff8,
                    Random(seed),
                )
                .rules
                .activeRuleKeys()
                .toSet() - "RULE_ROULETTE"

            assertTrue(drawn.all { it in pool }, "seed $seed drew ${drawn - pool}")
        }
    }

    @Test
    fun aNonRouletteOpponentGetsNothingExtra() {
        val plain = opponent.copy(ruleKeys = listOf("RULE_SAME"))

        for (seed in seeds) {
            val keys = PveMatches.assemble(
                profile(),
                plain,
                catalog,
                TestFormats.ff14,
                Random(seed),
            )
                .rules
                .activeRuleKeys()

            assertEquals(listOf("RULE_SAME"), keys, "seed $seed")
        }
    }

    // ---- The pre-match chain reaches the match ---------------------------

    @Test
    fun anElementalOpponentGetsAnElementalBoard() {
        val elemental = ff8Opponent.copy(ruleKeys = listOf("RULE_ELEMENTAL"))

        val match = PveMatches.assemble(
            profile(TestFormats.FF8_BLOCK),
            elemental,
            catalog,
            TestFormats.ff8,
            Random(1),
        )

        assertEquals(TypeRule.ELEMENTAL, match.rules.typeRule)
        assertTrue(match.setup.state.board.elements.any { it != null })
    }

    @Test
    fun anAllOpenOpponentRevealsItsWholeHand() {
        val open = opponent.copy(ruleKeys = listOf("RULE_ALL_OPEN"))

        val match = PveMatches.assemble(profile(), open, catalog, TestFormats.ff14, Random(1))
        val red = match.setup.state.hands[CardColor.RED].orEmpty()

        assertEquals(OpenRule.ALL_OPEN, match.rules.open)
        assertEquals(red.size, match.setup.opponentVisibility.visible(red).size)
    }

    @Test
    fun aDefaultOpponentRevealsNothing() {
        val match = PveMatches.assemble(profile(), opponent, catalog, TestFormats.ff14, Random(1))

        assertTrue(match.setup.opponentVisibility.visiblePositions.isEmpty())
    }

    /** Under Random the deck is ignored and the hand comes from the whole collection. */
    @Test
    fun aRandomOpponentMakesTheHandComeFromTheCollection() {
        val chaotic = opponent.copy(ruleKeys = listOf("RULE_RANDOM"))
        val deck = Deck("Deck", (1..5).map(::ff14))
        val owner = profile(cards = (1..12).associate { ff14(it) to 1 }, decks = listOf(deck))
        val outsideTheDeck = (6..12).map(::ff14).toSet()

        val dealtOutside = seeds.count { seed ->
            PveMatches.assemble(owner, chaotic, catalog, TestFormats.ff14, Random(seed))
                .setup.state.hands[CardColor.BLUE]
                .orEmpty()
                .any { it.id in outsideTheDeck }
        }

        assertTrue(dealtOutside > 0, "a Random hand must be able to leave the deck behind")
    }

    @Test
    fun theIntroAnnouncesWhatTheOpponentImposes() {
        val showy = opponent.copy(ruleKeys = listOf("RULE_ALL_OPEN", "RULE_REVERSE"))

        val intro = PveMatches.assemble(
            profile(),
            showy,
            catalog,
            TestFormats.ff14,
            Random(1),
        ).setup.intro

        assertEquals(
            listOf(
                MatchIntroStep.ALL_OPEN,
                MatchIntroStep.REVERSE,
                MatchIntroStep.COIN_FLIP,
                MatchIntroStep.START,
            ),
            intro,
        )
    }

    @Test
    fun aMatchStartsAtPlacementZeroWithACoinFlip() {
        val match = PveMatches.assemble(profile(), opponent, catalog, TestFormats.ff14, Random(1))

        assertEquals(0, match.setup.state.placement)
        assertEquals(match.setup.coinFlip?.winner, match.setup.state.currentPlayer)
    }

    @Test
    fun bothSidesCanWinTheFlipAcrossSeeds() {
        val first = seeds.map {
            PveMatches.assemble(
                profile(),
                opponent,
                catalog,
                TestFormats.ff14,
                Random(it),
            ).setup.state.currentPlayer
        }

        assertEquals(setOf(CardColor.BLUE, CardColor.RED), first.toSet())
    }

    @Test
    fun aMatchIsReproducibleForAGivenSeed() {
        assertEquals(
            PveMatches.assemble(profile(), opponent, catalog, TestFormats.ff14, Random(3)),
            PveMatches.assemble(profile(), opponent, catalog, TestFormats.ff14, Random(3)),
        )
    }

    // ---- Data faults are loud -------------------------------------------

    /**
     * A card id that names nothing is refused rather than dropped.
     *
     * Both directions are data faults that cannot be reached by playing: `NpcBundleTest` holds
     * every shipped opponent to resolvable ids and a full hand, and [GameSave.sane] keeps a
     * profile's card list clean. A match quietly played with four cards would be worse than a
     * crash.
     */
    @Test
    fun anUnresolvableOpponentCardIsAProgrammingError() {
        // 200 is a legal number in block 1 and names no card in this fixture catalog, which
        // stops at 40 — an unresolvable id now has to be in range to get that far.
        val broken = opponent.copy(
            fetishCards = listOf(1, 2, 3, 4, 200).map(::ff14),
            cards = emptyList(),
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            PveMatches.assemble(profile(), broken, catalog, TestFormats.ff14, Random(1))
        }
        assertTrue("test-npc" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun aProfileWhoseCardsTheFormatDoesNotAdmitIsAProgrammingError() {
        // Was "not in its collection". A profile has no collection now, so the mismatch this
        // guards is between the cards held and the cards the **format** admits — which is the
        // same failure, stated where the rule actually lives.
        val impossible = profile(
            cards = (31..35).associate { ff14(it) to 1 },
            decks = emptyList(),
        )

        assertFailsWith<IllegalArgumentException> {
            PveMatches.assemble(impossible, ff8Opponent, catalog, TestFormats.ff8, Random(1))
        }
    }

    /**
     * `RULE_RANDOM` draws five without replacement from the collection, and a copy is a card the
     * draw can reach — a profile holding three of one card and two others can field a hand that a
     * distinct-card list would have refused to deal. See `GameSave.ownedCardIds`.
     */
    @Test
    fun aRandomHandCanBeDealtFromFewerThanFiveDistinctCards() {
        val chaotic = opponent.copy(ruleKeys = listOf("RULE_RANDOM"))
        val hoarder = profile(
            cards = mapOf(ff14(1) to 3, ff14(2) to 1, ff14(3) to 1),
            decks = emptyList(),
        )

        val hand = PveMatches.assemble(hoarder, chaotic, catalog, TestFormats.ff14, Random(1))
            .setup.state.hands.getValue(CardColor.BLUE)

        assertEquals(HAND_SIZE, hand.size)
        assertEquals(3, hand.count { it.id == ff14(1) }, "all three copies are drawable")
    }
}
