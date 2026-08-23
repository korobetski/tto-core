package com.tripletriad.data

/**
 * The two shipped rule pools, as fixtures.
 *
 * These lists used to be `Roulette.pools`, compiled into the engine. They are content now — the
 * client ships `formats.json` and the server reads a copy — so this module has no data of its own
 * to test against, and states what it expects instead.
 *
 * **Not a second source of truth.** `FormatBundleTest` in the client holds the shipped file to the
 * same content, so a drift between them is caught there. These exist to let `:core`'s own tests
 * assemble a match with no resource loader — the same reason its `Npc` fixtures are written inline
 * rather than read from `npcs.json`.
 */
internal object TestFormats {
    /**
     * The two shipped blocks. `CardCollection` used to name them; a block is just an integer.
     *
     * FFVIII sits at 8 rather than 2 so that FFXIV, which has 454 cards and therefore needs two
     * blocks, can grow contiguously from 1 without ever colliding with it — see [CardSet].
     */
    const val FF14_BLOCK: Int = 1
    const val FF8_BLOCK: Int = 8

    val ff14: Format = Format(
        id = "ff14-standard",
        nameKey = "APP_FORMAT_FF14_STANDARD",
        // Both of FFXIV's blocks — see the class KDoc. A format admitting only the first would
        // hide the second from `ShopCatalog.offers` and from anything else that filters by format.
        blocks = listOf(FF14_BLOCK, 2),
        rules = listOf(
            "RULE_ALL_OPEN",
            "RULE_ASCENSION",
            "RULE_CHAOS",
            "RULE_DESCENSION",
            "RULE_FALLEN_ACE",
            "RULE_ORDER",
            "RULE_PLUS",
            "RULE_RANDOM",
            "RULE_REVERSE",
            "RULE_SAME",
            "RULE_SUDDEN_DEATH",
            "RULE_SWAP",
            "RULE_THREE_OPEN",
        ),
    )

    val ff8: Format = Format(
        id = "ff8-standard",
        nameKey = "APP_FORMAT_FF8_STANDARD",
        blocks = listOf(FF8_BLOCK),
        rules = listOf(
            "RULE_ALL_OPEN",
            "RULE_ELEMENTAL",
            "RULE_PLUS",
            "RULE_RANDOM",
            "RULE_SAME",
            "RULE_SAME_WALL",
            "RULE_SUDDEN_DEATH",
            "RULE_THREE_OPEN",
        ),
    )

    val catalog: FormatCatalog = FormatCatalog(listOf(ff14, ff8))
}
