package com.tripletriad.model

import com.tripletriad.data.TestFormats
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [MatchPreparation], [HandVisibility] and [CoinFlip] — the pre-match rule chain from
 * [game-rules.md](../../../../../../../docs/analysis/game-rules.md) § 13.
 */
class MatchSetupTest {
    /** Fixtures live in block 1; ids are global, so a bare number is not one. */
    private val testBlock = 1
    private fun card(id: Int) = Card(
        // Fixtures number their cards from 1; ids are global.
        id = Card.idFor(testBlock, id),
        nameKey = "STR_TEST_$id",
        name = "Test $id",
        top = 5,
        right = 5,
        bottom = 5,
        left = 5,
        rarity = 1,
    )

    private fun hand(vararg ids: Int) = ids.map(::card)

    /** The same fixture at a capped rank — [DeckLimits] reads `rarity` and nothing else. */
    private fun card(id: Int, rarity: Int) = card(id).copy(rarity = rarity)

    private val blueDeck = hand(1, 2, 3, 4, 5)
    private val redDeck = hand(6, 7, 8, 9, 10)
    private val collection = (1..30).map(::card)

    private val seeds = 0 until 60

    // ---- Random hand ------------------------------------------------------

    @Test
    fun aRandomHandHoldsFiveDistinctCardsFromTheCollection() {
        for (seed in seeds) {
            val drawn = MatchPreparation.randomHand(collection, Random(seed))

            assertEquals(HAND_SIZE, drawn.size, "seed $seed")
            assertEquals(HAND_SIZE, drawn.distinctBy { it.id }.size, "seed $seed dealt a duplicate")
            assertTrue(drawn.all { it in collection }, "seed $seed dealt a card nobody owns")
        }
    }

    /**
     * The AS3 loop pushes the last remaining card repeatedly once the pool is down to one
     * (`BaseMatchScreen.as:128-131`), so a four-card collection deals a hand with a duplicate.
     * Refused here rather than reproduced — see [MatchPreparation.randomHand].
     */
    @Test
    fun aCollectionSmallerThanAHandIsRefusedRatherThanPaddedWithDuplicates() {
        val tooFew = hand(1, 2, 3, 4)

        val failure = assertFailsWith<IllegalArgumentException> {
            MatchPreparation.randomHand(tooFew, Random(1))
        }
        assertTrue("4" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun aCollectionOfExactlyFiveDealsAllOfIt() {
        val drawn = MatchPreparation.randomHand(blueDeck, Random(3))

        assertEquals(blueDeck.map { it.id }.toSet(), drawn.map { it.id }.toSet())
    }

    /**
     * The original leaves a five-card collection in its own order (`:126`), which under
     * [OrderRule.ORDER] decides which card must be played. Shuffling it is the deliberate change.
     */
    @Test
    fun aFiveCardCollectionIsStillShuffled() {
        val orders = seeds.map {
            MatchPreparation.randomHand(
                blueDeck,
                Random(it),
            ).map { c -> c.id }
        }

        assertTrue(orders.distinct().size > 1, "the draw must be able to reorder five cards")
    }

    @Test
    fun aRandomHandIsReproducibleForAGivenSeed() {
        assertEquals(
            MatchPreparation.randomHand(collection, Random(7)),
            MatchPreparation.randomHand(collection, Random(7)),
        )
    }

    /**
     * Random draws under [DeckLimits], and the reason is an incentive rather than a rule.
     *
     * A hand spliced out of the *collection* and exempt from the caps would have made selling the
     * way around them: dump every low card and the draw is forced into the five-ace hand the deck
     * editor refuses to build.
     */
    @Test
    fun aRandomHandIsDealtUnderTheStarRankCaps() {
        val aces = (1..8).map { card(it, rarity = 5) } + (9..16).map { card(it, rarity = 4) }
        val rich = aces + (17..30).map(::card)

        for (seed in seeds) {
            val drawn = MatchPreparation.randomHand(rich, Random(seed))

            assertEquals(HAND_SIZE, drawn.size, "seed $seed")
            assertTrue(DeckLimits.isLegal(drawn.map { it.id }, rich.associateBy { it.id }), "$seed")
        }
    }

    /** The caps refuse a second ace, never the first: the rule promises nothing either way. */
    @Test
    fun aRandomHandCanStillDrawAnAce() {
        val rich = (1..4).map { card(it, rarity = 5) } + (5..30).map(::card)

        val withAnAce = seeds.count { seed ->
            MatchPreparation.randomHand(rich, Random(seed)).any { it.rarity == 5 }
        }

        assertTrue(withAnAce > 0, "an ace must still be drawn when the shuffle offers one")
    }

    /**
     * A collection that cannot field a legal five comes up short rather than dealing an illegal
     * hand — the profile sold down to nothing but aces, which is the case the caps exist for.
     */
    @Test
    fun aCollectionOfNothingButAcesCannotFillARandomHand() {
        val aces = (1..8).map { card(it, rarity = 5) }

        assertEquals(1, MatchPreparation.randomHand(aces, Random(1)).size)
    }

    // ---- Swap -------------------------------------------------------------

    @Test
    fun swapExchangesExactlyOneCardEachWay() {
        for (seed in seeds) {
            val (blue, red) = MatchPreparation.swap(blueDeck, redDeck, Random(seed))

            assertEquals(HAND_SIZE, blue.size, "seed $seed")
            assertEquals(HAND_SIZE, red.size, "seed $seed")
            val gained = blue.map { it.id }.toSet() - blueDeck.map { it.id }.toSet()
            val lost = blueDeck.map { it.id }.toSet() - blue.map { it.id }.toSet()
            assertEquals(1, gained.size, "seed $seed: blue gained $gained")
            assertEquals(1, lost.size, "seed $seed: blue lost $lost")
            assertTrue(gained.single() in redDeck.map { it.id }, "seed $seed")
            assertTrue(lost.single() in red.map { it.id }, "seed $seed")
        }
    }

    /**
     * `swapCardWith` redraws the *slot*, so the slot's colour is unchanged and the swapped card
     * belongs to whoever received it (`playerPanel.as:217-219`).
     */
    @Test
    fun aSwappedCardTakesItsNewOwnersColour() {
        val (blue, red) = MatchPreparation.swap(
            blueDeck.map { it.copy(owner = CardColor.BLUE) },
            redDeck.map { it.copy(owner = CardColor.RED) },
            Random(2),
        )

        assertTrue(blue.all { it.owner == CardColor.BLUE }, "blue: $blue")
        assertTrue(red.all { it.owner == CardColor.RED }, "red: $red")
    }

    /**
     * There is no "unless it is the same card" guard in the original, and none here — so two hands
     * holding the same cards can swap a card for a copy of another one it already has, leaving a
     * duplicate in hand.
     *
     * Left as it is rather than guarded. The two hands in a real match come from different sources
     * (a deck or a collection on one side, an opponent's fetish cards and pool on the other) and
     * can legitimately overlap, so "the same card id twice" is not evidence of a bug the setup
     * should be refusing; and a duplicate in hand is harmless, since [MatchState.play] matches the
     * card by id and removes one copy.
     */
    @Test
    fun swappingOverlappingHandsCanLeaveADuplicateInHand() {
        val sizes = seeds.map { seed ->
            val swapped = MatchPreparation.swap(blueDeck, blueDeck, Random(seed))
            assertEquals(HAND_SIZE, swapped.blue.size, "seed $seed")
            assertEquals(HAND_SIZE, swapped.red.size, "seed $seed")
            swapped.blue.distinctBy { it.id }.size
        }

        assertTrue(HAND_SIZE in sizes, "identical indices leave the hand unchanged")
        assertTrue(HAND_SIZE - 1 in sizes, "differing indices duplicate a card, as the AS3 does")
    }

    @Test
    fun swappingWithAnEmptyHandIsANoOp() {
        assertEquals(
            SwappedHands(blueDeck, emptyList()),
            MatchPreparation.swap(blueDeck, emptyList(), Random(1)),
        )
        assertEquals(
            SwappedHands(emptyList(), redDeck),
            MatchPreparation.swap(emptyList(), redDeck, Random(1)),
        )
    }

    /**
     * The point of [SwappedHands] carrying positions at all: a card you handed over out of your own
     * deck is one you can name on sight, wherever the Open rule says it should be face down.
     */
    @Test
    fun aSwapReportsWhereEachGivenCardLanded() {
        for (seed in seeds) {
            val swapped = MatchPreparation.swap(blueDeck, redDeck, Random(seed))
            val blueSees = swapped.blueSees ?: error("seed $seed reported no slot")
            val redSees = swapped.redSees ?: error("seed $seed reported no slot")

            // Blue's card went across, so the slot blue is owed sight of holds a card blue owned.
            assertTrue(
                swapped.red[blueSees].id in blueDeck.map { it.id },
                "seed $seed: red's slot $blueSees is not a card blue gave away",
            )
            assertTrue(
                swapped.blue[redSees].id in redDeck.map { it.id },
                "seed $seed: blue's slot $redSees is not a card red gave away",
            )
        }
    }

    @Test
    fun aHandThatWasNotSwappedOwesNobodyASlot() {
        val swapped = MatchPreparation.swap(blueDeck, emptyList(), Random(1))

        assertEquals(emptySet(), swapped.blueKnows)
        assertEquals(emptySet(), swapped.redKnows)
    }

    // ---- Open -------------------------------------------------------------

    @Test
    fun theDefaultOpenRuleRevealsNothing() {
        val visibility = HandVisibility.forRule(OpenRule.NONE, redDeck, Random(1))

        assertEquals(emptySet(), visibility.visiblePositions)
        assertTrue(redDeck.indices.none(visibility::isVisible))
    }

    @Test
    fun allOpenRevealsTheWholeHand() {
        val visibility = HandVisibility.forRule(OpenRule.ALL_OPEN, redDeck, Random(1))

        assertEquals(redDeck, visibility.visible(redDeck))
    }

    @Test
    fun threeOpenRevealsExactlyThree() {
        for (seed in seeds) {
            val visibility = HandVisibility.forRule(OpenRule.THREE_OPEN, redDeck, Random(seed))

            assertEquals(
                HandVisibility.THREE_OPEN_COUNT,
                visibility.visible(redDeck).size,
                "seed $seed",
            )
            assertTrue(visibility.visiblePositions.all { it in redDeck.indices })
        }
    }

    // ---- What a swap makes public ------------------------------------------

    @Test
    fun aClosedHandStillShowsTheCardTheOtherSideGaveAway() {
        val visibility =
            HandVisibility.forRule(OpenRule.NONE, redDeck, Random(1), known = setOf(2))

        assertEquals(setOf(2), visibility.visiblePositions)
        assertEquals(listOf(redDeck[2]), visibility.visible(redDeck))
    }

    /**
     * The half of the rule that is easy to get wrong: a swapped card is **one of** the three, not a
     * fourth. Three Open shows three cards or it is not Three Open.
     */
    @Test
    fun threeOpenCountsTheSwappedCardAmongItsThree() {
        for (seed in seeds) {
            val visibility =
                HandVisibility.forRule(OpenRule.THREE_OPEN, redDeck, Random(seed), known = setOf(1))

            assertEquals(
                HandVisibility.THREE_OPEN_COUNT,
                visibility.visiblePositions.size,
                "seed $seed revealed ${visibility.visiblePositions}",
            )
            assertTrue(visibility.isVisible(1), "seed $seed hid the swapped card")
        }
    }

    @Test
    fun allOpenIsUnchangedByASwapItAlreadyReveals() {
        val plain = HandVisibility.forRule(OpenRule.ALL_OPEN, redDeck, Random(1))
        val swapped =
            HandVisibility.forRule(OpenRule.ALL_OPEN, redDeck, Random(1), known = setOf(3))

        assertEquals(plain, swapped)
    }

    /**
     * `shuffled` reads the generator by hand size and not by how many are taken, so revealing one
     * fewer at random costs no draw — and a seed deals exactly what it dealt before. `prepare`'s
     * KDoc explains why that is worth a test rather than a comment.
     */
    @Test
    fun knowingASlotDoesNotChangeWhatTheGeneratorGoesOnToDeal() {
        for (seed in seeds) {
            val plain = Random(seed).also {
                HandVisibility.forRule(OpenRule.THREE_OPEN, redDeck, it)
            }
            val withSwap = Random(seed).also {
                HandVisibility.forRule(OpenRule.THREE_OPEN, redDeck, it, known = setOf(0))
            }

            assertEquals(plain.nextInt(), withSwap.nextInt(), "seed $seed diverged")
        }
    }

    @Test
    fun threeOpenDoesNotAlwaysRevealTheSameThree() {
        val revealed = seeds
            .map {
                HandVisibility.forRule(OpenRule.THREE_OPEN, redDeck, Random(it)).visiblePositions
            }
            .distinct()

        assertTrue(revealed.size > 1, "the three are drawn, not fixed")
    }

    /**
     * Visibility is by card id, not by slot: a slot index would name a different card as soon as
     * the hand closes up behind a played one.
     */
    @Test
    fun visibilitySurvivesTheHandShrinking() {
        val visibility = HandVisibility.forRule(OpenRule.THREE_OPEN, redDeck, Random(5))
        val shown = visibility.visible(redDeck)

        // Every slot in turn, not just the first: the played card may be in front of a revealed
        // one, behind it, or be it, and the three shift differently.
        for (played in redDeck.indices) {
            val remaining = redDeck.filterIndexed { at, _ -> at != played }

            assertEquals(
                shown.filterIndexed { at, _ -> redDeck.indexOf(shown[at]) != played },
                visibility.afterPlaying(played).visible(remaining),
                "the same cards stay revealed after slot $played is played",
            )
        }
    }

    /**
     * The regression that made positions necessary: with two copies of one card in hand, an
     * id-keyed visibility collapsed them into one entry and revealed two cards or four.
     */
    @Test
    fun threeOpenRevealsThreeEvenWhenTheHandHoldsDuplicates() {
        val twins = listOf(redDeck[0], redDeck[0], redDeck[1], redDeck[1], redDeck[2])

        for (seed in seeds) {
            val visibility = HandVisibility.forRule(OpenRule.THREE_OPEN, twins, Random(seed))

            assertEquals(
                HandVisibility.THREE_OPEN_COUNT,
                visibility.visible(twins).size,
                "seed $seed",
            )
        }
    }

    // ---- Coin flip --------------------------------------------------------

    @Test
    fun theCoinFlipIsThreeRollsDecidedByMajority() {
        for (seed in seeds) {
            val flip = CoinFlip.toss(Random(seed))

            assertEquals(CoinFlip.ROLLS, flip.rolls.size, "seed $seed")
            val blue = flip.rolls.count { it == CardColor.BLUE }
            assertEquals(
                if (blue > CoinFlip.ROLLS - blue) CardColor.BLUE else CardColor.RED,
                flip.winner,
                "seed $seed rolled ${flip.rolls}",
            )
        }
    }

    @Test
    fun bothSidesCanWinTheFlip() {
        assertEquals(
            setOf(CardColor.BLUE, CardColor.RED),
            seeds.map { CoinFlip.toss(Random(it)).winner }.toSet(),
        )
    }

    /** `blueCount > redCount` is strict, so a tie — impossible with three rolls — goes to red. */
    @Test
    fun aTiedFlipGoesToRed() {
        assertEquals(
            CardColor.RED,
            CoinFlip(listOf(CardColor.BLUE, CardColor.RED)).winner,
        )
    }

    @Test
    fun aForcedFlipNamesItsWinner() {
        for (color in CardColor.entries) {
            assertEquals(color, CoinFlip.forced(color).winner)
        }
    }

    @Test
    fun aFlipWithNoRollsIsAProgrammingError() {
        assertFailsWith<IllegalArgumentException> { CoinFlip(emptyList()) }
    }

    // ---- Intro sequence ---------------------------------------------------

    @Test
    fun aRuleLessMatchAnnouncesOnlyTheFlipAndTheStart() {
        assertEquals(
            listOf(MatchIntroStep.COIN_FLIP, MatchIntroStep.START),
            MatchPreparation.introSteps(GameRules()),
        )
    }

    /**
     * The order is `BaseMatchScreen`'s cascade, and every applicable step runs. The plan's
     * subject-less `when` would have run only the first — see
     * [07-PHASE-3-CORE-LOGIC.md](../../../../../../../docs/migration/07-PHASE-3-CORE-LOGIC.md).
     */
    @Test
    fun everyApplicableStepIsAnnouncedInSourceOrder() {
        val rules = GameRules(
            random = true,
            open = OpenRule.THREE_OPEN,
            order = OrderRule.CHAOS,
            reverse = true,
            fallenAce = true,
            swap = true,
        )

        assertEquals(
            listOf(
                MatchIntroStep.RANDOM,
                MatchIntroStep.THREE_OPEN,
                MatchIntroStep.CHAOS,
                MatchIntroStep.REVERSE,
                MatchIntroStep.FALLEN_ACE,
                MatchIntroStep.SWAP,
                MatchIntroStep.COIN_FLIP,
                MatchIntroStep.START,
            ),
            MatchPreparation.introSteps(rules),
        )
    }

    @Test
    fun eachSlotAnnouncesItsOwnMemberOnly() {
        assertTrue(
            MatchIntroStep.ALL_OPEN in MatchPreparation.introSteps(
                GameRules(open = OpenRule.ALL_OPEN),
            ),
        )
        assertTrue(
            MatchIntroStep.ORDER in MatchPreparation.introSteps(
                GameRules(order = OrderRule.ORDER),
            ),
        )
        assertFalse(
            MatchIntroStep.CHAOS in MatchPreparation.introSteps(
                GameRules(order = OrderRule.ORDER),
            ),
        )
    }

    /** A rematch skips the hand build and the flip, and keeps Swap (`:116-118`, `:238`). */
    @Test
    fun aRematchSkipsTheHandBuildAndTheFlipButNotTheSwap() {
        val rules = GameRules(random = true, swap = true, reverse = true)

        assertEquals(
            listOf(MatchIntroStep.REVERSE, MatchIntroStep.SWAP, MatchIntroStep.START),
            MatchPreparation.introSteps(rules, rematch = true),
        )
    }

    @Test
    fun theStartAnnouncementIsAlwaysLast() {
        for (seed in seeds) {
            val rules = Roulette.augment(GameRules(), TestFormats.ff14.rules, Random(seed))

            assertEquals(
                MatchIntroStep.START,
                MatchPreparation.introSteps(rules).last(),
                "seed $seed",
            )
        }
    }

    // ---- Elements ---------------------------------------------------------

    @Test
    fun elementsAreRolledOnlyUnderElemental() {
        for (rule in TypeRule.entries) {
            val elements = MatchPreparation.elementsFor(GameRules(typeRule = rule), Random(1))

            assertEquals(Board.SIZE, elements.size, rule.name)
            if (rule == TypeRule.ELEMENTAL) {
                assertTrue(elements.any { it != null }, "seed 1 under Elemental placed no element")
            } else {
                assertTrue(elements.all { it == null }, "$rule must not place elements")
            }
        }
    }

    @Test
    fun onlyFf8ElementsAreEverPlaced() {
        for (seed in seeds) {
            val elements = MatchPreparation.elementsFor(
                GameRules(typeRule = TypeRule.ELEMENTAL),
                Random(seed),
            )

            assertTrue(
                elements.filterNotNull().all { it in MatchState.FF8_ELEMENTS },
                "seed $seed placed a tribe as an element",
            )
        }
    }

    // ---- prepare ----------------------------------------------------------

    @Test
    fun aPreparedMatchIsPlayableFromTheFirstFrame() {
        val setup = MatchPreparation.prepare(HandSource(blueDeck), redDeck, random = Random(1))

        assertEquals(0, setup.state.placement)
        assertEquals(HAND_SIZE, setup.state.hands[CardColor.BLUE]?.size)
        assertEquals(HAND_SIZE, setup.state.hands[CardColor.RED]?.size)
        assertNotNull(setup.state.currentPlayer)
        assertEquals(HAND_SIZE, setup.state.playableCards().size)
    }

    @Test
    fun theFlipDecidesWhoMovesFirst() {
        for (color in CardColor.entries) {
            val setup = MatchPreparation.prepare(
                HandSource(blueDeck),
                redDeck,
                random = Random(1),
                forcedFlip = CoinFlip.forced(color),
            )

            assertEquals(color, setup.state.currentPlayer)
            assertEquals(color, setup.coinFlip?.winner)
        }
    }

    @Test
    fun theChosenDeckIsUsedWhenRandomIsOff() {
        val setup = MatchPreparation.prepare(
            HandSource(blueDeck, collection),
            redDeck,
            random = Random(1),
        )

        assertEquals(blueDeck.map { it.id }, setup.state.hands[CardColor.BLUE]?.map { it.id })
    }

    /** Under Random the deck selector never opens, so the chosen deck is ignored (`:120-135`). */
    @Test
    fun theChosenDeckIsIgnoredWhenRandomIsOn() {
        val outsideTheDeck = collection.map { it.id }.toSet() - blueDeck.map { it.id }.toSet()

        val dealtOutside = seeds.count { seed ->
            MatchPreparation.prepare(
                HandSource(blueDeck, collection),
                redDeck,
                rules = GameRules(random = true),
                random = Random(seed),
            ).state.hands[CardColor.BLUE].orEmpty().any { it.id in outsideTheDeck }
        }

        assertTrue(dealtOutside > 0, "a Random hand must be able to leave the deck behind")
    }

    /**
     * A rule may take the choice away; it may not refuse to deal.
     *
     * The draw comes up short for a collection the caps cannot make five legal cards out of, and
     * the chosen deck is the answer there — even when that deck breaks the caps itself, because
     * it is the deck the server already agreed to deal.
     */
    @Test
    fun randomFallsBackToTheChosenDeckWhenNoLegalFiveExists() {
        val aces = (1..8).map { card(it, rarity = 5) }

        val setup = MatchPreparation.prepare(
            HandSource(blueDeck, aces),
            redDeck,
            rules = GameRules(random = true),
            random = Random(1),
        )

        assertEquals(blueDeck.map { it.id }, setup.state.hands[CardColor.BLUE]?.map { it.id })
    }

    /** Random only ever built the local player's hand; an opponent has its own. */
    @Test
    fun randomDoesNotTouchTheOpponentsHand() {
        val setup = MatchPreparation.prepare(
            HandSource(blueDeck, collection),
            redDeck,
            rules = GameRules(random = true),
            random = Random(1),
        )

        assertEquals(redDeck.map { it.id }, setup.state.hands[CardColor.RED]?.map { it.id })
    }

    @Test
    fun swapIsAppliedBeforeTheMatchStarts() {
        val setup = MatchPreparation.prepare(
            HandSource(blueDeck),
            redDeck,
            rules = GameRules(swap = true),
            random = Random(1),
        )
        val blue = setup.state.hands[CardColor.BLUE].orEmpty()

        assertEquals(1, (blue.map { it.id }.toSet() - blueDeck.map { it.id }.toSet()).size)
        assertTrue(blue.all { it.owner == CardColor.BLUE })
    }

    @Test
    fun visibilityAppliesToTheHandTheOpponentActuallyHolds() {
        val setup = MatchPreparation.prepare(
            HandSource(blueDeck),
            redDeck,
            rules = GameRules(swap = true, open = OpenRule.ALL_OPEN),
            random = Random(1),
        )
        val red = setup.state.hands[CardColor.RED].orEmpty()

        assertEquals(
            red.indices.toSet(),
            setup.opponentVisibility.visiblePositions,
            "the swapped-in card must be visible too",
        )
    }

    @Test
    fun elementalPreparesAnElementalBoard() {
        val setup = MatchPreparation.prepare(
            HandSource(blueDeck),
            redDeck,
            rules = GameRules(typeRule = TypeRule.ELEMENTAL),
            random = Random(1),
        )

        assertTrue(setup.state.board.elements.any { it != null })
    }

    @Test
    fun aPreparedMatchIsReproducibleForAGivenSeed() {
        val rules = GameRules(random = true, swap = true, open = OpenRule.THREE_OPEN)

        assertEquals(
            MatchPreparation.prepare(HandSource(blueDeck, collection), redDeck, rules, Random(11)),
            MatchPreparation.prepare(HandSource(blueDeck, collection), redDeck, rules, Random(11)),
        )
    }

    // ---- prepareRematch ---------------------------------------------------

    /** A running match has no rematch to prepare. */
    @Test
    fun aRematchNeedsAFinishedMatch() {
        val setup = MatchPreparation.prepare(HandSource(blueDeck), redDeck, random = Random(1))

        assertFailsWith<IllegalArgumentException> {
            MatchPreparation.prepareRematch(setup.state, Random(1))
        }
    }

    @Test
    fun aRematchHasNoCoinFlipAndKeepsTheTurnOrder() {
        val finished = playOut(GameRules(suddenDeath = true))

        val rematch = MatchPreparation.prepareRematch(finished, Random(1))

        assertNull(rematch.coinFlip, "the previous timeline carries over")
        assertEquals(finished.order, rematch.state.order)
        assertEquals(0, rematch.state.placement)
        assertFalse(MatchIntroStep.COIN_FLIP in rematch.intro)
    }

    @Test
    fun aRematchDealsEachSideTheCardsItOwned() {
        val finished = playOut(GameRules(suddenDeath = true))
        val owned = CardColor.entries.associateWith { color ->
            finished.board.cells.filterNotNull().count { it.owner == color } +
                finished.hands[color].orEmpty().size
        }

        val rematch = MatchPreparation.prepareRematch(finished, Random(1))

        for (color in CardColor.entries) {
            assertEquals(
                owned[color],
                rematch.state.hands[color]?.size,
                "$color should carry over what it owned",
            )
        }
    }

    @Test
    fun aRematchUnderSwapExchangesACardAgain() {
        val finished = playOut(GameRules(suddenDeath = true, swap = true))
        val carried = finished.board.cells.filterNotNull()
            .filter { it.owner == CardColor.BLUE }
            .map { it.card.id } + finished.hands[CardColor.BLUE].orEmpty().map { it.id }

        val rematch = MatchPreparation.prepareRematch(finished, Random(1))
        val blue = rematch.state.hands[CardColor.BLUE].orEmpty().map { it.id }

        assertTrue(MatchIntroStep.SWAP in rematch.intro)
        assertEquals(carried.size, blue.size, "a swap exchanges, it does not add or remove")
        assertTrue(
            (blue.toSet() - carried.toSet()).isNotEmpty(),
            "blue should have received one of red's cards",
        )
    }

    /**
     * Plays a whole match to a 5-5 draw so the rematch path has something to work from.
     *
     * Every card is a plain 5/5/5/5, so no placement ever captures and each side keeps what it
     * played — the score is 5-5 by construction rather than by luck.
     */
    private fun playOut(rules: GameRules): MatchState {
        var state = MatchState.start(blueDeck, redDeck, rules = rules)
        while (!state.isFinished) {
            state = state.play(state.currentHand.first(), state.playablePositions().first())
        }
        return state
    }

    @Test
    fun theDrawnOutMatchUsedByTheRematchTestsIsActuallyADraw() {
        val finished = playOut(GameRules(suddenDeath = true))

        assertEquals(MatchScore(5, 5), finished.score)
        assertTrue(finished.outcome() is MatchOutcome.SuddenDeath)
    }
}
