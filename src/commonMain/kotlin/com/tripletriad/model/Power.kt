package com.tripletriad.model

/**
 * The lowest power a card can *show* once modifiers apply.
 *
 * Card data is 1..10 ([Card.POWER_RANGE]) but effective power is **0..10**: the AS3
 * clamp is `Math.min(10, Math.max(0, value))` (`tools.as:74-76`), Fallen Ace
 * produces 0 directly, and the Elemental penalty can drive a 1 down to 0. Confusing
 * the two ranges is the easiest way to get this wrong.
 *
 * Ascension and Descension are the exception and stop at [MIN_MODIFIED_POWER]; see there.
 */
const val MIN_EFFECTIVE_POWER: Int = 0

/**
 * The lowest power **Bonus and Malus** may drive a card to: 1, not 0.
 *
 * ### Why these two rules floor higher than everything else
 *
 * They are the only *cumulative* modifiers in the game. Elemental is a single ±1 decided by the
 * cell, and Fallen Ace is a single substitution; neither can run away. A Descension tally has no
 * bound at all — nine cards of one type on the board is −9 — so without a floor above zero the
 * rule stops being a penalty and becomes an eraser: every card of that type reads 0 on all four
 * sides, they all tie with each other, and the match is decided by whoever placed last.
 *
 * So the pair is stated as a range rather than as two clamps: **a card accumulates bonuses up to
 * [ACE_POWER] and maluses down to 1**, and stays a card at both ends.
 *
 * A card already *below* 1 when the tally is applied — only Fallen Ace does that — is not lifted
 * to 1 by a penalty. See [effectivePower], where the floor is `min(base, 1)` for exactly that
 * reason: a modifier may not move a card in the direction opposite to its own sign.
 */
const val MIN_MODIFIED_POWER: Int = 1

/** Clamps to 0..10. The AS3 `tools.madmax`. */
fun clampPower(value: Int): Int = value.coerceIn(MIN_EFFECTIVE_POWER, ACE_POWER)

/**
 * The per-type tally that drives Ascension and Descension — *Bonus* and *Malus* on screen.
 *
 * ### What it counts
 *
 * **Cards of that type on the board, signed by the rule.** `BaseMatchScreen.ascensionByType`
 * (`:350`) is a board-wide counter incremented (Ascension) or decremented (Descension) each time a
 * **typed** card is placed (`:339`, `:346`), and every card of that type on the board carries the
 * tally as a modifier.
 *
 * Two edges of that sentence are decisions rather than details, and both were settled deliberately:
 *
 * - **A card in hand neither contributes nor receives.** The AS3 `playerPanel.applyAscension`
 *   (`:153-178`) walks all five cards and adjusts the ones in hand too, so a player watched their
 *   hand's numbers drift while holding it. Here the modifier begins when the card lands. A hand
 *   card shows what is printed on it, which is also what it will be worth if the board does not
 *   change before it is played.
 * - **A card counts itself from the moment it is placed.** The original ran `ascensionPhase`
 *   *after* the flips (`TTOCore.as:171`), so the card that had just been played resolved its own
 *   captures without its own contribution — it was on the board and not yet counted. This port ran
 *   it that way too and no longer does. The rule now reads as one sentence with no exception in it,
 *   and — since the modifier is drawn on the board — a badge saying `+3` on a card that attacked as
 *   `+2` would be a screen contradicting itself at the only moment anybody is watching.
 *
 * The second is a change to what the engine computes; [com.tripletriad.protocol.CURRENT_VERSION]
 * carries it.
 */
data class AscensionTally(val counts: Map<CardType, Int> = emptyMap()) {
    operator fun get(type: CardType?): Int = if (type == null) 0 else counts[type] ?: 0

    /** Records a placement of [type] under [rule]. Untyped cards change nothing. */
    fun record(type: CardType?, rule: TypeRule): AscensionTally {
        val delta = when (rule) {
            TypeRule.ASCENSION -> 1
            TypeRule.DESCENSION -> -1
            else -> 0
        }
        return if (type == null || delta == 0) {
            this
        } else {
            AscensionTally(counts + (type to this[type] + delta))
        }
    }

    /** The tally as it stands once [card] has been placed under [rules]. */
    fun including(card: Card, rules: GameRules): AscensionTally = record(card.type, rules.typeRule)

    companion object {
        val EMPTY = AscensionTally()
    }
}

/**
 * The modifier the Elemental rule applies to a card sitting on [element].
 *
 * `TTOCore.as:47-56`, exactly:
 *
 * | condition | modifier |
 * |---|---|
 * | `card.type == tile.element` | +1 |
 * | tile has an element and the types differ | −1 |
 * | otherwise | 0 |
 *
 * **An untyped card takes −1 on any elemental tile.** It fails the first test and
 * passes the second, so it is penalised — and that is deliberate, not an accident of
 * the AS3 code: a card with no element is levelled down on an elemental tile exactly
 * like a card of the wrong element. Only a match gains +1. Confirmed against the
 * intended ruleset, see
 * [game-rules.md](../../../../../../../docs/analysis/game-rules.md) § 15.5.
 */
fun elementalModifier(type: CardType?, element: CardType?): Int = when {
    element == null -> 0
    type == element -> 1
    else -> -1
}

/**
 * The single signed modifier [card] carries under the active [TypeRule], or 0 under none.
 *
 * Split out of [effectivePower] because it is the number a **player** has to see. It is the same
 * on all four sides — every rule here shifts the whole card — so it is one badge on the board
 * rather than four adjustments a player is left to infer from digits that moved.
 *
 * Note this is the modifier *asked for*, not the modifier *achieved*: a `+4` on a card whose 8 is
 * already near the ceiling raises that side by two. The clamp is [effectivePower]'s, and it is
 * per side because the ceiling is per side.
 *
 * @param element the cell's element, read only under [TypeRule.ELEMENTAL].
 * @param tally the board's Bonus/Malus state, read only under the other two.
 */
fun powerModifier(
    card: Card,
    rules: GameRules,
    element: CardType? = null,
    tally: AscensionTally = AscensionTally.EMPTY,
): Int = when (rules.typeRule) {
    TypeRule.NONE -> 0
    TypeRule.ASCENSION, TypeRule.DESCENSION -> tally[card.type]
    TypeRule.ELEMENTAL -> elementalModifier(card.type, element)
}

/**
 * The power a placed card actually fights with on [side].
 *
 * Order of operations is taken from `TTOCore.applyRules:27-56` and matters:
 *
 * 1. start from the printed power;
 * 2. Fallen Ace turns a 10 into 0 — **before** any modifier, so an ace on a +1
 *    Ascension board reads 1, not 0;
 * 3. add the single active [TypeRule] modifier ([powerModifier]);
 * 4. clamp — to 0..10 in general, and to 1..10 under the two cumulative rules
 *    ([MIN_MODIFIED_POWER]).
 *
 * ### The floor is `min(base, 1)` and not `1`
 *
 * Because a floor is meant to stop a penalty, not to become a bonus. Fallen Ace hands this
 * function a base of 0; a flat floor of 1 would mean a Malus board *raised* a fallen ace, which is
 * two rules cancelling out through a clamp neither of them wrote. Taking the lower of the base and
 * the floor keeps the guarantee — a modifier never drags a card below 1 — without letting it drag
 * one upward either.
 *
 * Unlike the original, this is a pure function rather than mutable state on a
 * display object. That removes the three-independent-writers hazard described in
 * [game-rules.md](../../../../../../../docs/analysis/game-rules.md) § 15.8.
 */
fun effectivePower(
    card: Card,
    side: Side,
    rules: GameRules,
    element: CardType? = null,
    tally: AscensionTally = AscensionTally.EMPTY,
): Int {
    val printed = card.power(side)
    val base = if (rules.fallenAce && printed == ACE_POWER) MIN_EFFECTIVE_POWER else printed
    val modifier = powerModifier(card, rules, element, tally)
    return (base + modifier).coerceIn(floorFor(rules.typeRule, base), ACE_POWER)
}

/** How low a modifier may take a card whose unmodified power is [base]. See [effectivePower]. */
private fun floorFor(rule: TypeRule, base: Int): Int = when (rule) {
    TypeRule.ASCENSION, TypeRule.DESCENSION -> minOf(base, MIN_MODIFIED_POWER)
    else -> MIN_EFFECTIVE_POWER
}
