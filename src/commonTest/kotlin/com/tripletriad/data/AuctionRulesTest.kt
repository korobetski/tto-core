package com.tripletriad.data

import com.tripletriad.model.Card
import com.tripletriad.protocol.AuctionPolicy
import com.tripletriad.protocol.AuctionRefusal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [AuctionRules]: the floor, the two fees, the increment, and the two validations.
 *
 * Everything here is arithmetic both ends run — the bid button shows it before the player commits
 * and the server charges it afterwards — so what these tests pin is that there is only one answer.
 * The refusal *order* is pinned too, because it is what decides which field the seller is told to
 * fix, and an order nobody asserts is an order that drifts.
 */
class AuctionRulesTest {
    private fun card(number: Int, rarity: Int) = Card(
        id = Card.idFor(block = 1, number = number),
        nameKey = "STR_TEST_$number",
        name = "Test $number",
        top = 1,
        right = 1,
        bottom = 1,
        left = 1,
        rarity = rarity,
    )

    private val common = card(number = 10, rarity = 1)
    private val legend = card(number = 50, rarity = 5)
    private val cards: Map<Int, Card> = listOf(common, legend).associateBy { it.id }
    private val policy = AuctionPolicy()

    /** The specification's own sentence: "100 pour une carte rang 1". The shop price, unchanged. */
    @Test
    fun theFloorIsWhatAShopWouldPay() {
        assertEquals(100, AuctionRules.floorPriceOf(common.id, cards))
        assertEquals(1_500, AuctionRules.floorPriceOf(legend.id, cards))
        assertEquals(
            CardValue.resaleOf(legend.id, cards),
            AuctionRules.floorPriceOf(legend.id, cards),
            "the auction adds no second ladder",
        )
    }

    @Test
    fun theCeilingIsAMultipleOfWorthAndNotOfTheShopPrice() {
        assertEquals(250 * 20, AuctionRules.ceilingPriceOf(common.id, cards, policy))
        assertEquals(3_750 * 20, AuctionRules.ceilingPriceOf(legend.id, cards, policy))
    }

    /** Both fees round up and neither can reach zero. The object's KDoc says why up. */
    @Test
    fun feesRoundUpAndNeverToNothing() {
        assertEquals(5, AuctionRules.listingFee(100))
        assertEquals(6, AuctionRules.listingFee(101), "5.05 rounds up")
        assertEquals(1, AuctionRules.listingFee(1), "a free listing is what a flood script wants")

        assertEquals(3, AuctionRules.buyerFee(100))
        assertEquals(4, AuctionRules.buyerFee(101), "3.03 rounds up")
        assertEquals(1, AuctionRules.buyerFee(1))
    }

    /** The only figure a bidder is ever shown on a button. */
    @Test
    fun theTotalDueIsTheBidPlusTheBuyersFee() {
        assertEquals(103, AuctionRules.totalDue(100))
        assertEquals(1_030, AuctionRules.totalDue(1_000))
    }

    @Test
    fun theFirstBidIsTheStartingPriceAndEveryOneAfterClearsTheIncrement() {
        assertEquals(100, AuctionRules.minimumBid(startPrice = 100, topBid = null))
        assertEquals(105, AuctionRules.minimumBid(startPrice = 100, topBid = 100))
        assertEquals(2, AuctionRules.minimumBid(startPrice = 1, topBid = 1), "never a free raise")
    }

    @Test
    fun aListingAtTheFloorWithABiggerReserveIsAllowed() {
        assertNull(listing(startPrice = 100, reservePrice = 400))
    }

    @Test
    fun aListingBelowTheFloorIsRefused() {
        assertEquals(AuctionRefusal.BELOW_FLOOR, listing(startPrice = 99, reservePrice = 400))
    }

    /**
     * The fee dodge that the reserve-above-start rule closes.
     *
     * The listing fee is a fraction of the **reserve**, so without this a seller lists at 5 000
     * with a reserve of 100 and pays 5 MGP for a lot they intend to sell for fifty times that.
     */
    @Test
    fun aReserveUnderTheStartingPriceIsRefused() {
        assertEquals(
            AuctionRefusal.RESERVE_BELOW_START,
            listing(startPrice = 5_000, reservePrice = 100),
        )
    }

    @Test
    fun aReserveOverTheCeilingIsRefused() {
        assertEquals(
            AuctionRefusal.ABOVE_CEILING,
            listing(startPrice = 100, reservePrice = 5_001),
        )
    }

    @Test
    fun aSellerWhoCannotPayTheListingFeeIsRefused() {
        assertEquals(
            AuctionRefusal.CANNOT_AFFORD,
            listing(startPrice = 100, reservePrice = 400, purse = 19),
        )
        assertNull(listing(startPrice = 100, reservePrice = 400, purse = 20), "5% of 400")
    }

    @Test
    fun aCardTheProfileCannotPartWithIsRefusedBeforeAnythingElse() {
        assertEquals(
            AuctionRefusal.NOT_YOURS,
            listing(startPrice = 1, reservePrice = 1, spareCopies = 0, purse = 0, openLots = 99),
            "the first field the seller would have to fix",
        )
    }

    @Test
    fun aSellerAtTheirLotLimitIsRefused() {
        assertEquals(
            AuctionRefusal.TOO_MANY_LOTS,
            listing(startPrice = 100, reservePrice = 400, openLots = policy.maxOpenLots),
        )
    }

    @Test
    fun aBidThatClearsTheMinimumAndIsAffordableIsAllowed() {
        assertNull(bid(amount = 105, topBid = 100, purse = 111))
    }

    @Test
    fun biddingOnYourOwnLotIsRefusedFirst() {
        assertEquals(
            AuctionRefusal.YOUR_OWN_LOT,
            bid(amount = 1, topBid = 100, purse = 0, isSeller = true, isTopBidder = true),
        )
    }

    @Test
    fun biddingAgainstYourselfIsRefused() {
        assertEquals(
            AuctionRefusal.ALREADY_LEADING,
            bid(amount = 200, topBid = 100, purse = 1_000, isTopBidder = true),
        )
    }

    @Test
    fun aBidUnderTheIncrementIsRefused() {
        assertEquals(AuctionRefusal.BID_TOO_LOW, bid(amount = 104, topBid = 100, purse = 1_000))
    }

    @Test
    fun aBidOverTheCeilingIsRefused() {
        assertEquals(
            AuctionRefusal.ABOVE_CEILING,
            bid(amount = 5_001, topBid = 100, purse = 1_000_000),
        )
    }

    /**
     * Affordability counts the fee, which is the point of checking it here at all.
     *
     * A purse holding exactly the bid is a purse that cannot pay for the bid.
     */
    @Test
    fun aBidderWhoCannotCoverTheFeeIsRefused() {
        assertEquals(AuctionRefusal.CANNOT_AFFORD, bid(amount = 105, topBid = 100, purse = 105))
        assertNull(bid(amount = 105, topBid = 100, purse = 109), "105 plus 3% of 105, rounded up")
    }

    @Test
    fun aBidWellBeforeTheEndDoesNotMoveIt() {
        val end = 1_000_000L

        assertEquals(end, AuctionRules.extendedEnd(end, now = end - 300_000L, policy = policy))
    }

    @Test
    fun aBidInsideTheClosingWindowPushesTheEndOut() {
        val end = 1_000_000L
        val now = end - 30_000L

        assertEquals(now + 120_000L, AuctionRules.extendedEnd(end, now, policy))
    }

    /** A bid landing on the nominal end still gets the full window, never a shorter one. */
    @Test
    fun theEndNeverMovesBackwards() {
        val end = 1_000_000L

        assertEquals(end + 120_000L, AuctionRules.extendedEnd(end, now = end, policy = policy))
    }

    private fun listing(
        startPrice: Int,
        reservePrice: Int,
        spareCopies: Int = 1,
        purse: Int = 10_000,
        openLots: Int = 0,
    ): AuctionRefusal? = AuctionRules.validateListing(
        cardId = common.id,
        startPrice = startPrice,
        reservePrice = reservePrice,
        cards = cards,
        policy = policy,
        spareCopies = spareCopies,
        purse = purse,
        openLots = openLots,
    )

    private fun bid(
        amount: Int,
        topBid: Int?,
        purse: Int,
        isSeller: Boolean = false,
        isTopBidder: Boolean = false,
    ): AuctionRefusal? = AuctionRules.validateBid(
        amount = amount,
        startPrice = 100,
        topBid = topBid,
        cardId = common.id,
        cards = cards,
        policy = policy,
        purse = purse,
        isSeller = isSeller,
        isTopBidder = isTopBidder,
    )
}
