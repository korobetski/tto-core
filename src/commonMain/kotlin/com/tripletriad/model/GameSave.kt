package com.tripletriad.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.jsonPrimitive

/** Win/loss record. `Save.DATAS.STATS` (`Save.as:32`). */
@Serializable
data class Stats(
    @SerialName("WINS") val wins: Int = 0,
    @SerialName("DEFEATS") val defeats: Int = 0,
    @SerialName("DRAWS") val draws: Int = 0,
) {
    val played: Int get() = wins + defeats + draws

    /** Wins as a fraction of matches finished, 0f when none have been. */
    val winRate: Float get() = if (played == 0) 0f else wins.toFloat() / played

    fun recording(outcome: MatchOutcome): Stats = when (outcome) {
        is MatchOutcome.Win -> copy(wins = wins + 1)
        is MatchOutcome.Draw -> copy(draws = draws + 1)
        // A sudden-death draw is not an outcome yet — the rematch decides it — so it counts as
        // nothing. `PVEMatchScreen.as:175` dispatches the rematch instead of touching STATS.
        is MatchOutcome.SuddenDeath -> this
    }

    /**
     * Records a loss. Separate from [recording] because [MatchOutcome.Win] carries the winner and
     * only the caller knows which side the profile was on.
     */
    fun recordingDefeat(): Stats = copy(defeats = defeats + 1)

    /**
     * Records [result] from the profile's point of view — the counter each `endGame` branch bumps
     * (`PVEMatchScreen.as:71`, `:100`, `:141`).
     *
     * [recording] and [recordingDefeat] take an outcome and cannot tell a win from a loss without
     * knowing which side the profile played; this takes the already-resolved result, which is what
     * a caller holding a [MatchResult] has. Both are kept: the outcome pair is what a PvP screen
     * has, and the sudden-death case has to be unrepresentable in this one.
     */
    fun recordingStats(result: MatchResult): Stats = when (result) {
        MatchResult.WIN -> copy(wins = wins + 1)
        MatchResult.DRAW -> copy(draws = draws + 1)
        MatchResult.LOSE -> copy(defeats = defeats + 1)
    }
}

/**
 * The three multipliers a potion can raise. `Save.DATAS.BOONS` (`Save.as:34`).
 *
 * [luck] is in the save file and in nothing else: no potion grants it ([PotionType] has no LUCK
 * member) and no rule reads it. Carried so a profile round-trips, and no further.
 */
@Serializable
data class Boons(
    @SerialName("MGP") val mgp: Int = 0,
    @SerialName("XP") val xp: Int = 0,
    @SerialName("LUCK") val luck: Int = 0,
) {
    /** Applies a potion's effect. `PotionItem.modifier` is the only producer. */
    fun raised(modifier: BoonModifier): Boons = when (modifier.type) {
        BoonType.XP -> copy(xp = xp + modifier.value)
        BoonType.MGP -> copy(mgp = mgp + modifier.value)
    }

    /**
     * Consumes one boon of [type] — `Game.PROFILE_DATAS.BOONS.MGP -= 1` (`PVEMatchScreen.as:76`).
     *
     * So a boon is a **count of boosted matches**, not a permanent multiplier: a small MGP potion
     * ([PotionType.SMALL_MGP], value 2) buys two matches paying 20% more. Clamped at zero, because
     * the AS3 stores these as `uint` arithmetic on an untyped object and a negative count has no
     * meaning either way.
     */
    fun spending(type: BoonType): Boons = when (type) {
        BoonType.XP -> copy(xp = (xp - 1).coerceAtLeast(0))
        BoonType.MGP -> copy(mgp = (mgp - 1).coerceAtLeast(0))
    }
}

/**
 * A saved five-card deck. `Save.DATAS.DECKS` (`Save.as:31`), whose comment caps the *number* of
 * decks at five — see [GameSave.MAX_DECKS], which is where this port departs from that and why.
 *
 * The card list is **not** validated to five here: the AS3 deck builder allows a partial deck to be
 * saved and `DeckSelector` refuses to start a match with one. Validating at construction would make
 * a legitimately half-built deck unloadable.
 */
@Serializable
data class Deck(
    @SerialName("name") val name: String,
    @SerialName("cards") val cards: List<Int> = emptyList(),
) {
    val isComplete: Boolean get() = cards.size == HAND_SIZE

    /** The same deck with no cards in it. Keeps the name, which is what the slot is known by. */
    fun emptied(): Deck = copy(cards = emptyList())

    /**
     * The same deck with [cardId] added, or unchanged when it is already full.
     *
     * ### Why adding appends instead of filling a chosen slot
     *
     * The AS3 editor is slot-addressed: five fixed `Card` clips, tap a card then tap the slot to
     * put it in, and `saveDeck_Handler` pushes `0` for each clip left empty — so `DECKS[n].cards`
     * is always five long with zeroes standing in for holes, and `DeckSelector` counts the non-zero
     * entries to decide whether the deck may be played.
     *
     * A hole is not worth representing. The **only** thing a card's position in a deck decides is
     * the play order under `RULE_ORDER`, and a hand dealt from a list with zeroes in it would be
     * short. So the list here holds cards and nothing else, [isComplete] is a size check, and the
     * editor offers add and remove rather than five addressable slots. A player who wants a
     * different order removes and re-adds, which is the same number of taps as the original's
     * tap-card-then-tap-slot.
     *
     * A duplicate is allowed through **here** and bounded elsewhere. It used to be unbounded, on
     * the grounds that nothing in the original prevents the same card appearing twice in a deck
     * either; that reasoning held only while a card could not be owned twice. It can now, so the
     * rule is "no more copies than are owned" and it lives in [isAffordable] — this method has no
     * profile to ask. See `docs/migration/20-CARD-COPIES-AND-PLATFORM-ACCOUNTS.md` § 1.
     *
     * The looser statement is still true of a *hand*, which is a different thing: `RULE_SWAP` can
     * produce a genuine duplicate mid-match whatever the deck was built from.
     *
     * [DeckLimits]' star-rank caps are bounded elsewhere for the same reason and in the same place:
     * this method has no card table to read a rank out of, so the question is [isLegal]'s.
     */
    fun plusCard(cardId: Int): Deck =
        if (cards.size >= HAND_SIZE) this else copy(cards = cards + cardId)

    /** How many times this deck names [cardId]. */
    fun copiesUsed(cardId: Int): Int = cards.count { it == cardId }

    /**
     * True when this deck names no card more times than [owned] holds copies of it.
     *
     * Multiset containment, and the reason it is a function on [Deck] rather than a check in the
     * editor: the server has to be able to ask the same question of a submitted deck, and a rule
     * enforced only by the screen that builds decks is a rule enforced only by the client. See
     * [com.tripletriad.protocol.TranscriptVerifier].
     *
     * **There is no budget across decks.** Two saved decks may each name the single copy owned;
     * decks are not simultaneous, and a global budget would make saving one depend on the contents
     * of the other seven.
     *
     * @param owned card id to copies held — [GameSave.cards].
     */
    fun isAffordable(owned: Map<Int, Int>): Boolean =
        cards.groupingBy { it }.eachCount().all { (id, used) -> used <= (owned[id] ?: 0) }

    /**
     * True when this deck breaks none of [DeckLimits]' star-rank caps.
     *
     * The second half of "is this deck legal", and separate from [isAffordable] because the two ask
     * different sources: affordability is a question about the *profile*, legality is a question
     * about the *card table*. A caller that has both asks both — `PveMatches.playableDecks` and
     * `TranscriptVerifier` are the two that must.
     *
     * A deck stored before the caps existed can answer false without ever having been edited, which
     * is the same live condition [isAffordable] describes: it is refused where it is dealt and
     * repaired in the editor, never rewritten underneath the player.
     *
     * @param cards id to card — `CardCatalog.byId`. Ids it does not resolve are not counted; see
     *   [DeckLimits.tally].
     */
    fun isLegal(cards: Map<Int, Card>): Boolean = DeckLimits.isLegal(this.cards, cards)

    /** The same deck without the card at position [at]. Unchanged if there is none. */
    fun minusCardAt(at: Int): Deck =
        if (at !in cards.indices) this else copy(cards = cards.filterIndexed { i, _ -> i != at })
}

/**
 * A whole profile — one `.sav` file.
 *
 * `Save.DATAS` as `setToDefaultValues()` builds it (`Save.as:21-48`), with the AS3 `SCREAMING_CASE`
 * keys kept as `@SerialName`s — **plus a named list of fields this port added**, below. Those names
 * are load-bearing in the same way [com.tripletriad.settings.UserSettings]' snake_case ones are:
 * they are what is on disk, and renaming a field silently would orphan the data behind it. Every
 * field has a default, so a save written by an older build still loads.
 *
 * ### Fields the AS3 never had
 *
 * This used to say "field-for-field", and it was true until daily quests. The list exists so the
 * next person adding one knows they are not the first, and so that "is this in the original?" has
 * an answer that is not a search through `Save.as`.
 *
 * - **`QUESTS`** — [quests], the day's daily quests and their progress. Server-owned; see
 *   [withServerOwnedFrom].
 *
 * ### What is *not* a field
 *
 * - **`STATS.FORFEITS`** is derived, not stored: `Save.load()` overwrites whatever the file said
 *   with `STARTED_MATCHES - ENDED_MATCHES` on every load (`Save.as:59`), so the stored value never
 *   survives a round trip. It is [forfeits] here.
 * - **`NPC_W_TOTAL`**, which `Achievements.as` reads off `Game.PROFILE_DATAS`, is nowhere in
 *   `Save.DATAS` — it is computed elsewhere from `NPC_W`. It is [npcWinsTotal] here.
 * - **`LEVEL` and `RANK` are stored but redundant**: both are pure functions of the XP fields
 *   ([XpTable]). They are kept as fields because they are in the file, and [sane] recomputes them
 *   on load so a hand-edited or stale value cannot disagree with the XP it claims to be.
 *
 * ### Types that differ from the migration plan
 *
 * `docs/migration/13-DATA-MODELS.md` types `achievements` as `Map<String, Boolean>`. It is a
 * **timestamp**: `Achievements.check()` writes `ACHIEVEMENTS[id] = new Date().getTime()`
 * (`Achievements.as:79`), which is what lets the UI say when one was earned. Modelled as
 * `Map<String, Long>`.
 *
 * The same document types card ids as `UInt`. They are `Int` here, matching [Card.id] — see the
 * note there; `UInt` buys a range no card comes near and costs interoperability with every list
 * API.
 */
// Twenty-one small readers and copiers on the one type the whole game is *about*. The threshold is
// there to catch a class doing too many jobs; this one does a single job — be the profile — and
// every function is two or three lines that keep a `copy(…)` out of a call site. Suppressed here
// rather than by raising the repo-wide threshold, so nothing else inherits the exemption.
@Suppress("TooManyFunctions")
@Serializable
data class GameSave(
    @SerialName("USERNAME") val username: String = DEFAULT_USERNAME,
    @SerialName("CREATION_DATE") val creationDate: Long = 0L,
    @SerialName("LAST_SAVE") val lastSave: Long = 0L,
    @SerialName("SAVE_NUMBER") val saveNumber: Int = 0,
    /** `0` or `1`. An AS3 `uint` used as a flag; kept as written. */
    @SerialName("ADMIN") val admin: Int = 0,
    /**
     * Card id to **how many copies** are owned. Empty on a profile that has not opened a starter
     * box yet — see [new].
     *
     * A map and not a list of ids, which is what the AS3 stored and what this port carried until
     * `docs/migration/20-CARD-COPIES-AND-PLATFORM-ACCOUNTS.md` § 1 — a card can be owned several
     * times, and a deck may not use more copies than are held ([Deck.isAffordable]).
     *
     * A count per card rather than a repeated id, because **two copies are indistinguishable**.
     * [Card] is a value; nothing on it identifies an instance, and `captured()` returns a copy with
     * the owner flipped and no identity of its own. Repeating the id would invent an identity
     * nothing has a use for and make every read collapse it again.
     *
     * Counts are always positive — [sane] drops the rest, because a zero-copy entry is the same
     * fact as no entry and holding both is how the two come to disagree.
     *
     * Reads the AS3's bare `[1, 3, 6]` as well as its own `{"1": 1, "3": 1}` — see
     * [CardCopiesSerializer]. Written only in the latter form.
     */
    @SerialName("CARDS")
    @Serializable(with = CardCopiesSerializer::class)
    val cards: Map<Int, Int> = emptyMap(),
    @SerialName("DECKS")
    val decks: List<Deck> = emptyList(),
    @SerialName("STATS") val stats: Stats = Stats(),
    /** The inventory. `Save.as:33` — a list of `Item.__toJSON()` objects. */
    @SerialName("BAG") val bag: List<Item> = emptyList(),
    @SerialName("BOONS") val boons: Boons = Boons(),
    @SerialName("MGP") val mgp: Int = STARTING_MGP,
    @SerialName("XP") val xp: Long = 0L,
    @SerialName("LEVEL") val level: Int = 1,
    @SerialName("PVP_XP") val pvpXp: Long = 0L,
    @SerialName("RANK") val rank: Int = 1,
    @SerialName("AVATAR_ID") val avatarId: String = DEFAULT_AVATAR,
    @SerialName("STARTED_MATCHES") val startedMatches: Int = 0,
    @SerialName("ENDED_MATCHES") val endedMatches: Int = 0,
    @SerialName("PVE_MATCHES") val pveMatches: Int = 0,
    @SerialName("PVP_MATCHES") val pvpMatches: Int = 0,
    /** Achievement id to the epoch-millis instant it was earned. */
    @SerialName("ACHIEVEMENTS") val achievements: Map<String, Long> = emptyMap(),
    /**
     * The current UTC day's quests and their progress. Not an AS3 field — see the header.
     *
     * Reset by [com.tripletriad.data.DailyQuestRepository] on the first credit of a new day, and
     * **owned by the server**: [withServerOwnedFrom] is what stops a client asserting its own.
     */
    @SerialName("QUESTS") val quests: DailyQuests = DailyQuests(),
    /**
     * Wins per NPC, keyed by the NPC's **`iconID`** — `'jonas'`, `'tt-master'`. `NPC_W`.
     *
     * Not by `id`, however much that reads like the natural key: `PVEMatchScreen.as:110` writes
     * `NPC_W[this._NPC.iconID]`, and every other site does the same. That turns out to matter
     * rather than being an eccentricity, because **NPC ids are not unique**: the ff8 table declares
     * `id:2` twice (Chocoboy and UFO) and `id:13` twice, so keying by id would silently merge two
     * opponents' records. Icon ids are unique across both tables.
     */
    @SerialName("NPC_W") val npcWins: Map<String, Int> = emptyMap(),
    /**
     * Rule constant to wins with it active. `RULES_W`, read by the Wheel-of-Fortune achievements.
     */
    @SerialName("RULES_W") val rulesWins: Map<String, Int> = emptyMap(),
    /**
     * The tournament under way, or null when there is none — which is the ordinary state.
     *
     * Not an AS3 field: the original's ladders held their position in a screen and lost it on the
     * way out. **Owned by the server** ([withServerOwnedFrom]), because an open run is what waives
     * the opponents' own stakes and multiplies their drops — see [CampaignRun].
     */
    @SerialName("CAMPAIGN_RUN") val campaignRun: CampaignRun? = null,
    /**
     * Tournaments won, keyed by `Campaign.key`, counting how often each was climbed to the top.
     *
     * The durable half of [campaignRun], which is cleared the moment a run resolves and so cannot
     * answer "has this player ever finished the Card Club" — the question a ladder gated behind
     * another one has to ask. Keyed like [npcWins] and for the same reason: the key is the thing's
     * stable identity, not its position in a list.
     *
     * **Owned by the server**: it is what unlocks ladders and opponents.
     */
    @SerialName("CAMPAIGN_W") val campaignWins: Map<String, Int> = emptyMap(),
    /**
     * The last UTC day each tournament was entered on, as `YYYY-MM-DD`.
     *
     * One entry per ladder per day, which is what makes the fee a decision rather than a grind: a
     * run that ends badly ends the day's attempt at *that* ladder, and the others stay open. Keyed
     * by `Campaign.key` and holding a **day** rather than a count, so nobody banks yesterday's
     * unused entries.
     *
     * The same key and the same 00:00 UTC boundary as [quests] — see [questDayOf]. **Owned by the
     * server**, because a client writing this one would be choosing when tomorrow starts.
     */
    @SerialName("CAMPAIGN_DAY") val campaignEntries: Map<String, String> = emptyMap(),
) {
    /**
     * Matches begun and abandoned. `Save.as:59`, and the reason `STATS.FORFEITS` is not stored.
     *
     * `@Transient` is valid here — unlike on the body properties `docs/migration/13-DATA-MODELS.md`
     * warned about — because this is a computed property with no backing field, so there is nothing
     * for the serializer to write in the first place. The annotation is redundant and omitted.
     */
    val forfeits: Int get() = (startedMatches - endedMatches).coerceAtLeast(0)

    /**
     * Total NPC defeats, which `Achievements.as` reads as `NPC_W_TOTAL`.
     *
     * The original computes the same sum in `PVEMatchScreen.as:169-172` and then **assigns it back
     * onto `PROFILE_DATAS`**, so `JSON.stringify(DATAS)` writes a `NPC_W_TOTAL` key into the file
     * even though `setToDefaultValues()` never declares one. Derived data in a save file: it is
     * ignored on load here (`ignoreUnknownKeys`) and not written back, so it disappears the first
     * time a legacy-shaped profile is re-saved.
     */
    val npcWinsTotal: Int get() = npcWins.values.sum()

    /** True once the profile has earned [id]. */
    fun hasAchievement(id: String): Boolean = achievements.containsKey(id)

    /** How many copies of [cardId] the profile holds, zero when it holds none. */
    fun copiesOf(cardId: Int): Int = cards[cardId] ?: 0

    /**
     * True once the profile owns at least one [cardId].
     *
     * Kept alongside [copiesOf] rather than folded into it: a dozen call sites want the boolean,
     * and nothing is gained by making each of them write the comparison.
     */
    fun ownsCard(cardId: Int): Boolean = copiesOf(cardId) > 0

    /** The collection as one card per copy, ascending — what a hand may be drawn from. */
    fun ownedCardIds(): List<Int> =
        cards.entries.sortedBy { it.key }.flatMap { (id, copies) -> List(copies) { id } }

    /**
     * The same profile with derived fields brought back in line with their sources, and volumes of
     * nonsense clamped.
     *
     * The counterpart of [com.tripletriad.settings.UserSettings.sane], and for the same reason: a
     * `.sav` is user-writable and outlives the build that wrote it. [SaveRepository] applies this
     * on every load and before every write, so an inconsistent profile cannot reach the game and
     * cannot be created by it.
     */
    fun sane(): GameSave = copy(
        level = XpTable.levelFor(xp.coerceAtLeast(0)),
        rank = XpTable.rankFor(pvpXp.coerceAtLeast(0)),
        xp = xp.coerceAtLeast(0),
        pvpXp = pvpXp.coerceAtLeast(0),
        mgp = mgp.coerceAtLeast(0),
        // Positive ids held a positive number of times. This used to read `.distinct().sorted()`,
        // so that the collection screen could not show a card twice; a card owned twice is now a
        // fact rather than a corruption, and the screen shows it as a count.
        cards = cards.filterKeys { it > 0 }.filterValues { it > 0 },
        // Empty stacks are not items. The AS3 leaves them in the bag and draws a "0".
        bag = bag.filter { it.stack > 0 },
        decks = decks.take(MAX_DECKS),
    )

    /**
     * Records that a match has begun: `STARTED_MATCHES` up, and the per-mode counter with it.
     *
     * Kept on the model rather than in the repository because the two counters must move together —
     * that is what makes [forfeits] mean anything.
     */
    fun startingMatch(againstNpc: Boolean): GameSave = copy(
        startedMatches = startedMatches + 1,
        pveMatches = if (againstNpc) pveMatches + 1 else pveMatches,
        pvpMatches = if (againstNpc) pvpMatches else pvpMatches + 1,
    )

    /** Records that a match has finished, whatever its result. */
    fun endingMatch(): GameSave = copy(endedMatches = endedMatches + 1)

    /** Adds [amount] MGP, or removes it. Never goes below zero — the shop checks affordability. */
    fun withMgp(amount: Int): GameSave = copy(mgp = (mgp + amount).coerceAtLeast(0))

    /** Adds PvE experience and recomputes [level]. */
    fun withXp(amount: Long): GameSave =
        copy(xp = xp + amount).let { it.copy(level = XpTable.levelFor(it.xp)) }

    /** Adds PvP experience and recomputes [rank]. */
    fun withPvpXp(amount: Long): GameSave =
        copy(pvpXp = pvpXp + amount).let { it.copy(rank = XpTable.rankFor(it.pvpXp)) }

    /**
     * Adds [copies] of [cardId] to the collection, stacking onto what is already held.
     *
     * This used to return the profile unchanged when the card was already owned, on the grounds
     * that cards were a set. They are a multiset now, so a second copy is kept — which is what
     * gives a duplicate any value at all. See `ItemUse.PackOpened` and § 1 of
     * `docs/migration/20-CARD-COPIES-AND-PLATFORM-ACCOUNTS.md`.
     */
    fun withCard(cardId: Int, copies: Int = 1): GameSave {
        require(copies > 0) { "copies must be positive, was $copies" }
        return copy(cards = cards + (cardId to copiesOf(cardId) + copies))
    }

    /**
     * How many copies of [cardId] are **not** promised to a deck.
     *
     * What a player may safely part with. Selling is the caller — see the collection browser — and
     * without this it would be possible to sell the copy a saved deck is built around, leaving a
     * deck [Deck.isAffordable] refuses and a match the server will not accept. The player would
     * meet that as a rejection at the point of play, which is the worst place to learn it.
     *
     * The **maximum** over decks rather than the sum: five decks each fielding one copy of a card
     * need one copy between them, not five. A deck listing the same card twice needs two, which is
     * why the count is per deck rather than a membership test.
     */
    fun spareCopiesOf(cardId: Int): Int {
        val reserved = decks.maxOfOrNull { deck -> deck.cards.count { it == cardId } } ?: 0
        return (copiesOf(cardId) - reserved).coerceAtLeast(0)
    }

    /**
     * Removes [copies] of [cardId], dropping the entry entirely when the last one goes.
     *
     * The inverse of [withCard], and the only thing in this game that takes a card **away** from a
     * player. It exists for the player-versus-player card wager — the classic Triple Triad stake,
     * and the one irreversible thing that can happen to a collection.
     *
     * Dropping the key rather than leaving a zero is not tidiness. `cards` is read as a multiset by
     * [copiesOf], [ownsCard] and [ownedCardIds], and a `0` entry would answer "owned" to anything
     * that asked with `containsKey` — which is the mistake a reader is most likely to make. No
     * state makes a zero meaningful, so none is stored.
     *
     * **The decks are deliberately left alone.** A deck that named the last copy is now
     * unaffordable, and the answer is to refuse the deck rather than to edit it: pruning the card
     * out here would silently rewrite something the player built, as a side effect of losing a
     * match, and hand them back a four-card deck they never made. Leaving it means the deck is
     * refused with its five cards intact and repairs itself the moment another copy is obtained.
     *
     * **That only works if every lookup actually refuses it**, and for a long time one did not.
     * `PveMatch.playableDecks` has always filtered on [Deck.isAffordable] — "a stored deck can
     * become unaffordable without being edited" — but `PveMatch.playerDeck`, which is what the
     * *referee* deals a player-versus-player match from, checked [Deck.isComplete] alone. So the
     * loser of a card wager kept fielding the card they had just lost. The two lookups agree now;
     * if a third is ever added, it is this promise it has to keep.
     *
     * Floors at zero rather than throwing. Whether a wager was legal is the server's to check
     * before the match starts; a credit path that threw halfway through would leave a match half
     * settled, which is worse than a subtraction that cannot go negative.
     */
    fun withoutCard(cardId: Int, copies: Int = 1): GameSave {
        require(copies > 0) { "copies must be positive, was $copies" }
        val left = copiesOf(cardId) - copies
        return copy(cards = if (left > 0) cards + (cardId to left) else cards - cardId)
    }

    /**
     * Records a win against an NPC, for the Triple Team achievements.
     *
     * @param npcIconId the NPC's [Npc.iconId] — the key `NPC_W` actually uses. See [npcWins].
     */
    fun withNpcWin(npcIconId: String): GameSave =
        copy(npcWins = npcWins + (npcIconId to (npcWins[npcIconId] ?: 0) + 1))

    /** The run under way in [campaignKey], or null — including when the run is a different one. */
    fun runIn(campaignKey: String): CampaignRun? =
        campaignRun?.takeIf { it.campaignKey == campaignKey }

    /**
     * True once [campaignKey] has been entered on [day], which is what closes it until tomorrow.
     *
     * Asked of the day rather than of the run, because the two answer different questions: a run
     * that ended in defeat five minutes ago is gone, and the entry it spent is not.
     */
    fun hasEnteredToday(campaignKey: String, day: String): Boolean =
        campaignEntries[campaignKey] == day

    /**
     * Opens [run] and spends the day's entry for its ladder, in one step.
     *
     * The two are deliberately not separable. Stamping the day at *entry* rather than at resolution
     * is the whole of the limit: a first-round defeat has to cost the attempt, and settling-time
     * bookkeeping would only ever bite the players who won.
     */
    fun enteringCampaign(run: CampaignRun, day: String): GameSave = copy(
        campaignRun = run,
        campaignEntries = campaignEntries + (run.campaignKey to day),
    )

    /** Closes the run, whatever became of it. A defeat and a completion both end here. */
    fun leavingCampaign(): GameSave = copy(campaignRun = null)

    /** Records a ladder climbed to the top, and closes the run that climbed it. */
    fun withCampaignWin(campaignKey: String): GameSave = copy(
        campaignRun = null,
        campaignWins = campaignWins + (campaignKey to (campaignWins[campaignKey] ?: 0) + 1),
    )

    /**
     * Records a win with each of [rules]' active rules, for the Wheel-of-Fortune achievements.
     *
     * The keys are the AS3 rule constants (`RULE_ROULETTE`, …) because that is what
     * `Achievements.as:52-57` looks up. [GameRules.activeRuleKeys] is the single place that mapping
     * lives.
     */
    fun withRulesWin(rules: GameRules): GameSave {
        val updated = rulesWins.toMutableMap()
        for (key in rules.activeRuleKeys()) {
            updated[key] = (updated[key] ?: 0) + 1
        }
        return copy(rulesWins = updated)
    }

    /**
     * The same profile with deck slot [index] replaced by [deck].
     *
     * Slots below [index] that do not exist yet are filled with **unnamed empty decks**, because
     * `DECKS` is a fixed five-slot array on screen and a sparse one in the data: the AS3 assigns
     * `DECKS[selectedIndex] = deck` directly (`DecksScreen.as:385`), which on an AS3 array leaves
     * `undefined` holes that `JSON.stringify` writes as `null` and `if (_userDecks[i])` then reads
     * back as "empty deck". A `List<Deck>` cannot hold that hole, and making the list nullable to
     * model it would push the null onto every reader; an empty deck is what the screen draws for a
     * hole anyway, and [Deck.isComplete] keeps it out of every match either way.
     *
     * The name is left blank rather than defaulted to `"Deck 2"`: that label is `STR_DECK` plus a
     * number, and a model that reached into the locale bundles would put the player's language into
     * their save file.
     *
     * @throws IllegalArgumentException if [index] is not a slot — a screen offers exactly
     *   [MAX_DECKS] of them, so an out-of-range index is a programming error and not a user action.
     */
    fun withDeck(index: Int, deck: Deck): GameSave {
        require(index in 0 until MAX_DECKS) { "deck slot must be in 0..<$MAX_DECKS, was $index" }
        val slots = decks.toMutableList()
        while (slots.size <= index) slots += Deck(name = "", cards = emptyList())
        slots[index] = deck
        return copy(decks = slots)
    }

    /**
     * Empties deck slot [index], keeping the slot and its name.
     *
     * `resetDeckHandler` (`DecksScreen.as:342-362`) is the one place the original is not merely
     * awkward but **does not work**: it pushes five zeroes onto the deck's existing card list — so
     * a full deck becomes ten entries rather than none — and then calls
     * `Game.PROFILE_DATAS.DECKS.slice(index, 1)`, which returns a copy and mutates nothing, where
     * `splice` was meant. It then saves. The visual list is rebuilt empty, the file is not, and the
     * deck reappears on the next load.
     *
     * Emptying the cards and keeping the slot is what the button visibly claims to do.
     */
    fun clearingDeck(index: Int): GameSave {
        if (index !in decks.indices) return this
        return copy(
            decks = decks.mapIndexed { at, deck -> if (at == index) deck.emptied() else deck },
        )
    }

    /** Marks [id] earned at [instant], keeping the first time if it was earned already. */
    fun withAchievement(id: String, instant: Long): GameSave =
        if (hasAchievement(id)) this else copy(achievements = achievements + (id to instant))

    /** This profile with [updated] as its daily quests. */
    fun withQuests(updated: DailyQuests): GameSave = copy(quests = updated)

    /** Whether today's [questId] has already paid out. */
    fun hasCompletedQuest(questId: String): Boolean = quests.isCompleted(questId)

    /**
     * This profile with every **server-owned** field taken from [stored] instead of from here.
     *
     * The list of such fields, stated once. A profile arriving from a client is taken at its word
     * for everything else, because those are things the player really did decide and a server that
     * recomputed them would be a server that has to model every screen. What it must not take on
     * trust is anything a *match* established, and each field below is exactly that.
     *
     * ### This list only grows
     *
     * It is the ratchet by which the client stops being believed. Each entry moved here is a way of
     * forging progression closed for good, and the intended end state is that a profile arriving
     * from a client carries **nothing monnayable** — see the plan's § 1.1. Nothing is ever removed
     * from this list; a field that turns out to belong to the client was put here by mistake.
     *
     * - **`quests`** — a daily quest pays on the strength of matches the server replayed, so a
     *   client asserting one would be paying itself.
     * - **`achievements`** — awarded by `AchievementRepository.credit`, which only ever runs inside
     *   `MatchRewards.credit` and `creditPvp`. The client reads them and never writes one.
     * - **`stats`** — wins, defeats and draws. Written by the same two functions, off the server's
     *   own replay of the score. Note this is not `startedMatches`/`endedMatches`, which the client
     *   does move when a match begins and is abandoned, and which stay believed.
     * - **`mgp`** — the purse. Every way it moves is now an intent the server carries out: the
     *   shop, the resale counter, a ladder's entry fee, a match's payout.
     * - **`bag`** and **`boons`** — filled by buying, emptied by using, selling and discarding, all
     *   of which are intents.
     * - **`xp`, `pvpXp`, `level`, `rank`** — written only by `MatchRewards`, off a replay. `level`
     *   and `rank` are pure functions of the XP fields anyway, so believing them separately was
     *   always a way for two numbers to disagree.
     *
     * - **`cards`** — the collection, and the most valuable forgery there was. It closed last,
     *   because it was the one field with a legitimate client-side writer left: `StarterPack`
     *   repairs a profile that has sold everything and can no longer field a deck, and it lived in
     *   the client module where no server could run it. Taking `cards` before moving it would have
     *   made that repair silently do nothing — a worse failure than the forgery, and the reason the
     *   order mattered.
     *
     * ### What stays believed, and always will
     *
     * **`decks`** — arranging five cards you already own is the player's to decide, and a deck
     * that breaks a rule is refused where it matters: `Deck.isAffordable` and `Deck.isLegal`, at
     * the point a match is dealt. Refusing to *store* one instead would mean a client that saves
     * decks could be told its profile is invalid over a slot it is not even about to play.
     * **`avatarId`** and the rest of the presentation likewise. The test is not
     * "could a client change this" but "is it worth anything to anybody else".
     *
     * **`startedMatches`/`endedMatches`** — the client moves these when a match begins and when it
     * is abandoned, and only the client knows. Their difference is the forfeit count, which costs
     * nothing to overstate.
     *
     * Applied silently rather than as a refusal: an honest client sends the whole profile back
     * including the fields it was last told about, so rejecting the request would break the
     * ordinary case to punish nobody.
     */
    fun withServerOwnedFrom(stored: GameSave): GameSave = copy(
        quests = stored.quests,
        achievements = stored.achievements,
        campaignRun = stored.campaignRun,
        campaignWins = stored.campaignWins,
        campaignEntries = stored.campaignEntries,
        stats = stored.stats,
        mgp = stored.mgp,
        bag = stored.bag,
        boons = stored.boons,
        xp = stored.xp,
        pvpXp = stored.pvpXp,
        level = stored.level,
        rank = stored.rank,
        cards = stored.cards,
    )

    companion object {
        /** `Save.as:27`. A moogle name, and the original's default. */
        const val DEFAULT_USERNAME = "Kuplu Kopo"

        /** `Save.as:40`. */
        const val DEFAULT_AVATAR = "ffxiv_twi03005"

        /** `Save.as:31`. */
        const val DEFAULT_DECK_NAME = "Starter deck"

        /**
         * How many deck slots a profile has. **Eight, where `Save.as:31` caps it at five.**
         *
         * ### The deviation, and why it is one
         *
         * The original's five is a number in a comment beside a five-element array literal, not a
         * rule the game enforces anywhere else: nothing in `Save.as`, `DecksScreen.as` or the match
         * code cares how many slots there are, and no rule, achievement or price is derived from
         * the count. It is a capacity, and capacities are the one kind of constant a port is
         * entitled to revisit — unlike, say, `HAND_SIZE`, which five other things are arithmetic
         * over.
         *
         * Eight because the client now ships three formats with different admitted pools, and five
         * slots stopped being enough to keep one deck per format plus the ones a player is actually
         * building. That is a product decision; it is written here rather than in the UI because
         * the model is what enforces it — see [withDeck] and [sane].
         *
         * ### What raising it costs, and what it does not
         *
         * - **Existing saves are unaffected.** [sane] *truncates* to this number and never pads, so
         *   a five-deck save stays a five-deck save and simply gains three empty slots on screen.
         * - **A server on an older `core` is unaffected**, which is not obvious and was checked:
         *   `tto-server` stores the profile as verbatim JSON and never calls [sane], and a chosen
         *   deck slot is resolved with `decks.getOrNull(slot)` in `PveMatch.playerDeck` with no
         *   bound of its own. So an eight-deck save round-trips, and slot 6 resolves, against a
         *   server that still thinks the cap is five.
         * - **Downgrading loses decks.** A client built against a `core` where this is 5 will run
         *   [sane] over an eight-deck save and drop slots 6 to 8. That is the one direction with a
         *   cost, and it is the usual cost of a capacity going up.
         */
        const val MAX_DECKS = 8

        /** `Save.as:35`. */
        const val STARTING_MGP = 100

        /**
         * A brand-new profile, **holding nothing**.
         *
         * `setToDefaultValues()` (`Save.as:21-48`), with the two timestamps injected rather than
         * read from a clock: `commonMain` has no `System.currentTimeMillis`, and a model whose
         * defaults read the wall clock cannot be tested — the same reasoning
         * `docs/migration/13-DATA-MODELS.md` applies to `GameState`.
         *
         * ### Why it deals no cards, where `Save.as:30` dealt five
         *
         * Because those five were a lie the moment a player could choose a box. They were numbers
         * read through `MODE` — the first, third, sixth, seventh and tenth card of *whichever*
         * table was selected — and global card ids turned them into five cards of block 1, FFXIV,
         * for everybody. A character registered on a server was created from this function and
         * then never granted anything else: `StarterPack.opened` ran on the client's copy and
         * `withServerOwnedFrom` took `cards` straight back off it. Choosing the FFVIII box and
         * walking into the first match with five FFXIV monsters is what that looked like.
         *
         * So there is no floor any more, and `StarterPack.isOwedBy` is true of a profile this
         * function made — which is exactly what it should say about a character that has not yet
         * been dealt a box. Every creation path opens one: `SaveRepository.create` locally,
         * `POST /me/starter` with the chosen `starterId` on an account.
         */
        fun new(
            username: String = DEFAULT_USERNAME,
            createdAt: Long,
        ): GameSave = GameSave(
            username = username,
            creationDate = createdAt,
            lastSave = createdAt,
        )
    }
}

/**
 * Reads [GameSave.cards] from either shape: the AS3's array of ids, or this build's id-to-copies
 * object.
 *
 * ### Why the old shape is still read
 *
 * Because [GameSave]'s own contract says so — *"a save written by an older build still loads"* —
 * and `CARDS` is the one field whose **shape** changed rather than its contents. Every other field
 * gained a default and kept its type; this one went from `[1, 3, 6]` to `{"1": 1, "3": 1}` when a
 * card became something you could own twice, and without this a profile written before that would
 * fail to parse rather than load with one copy of everything.
 *
 * `docs/migration/20-CARD-COPIES-AND-PLATFORM-ACCOUNTS.md` § 1 argues the change is free because it
 * rides document 19's card reset, which voids every stored card id anyway. That is true of the
 * release and not of this branch, and it would in any case be a poor reason to break a file format
 * a test asserts. The cost is fifteen lines that can be deleted the day the reset lands.
 *
 * **A repeated id in the legacy form counts as a copy.** The AS3 never wrote one — `Save.as` stores
 * each id once — but `[7, 7]` has exactly one sensible reading now and refusing it would be a
 * parse failure over a file that says what it means.
 *
 * Writing is always the object form: there is one shape on disk going forward, and a serializer
 * that emitted whichever form happened to round-trip would make the file's shape depend on its
 * history.
 */
object CardCopiesSerializer :
    JsonTransformingSerializer<Map<Int, Int>>(
        MapSerializer(Int.serializer(), Int.serializer()),
    ) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        if (element !is JsonArray) return element
        val counts = mutableMapOf<String, Int>()
        for (id in element) {
            val key = id.jsonPrimitive.content
            counts[key] = (counts[key] ?: 0) + 1
        }
        return JsonObject(counts.mapValues { (_, copies) -> JsonPrimitive(copies) })
    }
}
