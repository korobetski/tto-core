package com.tripletriad.data

import com.tripletriad.model.Card
import com.tripletriad.model.Deck
import com.tripletriad.model.GameSave
import com.tripletriad.model.HAND_SIZE
import kotlin.random.Random

/**
 * Granting a character the box it opens with, and repairing one that never got a usable box.
 *
 * ### What the box is
 *
 * [StarterCatalog] — `starters.json`, nine cards, document 19's replacement for the AS3's
 * hard-coded five. The composition rule and the reasoning behind it are documented there; this type
 * is the two places the grant happens, and the one place the four unauthored cards are drawn.
 *
 * ### Why the same object answers both questions
 *
 * [opened] deals the box to a profile that is being made. It cannot help the ones already stored on
 * a server, which is what [isOwedBy] and [grantedTo] are for: the shop offers the pack back to any
 * character short of a playable hand, so a broken account repairs itself without a migration and
 * without support. One rule — *fewer than five distinct cards* — asked in both places, rather than
 * a screen-local guess in each.
 *
 * ### The grant is the server's, on an account
 *
 * It was not, and that was a bug a player could see: the client applied [opened] to its own copy
 * and pushed the profile, `GameSave.withServerOwnedFrom` took `cards` straight back off it, and a
 * character created by choosing the FFVIII box walked into its first match holding five FFXIV
 * cards — the floor `GameSave.new` used to seed. So `GameSave.new` deals **nothing** now, and the
 * choice reaches the server as `ClaimStarterRequest.starterId`, which it resolves against its own
 * copy of the catalogue. The client never sends a card id, exactly as it never sends a score.
 */
object StarterPack {
    /** How many cards a character needs before it can field a deck at all. */
    const val SIZE: Int = HAND_SIZE

    /** How many of the box's cards are drawn rather than authored. See [StarterCatalog]. */
    const val DRAWN: Int = 4

    /**
     * How many distinct cards [save] owns.
     *
     * Distinct, not copies: five copies of one card is not a deck, and [Deck.isAffordable] would
     * refuse the hand built from it.
     *
     * No longer filtered by set. A profile used to be confined to one table and a card outside it
     * was unfieldable; with `MODE` gone every card a player owns is theirs, and whether it may be
     * *played* is the format's question, asked at the match rather than here.
     */
    fun playableCards(save: GameSave): Int = save.cards.count { (_, copies) -> copies > 0 }

    /** Whether [save] is short of a playable hand and should be given the pack. */
    fun isOwedBy(save: GameSave): Boolean = playableCards(save) < SIZE

    /**
     * What [starter]'s four drawn cards may come out of, in id order.
     *
     * The block's **commons**, minus the authored five. Commons because the deck already carries
     * the one card the box is allowed to be proud of, and four more rares would make the draw worth
     * more than the choice; minus the five because a "draw" that hands back a card the player was
     * already given is a card the player did not get.
     *
     * Sorted rather than left in catalogue order, so the shuffle below is the only source of
     * variation and a draw is reproducible from its seed on both ends.
     */
    fun pool(starter: Starter, cards: Map<Int, Card>): List<Int> = cards.values
        .filter {
            it.block == starter.block &&
                it.rarity == StarterCatalog.COMMON_RARITY &&
                it.id !in starter.deck
        }
        .map { it.id }
        .sorted()

    /**
     * Four cards out of [pool], or fewer when the block cannot fill it.
     *
     * Fewer rather than a throw: `StarterCatalog.violations` refuses a block too thin to draw from
     * at authoring time, and a shipped catalogue that slipped past it should deal a small box
     * rather than fail to create a character.
     */
    fun drawn(starter: Starter, cards: Map<Int, Card>, random: Random): List<Int> =
        pool(starter, cards).shuffled(random).take(DRAWN)

    /** The nine ids [starter] deals: the authored five, then the draw. */
    fun contentsOf(starter: Starter, cards: Map<Int, Card>, random: Random): List<Int> =
        starter.deck + drawn(starter, cards, random)

    /**
     * [save] holding exactly [starter]'s nine cards, with its deck in the first slot.
     *
     * The one place a starter is *written* onto a profile, and therefore the one place the shape of
     * that write is decided: one copy of each card, and the authored five as the opening deck.
     * Both creation paths go through it, which is what stops a character created locally and a
     * character created by registering from being dealt different boxes — they were, and the way
     * they were is in this object's KDoc.
     *
     * A **replacement** and not a top-up, unlike [grantedTo], and only sound because of where it is
     * called from: the starter is chosen once, before a single match, on a profile that owns
     * nothing — `GameSave.new` deals no cards at all.
     */
    fun opened(save: GameSave, starter: Starter, cards: Map<Int, Card>, random: Random): GameSave =
        save.copy(
            cards = contentsOf(starter, cards, random).associateWith { 1 },
            decks = listOf(Deck(GameSave.DEFAULT_DECK_NAME, starter.deck)),
        )

    /**
     * [save] with a starter pack in it, or unchanged when it was not owed one.
     *
     * Additive: a character that owns three fieldable cards keeps them and is topped up, because
     * the two it is missing are the whole of what is wrong with it. Copies already held are not
     * doubled — this is a repair, not a reward — so a profile that already holds some of the box
     * ends up short of nine, which is the honest outcome: it was given what it lacked.
     *
     * The authored deck is prepended when none of the saved decks can be fielded, which is always
     * the case here: a profile owed this pack owns fewer than five playable cards, so no complete
     * deck can be affordable. The list is trimmed to [GameSave.MAX_DECKS], so a character already
     * holding a full set of named decks loses the last of them — decks it demonstrably cannot
     * play, on the one path that exists to make it playable again.
     *
     * @param starter the box the player chose, and the whole of why this takes one: a new account
     *   claims *its* starter here and would otherwise be handed the catalogue's first. Null is the
     *   shop's repair offer, which asks for nothing in particular.
     */
    // ReturnCount: two guards and the result. Both guards say "there is nothing to do", and
    // folding them into one `if` would nest the whole body inside it for no gain.
    @Suppress("ReturnCount")
    fun grantedTo(
        save: GameSave,
        catalog: StarterCatalog,
        cards: Map<Int, Card>,
        random: Random,
        starter: Starter? = null,
    ): GameSave {
        if (!isOwedBy(save)) return save
        // The first authored starter when the caller named none, and not a released one:
        // [StarterCatalog.released] needs the card sets to know what is released, and this has no
        // way to be handed them. A profile owed this pack owns almost nothing, so there is no set
        // of its own to prefer either. Repairing with the first box is a decision, not a guess.
        val box = starter ?: catalog.starters.firstOrNull() ?: return save
        val granted = contentsOf(box, cards, random).fold(save) { profile, id ->
            if (profile.ownsCard(id)) profile else profile.withCard(id)
        }
        return granted.copy(
            decks = (listOf(Deck(GameSave.DEFAULT_DECK_NAME, box.deck)) + granted.decks)
                .take(GameSave.MAX_DECKS),
        )
    }
}
