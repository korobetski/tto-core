package com.tripletriad.data

import com.tripletriad.model.GameRules
import com.tripletriad.model.OpenRule
import com.tripletriad.model.OrderRule
import com.tripletriad.model.TypeRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Choosing rules: the pool a format allows, and taking a rule back off.
 *
 * ### Why subtraction needed writing at all
 *
 * `GameRules.withRuleKey` is additive, and for its whole life that was enough: an NPC declared a
 * list of constants and a rule set was built up from it. Nothing ever needed a rule *removed*,
 * because nothing ever changed its mind.
 *
 * A player ticking checkboxes does. So `withoutRuleKey` exists, and the test that matters is that
 * it is the exact inverse over **every** key the table knows — not over a sample, because the way
 * this breaks is one entry in a sixteen-row table being wrong, which is what sampling fails to
 * find.
 */
class RulePoolTest {

    /** Every rule key, exercised through a format that allows all of them. */
    private val everything = Format(
        id = "test-all",
        nameKey = "STR_TEST",
        blocks = listOf(1),
        rules = ALL_KEYS,
    )

    /** Adding a key then removing it leaves the set exactly as it started, for all sixteen. */
    @Test
    fun removingAKeyUndoesAddingIt() {
        for (key in ALL_KEYS) {
            val round = GameRules().withRuleKey(key).withoutRuleKey(key)

            assertEquals(GameRules(), round, key)
        }
    }

    /** And adding one is visible, so the round trip above is not vacuous. */
    @Test
    fun addingAKeyChangesSomething() {
        for (key in ALL_KEYS) {
            assertTrue(
                GameRules().withRuleKey(key).activeRuleKeys().contains(key),
                "$key did not turn itself on",
            )
        }
    }

    /**
     * Clearing one of the three exclusive slots returns it to its default, not to its sibling.
     *
     * Unticking All Open means no Open rule. Unticking it into Three Open would be a checkbox that
     * turned a different one on, which is the shape of bug a "toggle" invites.
     */
    @Test
    fun clearingASlotReturnsItToItsDefault() {
        val open = GameRules().withRuleKey("RULE_ALL_OPEN").withoutRuleKey("RULE_ALL_OPEN")
        val order = GameRules().withRuleKey("RULE_CHAOS").withoutRuleKey("RULE_CHAOS")
        val type = GameRules().withRuleKey("RULE_ELEMENTAL").withoutRuleKey("RULE_ELEMENTAL")

        assertEquals(OpenRule.NONE, open.open)
        assertEquals(OrderRule.FREE, order.order)
        assertEquals(TypeRule.NONE, type.typeRule)
    }

    /**
     * Clearing a slot that holds the *other* member leaves it alone.
     *
     * Three Open is on and All Open is unticked: the slot must stay on Three Open. A `clear` that
     * did not check which member is set would silently turn off a rule the player never touched —
     * the two share one field.
     */
    @Test
    fun clearingASlotHoldingAnotherMemberChangesNothing() {
        val threeOpen = GameRules().withRuleKey("RULE_THREE_OPEN")

        assertEquals(threeOpen, threeOpen.withoutRuleKey("RULE_ALL_OPEN"))
    }

    /** A key naming no rule is ignored both ways, as the AS3 `switch` with no default is. */
    @Test
    fun anUnknownKeyIsIgnoredBothWays() {
        val rules = GameRules().withRuleKey("RULE_SAME")

        assertEquals(rules, rules.withRuleKey("RULE_NOT_A_RULE"))
        assertEquals(rules, rules.withoutRuleKey("RULE_NOT_A_RULE"))
    }

    /** `toggling` is the two of them, chosen by a boolean. */
    @Test
    fun togglingIsBothDirections() {
        val on = GameRules().toggling("RULE_PLUS", on = true)

        assertTrue(on.plus)
        assertFalse(on.toggling("RULE_PLUS", on = false).plus)
    }

    /** A format keeps what it allows and drops what it does not. */
    @Test
    fun confineKeepsOnlyThePoolsRules() {
        val narrow = everything.copy(rules = listOf("RULE_SAME", "RULE_PLUS"))
        val asked = GameRules().withRuleKey("RULE_SAME").withRuleKey("RULE_ELEMENTAL")

        val kept = narrow.confine(asked)

        assertTrue(kept.same, "an allowed rule was dropped")
        assertEquals(TypeRule.NONE, kept.typeRule, "a rule outside the pool survived")
        assertFalse(narrow.admitsRules(asked))
    }

    /** A rule set already inside the pool comes back untouched, and is admitted. */
    @Test
    fun confineLeavesAnAllowedSetAlone() {
        val asked = GameRules()
            .withRuleKey("RULE_SAME")
            .withRuleKey("RULE_CHAOS")
            .withRuleKey("RULE_ELEMENTAL")

        assertEquals(asked, everything.confine(asked))
        assertTrue(everything.admitsRules(asked))
    }

    /**
     * Roulette is not something a host may tick.
     *
     * `GameRules.roulette` is what the Wheel of Fortune achievements count, so it means "this set
     * came out of a draw" — a claim only `Roulette.augment` may make. It is therefore off
     * the choosable list even though it is in the pool, and a set arriving with it set is refused.
     */
    @Test
    fun rouletteIsInThePoolAndNotOnTheMenu() {
        assertTrue("RULE_ROULETTE" in everything.rules)
        assertFalse("RULE_ROULETTE" in everything.choosableRuleKeys())
        assertFalse(everything.admitsRules(GameRules(roulette = true)))
    }

    private companion object {
        /** The sixteen constants `RuleKeys` maps, which is `internal` and cannot be read here. */
        val ALL_KEYS = listOf(
            "RULE_ALL_OPEN", "RULE_THREE_OPEN",
            "RULE_ORDER", "RULE_CHAOS",
            "RULE_ASCENSION", "RULE_DESCENSION", "RULE_ELEMENTAL",
            "RULE_SUDDEN_DEATH", "RULE_RANDOM", "RULE_REVERSE", "RULE_FALLEN_ACE",
            "RULE_SAME", "RULE_SAME_WALL", "RULE_PLUS", "RULE_SWAP", "RULE_ROULETTE",
        )
    }
}
