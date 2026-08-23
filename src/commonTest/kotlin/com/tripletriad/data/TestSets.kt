package com.tripletriad.data

/**
 * The two shipped blocks, for a fixture catalog.
 *
 * One declaration rather than one per test file: a [CardCatalog] now needs its sets as well as its
 * cards, and four copies of the same two rows is four places to disagree about what block `ff8` is.
 * Tests that care about set metadata — released flags, sort order — build their own.
 */
internal val TEST_SETS: List<CardSet> = listOf(
    CardSet(
        blocks = listOf(1),
        slug = "ff14",
        nameKey = "APP_SET_FF14",
        sortOrder = 1,
        released = true,
    ),
    CardSet(
        blocks = listOf(8),
        slug = "ff8",
        nameKey = "APP_SET_FF8",
        sortOrder = 2,
        released = true,
    ),
)
