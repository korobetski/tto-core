package com.tripletriad.protocol

import com.tripletriad.model.AscensionTally
import com.tripletriad.model.Board
import com.tripletriad.model.Card
import com.tripletriad.model.CardColor
import com.tripletriad.model.CardType
import com.tripletriad.model.GameRules
import com.tripletriad.model.GameSave
import com.tripletriad.model.MatchResult
import com.tripletriad.model.MatchView
import com.tripletriad.model.PlacedCard
import com.tripletriad.model.PlayResult
import com.tripletriad.model.TradeRule
import com.tripletriad.model.TurnOrder
import kotlinx.serialization.Serializable

/**
 * Player versus player, over the server.
 *
 * ### Why the server is the referee and not a relay
 *
 * A PvE match is played entirely on the client, and the server checks it afterwards by replaying
 * the transcript. That works because the client already holds both hands — it is running the
 * opponent — so hiding one from itself is a courtesy, not a secret.
 *
 * Against a person it cannot work. If each client held both hands, "do not look" would be the only
 * thing protecting a player's cards, and a modified client would see everything with no trace in
 * any transcript. So the server holds the one `MatchState`, deals both hands, decides whose turn it
 * is, and sends each side a [PvpMatchView] containing **only what that side may see**. A client
 * cannot leak what it was never sent.
 *
 * That also settles who owns the dice. Under `OrderRule.CHAOS` the playable card is a roll; two
 * devices rolling separately would disagree and one player's move would be refused for no visible
 * reason. The roll happens once, on the server, and travels as [PvpMatchView.playable].
 *
 * ### Why the wire carries ids and not cards
 *
 * Both ends hold `cards.json`. Sending a whole [Card] — name key, four sides, type, rarity — for
 * every slot and every cell would put a catalogue on the wire many times per match to say what an
 * integer already says. `RewardSummary` sends achievement ids on exactly this argument.
 *
 * The client turns ids back into cards through its own catalogue and rebuilds a [MatchView]. If the
 * two catalogues ever disagree, that is a version problem and `AppVersion` is the thing that
 * refuses the connection — not something a fatter payload would have rescued.
 */

/**
 * A card in a cell — [BoardCell], under the name this file used when it was the only refereed
 * match there was.
 *
 * An alias rather than a rename, so that no caller and no payload moved. See `MatchWire.kt`.
 */
typealias PvpCell = BoardCell

/**
 * The placement that just happened — [Placement], under this file's older name.
 *
 * ### What it is not
 *
 * Not a move log. One placement, the most recent, exactly as [PvpMatchView] is one position rather
 * than a history — a client animating the last move is all this is for. The move *history* lives on
 * the server as the match's inputs and is what makes a settlement auditable; this is presentation.
 *
 * Note the environment match does **not** hold to that, and cannot: its server answers one request
 * with two placements, the player's and the opponent's reply. See `PveMatchView.plays`.
 */
typealias PvpPlay = Placement

/**
 * One side's view of a live match, as it travels.
 *
 * The wire form of [MatchView], and deliberately not [MatchView] itself: that type holds real
 * [Card]s and is what the *screen* wants, while this holds ids and is what the *network* wants.
 * Keeping them apart is what stops the model's shape from becoming a protocol that cannot be
 * changed without a version bump.
 *
 * @property cells the board, nine entries, `null` where empty.
 * @property elements the nine board elements, `null` where a tile has none. Only ever non-null
 *   under the Elemental rule.
 * @property hand this side's cards, by id, in slot order.
 * @property opponentHand the other side's slots: an id where the rules reveal it, `null` otherwise.
 *   The **length** is the opponent's real card count, which is public — a player can count the
 *   cards in front of them.
 * @property playable slots of [hand] that may be played now. Empty when it is not this side's turn,
 *   which is how a client knows the board is read-only.
 * @property deadline epoch millis by which this side must move, or null when it is not their turn.
 *   Sent rather than a remaining duration because a client's clock offset is a fixed error on an
 *   instant and a compounding one on a countdown restarted at every poll.
 * @property lastPlay the placement that produced this position, or null on a board nothing has been
 *   played on. Sent to **both** sides and identical for both: what a move flipped is public the
 *   moment it flips. See [PvpPlay] for why it cannot be recomputed from the boards.
 * @property rematch how many Sudden Death rematches have been played, 0 for the first board. A
 *   rematch resets [cells] and [placement], so without this a client cannot tell a fresh board from
 *   the first one and will not replay the opening for it — the same field `PveMatchView` carries,
 *   for the same reason. The rematch itself is *derived* on the server rather than stored; this is
 *   the count travelling, not a second source of truth.
 * @property opponentAvatarId which avatar the other player chose, or blank when the server has
 *   none to give. A board draws the opponent's face, and against a program that face is the NPC
 *   portrait the roster already showed; against a person it is this. It travels beside
 *   [opponentName] because it is the same fact — who you are looking at — and because the name
 *   alone is what left the multiplayer board unable to draw the chrome the solo board uses.
 *
 *   Public by construction: an avatar is what a player picked to be seen as, and the lobby already
 *   shows their name to anyone who lists the tables.
 */
@Serializable
data class PvpMatchView(
    val matchId: String,
    val side: CardColor,
    val opponentName: String,
    val opponentAvatarId: String = "",
    val rules: GameRules,
    val formatId: String,
    val cells: List<PvpCell?>,
    val elements: List<CardType?>,
    val hand: List<Int>,
    val opponentHand: List<Int?>,
    val first: CardColor,
    val placement: Int,
    val ascension: Map<CardType, Int> = emptyMap(),
    val playable: List<Int> = emptyList(),
    val deadline: Long? = null,
    val status: PvpMatchStatus = PvpMatchStatus.PLAYING,
    val stake: PvpStake = PvpStake.None,
    val outcome: PvpOutcome? = null,
    val lastPlay: PvpPlay? = null,
    val rematch: Int = 0,
) {
    /**
     * This view as the model type the screen renders, resolving ids through [cards].
     *
     * Returns null if any id is unknown, rather than dropping the card: a board with a hole in it
     * would be a match the player cannot reason about, and a client whose catalogue disagrees with
     * the server's has a version problem to report, not a frame to render.
     */
    fun toMatchView(cards: Map<Int, Card>): MatchView? {
        // Every id is checked before any is used, so the refusal is one decision taken once
        // rather than three `?: return null`s that each mean the same thing in a different place.
        val named = hand + opponentHand.filterNotNull() + cells.mapNotNull { it?.cardId } +
            listOfNotNull(lastPlay?.cardId)
        if (named.any { it !in cards }) return null

        // Stamped, not taken as they come. A catalogue card carries `owner = BLUE` as a default
        // rather than a fact, so a red player's own hand would arrive in the opponent's colour —
        // and `CardFace` fills from exactly this field. `MatchState.start` stamps its hands for the
        // same reason; this is that step for a hand that arrived as integers.
        val ownCards = hand.map { cards.getValue(it).copy(owner = side) }
        val theirCards = opponentHand.map { id ->
            id?.let { cards.getValue(it).copy(owner = side.opposite()) }
        }
        val board = Board(
            cells = cells.map { cell ->
                cell?.let { PlacedCard(cards.getValue(it.cardId), it.owner) }
            },
            elements = elements,
        )

        return MatchView(
            side = side,
            rules = rules,
            board = board,
            ownHand = ownCards,
            opponentHand = theirCards,
            order = TurnOrder(first),
            placement = placement,
            tally = AscensionTally(ascension),
            // Stamped with whoever played it, for the reason the hands above are: a catalogue card
            // carries `owner = BLUE` as a default, and `MatchState` stamps every hand with the side
            // holding it — so this is the card as it left that hand.
            lastPlay = lastPlay?.let { play ->
                PlayResult(
                    player = play.player,
                    card = cards.getValue(play.cardId).copy(owner = play.player),
                    position = play.position,
                    captures = play.captures,
                    handIndex = play.handIndex,
                )
            },
            playableHandIndices = playable,
        )
    }

    companion object {
        /** [view] projected onto the wire. The inverse of [toMatchView], up to the ids. */
        @Suppress("LongParameterList")
        fun of(
            view: MatchView,
            matchId: String,
            opponentName: String,
            formatId: String,
            opponentAvatarId: String = "",
            status: PvpMatchStatus = PvpMatchStatus.PLAYING,
            stake: PvpStake = PvpStake.None,
            deadline: Long? = null,
            outcome: PvpOutcome? = null,
            rematch: Int = 0,
        ): PvpMatchView = PvpMatchView(
            matchId = matchId,
            side = view.side,
            opponentName = opponentName,
            opponentAvatarId = opponentAvatarId,
            rules = view.rules,
            formatId = formatId,
            cells = view.board.cells.map { placed ->
                placed?.let { PvpCell(it.card.id, it.owner) }
            },
            elements = view.board.elements,
            hand = view.ownHand.map { it.id },
            opponentHand = view.opponentHand.map { it?.id },
            first = view.order.first,
            placement = view.placement,
            ascension = view.tally.counts,
            playable = view.playableHandIndices,
            deadline = deadline,
            status = status,
            stake = stake,
            outcome = outcome,
            rematch = rematch,
            lastPlay = view.lastPlay?.let { play ->
                PvpPlay(
                    player = play.player,
                    cardId = play.card.id,
                    position = play.position,
                    captures = play.captures,
                    handIndex = play.handIndex,
                )
            },
        )
    }
}

/**
 * Where a match is in its life.
 *
 * [ABANDONED] and [FORFEITED] are separate because they are different facts: one player walked away
 * from a match that had begun and lost it, versus a match that never started because nobody
 * arrived. Collapsing them would make a table that lapsed look like a defeat on the record.
 */
@Serializable
enum class PvpMatchStatus {
    /** Paired, both sides notified, waiting for the first move. */
    PLAYING,

    /**
     * The board is done and the winner still owes a choice.
     *
     * Only [TradeRule.ONE] and [TradeRule.DIFF] reach it: those are the two wagers where somebody
     * has to *name* the cards they are taking, and nothing can be credited until they do. Every
     * other ending goes straight to [FINISHED].
     *
     * A separate status rather than a flag on [FINISHED], because the difference is whether the
     * match has been paid — and a status that sometimes means paid and sometimes does not is the
     * one thing standing between a settlement and crediting it twice.
     */
    AWAITING_CLAIM,

    /** Nine cards placed, a sudden-death rematch decided it, and both sides have been paid. */
    FINISHED,

    /** One side ran out of time to come back. See [PvpOutcome.forfeitedBy]. */
    FORFEITED,

    /** Never began: the invitation lapsed, or the table was withdrawn. Nothing is credited. */
    ABANDONED,
}

/**
 * How a finished match ended, from the reader's own side.
 *
 * ### [blue] and [red] are the server's colours
 *
 * As is [forfeitedBy]. A client is free to draw a match from its own side — and this app's does,
 * mirroring a red player's board so they see themselves in blue like everybody else — but nothing
 * here is mirrored with it. A screen comparing [forfeitedBy] against a mirrored view's side would
 * tell half of all players the opposite of what happened, so the comparison must be made against
 * the side the server dealt. For a score to *show*, prefer `MatchView.score`, which is already
 * told from the reader's side.
 *
 * @property result this side's result. A sudden-death draw is not one — the rematch decides it —
 *   which is why `MatchResult.of` returns null there and why this field carries the *settled*
 *   answer only.
 * @property forfeitedBy who walked away, or null if the match was played out. Named rather than
 *   implied by the result, because "you won" and "you won because they left" are not the same
 *   sentence to show a player.
 * @property stakeMgp what the wager moved, signed from this side: positive won, negative paid. Kept
 *   apart from [mgp] — which is the flat payout every match earns — so a screen can say "100 MGP,
 *   and 50 more off them" rather than one number that hides the bet.
 * @property cardsWon the ids taken from the other side. Plural because [TradeRule.DIFF] and
 *   [TradeRule.ALL] take several, and because [TradeRule.DIRECT] can hand cards to **both** sides.
 * @property cardsLost the ids given up. Not the mirror of [cardsWon] under Direct, where each side
 *   may have a non-empty list of both.
 * @property picksOwed how many of the other side's cards this reader still has to name. Non-zero
 *   only while the status is [PvpMatchStatus.AWAITING_CLAIM], and only for the winner.
 * @property pickFrom the cards this claim is being made from — the loser's dealt hand. **Sent to
 *   both sides while a claim is outstanding**, and empty otherwise.
 *
 *   It is the one place this protocol puts a hand on the wire that it otherwise hides, and it goes
 *   to each side for a different reason. The winner cannot choose from cards they have not been
 *   shown. The loser is about to have cards taken out of their collection, and *which* is not a
 *   question the final board answers — the board says who owns what now, and nothing about what was
 *   dealt. Sending it to the winner alone left the loser watching a name and a countdown.
 *
 *   No hand is leaked by the second half: the loser's own is what the loser is shown. The list is
 *   identical for both readers, which is the honest shape — it describes the claim rather than the
 *   reader, and [picksOwed] is what still separates the two.
 * @property claimDeadline epoch millis by which the choice must be made, after which the server
 *   makes it. Sent as an instant for the reason [PvpMatchView.deadline] is.
 * @property achievementIds what this match unlocked, by id. `MatchRewards.creditPvp` has credited
 *   achievements since PvP was refereed — `RULES_W` counts a rule win whoever was on the other side
 *   of it — and none of them had anywhere to be announced, so a player unlocked them without being
 *   told. Ids rather than achievements, on the same argument `RewardSummary` makes: an unlock rule
 *   is data both ends hold, and putting the catalogue on the wire once per match says what one
 *   string already says.
 * @property questIds the daily quests this match finished, by id. Same argument, same catalogue.
 */
@Serializable
data class PvpOutcome(
    val result: MatchResult,
    val blue: Int,
    val red: Int,
    val forfeitedBy: CardColor? = null,
    val mgp: Int = 0,
    val xp: Int = 0,
    val stakeMgp: Int = 0,
    val cardsWon: List<Int> = emptyList(),
    val cardsLost: List<Int> = emptyList(),
    val picksOwed: Int = 0,
    val pickFrom: List<Int> = emptyList(),
    val claimDeadline: Long? = null,
    val achievementIds: List<String> = emptyList(),
    val questIds: List<String> = emptyList(),
)

/**
 * What is being played for: some MGP, some cards, or neither.
 *
 * ### Why the card half is a rule and not a pair of ids
 *
 * This was two named cards — one from each side, agreed before the match — and the argument for it
 * was that a wager should be evaluable: you can see what you are risking *and* what you stand to
 * win before you accept. That is true, and it is not how Triple Triad has ever worked.
 *
 * What the original does is put the whole hand at risk and settle it afterwards by a **rule**: the
 * winner takes one of the five, or as many as the margin, or all of them, or each side simply keeps
 * what it captured. Naming a card up front cannot express any of those, and the interesting half of
 * the wager — *which* card — is the decision it takes away.
 *
 * So the stake is a [TradeRule] and a number of MGP, and both may be nothing. The safety the sealed
 * pair bought is not lost: [TradeRule] is an enum, so a settlement `when` that forgets one is still
 * a compile error, and there is now only one place that has to exhaust it instead of two.
 *
 * A stake is proposed by whoever opens the table or sends the invitation, and accepted by joining
 * it. There is no counter-offer: two rounds of negotiation is a chat feature, and this is a card
 * game.
 *
 * @property mgp what each side puts up. The winner takes it; a draw returns it. Checked against
 *   both purses **before** the match, because there is no escrow — see `MatchRewards.creditPvp`.
 * @property trade how cards change hands, if they do.
 */
@Serializable
data class PvpStake(
    val mgp: Int = 0,
    val trade: TradeRule = TradeRule.NONE,
) {
    /** Nothing at stake but the flat payout every match pays. */
    val isFree: Boolean get() = mgp == 0 && trade == TradeRule.NONE

    companion object {
        /** The stake that risks nothing. What an unstated wager means. */
        val None: PvpStake = PvpStake()
    }
}

/**
 * How large a wager a deployment lets a player propose, and what counts as a large one.
 *
 * ### Why there is a ceiling at all
 *
 * There is **no escrow** on a PvP stake — `MatchRewards.creditPvp` settles it after the fact
 * against whatever the purse holds then. Both purses are checked before the match, so the wager is
 * covered when it is agreed, but "covered" is the only thing that check knows. A player with 40 000
 * MGP could open a table for all of it, and the two failures that follow are different and both
 * bad: somebody joins by mistake and loses an evening's play to one match, or somebody joins on
 * purpose and this is how a farmed account hands its balance to a main one in a single game.
 *
 * A cap that grows with [GameSave.level] answers both, because it is the one quantity an account
 * cannot buy: the ceiling has to be *played* up to, and a fresh account cannot receive more than a
 * fresh account is allowed to wager. It is the same argument [Unlocks] makes — a level is what
 * makes a second account cost something — applied to the amount rather than to the door.
 *
 * ### Why it is on the wire
 *
 * For the reason [Unlocks] and [AuctionPolicy] are: the number will be tuned, and a constant
 * compiled into both ends turns a tuning into a coordinated release. A client draws what it is
 * told, and refuses to send what it knows will be refused; the server runs [allows] against its own
 * copy, and that run is the one that counts.
 *
 * **Only [allows] is enforced.** [isHeavy] is a question about the *reader* — is this stake a large
 * share of the purse I am about to risk — which has no server-side meaning, since the answer
 * differs for every player looking at the same table. It lives here so the threshold is stated once
 * and tuned with the rest.
 *
 * @property perLevel MGP of ceiling earned per level. The ceiling itself is [perLevel] × level.
 * @property heavyPercent the share of a player's own purse at which a stake is worth warning them
 *   about before they sit down.
 */
@Serializable
data class PvpStakePolicy(
    val perLevel: Int = DEFAULT_PER_LEVEL,
    val heavyPercent: Int = DEFAULT_HEAVY_PERCENT,
) {
    /** The largest wager [save] may propose or accept. */
    fun ceilingFor(save: GameSave): Int = perLevel * save.level

    /**
     * Whether [save] may be a party to [stake].
     *
     * Both ends of the range matter. The ceiling is the point of this class; the floor is that a
     * **negative** wager settles backwards — `PvpMatchRow.spoils` pays the winner `stake.mgp` — so
     * a table opened for -5 000 MGP is a table you win by losing. Nothing else refuses it: the
     * affordability check asks whether the purse is *at least* the stake, which every negative
     * number passes.
     */
    fun allows(save: GameSave, stake: PvpStake): Boolean =
        stake.mgp >= 0 && stake.mgp <= ceilingFor(save)

    /**
     * Whether [mgp] is a large enough share of [purse] to be worth a second tap.
     *
     * In hundredths and against a `Long`, because the multiplication is the whole purse and the
     * comparison has to hold at the top of the range. An empty purse makes every non-zero wager
     * heavy, which is right: it is the one a player can least afford.
     */
    fun isHeavy(mgp: Int, purse: Int): Boolean =
        mgp > 0 && mgp.toLong() * PERCENT >= purse.toLong() * heavyPercent

    companion object {
        /**
         * A hundred a level: one match's winning payout, `MatchRewards.PVP_WIN_MGP`.
         *
         * So the ceiling is "as much as this account has been paid for winning, roughly" — 500
         * at the level multiplayer opens at, 2 200 at the top of the ladder. High enough
         * that a serious wager between two established players is not clipped, low enough
         * that no single match can move a fortune.
         */
        const val DEFAULT_PER_LEVEL: Int = 100

        /** A quarter of what you hold. Below that a loss stings; at that it hurts. */
        const val DEFAULT_HEAVY_PERCENT: Int = 25

        private const val PERCENT = 100L
    }
}

/** A player being told a match is now open for them, and which one. */
@Serializable
data class PvpQueueState(
    val waiting: Boolean,
    val since: Long? = null,
    val matchId: String? = null,
)

/**
 * An invitation to a named player.
 *
 * @property expiresAt when it lapses. An invitation with no expiry is a notification that never
 *   goes away, and both sides need to know when the offer stops standing rather than discovering
 *   it on a refusal.
 */
@Serializable
data class PvpChallenge(
    val id: String,
    val fromName: String,
    val toName: String,
    val expiresAt: Long,
    /**
     * What is being proposed — the same four things a table states.
     *
     * An invitation used to carry a wager and nothing else, so a directed match always played the
     * default format under whatever the roulette drew. That made the two ways into a match
     * unequal for no reason a player could see: you could name your terms to strangers and not to
     * a friend. [PvpTableRequest] is reused rather than restated so the two paths cannot drift —
     * and so the server validates both through one function.
     */
    val terms: PvpTableRequest = PvpTableRequest(formatId = ""),
    val matchId: String? = null,
) {
    /** What the match will be played for. */
    val stake: PvpStake get() = terms.stake
}

/** One placement, as a client asks for it. */
@Serializable
data class PvpMove(val handIndex: Int, val position: Int)
