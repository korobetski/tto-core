package com.tripletriad.model

/**
 * How many top-rank cards one deck may name, and how many times it may name one card.
 *
 * ### Why a deck-building rule exists at all
 *
 * Nothing in the AS3 original caps a deck by rarity — `DecksScreen.as` lets any five owned cards
 * sit in a slot, and the only figure it shows is the sum of their rarities, which it calls the
 * deck's "power". That is a *display* of the problem rather than a rule about it: the strongest
 * deck a collection can express is always its five best cards, so a player who has them fields
 * them and every match after that is decided by the collection rather than by the board.
 *
 * The cap is the correction, and it is deliberately shaped like FFXIV's own: **two cards of four
 * stars or more, of which at most one five-star**, everything below uncapped, and no card twice.
 * It leaves at least three of the five slots to cards a new account already owns, which is what
 * makes a starter collection a deck rather than a placeholder, and it does it without a per-card
 * ban list that would have to be maintained as sets ship.
 *
 * This build's reading replaces an earlier, looser one — one five-star **and** two four-stars,
 * copies allowed — that let a deck hold three top cards. A deck saved under it may now be over the
 * budget; see [admits] for why the editor still lets such a deck be repaired.
 *
 * ### What it is not applied to
 *
 * - **Opponent hands.** An NPC's five cards are authored data (`NpcCatalog`), not a deck somebody
 *   built out of a collection, and an opponent that is *meant* to open with three aces is a
 *   difficulty knob. Applying this there would silently rewrite every shipped catalog.
 * - **`RULE_SWAP`.** Swap trades one card between the two hands mid-match, so a hand can end up
 *   holding two five-stars — the opponent's and its own. That is the rule taking a card *out* of
 *   the player's hands rather than a deck the player built, it is the only way to field more than
 *   the caps allow, and it stays that way on purpose: a swap that refused to hand over an ace
 *   would be a rule that reads differently depending on what the other side owns.
 *
 * `RULE_RANDOM` is **not** in that list, though it deals a hand too. It draws from the whole
 * collection rather than from a deck, so exempting it would have made *selling* the lever the caps
 * took away: empty the collection of everything but five-stars and the random draw is forced into
 * the deck the editor refuses to build. [MatchPreparation.randomHand] therefore draws under the
 * caps, and leads with the top cards the collection can field ([strongestLegalHand]): a hand one
 * rule deals should not be weaker at the top than the deck the player could have brought.
 *
 * ### Where it is enforced
 *
 * Beside [Deck.isAffordable], and for the identical reason: the client greys the picks that would
 * break it so the player never builds an illegal deck, and the server asks the same question again
 * of the deck it is about to deal ([com.tripletriad.data.PveMatches.playerDeck]) and of a
 * transcript's declared deck ([com.tripletriad.protocol.TranscriptVerifier]). A rule enforced only
 * by the screen that builds decks is a rule enforced only by the client.
 */
object DeckLimits {
    /** A deck may name one five-star. `Card.rarity` 5. */
    const val MAX_FIVE_STARS: Int = 1

    /**
     * A deck may name two cards of four stars **or more**, the five-star included.
     *
     * One budget and not a cap per rank: FFXIV allows "one five-star, or two four-stars", and a
     * five-star spends one of the two slots a pair of four-stars would have taken. Capped per rank,
     * a deck could hold 5 + 4 + 4 — three cards at the top, which the game never allows.
     */
    const val MAX_FOUR_STARS_OR_MORE: Int = 2

    /**
     * The lowest rank each cap counts, to how many cards at **that rank or above** a deck may hold.
     *
     * Keyed by floor rather than by exact rank because the caps nest: the four-star budget counts
     * the five-star too, so `{5: 1, 4: 2}` reads "at most one card of five stars, at most two of
     * four or more". A rank below every floor is uncapped — see [limitOf]. Iterated in descending
     * rank order by everything that displays it, so it is built that way.
     */
    val MAX_BY_RARITY: Map<Int, Int> = mapOf(
        FIVE_STARS to MAX_FIVE_STARS,
        FOUR_STARS to MAX_FOUR_STARS_OR_MORE,
    )

    /**
     * How many copies of one card a deck may name.
     *
     * FFXIV's rule, and not the AS3 original's, which let a deck name a card as often as the
     * collection held it. A collection still holds copies — they are what the shop, the bag and the
     * auction trade — but a second copy in a deck was the one way to field a card twice as strong
     * as the caps meant, one rank at a time.
     */
    const val MAX_COPIES: Int = 1

    /** How many cards at [rarity] or above a deck may name; [HAND_SIZE] if no cap starts there. */
    fun limitOf(rarity: Int): Int = MAX_BY_RARITY[rarity] ?: HAND_SIZE

    /**
     * How many cards [cardIds] names at or above each cap's floor.
     *
     * Every cap is present, zero included: this is what the deck editor draws as `0 / 1`, and a
     * counter that appears only once it is non-zero is a rule the player meets by breaking it.
     *
     * An id [cards] does not resolve is not counted. It is not this object's refusal to make —
     * `PveMatches.playableDecks` drops a deck naming a card outside the format and `assemble`
     * throws on one naming no card at all — and guessing a rank for it would turn one failure
     * into a different, wronger one.
     */
    fun tally(cardIds: List<Int>, cards: Map<Int, Card>): Map<Int, Int> =
        tally(cardIds.mapNotNull { cards[it] })

    /**
     * How many cards [hand] holds at or above each cap's floor.
     *
     * The id-free half of the pair, for the callers that already hold the cards themselves — a
     * hand being dealt ([MatchPreparation.randomHand]) never had ids to resolve.
     */
    fun tally(hand: List<Card>): Map<Int, Int> =
        MAX_BY_RARITY.mapValues { (floor, _) -> hand.count { it.rarity >= floor } }

    /**
     * The caps [cardIds] breaks, as each cap's floor to the count it holds. Empty is within them.
     *
     * A second five-star breaks its own cap without breaking the budget it also spends: `5, 5`
     * reports `{5: 2}`, and `5, 5, 4` reports `{5: 2, 4: 3}`.
     */
    fun overLimit(cardIds: List<Int>, cards: Map<Int, Card>): Map<Int, Int> =
        tally(cardIds, cards).filter { (floor, used) -> used > limitOf(floor) }

    /** The ids [cardIds] names more than [MAX_COPIES] times, to how many times it names them. */
    fun repeated(cardIds: List<Int>): Map<Int, Int> =
        cardIds.groupingBy { it }.eachCount().filter { (_, used) -> used > MAX_COPIES }

    /** True when [cardIds] breaks no cap and names no card twice. */
    fun isLegal(cardIds: List<Int>, cards: Map<Int, Card>): Boolean =
        overLimit(cardIds, cards).isEmpty() && repeated(cardIds).isEmpty()

    /**
     * True when [card] may be added to a deck that already holds [cardIds].
     *
     * What the deck editor dims a pick on. Deliberately asks about the *addition* rather than
     * re-checking the whole deck afterwards, so that a deck which is already over a cap — one
     * built before this rule existed — does not answer "no" to every card including the ranks it
     * has room for.
     */
    fun admits(cardIds: List<Int>, cards: Map<Int, Card>, card: Card): Boolean =
        card.id !in cardIds && admits(cardIds.mapNotNull { cards[it] }, card)

    /** True when [card] may be added to [hand]. The id-free half of the pair. */
    fun admits(hand: List<Card>, card: Card): Boolean =
        hand.none { it.id == card.id } &&
            MAX_BY_RARITY.all { (floor, limit) ->
                card.rarity < floor || hand.count { it.rarity >= floor } < limit
            }

    /**
     * The first [HAND_SIZE] of [cardIds] that break no cap, in the order given.
     *
     * The fallback path's answer, not a deck builder: `PveMatches.playerDeck` hands back five owned
     * cards to a profile with no usable deck, and five cards the server would then refuse is a
     * worse answer than a weaker five. Greedy in the order it is given because that order is the
     * caller's — [GameSave.ownedCardIds] is ascending by id — and re-sorting here would make
     * which five a profile falls back to depend on this function rather than on the collection.
     *
     * Shorter than [HAND_SIZE] when the caps cannot be met, which the caller already has to handle:
     * a profile owning fewer than five distinct cards is the case that shape exists for.
     */
    fun firstLegalHand(cardIds: List<Int>, cards: Map<Int, Card>): List<Int> =
        firstLegalHand(cardIds.mapNotNull { cards[it] }).map { it.id }

    /**
     * The first [HAND_SIZE] of [hand] that break no cap, in the order given.
     *
     * The id-free half of the pair.
     *
     * Greedy, and it is worth naming that greedy is exact here rather than an approximation. Every
     * limit is only ever *refused*, never required, and the caps nest — a card that fits the
     * five-star cap is counted by the four-star budget as well, never by a budget the other does
     * not see — so passing over a card can never make a later card unusable. Running the whole
     * list and taking what fits therefore comes up short only when the collection genuinely cannot
     * field [HAND_SIZE] cards under the caps. That makes a short answer a feasibility verdict the
     * caller can act on, not a heuristic that gave up.
     */
    fun firstLegalHand(hand: List<Card>): List<Card> {
        val taken = mutableListOf<Card>()
        for (card in hand) {
            // One condition rather than two early exits: the list is a collection, not a stream,
            // so running to the end of it once it is full costs a comparison per remaining card.
            if (taken.size < HAND_SIZE && admits(taken, card)) {
                taken += card
            }
        }
        return taken
    }

    /**
     * A legal hand from [order] holding as many four-stars-or-more as the caps allow, the rest
     * taken in order, and everything returned in the order [order] gave it.
     *
     * What `RULE_RANDOM` deals from a shuffled collection. [firstLegalHand] alone was legal but
     * mean: a shuffle leading with five low cards dealt no four-star at all to a player whose deck
     * holds two, and Random became a rule a strong collection lost to. Taking the top cards first
     * — whichever the shuffle offers first, so *which* ones stays random — makes a Random hand as
     * strong at the top as a deck the player could have built, and no stronger.
     *
     * Exact for the same reason [firstLegalHand] is: the high cards are chosen first under the same
     * caps, and a low card is never refused on their account.
     */
    fun strongestLegalHand(order: List<Card>): List<Card> {
        val top = firstLegalHand(order.filter { it.rarity >= FOUR_STARS })
        val chosen = firstLegalHand(top + order).mapTo(mutableSetOf()) { it.id }
        return order.distinctBy { it.id }.filter { it.id in chosen }
    }

    /**
     * `Card.rarity` of a five-star. Named so the map above reads as a rule, not as arithmetic.
     */
    private const val FIVE_STARS: Int = 5

    /** `Card.rarity` of a four-star. */
    private const val FOUR_STARS: Int = 4
}
