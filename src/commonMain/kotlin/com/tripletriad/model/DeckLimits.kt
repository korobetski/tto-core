package com.tripletriad.model

/**
 * How many cards of each star rank one deck may name.
 *
 * ### Why a deck-building rule exists at all
 *
 * Nothing in the AS3 original caps a deck by rarity — `DecksScreen.as` lets any five owned cards
 * sit in a slot, and the only figure it shows is the sum of their rarities, which it calls the
 * deck's "power". That is a *display* of the problem rather than a rule about it: the strongest
 * deck a collection can express is always its five best cards, so a player who has them fields
 * them and every match after that is decided by the collection rather than by the board.
 *
 * The cap is the correction, and it is deliberately shaped like FFXIV's own: **one five-star and
 * two four-stars**, everything below uncapped. It leaves at least two of the five slots to cards a
 * new account already owns, which is what makes a starter collection a deck rather than a
 * placeholder, and it does it without a per-card ban list that would have to be maintained as sets
 * ship.
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
 * caps — it does not *guarantee* an ace, it only guarantees the hand it deals is one the player
 * could have built.
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

    /** A deck may name two four-stars. `Card.rarity` 4. */
    const val MAX_FOUR_STARS: Int = 2

    /**
     * Rank to how many of it one deck may hold, **capped ranks only**.
     *
     * A rank absent from this map is uncapped, which is [HAND_SIZE] in practice — see [limitOf].
     * Iterated in descending rank order by everything that displays it, so it is built that way.
     */
    val MAX_BY_RARITY: Map<Int, Int> = mapOf(
        FIVE_STARS to MAX_FIVE_STARS,
        FOUR_STARS to MAX_FOUR_STARS,
    )

    /** How many cards of [rarity] a deck may name. [HAND_SIZE] when the rank is uncapped. */
    fun limitOf(rarity: Int): Int = MAX_BY_RARITY[rarity] ?: HAND_SIZE

    /**
     * How many cards of each **capped** rank [cardIds] names.
     *
     * Every capped rank is present, zero included: this is what the deck editor draws as `0 / 1`,
     * and a counter that appears only once it is non-zero is a rule the player meets by breaking
     * it.
     *
     * An id [cards] does not resolve is not counted. It is not this object's refusal to make —
     * `PveMatches.playableDecks` drops a deck naming a card outside the format and `assemble`
     * throws on one naming no card at all — and guessing a rank for it would turn one failure
     * into a different, wronger one.
     */
    fun tally(cardIds: List<Int>, cards: Map<Int, Card>): Map<Int, Int> =
        tally(cardIds.mapNotNull { cards[it] })

    /**
     * How many cards of each **capped** rank [hand] holds.
     *
     * The id-free half of the pair, for the callers that already hold the cards themselves — a
     * hand being dealt ([MatchPreparation.randomHand]) never had ids to resolve.
     */
    fun tally(hand: List<Card>): Map<Int, Int> {
        val counted = hand.groupingBy { it.rarity }.eachCount()
        return MAX_BY_RARITY.mapValues { (rarity, _) -> counted[rarity] ?: 0 }
    }

    /**
     * The capped ranks [cardIds] names too many of, as rank to the count it holds. Empty is legal.
     */
    fun overLimit(cardIds: List<Int>, cards: Map<Int, Card>): Map<Int, Int> =
        tally(cardIds, cards).filter { (rarity, used) -> used > limitOf(rarity) }

    /** True when [cardIds] breaks no rank cap. */
    fun isLegal(cardIds: List<Int>, cards: Map<Int, Card>): Boolean =
        overLimit(cardIds, cards).isEmpty()

    /**
     * True when [card] may be added to a deck that already holds [cardIds].
     *
     * What the deck editor dims a pick on. Deliberately asks about the *addition* rather than
     * re-checking the whole deck afterwards, so that a deck which is already over a cap — one
     * built before this rule existed — does not answer "no" to every card including the ranks it
     * has room for.
     */
    fun admits(cardIds: List<Int>, cards: Map<Int, Card>, card: Card): Boolean =
        admits(cardIds.mapNotNull { cards[it] }, card)

    /** True when [card] may be added to [hand]. The id-free half of the pair. */
    fun admits(hand: List<Card>, card: Card): Boolean =
        tally(hand).getOrElse(card.rarity) { 0 } < limitOf(card.rarity)

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
     * a profile owning fewer than five cards is the case that shape exists for.
     */
    fun firstLegalHand(cardIds: List<Int>, cards: Map<Int, Card>): List<Int> =
        firstLegalHand(cardIds.mapNotNull { cards[it] }).map { it.id }

    /**
     * The first [HAND_SIZE] of [hand] that break no cap, in the order given.
     *
     * The id-free half of the pair, and what `RULE_RANDOM` deals: given a shuffled collection it
     * takes the first five cards the caps admit, which is the same draw as before for every
     * collection whose top five were already legal and a legal draw for the rest.
     *
     * Greedy, and it is worth naming that greedy is exact here rather than an approximation. A
     * capped rank is only ever *refused*, never required, so passing over a card can never make a
     * later card unusable — running the whole list and taking what fits therefore comes up short
     * only when the collection genuinely cannot field [HAND_SIZE] cards under the caps. That makes
     * a short answer a feasibility verdict the caller can act on, not a heuristic that gave up.
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
     * `Card.rarity` of a five-star. Named so the map above reads as a rule, not as arithmetic.
     */
    private const val FIVE_STARS: Int = 5

    /** `Card.rarity` of a four-star. */
    private const val FOUR_STARS: Int = 4
}
