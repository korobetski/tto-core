package com.tripletriad.data

import com.tripletriad.model.Deck
import com.tripletriad.model.GameSave
import com.tripletriad.model.HAND_SIZE

/**
 * Granting a character the box it opens with, and repairing one that never got a usable box.
 *
 * ### What the box is
 *
 * [StarterCatalog] — `starters.json`, ten cards, document 19's replacement for the AS3's hard-coded
 * five. The composition rule and the reasoning behind it are documented there; this type is the two
 * places the grant happens.
 *
 * ### Why the same object answers both questions
 *
 * [opened] deals the box to a profile that is being made. It cannot help the ones already stored on
 * a server, which is what [isOwedBy] and [grantedTo] are for: the shop offers the pack back to any
 * character short of a playable hand, so a broken account repairs itself without a migration and
 * without support. One rule — *fewer than five distinct cards* — asked in both places, rather than
 * a screen-local guess in each.
 *
 * ### The choice is a real one now
 *
 * `MODE` is gone, so opening the FFXIV box no longer confines a player to FFXIV: the shop, the
 * opponents and the campaign are questions of *format*, asked at the match. A starter is what a
 * character is dealt on its first day and nothing more, which is what document 19 asked for.
 *
 * ### What is not document 19 yet
 *
 * **The grant is the client's.** Document 19 § The server grants it, not the client puts it on the
 * server: the client would send `starterId` and never the cards, and the server would resolve it
 * against its own copy of `starters.json`. That needs the `NewCharacter` endpoint of document 18,
 * which is *proposed* and does not exist — today the server creates the character at registration.
 * Until then the client grants, and this comment is the marker for where that moves.
 */
object StarterPack {
    /** How many cards a character needs before it can field a deck at all. */
    const val SIZE: Int = HAND_SIZE

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
     * [save] holding exactly [starter]'s cards, with its deck in the first slot.
     *
     * The one place a starter is *written* onto a profile, and therefore the one place the shape of
     * that write is decided: one copy of each card, and the authored five as the opening deck.
     * Both creation paths go through it, which is what stops a character created locally and a
     * character created by registering from being dealt different boxes — they were, until this
     * existed: `GameSave.new` still seeds the AS3's five, and only the account path had been moved
     * onto the catalogue.
     *
     * A **replacement** and not a top-up, unlike [grantedTo], and only sound because of where it is
     * called from: the starter is chosen once, at creation, before a single match — so the cards
     * being dropped are the defaults dealt a moment ago and nothing else.
     */
    fun opened(save: GameSave, starter: Starter): GameSave = save.copy(
        cards = starter.cards.associateWith { 1 },
        decks = listOf(Deck(GameSave.DEFAULT_DECK_NAME, starter.deck)),
    )

    /**
     * [save] with the starter pack in it, or unchanged when it was not owed one.
     *
     * Additive: a character that owns three fieldable cards keeps them and is topped up, because
     * the two it is missing are the whole of what is wrong with it. Copies already held are not
     * doubled — this is a repair, not a reward.
     *
     * The authored deck is prepended when none of the saved decks can be fielded, which is always
     * the case here: a profile owed this pack owns fewer than five playable cards, so no complete
     * deck can be affordable. The list is trimmed to [GameSave.MAX_DECKS], so a character already
     * holding five named decks loses the last of them — decks it demonstrably cannot play, on the
     * one path that exists to make it playable again.
     *
     * Unchanged when nothing is authored at all, which [StarterCatalog.violations] refuses at
     * authoring time. A shipped catalogue with no starter in it is a content bug, and giving
     * nothing is the honest outcome rather than inventing five ids.
     */
    // ReturnCount: two guards and the result. Both guards say "there is nothing to do", and
    // folding them into one `if` would nest the whole body inside it for no gain.
    @Suppress("ReturnCount")
    fun grantedTo(save: GameSave, catalog: StarterCatalog): GameSave {
        if (!isOwedBy(save)) return save
        // The first authored starter, and not a released one: [StarterCatalog.released] needs the
        // card sets to know what is released, and this has no way to be handed them. A profile owed
        // this pack owns almost nothing, so there is no set of its own to prefer either — and there
        // is no `MODE` left to ask. Repairing with the first box is a decision, not a guess.
        val starter = catalog.starters.firstOrNull() ?: return save
        val granted = starter.cards.fold(save) { profile, id ->
            if (profile.ownsCard(id)) profile else profile.withCard(id)
        }
        return granted.copy(
            decks = (listOf(Deck(GameSave.DEFAULT_DECK_NAME, starter.deck)) + granted.decks)
                .take(GameSave.MAX_DECKS),
        )
    }
}
