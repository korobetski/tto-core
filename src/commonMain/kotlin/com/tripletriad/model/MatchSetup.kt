package com.tripletriad.model

import kotlin.random.Random

/**
 * Which of a hand's cards the other side can see — the Open rule.
 *
 * `playerPanel.set openRule` (`playerPanel.as:181-199`) is the whole of it: All Open turns every
 * card face up, Three Open hides all five then reveals three at random, and the default hides
 * everything. Nothing else in the original reads the rule, which is why [OpenRule] is documented
 * as presentation only.
 *
 * **Open is only ever about the opponent.** `BaseMatchScreen.openPhase` assigns `RULE_ALL_OPEN` to
 * `bluePlayer` on *both* branches (`:172`, `:176`) — the local player always sees their own hand
 * whatever the rule says.
 *
 * ### Why this is recorded by position and not by card id
 *
 * It **was** by card id, on the grounds that ids are unique within a hand — [randomHand] draws
 * without replacement, and [Npc.randomHand] tops a hand up from a pool it removes from. That
 * premise died with `docs/migration/20-CARD-COPIES-AND-PLATFORM-ACCOUNTS.md` § 1: a card can be
 * owned twice, so a deck can name it twice, so a hand can hold two of it.
 *
 * The failure it caused was silent and rule-visible. Under [OpenRule.THREE_OPEN] the rule takes
 * three cards and collected their **ids into a set** — with a duplicate in hand that set holds two,
 * and [visible] then showed **two cards or four, never three**. Nothing threw and nothing logged;
 * the opponent simply saw the wrong number of cards.
 *
 * Positions bring back the problem the id-keyed version was written to avoid: an AS3 panel keeps
 * its five slots forever, while [MatchState.hands] holds only the cards still in hand and closes
 * the gap, so a position starts naming a different card the moment one is played. That is what
 * [afterPlaying] is for, and [PlayResult.handIndex] is what a caller feeds it.
 */
data class HandVisibility(val visiblePositions: Set<Int>) {
    /** Whether the card at [position] in the hand is face up. */
    fun isVisible(position: Int): Boolean = position in visiblePositions

    /** The subset of [hand] the other side can see, in hand order. */
    fun visible(hand: List<Card>): List<Card> =
        hand.filterIndexed { position, _ -> isVisible(position) }

    /**
     * The same visibility after the card at [position] has left the hand.
     *
     * The played position drops out and everything behind it shifts down one, because
     * [MatchState.play] rebuilds the hand without it rather than leaving a hole. A caller that
     * forgets this does not get an exception — it gets the wrong cards face up — which is why
     * [PlayResult] carries the index rather than leaving each caller to find it.
     */
    fun afterPlaying(position: Int): HandVisibility = HandVisibility(
        visiblePositions.mapNotNullTo(mutableSetOf()) {
            when {
                it < position -> it
                it > position -> it - 1
                else -> null
            }
        },
    )

    companion object {
        /** `RULE_THREE_OPEN` reveals three of the five (`playerPanel.as:190`). */
        const val THREE_OPEN_COUNT: Int = 3

        /** Nothing revealed — `RULE_DEFAULT_OPEN`. */
        val HIDDEN: HandVisibility = HandVisibility(emptySet())

        /**
         * What [rule] reveals of [hand].
         *
         * [random] is only read for [OpenRule.THREE_OPEN], and uniformly: the original's
         * `tools.rand(randomizer.length - 1)` makes the first and last remaining slot half as
         * likely at each of the three draws
         * ([game-rules.md](../../../../../../../docs/analysis/game-rules.md) § 15.6).
         */
        fun forRule(
            rule: OpenRule,
            hand: List<Card>,
            random: Random = Random.Default,
        ): HandVisibility = when (rule) {
            OpenRule.NONE -> HIDDEN
            OpenRule.ALL_OPEN -> HandVisibility(hand.indices.toSet())
            OpenRule.THREE_OPEN ->
                HandVisibility(
                    hand.indices.shuffled(random).take(THREE_OPEN_COUNT).toSet(),
                )
        }
    }
}

/**
 * The coin toss that decides who moves first — `PileOuFace`.
 *
 * Three cards land on the board, each blue or red, and the majority colour goes first
 * (`PileOuFace.as:34`, `:93-95`, `:115-129`). Three fair tosses with a majority verdict is a fair
 * ½ either way, so this is a single coin flip dressed as three; the rolls are kept because they are
 * what the animation shows.
 *
 * A tie goes to red, since the test is the strict `blueCount > redCount`. With [ROLLS] tosses a tie
 * cannot happen, but [forced] can produce any list and the rule should not change with the count.
 */
data class CoinFlip(val rolls: List<CardColor>) {
    init {
        require(rolls.isNotEmpty()) { "a coin flip needs at least one roll" }
    }

    val winner: CardColor
        get() {
            val blue = rolls.count { it == CardColor.BLUE }
            return if (blue > rolls.size - blue) CardColor.BLUE else CardColor.RED
        }

    companion object {
        const val ROLLS: Int = 3

        fun toss(random: Random = Random.Default): CoinFlip = CoinFlip(
            List(ROLLS) { if (random.nextBoolean()) CardColor.BLUE else CardColor.RED },
        )

        /**
         * A flip rigged so [winner] is [color] — `pof.rolls = (playerColor == 'red') ? [0,0,0] :
         * [1,1,1]` (`PVPMatchScreen.as:150`), where the server has already decided and the
         * animation only reports it. `TutorialScreen.as:64` rigs it the same way.
         */
        fun forced(color: CardColor): CoinFlip = CoinFlip(List(ROLLS) { color })
    }
}

/**
 * One announcement in the pre-match sequence.
 *
 * `BaseMatchScreen` runs these as a `setTimeout` cascade of seven functions, each waiting ~1.4 s on
 * an animation and calling the next (`:113-254`). Two of them do work — [RANDOM] builds a hand,
 * [SWAP] exchanges a card — and the rest exist only to tell the player which rules are in force.
 *
 * Modelled as a **list of steps rather than a phase state machine**: the plan in
 * [07-PHASE-3-CORE-LOGIC.md](../../../../../../../docs/migration/07-PHASE-3-CORE-LOGIC.md)
 * called for an eleven-member `GamePhase` enum held on the state and advanced by a `next()`
 * function, but a phase that only plays an animation is not state anyone can be *in* — nothing
 * downstream branches on it, and it cannot be resumed, saved or tested. [MatchSetup] hands the UI
 * the steps to play, having already applied everything they announce, so the match is legal from
 * the first frame whether the UI plays them or skips them.
 */
enum class MatchIntroStep {
    /** `RandomAnim` — the hand was built from the collection rather than chosen. */
    RANDOM,

    /** `AllOpenAnim`. */
    ALL_OPEN,

    /** `ThreeOpenAnim`. */
    THREE_OPEN,

    /** `OrderAnim`. */
    ORDER,

    /** `ChaosAnim`. */
    CHAOS,

    /** `ReverseAnim`. */
    REVERSE,

    /** `FallenAceAnim`. */
    FALLEN_ACE,

    /** `SwapAnim` — a card has already been exchanged. */
    SWAP,

    /** `PileOuFace` — the three cards that decide who starts. */
    COIN_FLIP,

    /** `StartAnim`. Always last, and always present. */
    START,
}

/**
 * A match ready to play, plus what the UI should announce before the first move.
 *
 * @property state the match, with hands dealt, elements rolled and the first player decided.
 * @property opponentVisibility what the local (blue) player may see of the red hand. The reverse
 *   direction is not modelled: the original always shows a player their own cards.
 * @property coinFlip the three rolls to animate, or `null` on a Sudden Death rematch — the turn
 *   order carries over and there is no new flip (`BaseMatchScreen.as:238`).
 */
data class MatchSetup(
    val state: MatchState,
    val opponentVisibility: HandVisibility,
    val coinFlip: CoinFlip?,
    val intro: List<MatchIntroStep>,
)

/**
 * Where the local player's hand comes from.
 *
 * Two lists rather than one, because `RULE_RANDOM` does not draw from the deck: it splices from
 * `Game.PROFILE_DATAS.CARDS`, the whole collection, and the deck selector never opens
 * (`BaseMatchScreen.as:120-135`). So both are needed up front — which of them is read is the rule's
 * decision, not the caller's.
 *
 * @property deck the five cards the player chose.
 * @property collection everything the player owns. Defaults to [deck], so a caller that knows
 *   Random is off need not supply one.
 *
 *   Note the original has a special case here that this does not keep:
 *   `if (randomizer.length == 5) randomCards = randomizer` (`:126`) skips the draw entirely for a
 *   player who owns exactly five cards, leaving the hand in collection order. That order is not
 *   cosmetic — under [OrderRule.ORDER] it decides which card must be played next — so a rule named
 *   Random shuffling five cards is the behaviour worth having.
 */
data class HandSource(val deck: List<Card>, val collection: List<Card> = deck)

/**
 * The pre-match rule chain — everything between "start a match" and the first placement.
 *
 * [MatchState.start] deliberately takes finished hands and a first player, on the grounds that
 * assembling them is a separate job. This is that job.
 */
object MatchPreparation {
    /**
     * A hand of [HAND_SIZE] cards drawn from [collection] — the `RULE_RANDOM` deck.
     *
     * `BaseMatchScreen.deckSelectionPhase` (`:120-135`) splices from a copy of
     * `Game.PROFILE_DATAS.CARDS`, the player's **whole collection**, not from a deck — under Random
     * the deck selector never opens and the chosen deck is ignored entirely.
     *
     * Drawn without replacement, which the original is not. Its loop reads
     * `(randomizer.length > 1) ? splice(…) : randomizer[0]`, so once the copy is down to one entry
     * it pushes that same card until the hand reaches five: a player owning four cards is dealt a
     * hand with the same card twice
     * ([game-rules.md](../../../../../../../docs/analysis/game-rules.md) § 15.7). Fewer than
     * [HAND_SIZE] cards is refused here instead — the starting profile owns five
     * ([GameSave.DEFAULT_CARDS]) and [GameSave.sane] deduplicates, so it cannot arise without a
     * corrupted save, and dealing a duplicate is worse than saying so.
     *
     * Only the local player's hand is affected. An opponent always builds its hand from its own
     * fetish cards and pool ([Npc.randomHand]), whatever the rules say.
     */
    fun randomHand(collection: List<Card>, random: Random = Random.Default): List<Card> {
        require(collection.size >= HAND_SIZE) {
            "a random hand needs at least $HAND_SIZE cards to draw from, had ${collection.size}"
        }
        return collection.shuffled(random).take(HAND_SIZE)
    }

    /**
     * One card from each hand, exchanged — `RULE_SWAP`.
     *
     * `BaseMatchScreen.swapPhase` (`:220-235`) takes a random card from each side and calls
     * `swapCardWith`, which redraws the *slot* with the other card's id (`playerPanel.as:217-219`).
     * So the card changes and the slot's colour does not: a swapped card belongs to whoever
     * received it, which is why both are re-stamped here.
     *
     * Unconditional when the rule is on — there is no "unless they are the same card" guard, and
     * two hands can legitimately hold the same card id.
     */
    fun swap(
        blue: List<Card>,
        red: List<Card>,
        random: Random = Random.Default,
    ): Pair<List<Card>, List<Card>> {
        if (blue.isEmpty() || red.isEmpty()) return blue to red
        val fromBlue = random.nextInt(blue.size)
        val fromRed = random.nextInt(red.size)
        val given = blue[fromBlue].copy(owner = CardColor.RED)
        val taken = red[fromRed].copy(owner = CardColor.BLUE)
        return blue.toMutableList().also { it[fromBlue] = taken } to
            red.toMutableList().also { it[fromRed] = given }
    }

    /**
     * The announcements [rules] calls for, in the order `BaseMatchScreen` runs them.
     *
     * @param rematch true for a Sudden Death rematch, which skips the hand build and the coin flip
     *   — `deckSelectionPhase` jumps straight to `openPhase` (`:116-118`) and `pileOuFace` keeps
     *   the previous timeline (`:238`). Every other step still runs, **Swap included**, so a
     *   rematch under Swap exchanges a card again.
     */
    fun introSteps(rules: GameRules, rematch: Boolean = false): List<MatchIntroStep> = buildList {
        if (rules.random && !rematch) add(MatchIntroStep.RANDOM)
        when (rules.open) {
            OpenRule.ALL_OPEN -> add(MatchIntroStep.ALL_OPEN)
            OpenRule.THREE_OPEN -> add(MatchIntroStep.THREE_OPEN)
            OpenRule.NONE -> Unit
        }
        when (rules.order) {
            OrderRule.ORDER -> add(MatchIntroStep.ORDER)
            OrderRule.CHAOS -> add(MatchIntroStep.CHAOS)
            OrderRule.FREE -> Unit
        }
        if (rules.reverse) add(MatchIntroStep.REVERSE)
        if (rules.fallenAce) add(MatchIntroStep.FALLEN_ACE)
        if (rules.swap) add(MatchIntroStep.SWAP)
        if (!rematch) add(MatchIntroStep.COIN_FLIP)
        add(MatchIntroStep.START)
    }

    /**
     * Board elements, rolled only under [TypeRule.ELEMENTAL].
     *
     * `openPhase` calls `board.elements()` behind exactly that test (`:158-160`), so a board with
     * no Elemental rule has no elements at all rather than elements nothing reads.
     */
    fun elementsFor(rules: GameRules, random: Random = Random.Default): List<CardType?> =
        if (rules.typeRule == TypeRule.ELEMENTAL) {
            MatchState.randomElements(random)
        } else {
            List(Board.SIZE) { null }
        }

    /**
     * Assembles a match: the hand build, the swap, the elements, the coin flip and the
     * announcements.
     *
     * @param blue where the local player's hand comes from — see [HandSource].
     * @param redHand the opponent's hand, already built. [Npc.randomHand] builds it from the
     *   opponent's fetish cards and pool, and no rule alters it.
     * @param forcedFlip a rigged coin flip, for when something else has already decided who starts
     *   — the server in PvP, a fixed script in the tutorial. Defaults to a real toss.
     */
    fun prepare(
        blue: HandSource,
        redHand: List<Card>,
        rules: GameRules = GameRules(),
        random: Random = Random.Default,
        forcedFlip: CoinFlip? = null,
    ): MatchSetup {
        val chosen = if (rules.random) randomHand(blue.collection, random) else blue.deck
        val (blueHand, opponentHand) =
            if (rules.swap) swap(chosen, redHand, random) else chosen to redHand
        val flip = forcedFlip ?: CoinFlip.toss(random)

        val state = MatchState.start(
            blueHand = blueHand,
            redHand = opponentHand,
            first = flip.winner,
            rules = rules,
            elements = elementsFor(rules, random),
        )
        return MatchSetup(
            state = state,
            opponentVisibility = HandVisibility.forRule(rules.open, opponentHand, random),
            coinFlip = flip,
            intro = introSteps(rules),
        )
    }

    /**
     * The Sudden Death rematch: each side takes the cards it ended up owning.
     *
     * [MatchState.suddenDeathRematch] does the regrouping; this adds what the phase chain does on
     * the way back round — new elements, another Swap if the rule is on, fresh Three Open
     * visibility — and reports that there is no coin flip.
     *
     * @throws IllegalArgumentException if [finished] is not over.
     */
    fun prepareRematch(finished: MatchState, random: Random = Random.Default): MatchSetup {
        val rules = finished.rules
        val regrouped = finished.suddenDeathRematch(elementsFor(rules, random))
        val state = if (rules.swap) {
            val (blueHand, redHand) = swap(
                regrouped.hands[CardColor.BLUE].orEmpty(),
                regrouped.hands[CardColor.RED].orEmpty(),
                random,
            )
            regrouped.copy(
                hands = mapOf(CardColor.BLUE to blueHand, CardColor.RED to redHand),
            )
        } else {
            regrouped
        }
        return MatchSetup(
            state = state,
            opponentVisibility = HandVisibility.forRule(
                rules.open,
                state.hands[CardColor.RED].orEmpty(),
                random,
            ),
            coinFlip = null,
            intro = introSteps(rules, rematch = true),
        )
    }

    /**
     * Assembles a match between two people, with a visibility for **each** side.
     *
     * ### Why [prepare] could not be reused
     *
     * It reports one `opponentVisibility`, and says why: "the reverse direction is not modelled —
     * the original always shows a player their own cards". That holds while one of the two sides is
     * a program the same process is running. With two people there are two audiences, each entitled
     * to a different half of the truth, and under Three Open they are **not mirror images**: the
     * rule reveals three cards drawn at random per hand, so blue may be showing slots 0, 2 and 4
     * while red is showing 1, 2 and 3.
     *
     * Deriving red's view from blue's would therefore be wrong rather than merely missing, and
     * wrong in the direction that leaks: whichever slots blue happened to reveal would be the ones
     * red was told about.
     *
     * ### What it does not do
     *
     * No `HandSource`, and so no `RULE_RANDOM` hand build. Where a person's five cards come from is
     * the caller's business — the server resolves each player's chosen deck, or draws from their
     * own collection, against the profile it holds. This takes the two hands as settled and applies
     * only what is *joint*: the swap, the elements, the turn order and the two visibilities.
     *
     * @param first who starts. Not a coin flip: the server decides, so that neither client can roll
     *   until it likes the answer. [CoinFlip] is still what the screens animate — it is handed the
     *   settled winner rather than tossing its own.
     */
    fun prepareVersus(
        blueHand: List<Card>,
        redHand: List<Card>,
        first: CardColor,
        rules: GameRules = GameRules(),
        random: Random = Random.Default,
    ): PvpSetup {
        val (blue, red) = if (rules.swap) swap(blueHand, redHand, random) else blueHand to redHand

        return PvpSetup(
            state = MatchState.start(
                blueHand = blue,
                redHand = red,
                first = first,
                rules = rules,
                elements = elementsFor(rules, random),
            ),
            // Two draws, not one applied twice. See the KDoc above.
            blueSeesRed = HandVisibility.forRule(rules.open, red, random),
            redSeesBlue = HandVisibility.forRule(rules.open, blue, random),
            intro = introSteps(rules),
        )
    }
}

/**
 * A match between two people, ready to play.
 *
 * The two-audience counterpart of [MatchSetup]. It carries no `coinFlip` because there was no toss:
 * the server settled the order, and a screen that wants to animate one builds a [CoinFlip] from the
 * winner it is told.
 *
 * @property blueSeesRed what blue may see of red's hand.
 * @property redSeesBlue what red may see of blue's hand. Independent of [blueSeesRed] — under Three
 *   Open the two are separate draws.
 */
data class PvpSetup(
    val state: MatchState,
    val blueSeesRed: HandVisibility,
    val redSeesBlue: HandVisibility,
    val intro: List<MatchIntroStep>,
) {
    /** What [side] may see of the other hand — the argument [MatchView.of] wants. */
    fun visibilityFor(side: CardColor): HandVisibility =
        if (side == CardColor.BLUE) blueSeesRed else redSeesBlue

    /** [state] as [side] sees it. */
    fun viewFor(side: CardColor, random: Random = Random.Default): MatchView =
        MatchView.of(state, side, visibilityFor(side), random)
}
