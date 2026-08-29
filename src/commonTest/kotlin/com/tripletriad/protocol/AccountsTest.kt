package com.tripletriad.protocol

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a client may send about an account, and what it may believe about one.
 *
 * Two things are being pinned here. The first is that [Credentials.looksLikeEmail] stays **loose**:
 * every test below that asserts an address is accepted is a test against somebody tightening it
 * into the shape that rejects real people. The second is that the fields added for confirmation
 * are compatible in the direction that matters — a payload written before they existed still reads.
 */
class AccountsTest {

    // ---- Addresses ---------------------------------------------------------

    @Test
    fun anOrdinaryAddressIsAccepted() {
        assertTrue(Credentials.looksLikeEmail("kuplu@example.org"))
    }

    /**
     * The four that a hand-written regex habitually refuses, and all four are deliverable.
     *
     * Plus-addressing is the one that would hurt most: it is how people file mail from a game, and
     * refusing it teaches them the game is broken rather than that their address is unusual.
     */
    @Test
    fun theAddressesThatNaiveValidationRefusesAreAllAccepted() {
        for (address in NOT_TO_BE_REFUSED) {
            assertTrue(Credentials.looksLikeEmail(address), "refused $address")
        }
    }

    @Test
    fun whatIsCertainlyNotAnAddressIsRefused() {
        for (value in NOT_ADDRESSES) {
            assertFalse(Credentials.looksLikeEmail(value), "accepted \"$value\"")
        }
    }

    /**
     * A second `@` is the one strictness kept, and this says why it is a judgement call.
     *
     * A quoted local part may legally contain one. It is vanishingly rare, and a second `@` is
     * overwhelmingly somebody typing two addresses into one field — so the trade is made
     * deliberately rather than by accident, and it is written down here so that changing it is
     * also deliberate.
     */
    @Test
    fun aSecondAtIsRefusedEvenThoughItCanBeLegal() {
        assertFalse(Credentials.looksLikeEmail("\"odd@name\"@example.org"))
    }

    // ---- Which request is being validated ----------------------------------

    @Test
    fun aSignInNeedsNoAddress() {
        assertTrue(Credentials("kuplu", "correct-horse").looksValid())
    }

    @Test
    fun aRegistrationWithoutOneIsRefusedByTheStricterCheck() {
        val bare = Credentials("kuplu", "correct-horse")
        assertTrue(bare.looksValid(), "it is still a valid sign-in")
        assertFalse(bare.looksValidToRegister(), "but not a valid registration")
    }

    @Test
    fun aRegistrationWithABadAddressIsRefused() {
        assertFalse(Credentials("kuplu", "correct-horse", "kuplu").looksValidToRegister())
    }

    @Test
    fun aShortPasswordIsRefusedWhateverTheAddress() {
        assertFalse(Credentials("kuplu", "short", "kuplu@example.org").looksValidToRegister())
    }

    // ---- Codes -------------------------------------------------------------

    @Test
    fun aCodeReadOffAPhoneInGroupsIsAccepted() {
        // Typed as "123 456", which is how six digits are read aloud and copied down.
        assertTrue(AccountCode.looksValid("123 456"))
    }

    @Test
    fun aCodeOfTheWrongLengthOrShapeIsRefused() {
        for (code in listOf("12345", "1234567", "12345a", "", "      ")) {
            assertFalse(AccountCode.looksValid(code), "accepted \"$code\"")
        }
    }

    @Test
    fun aResetNeedsBothAUsableCodeAndAUsablePassword() {
        assertTrue(PasswordReset("kuplu", "123456", "correct-horse").looksValid())
        assertFalse(PasswordReset("kuplu", "12345", "correct-horse").looksValid())
        assertFalse(PasswordReset("kuplu", "123456", "short").looksValid())
    }

    // ---- Compatibility -----------------------------------------------------

    /**
     * A body written by a build that had never heard of confirmation still reads.
     *
     * This is the one direction that has to hold: the server ships first, and every client in the
     * field was compiled against the shape without these fields. `verified` defaulting to true is
     * what stops such a client being told, by omission, that its account is unconfirmed.
     */
    @Test
    fun aPlayerStateFromBeforeConfirmationStillReadsAndIsNotTreatedAsUnverified() {
        val old = """{"save":{"USERNAME":"kuplu"}}"""
        val state = Json { ignoreUnknownKeys = true }.decodeFromString<PlayerState>(old)

        assertEquals("kuplu", state.save.username)
        assertTrue(state.verified, "an older payload must not read as an unconfirmed account")
    }

    private companion object {
        val NOT_TO_BE_REFUSED = listOf(
            "kuplu+triad@gmail.com",
            "o'brien@example.org",
            "someone@example.photography",
            "root@localhost",
        )

        val NOT_ADDRESSES = listOf(
            "kuplu",
            "@example.org",
            "kuplu@",
            "ku plu@example.org",
            "kuplu@exa mple.org",
        )
    }
}
