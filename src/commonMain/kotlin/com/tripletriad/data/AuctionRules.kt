package com.tripletriad.data

import com.tripletriad.model.Card
import com.tripletriad.protocol.AuctionPolicy
import com.tripletriad.protocol.AuctionRefusal
import kotlin.math.ceil

/**
 * What a card may be listed for, what an auction costs, and what a bid has to clear.
 *
 * ### Why the arithmetic is here and not on either end
 *
 * The same bargain the rest of this module strikes: a price the client computes is a price the
 * client can disagree about. The bid button has to *show* the total before the player commits to
 * it — see [Inventory.priceOf], which was split out of `Inventory.sell` for exactly that reason —
 * and a screen doing its own multiplication is how a button comes to promise one number and take
 * another. So both ends call these functions, and the server's answer is the one that lands.
 *
 * ### The ladder this hangs off
 *
 * [CardValue] already owns what a card is worth and what a shop pays for it. The auction house
 * adds no second ladder: the floor under a listing **is** the shop price ([floorPriceOf]), so a
 * card can never be auctioned for less than the counter next door would hand over without anybody
 * waiting. Below that the auction is strictly worse than the shop for the seller and pointless for
 * the buyer.
 *
 * ### Rounding goes to the house, once, here
 *
 * Every fee rounds **up** ([ceil]) and never to zero. Two reasons, and neither is greed: a fee that
 * rounds to nothing makes the cheapest listings free, which is what a flooding script wants; and a
 * fee computed one way on a screen and another in a transaction is a bug report nobody can
 * reproduce. One direction, stated once.
 */
object AuctionRules {
    /**
     * The seller's fee, as a fraction of the **reserve** rather than of the sale.
     *
     * Charged when the lot opens and never refunded — see [listingFee]. Taking it on the reserve is
     * what makes it payable at all: at that moment there is no sale price to take a fraction of,
     * and the reserve is the seller's own statement of what they think the card is worth.
     */
    const val LISTING_FEE_RATE: Double = 0.05

    /** The buyer's fee, as a fraction of the winning bid. Taken at settlement, on top of it. */
    const val BUYER_FEE_RATE: Double = 0.03

    /**
     * How much a bid must beat the standing one by, as a fraction of it.
     *
     * Without a floor under the *increment*, an auction's last minute is a hundred bids of one MGP
     * each, which costs the bidder nothing and costs everybody else a list that will not sit still.
     */
    const val MIN_INCREMENT_RATE: Double = 0.05

    /**
     * What a shop would pay for [cardId] — and the least it may be listed for.
     *
     * Inclusive: a listing *at* the floor is allowed. The floor is where the shop already stands,
     * so matching it is a legitimate "I would rather someone played this card than melted it", and
     * refusing the boundary would only mean every seller types one MGP more than they meant to.
     */
    fun floorPriceOf(cardId: Int, cards: Map<Int, Card>): Int = CardValue.resaleOf(cardId, cards)

    /**
     * The most [cardId] may be listed or bid for.
     *
     * ### This is not a price control, it is an anti-laundering measure
     *
     * The auction house is the one place where MGP moves from one account to another with nothing
     * checking what came back the other way. A player with two accounts can list a common, bid a
     * million on it from the second, and have moved a million — the cards are real, the bid is
     * real, and no rule above is broken. The two fees make it *cost* 8%, which is friction, not a
     * wall.
     *
     * A ceiling proportional to what the card is actually worth is the wall. It is deliberately
     * generous — [AuctionPolicy.maxPriceMultiple] times the card's worth, not its shop price, so a
     * five-star may still fetch many times what the counter pays — because the honest listing it
     * must not block is a scarce card in demand, and the abuse it must block is three orders of
     * magnitude away from that.
     *
     * The number travels in [AuctionPolicy] rather than being compiled in, for the reason
     * `Unlocks` gives: it will be tuned, and a constant in both ends turns a tuning into a
     * coordinated release.
     */
    fun ceilingPriceOf(cardId: Int, cards: Map<Int, Card>, policy: AuctionPolicy): Int =
        CardValue.worthOf(cardId, cards) * policy.maxPriceMultiple

    /** What opening a lot costs the seller. Never zero, whatever the reserve. */
    fun listingFee(reservePrice: Int): Int = feeOf(reservePrice, LISTING_FEE_RATE)

    /** What winning costs the buyer, on top of their bid. Never zero. */
    fun buyerFee(price: Int): Int = feeOf(price, BUYER_FEE_RATE)

    /**
     * What a bid actually takes out of a purse: the bid plus the buyer's fee.
     *
     * The only figure a bidder should ever be shown on a button, because it is the only one that
     * leaves their account.
     */
    fun totalDue(bid: Int): Int = bid + buyerFee(bid)

    /**
     * The least a bid may be: the starting price while nobody has bid, and the standing bid plus
     * [MIN_INCREMENT_RATE] afterwards.
     *
     * @param topBid the standing bid, or null when there is none.
     */
    fun minimumBid(startPrice: Int, topBid: Int?): Int =
        topBid?.let { it + feeOf(it, MIN_INCREMENT_RATE) } ?: startPrice

    /**
     * Whether these terms may be listed, or why not.
     *
     * The order of the checks is the order the seller filled the form in, so the refusal names the
     * field they would fix first.
     *
     * @param spareCopies how many copies of the card the profile can part with — `GameSave
     *   .spareCopiesOf`, not `copiesOf`. A card a saved deck is built on is not spare, and listing
     *   it would leave a deck that cannot be fielded.
     * @param purse the seller's MGP, which has to cover [listingFee] before anything opens.
     */
    // ReturnCount: eight guards, and the early return *is* the answer — each one names the field
    // to fix, so folding them into one expression would lose which of them failed.
    // LongParameterList: every argument is a distinct fact the decision needs and none of them
    // is derivable from another. A parameter object here would be this list with a name on it.
    @Suppress("ReturnCount", "LongParameterList")
    fun validateListing(
        cardId: Int,
        startPrice: Int,
        reservePrice: Int,
        cards: Map<Int, Card>,
        policy: AuctionPolicy,
        spareCopies: Int,
        purse: Int,
        openLots: Int,
    ): AuctionRefusal? {
        if (spareCopies < 1) return AuctionRefusal.NOT_YOURS
        if (openLots >= policy.maxOpenLots) return AuctionRefusal.TOO_MANY_LOTS
        if (startPrice < floorPriceOf(cardId, cards)) return AuctionRefusal.BELOW_FLOOR
        if (reservePrice < startPrice) return AuctionRefusal.RESERVE_BELOW_START
        val ceiling = ceilingPriceOf(cardId, cards, policy)
        if (reservePrice > ceiling) return AuctionRefusal.ABOVE_CEILING
        if (purse < listingFee(reservePrice)) return AuctionRefusal.CANNOT_AFFORD
        return null
    }

    /**
     * Whether this bid may be placed, or why not.
     *
     * ### What "afford" means here, and why it is not checked again later
     *
     * [totalDue], now. The bid is **taken** when it is placed and given back the moment somebody
     * outbids it, so a bidder can never owe money they no longer have — the whole class of "the
     * winner cannot pay" is unreachable rather than handled. It is also what stops a bidder with an
     * empty purse from running a lot up to a number nobody sane will beat and then simply not
     * paying, which costs them nothing and costs the seller the sale.
     *
     * @param isSeller whether the bidder is the one selling. Bidding on your own lot is shill
     *   bidding, whatever the intent.
     * @param isTopBidder whether they are already winning. Bidding against yourself raises the
     *   price you will pay and can do nothing else.
     */
    // The same two, for the same two reasons `validateListing` gives above.
    @Suppress("ReturnCount", "LongParameterList")
    fun validateBid(
        amount: Int,
        startPrice: Int,
        topBid: Int?,
        cardId: Int,
        cards: Map<Int, Card>,
        policy: AuctionPolicy,
        purse: Int,
        isSeller: Boolean,
        isTopBidder: Boolean,
    ): AuctionRefusal? {
        if (isSeller) return AuctionRefusal.YOUR_OWN_LOT
        if (isTopBidder) return AuctionRefusal.ALREADY_LEADING
        if (amount < minimumBid(startPrice, topBid)) return AuctionRefusal.BID_TOO_LOW
        if (amount > ceilingPriceOf(cardId, cards, policy)) return AuctionRefusal.ABOVE_CEILING
        if (purse < totalDue(amount)) return AuctionRefusal.CANNOT_AFFORD
        return null
    }

    /**
     * When a lot ends, given a bid landing at [now].
     *
     * A bid in the closing seconds pushes the end out — the sniping answer, and the reason it is
     * arithmetic rather than a rule anybody has to remember. Unbounded on purpose: every extension
     * costs a real bid with real money behind it, so an auction that keeps extending is an auction
     * two people genuinely want, which is the thing being sold working correctly.
     *
     * @return the new end, which is never earlier than [endsAt].
     */
    fun extendedEnd(endsAt: Long, now: Long, policy: AuctionPolicy): Long {
        val window = policy.antiSnipeSeconds * MILLIS_PER_SECOND
        if (endsAt - now > window) return endsAt
        return maxOf(endsAt, now + window)
    }

    /** Rounds a fee up and never to nothing. See this object's KDoc for why up. */
    private fun feeOf(base: Int, rate: Double): Int =
        ceil(base * rate).toInt().coerceAtLeast(1)

    private const val MILLIS_PER_SECOND = 1_000L
}
