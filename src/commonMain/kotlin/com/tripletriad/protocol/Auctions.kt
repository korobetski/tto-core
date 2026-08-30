package com.tripletriad.protocol

import com.tripletriad.data.AuctionRules
import kotlinx.serialization.Serializable

/**
 * The numbers a deployment runs its auction house on.
 *
 * On the wire in [ServerInfo] rather than compiled into both ends, for the reason [Unlocks] gives
 * at length: these will be tuned, and a constant in two binaries turns a tuning into a coordinated
 * release. A client draws what it is told; the server refuses on its own copy, and that copy is the
 * one that counts.
 *
 * @property maxOpenLots how many lots one account may have running at once. A cap on *listings*
 *   rather than on cards, because the abuse is flooding the list, not owning a lot of cards.
 * @property maxPriceMultiple the ceiling on a price, as a multiple of the card's worth. See
 *   [AuctionRules.ceilingPriceOf] — it is an anti-laundering measure, not a price control.
 * @property sellerDecisionHours how long a seller has to accept or refuse a top bid that fell short
 *   of their reserve. Bounded because the bidder's money is held for exactly that long: a window a
 *   seller could leave open indefinitely would be a way to freeze somebody else's purse.
 * @property antiSnipeSeconds how close to the end a bid has to land to push the end out, and how
 *   far it pushes it.
 */
@Serializable
data class AuctionPolicy(
    val maxOpenLots: Int = DEFAULT_MAX_OPEN_LOTS,
    val maxPriceMultiple: Int = DEFAULT_MAX_PRICE_MULTIPLE,
    val sellerDecisionHours: Int = DEFAULT_SELLER_DECISION_HOURS,
    val antiSnipeSeconds: Int = DEFAULT_ANTI_SNIPE_SECONDS,
) {
    /** [sellerDecisionHours] as the duration the server actually compares against a clock. */
    val sellerDecisionMillis: Long get() = sellerDecisionHours * MILLIS_PER_HOUR

    companion object {
        /** Five: enough to clear out a run of duplicates, few enough that a bot is throttled. */
        const val DEFAULT_MAX_OPEN_LOTS: Int = 5

        /**
         * Twenty times worth — a 5★ ceiling of 75 000 MGP against a shop price of 1 500.
         *
         * Deliberately far above any honest price, because the thing it blocks is three orders of
         * magnitude away and the thing it must not block is a scarce card in demand.
         */
        const val DEFAULT_MAX_PRICE_MULTIPLE: Int = 20

        /** Half a day: a seller sleeps, a bidder should not be frozen for longer than that. */
        const val DEFAULT_SELLER_DECISION_HOURS: Int = 12

        /** Two minutes, the same window the closing bid gets pushed by. */
        const val DEFAULT_ANTI_SNIPE_SECONDS: Int = 120

        private const val MILLIS_PER_HOUR = 60L * 60L * 1_000L
    }
}

/**
 * How long a lot runs. Chosen from a list rather than typed.
 *
 * A free-form duration is the one field on the listing form that would let two accounts arrange to
 * meet: a lot open for eleven seconds is not for sale to anybody who is not already watching for
 * it. Three lengths, all long enough that the list is the only way to find them.
 */
@Serializable
enum class AuctionDuration(val hours: Int) {
    SHORT(6),
    MEDIUM(12),
    LONG(24),
    ;

    val millis: Long get() = hours * 60L * 60L * 1_000L
}

/** Where a lot is in its life. */
@Serializable
enum class AuctionStatus {
    /** Running. Bids accepted, and the seller may withdraw it while nobody has bid. */
    OPEN,

    /**
     * Ended with bids that never reached the reserve, and the seller has not answered yet.
     *
     * The reserve is a floor the seller *may* waive: a bid at 90% of it is still money for a card
     * they were willing to part with, and burning the sale because of a number they typed a day
     * earlier serves nobody. So this is a real state and not an automatic refusal — see
     * `AuctionPolicy.sellerDecisionHours` for why it cannot last for ever.
     */
    AWAITING_SELLER,

    /** Settled: the buyer has the card, the seller has the purse. */
    SOLD,

    /** Ended with nothing to settle — no bid, or a reserve the seller declined to waive. */
    UNSOLD,

    /** Withdrawn by the seller before anybody bid. */
    CANCELLED,
    ;

    /** Whether a bid may still be placed. */
    val isOpen: Boolean get() = this == OPEN

    /** Whether the lot is over, however it ended. */
    val isFinished: Boolean get() = this == SOLD || this == UNSOLD || this == CANCELLED
}

/**
 * One lot, as the player asking about it is allowed to see it.
 *
 * ### Why the shape depends on who is looking
 *
 * [reservePrice] is the seller's own number and nobody else's business: publishing it tells every
 * bidder exactly what to bid and turns a reserve into a fixed price. But hiding it entirely would
 * leave a bidder unable to tell a lot that will settle from one that is going to end in the
 * seller's hands, which is worth knowing before committing money for a day. So the *number* is the
 * seller's and the *fact* is everyone's: [reserveMet] is always filled, [reservePrice] only for the
 * seller.
 *
 * The same goes for [yours], [youLead] and [yourBid] — they are answers about the viewer, not
 * properties of the lot, and a client that had to work them out from names would get them wrong the
 * first time two players share a display name.
 *
 * @property sellerName who opened it, or **null when that account no longer exists**. A lot
 *   outlives its seller on purpose: somebody else's money is held against it and somebody else's
 *   card is in it, so deleting an account settles its lots rather than erasing them. What a buyer
 *   keeps is the record of what they won and from whom, right up to the point where "whom" is
 *   nobody.
 * @property topBidderName who is winning, or null while nobody is. A name and not an id: it is
 *   shown, never compared — see [youLead], which is what a client compares.
 * @property soldFor what it finally went for, once [status] is [AuctionStatus.SOLD].
 * @property endsAt when it closes, in epoch millis. It **moves**: a bid in the last two minutes
 *   pushes it out. See [AuctionRules.extendedEnd].
 */
@Serializable
data class AuctionLot(
    val id: String,
    val cardId: Int,
    val sellerName: String? = null,
    val startPrice: Int,
    val endsAt: Long,
    val status: AuctionStatus = AuctionStatus.OPEN,
    val topBid: Int? = null,
    val topBidderName: String? = null,
    val bidCount: Int = 0,
    val reserveMet: Boolean = false,
    val reservePrice: Int? = null,
    val yours: Boolean = false,
    val youLead: Boolean = false,
    val yourBid: Int? = null,
    val soldFor: Int? = null,
) {
    /** What it stands at: the top bid, or the starting price while nobody has bid. */
    val currentPrice: Int get() = topBid ?: startPrice

    /** The least a bid may be right now. */
    val minimumBid: Int get() = AuctionRules.minimumBid(startPrice, topBid)

    /** Whether the seller may still withdraw it: only while nobody has committed money to it. */
    val isWithdrawable: Boolean get() = yours && status.isOpen && bidCount == 0
}

/**
 * A page of lots, and the clock they should be read against.
 *
 * @property now the server's own time when it answered. Every [AuctionLot.endsAt] is an absolute
 *   instant on *this* clock, and a phone whose clock is a minute fast would otherwise draw every
 *   countdown a minute short — which matters here in a way it does not elsewhere, because the last
 *   two minutes of a lot are when a player decides whether they still have time to bid.
 */
@Serializable
data class AuctionPage(
    val lots: List<AuctionLot> = emptyList(),
    val now: Long = 0L,
)

/**
 * Putting a card up.
 *
 * The card is named by id and comes out of the **collection**, not the bag — the seller parts with
 * a copy they own, and it is held by the auction house until the lot settles. No price on this wire
 * is trusted beyond being checked: `AuctionRules.validateListing` runs on the server's own card
 * table, so a client that asks to list a common at ten MGP is refused rather than obeyed.
 */
@Serializable
data class ListCardRequest(
    val cardId: Int,
    val startPrice: Int,
    val reservePrice: Int,
    val duration: AuctionDuration = AuctionDuration.MEDIUM,
    override val operationId: String,
) : Idempotent

/**
 * Bidding.
 *
 * @property amount the bid itself, **without** the buyer's fee. The fee is the server's arithmetic
 *   ([AuctionRules.buyerFee]) and is added on top; a client that sent the total would be naming its
 *   own tax rate.
 */
@Serializable
data class BidRequest(
    val lotId: String,
    val amount: Int,
    override val operationId: String,
) : Idempotent

/**
 * The three things a seller does to their own lot: withdraw it, accept a short bid, refuse one.
 *
 * One request type for all three, and the verb is the URL — the same argument [BagItemRequest]
 * makes. The payload is identical because the payload is *which lot*, and three types differing in
 * nothing but their name would be three places to fix the day a lot gains a field.
 */
@Serializable
data class AuctionLotRequest(
    val lotId: String,
    override val operationId: String,
) : Idempotent

/**
 * What the auction house did, and the profile it wrote.
 *
 * The profile comes back for the same reason [ItemUsed] carries it: the client no longer computes
 * the result, so it must be told rather than left to recompute — and a client that recomputed would
 * be a client that could disagree.
 *
 * @property lot the lot as it now stands, when there is one to show. Null when the request was
 *   refused before a lot was touched.
 * @property refusal why nothing happened, or null when something did. A refusal here is a **200**
 *   carrying this field, not a 4xx: the client asked a reasonable question and the answer is no,
 *   and the profile in the same response is the evidence — the same shape `ItemEffect.NotUseable`
 *   already has.
 */
@Serializable
data class AuctionOutcome(
    val player: PlayerState,
    val lot: AuctionLot? = null,
    val refusal: AuctionRefusal? = null,
)

/**
 * Why an auction request was refused, as a value rather than a sentence.
 *
 * The argument [PvpRefusal] makes: the prose beside it is English and this game ships in four
 * languages, so the client switches on the code and writes its own sentence.
 */
@Serializable
enum class AuctionRefusal {
    /** The auction house is not open to this account yet — see [Unlocks.auction]. */
    LOCKED,

    /** No such lot, or one that has already finished. */
    LOT_GONE,

    /** Not a card the profile can part with: not owned, or the last copy a saved deck names. */
    NOT_YOURS,

    /** [AuctionPolicy.maxOpenLots] reached. */
    TOO_MANY_LOTS,

    /** Below what a shop would pay for the card — see [AuctionRules.floorPriceOf]. */
    BELOW_FLOOR,

    /** A reserve under the starting price, which would be a reserve that means nothing. */
    RESERVE_BELOW_START,

    /** Over [AuctionRules.ceilingPriceOf]. */
    ABOVE_CEILING,

    /** The purse does not cover it — the listing fee, or the bid plus the buyer's fee. */
    CANNOT_AFFORD,

    /** Under [AuctionRules.minimumBid]. */
    BID_TOO_LOW,

    /** Bidding on a lot you are selling. */
    YOUR_OWN_LOT,

    /** Bidding against yourself: you are already the top bidder. */
    ALREADY_LEADING,

    /** Withdrawing a lot somebody has bid on. Once money is committed, the lot runs to its end. */
    ALREADY_BID,

    /** Answering for a lot that is not yours, or that is not waiting on an answer. */
    NOT_YOUR_DECISION,
}
