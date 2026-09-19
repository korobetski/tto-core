package com.tripletriad.model

/**
 * The deeds a settlement can record — see [GameSave.deeds] for why they are not derived.
 *
 * Each is recognised where the event it names is settled, and nowhere else: the one card that has
 * to leave a collection for [ZANTETSUKEN] leaves it only in a player-versus-player settlement, so
 * that is the only credit path that asks.
 */
object Deeds {
    /**
     * Losing FFVIII's Odin to a trade rule.
     *
     * The nod is to the original: at Lunatic Pandora, Seifer cuts Odin down with his own
     * Zantetsuken, and Gilgamesh turns up in later battles wielding it. Here the card goes the same
     * way — whoever takes it is Seifer — and the hidden achievement of the same name pays
     * Gilgamesh, the one FFVIII card nothing else in the game can give.
     */
    const val ZANTETSUKEN: String = "zantetsuken"

    /** FFVIII's Odin, `STR_FF8_CARD_92` — not FFXIV's, which is card 308. */
    const val ODIN_FF8: Int = 2140

    /** What losing [cardsLost] in a settlement amounts to. */
    fun fromCardsLost(cardsLost: List<Int>): Set<String> =
        if (ODIN_FF8 in cardsLost) setOf(ZANTETSUKEN) else emptySet()
}
