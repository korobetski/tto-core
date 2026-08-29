package com.tripletriad.protocol

import com.tripletriad.model.GameSave
import kotlinx.serialization.Serializable

/**
 * The levels at which the two ways of moving value *between* accounts open.
 *
 * ### Why these two and nothing else
 *
 * They are the only places a card or a purse crosses from one account to another: a refereed PvP
 * match settles a stake and a rating, and the auction house sells one player's card to another.
 * Everything else a level could gate — a format, an opponent, a booster — is a player spending
 * their own time on their own profile, and gating that would be difficulty, not defence.
 *
 * ### What this is actually for
 *
 * Not for stopping one person holding two accounts. It cannot: an address is free, a second one is
 * free, and nothing here inspects who is at the keyboard. It makes a second account **cost
 * something** — the levels have to be played twice — and that is the whole of what a threshold can
 * buy. The measures that catch what it lets through are elsewhere and are after the fact: the
 * server keeps every [VerifiedMatch], and a pair of accounts whose PvP history is mostly each other
 * is a shape a query can find.
 *
 * ### Why it is on the wire rather than a constant
 *
 * Because the number will be tuned, and a constant compiled into both ends turns a tuning into a
 * coordinated release: the server would start refusing at eight while every client still drew
 * "unlocks at level 5" on the door. So the *rule* is defined once, here, and the **numbers travel**
 * — a deployment sends its own in [ServerInfo], and a client renders what it was told. The defaults
 * are what a deployment that says nothing means.
 *
 * A client evaluating this is a courtesy to the player, never a check: it exists so the lobby can
 * say "not yet" instead of offering a door that answers with a refusal. The server runs the same
 * two functions against its own copy, and that run is the one that counts — the same division of
 * labour as [Credentials.looksValid].
 *
 * @property multiplayer the level at which refereed play against another person opens.
 * @property auction the level at which buying and selling between players opens.
 */
@Serializable
data class Unlocks(
    val multiplayer: Int = DEFAULT_MULTIPLAYER,
    val auction: Int = DEFAULT_AUCTION,
) {
    fun allowsMultiplayer(save: GameSave): Boolean = save.level >= multiplayer

    fun allowsAuction(save: GameSave): Boolean = save.level >= auction

    companion object {
        /**
         * Five, which is roughly an evening.
         *
         * Low enough that nobody arriving to play the game meets a wall, high enough that farming
         * accounts stops being free. Both numbers are the same today and are separate fields
         * anyway: they gate different things and there is no reason they must move together.
         */
        const val DEFAULT_MULTIPLAYER: Int = 5

        const val DEFAULT_AUCTION: Int = 5
    }
}
