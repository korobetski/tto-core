package com.tripletriad.protocol

import com.tripletriad.data.ItemUse
import com.tripletriad.model.BoosterItem
import com.tripletriad.model.BoosterType
import com.tripletriad.model.GameSave
import com.tripletriad.model.PotionItem
import com.tripletriad.model.PotionType
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What an intent looks like on the wire.
 *
 * ### The assertions that earn the file
 *
 * [aPurchaseCarriesNoPrice] and [anItemUsedCarriesTheProfileOnce]. Both are claims about what is
 * **absent**, and absence is exactly what a round-trip test does not check: a payload that quietly
 * grew a `price` field would round-trip perfectly and hand the shop back to the client.
 */
class IntentWireTest {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * There is nowhere in a purchase to put a price.
     *
     * The security property `BuyRequest` exists for, asserted against the encoded text rather than
     * the type — because the question is what a server could read off the bytes, and a field added
     * later with a default would satisfy any assertion made about the object.
     */
    @Test
    fun aPurchaseCarriesNoPrice() {
        val request = BuyRequest(
            item = BoosterItem(BoosterType.BRONZE),
            formatId = "free-play",
            operationId = "op-1",
        )

        val encoded = json.encodeToString(BuyRequest.serializer(), request)

        assertFalse("price" in encoded, "a purchase named a price: $encoded")
        assertFalse("mgp" in encoded, "a purchase named an amount: $encoded")
        assertEquals(request, json.decodeFromString(BuyRequest.serializer(), encoded))
    }

    /** Every intent carries an operation id, because a retry that repeats itself costs money. */
    @Test
    fun everyIntentCarriesAnOperationId() {
        val intents: List<Idempotent> = listOf(
            BagItemRequest(PotionItem(PotionType.SMALL_XP), "op-bag"),
            BuyRequest(PotionItem(PotionType.SMALL_XP), "free-play", "op-buy"),
            SellCardRequest(cardId = 257, operationId = "op-card"),
            EnterCampaignRequest(campaignKey = "cc", operationId = "op-ladder"),
        )

        assertTrue(
            intents.all { it.operationId.isNotBlank() },
            "an intent type was added without an id",
        )
    }

    /** Each one round-trips, id included. */
    @Test
    fun theIntentsRoundTrip() {
        val bag = BagItemRequest(BoosterItem(BoosterType.GOLD), "op-1")
        assertEquals(bag, json.decodeFromString(BagItemRequest.serializer(), enc(bag)))

        val card = SellCardRequest(257, "op-2")
        assertEquals(card, json.decodeFromString(SellCardRequest.serializer(), enc(card)))

        val ladder = EnterCampaignRequest("cc", "op-3")
        assertEquals(ladder, json.decodeFromString(EnterCampaignRequest.serializer(), enc(ladder)))
    }

    /**
     * A used item reports the profile **once**.
     *
     * `ItemUse` carries a whole `GameSave` and so does `PlayerState`; sending both would put the
     * profile on the wire twice to say one thing. [ItemEffect] is the parallel hierarchy that keeps
     * them apart, and this is what stops the two from being quietly merged back.
     */
    @Test
    fun anItemUsedCarriesTheProfileOnce() {
        val used = ItemUsed(
            player = PlayerState(save = GameSave.new("Kuplu", createdAt = 0L)),
            effect = ItemEffect.PackOpened(cardIds = listOf(260, 261), newCardIds = setOf(261)),
        )

        val encoded = json.encodeToString(ItemUsed.serializer(), used)

        assertEquals(1, encoded.split("\"USERNAME\"").size - 1, "the profile was sent twice")
        assertEquals(used, json.decodeFromString(ItemUsed.serializer(), encoded))
    }

    /** Every branch of the effect survives the wire, discriminator and all. */
    @Test
    fun everyEffectRoundTrips() {
        val effects = listOf(
            ItemEffect.PackOpened(listOf(1, 2), setOf(2)),
            ItemEffect.CardDrawn(cardId = 3, wasNew = true),
            ItemEffect.BoonRaised,
            ItemEffect.NotUseable,
        )

        for (effect in effects) {
            val encoded = json.encodeToString(ItemEffect.serializer(), effect)
            assertEquals(effect, json.decodeFromString(ItemEffect.serializer(), encoded), encoded)
        }
    }

    /**
     * The mapping from the engine's answer to the wire's is total and faithful.
     *
     * Walked over every `ItemUse` rather than spot-checked, because `effect()` is a `when` and the
     * failure mode of a `when` is a case somebody adds and maps to the wrong thing.
     */
    @Test
    fun everyOutcomeMapsToItsEffect() {
        val save = GameSave.new("Kuplu", createdAt = 0L)

        assertEquals(
            ItemEffect.PackOpened(listOf(7), setOf(7)),
            ItemUse.PackOpened(save, listOf(7), setOf(7)).effect(),
        )
        assertEquals(
            ItemEffect.CardDrawn(7, wasNew = true),
            ItemUse.CardDrawn(save, 7, wasNew = true).effect(),
        )
        assertEquals(ItemEffect.BoonRaised, ItemUse.BoonRaised(save).effect())
        assertEquals(ItemEffect.NotUseable, ItemUse.NotUseable(save).effect())
    }

    private inline fun <reified T> enc(value: T): String = Json.encodeToString(value)
}
