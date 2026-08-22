package com.tripletriad.data

import com.tripletriad.model.Availability
import com.tripletriad.model.NpcLevel
import com.tripletriad.model.PotionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [NpcCatalogParser] against a fragment of the real `npcs.json`, so this runs on every target.
 *
 * The bundled file in full is covered by `NpcBundleTest`, which needs the resource loader. What is
 * pinned here is the **wire shape** the extractor emits — a renamed field would otherwise read as
 * its default and an opponent would quietly lose its rules.
 */
class NpcCatalogTest {
    /**
     * Copied from the extractor's output for `NPCs.as` entries 1 and 8, plus one from the other
     * table — and each now declaring the format it plays, which is what replaced the two arrays.
     */
    private val json = """
        {
          "npcs": [
            {
              "id": 1,
              "name": "STR_NPC_TT_Master",
              "iconID": "tt-master",
              "formats": ["ff14-standard"],
              "rules": ["RULE_ALL_OPEN"],
              "fetishesCards": [2, 4, 5, 7, 13],
              "cards": [],
              "level": "STR_NPC_LEVEL_NOVICE",
              "matchFee": 5,
              "MGPReward": { "w": 10, "d": 4, "l": 1 },
              "itemRewards": [
                { "potion": "MGP_BOOST", "type": "potion", "rate": 0.02 },
                { "card": 4, "type": "card", "rate": 0.25 }
              ],
              "difficulty": 1
            },
            {
              "id": 8,
              "name": "STR_NPC_Trachtoum",
              "iconID": "tratchoum",
              "formats": ["ff14-standard"],
              "rules": ["RULE_THREE_OPEN"],
              "fetishesCards": [41, 49, 39],
              "cards": [38, 22, 47, 20],
              "level": "STR_NPC_LEVEL_AVERAGE",
              "matchFee": 20,
              "MGPReward": { "w": 47, "d": 18, "l": 7 },
              "itemRewards": [],
              "difficulty": 5,
              "availability": { "begins": 20, "ends": 8 }
            },
            {
              "id": 1,
              "name": "STR_NPC_KID",
              "iconID": "kid",
              "formats": ["ff8-standard"],
              "rules": ["RULE_ALL_OPEN"],
              "fetishesCards": [],
              "cards": [1, 2, 3],
              "level": "STR_NPC_LEVEL_NOVICE",
              "matchFee": 5,
              "MGPReward": { "w": 15, "d": 7, "l": 2 },
              "itemRewards": [],
              "difficulty": 0
            },
            {
              "id": 99,
              "name": "APP_NPC_ISHTAR",
              "iconID": "ishtar",
              "formats": ["ff8-standard"],
              "rules": ["RULE_RANDOM", "RULE_ROULETTE"],
              "fetishesCards": [],
              "cards": [1, 2, 3],
              "level": "STR_NPC_LEVEL_EXPERT",
              "matchFee": 30,
              "MGPReward": { "w": 128, "d": 56, "l": 28 },
              "itemRewards": [],
              "difficulty": 0,
              "requiresAchievement": "ac-cmp-cc"
            }
          ]
        }
    """.trimIndent()

    private val catalog = NpcCatalogParser.parse(json)

    @Test
    fun everyFieldOfTheExtractorsShapeIsRead() {
        val master = catalog.all.first()

        assertEquals(1, master.id)
        assertEquals("STR_NPC_TT_Master", master.nameKey)
        assertEquals("tt-master", master.iconId)
        assertEquals(listOf("RULE_ALL_OPEN"), master.ruleKeys)
        assertEquals(listOf(2, 4, 5, 7, 13), master.fetishCards)
        assertTrue(master.cards.isEmpty())
        assertEquals(NpcLevel.NOVICE, master.level)
        assertEquals(5, master.matchFee)
        assertEquals(10, master.mgpReward.win)
        assertEquals(4, master.mgpReward.draw)
        assertEquals(1, master.mgpReward.lose)
        assertEquals(1, master.difficulty)
        assertEquals(2, master.itemRewards.size)
        assertEquals(PotionType.MGP, master.itemRewards.first().potion)
        assertEquals(0.02, master.itemRewards.first().rate)
        assertEquals(4, master.itemRewards[1].cardId)
    }

    /** Absent in most entries, so it must default rather than fail to parse. */
    @Test
    fun anAbsentAvailabilityDefaultsToAlways() {
        assertEquals(Availability.Always, catalog.all.first().availability)
        assertTrue(catalog.all.first().availability.isAlwaysAvailable)
    }

    @Test
    fun aDeclaredAvailabilityIsReadAsHours() {
        val window = catalog.all[1].availability

        assertEquals(20, window.begins)
        assertEquals(8, window.ends)
        assertTrue(window.wrapsMidnight)
    }

    @Test
    fun collectionsAreSelectedByMode() {
        assertEquals(2, catalog.playing(FF14).size)
        assertEquals(2, catalog.playing(FF8).size)
        assertEquals(4, catalog.all.size)
    }

    /** Ids repeat across the ff8 table, so lookup is by icon. */
    @Test
    fun opponentsAreFoundByIconWithinTheirCollection() {
        assertEquals("STR_NPC_TT_Master", catalog.byIcon("tt-master", FF14)?.nameKey)
        assertNull(catalog.byIcon("tt-master", FF8), "collections do not leak")
        assertNull(catalog.byIcon("no-such-npc", FF14))
    }

    /** `NPCs.toListCollection()` sorts on difficulty, then match fee, then name. */
    @Test
    fun theAvailableListIsSortedAsTheAs3ListIs() {
        val available = catalog.available(FF14, hour = 21, level = ANY_LEVEL)

        assertEquals(listOf("tt-master", "tratchoum"), available.map { it.iconId })
    }

    @Test
    fun anOpponentOutsideItsWindowIsNotListed() {
        // Trachtoum runs 20:00-08:00, so it is absent at noon and present at 23:00.
        assertEquals(
            listOf("tt-master"),
            catalog.available(FF14, hour = 12, level = ANY_LEVEL).map {
                it.iconId
            },
        )
        assertTrue(
            catalog.available(FF14, hour = 23, level = ANY_LEVEL)
                .any { it.iconId == "tratchoum" },
        )
    }

    /**
     * The level gate: difficulty at most one above the character's own level.
     *
     * The fixture holds a difficulty-1 and a difficulty-5 opponent, so the boundary is walkable —
     * level 4 is the first that reaches 5, and level 3 is the last that does not.
     */
    @Test
    fun anOpponentTooHardForTheCharactersLevelIsNotListed() {
        fun icons(level: Int) =
            catalog.available(FF14, hour = 23, level = level).map { it.iconId }

        assertEquals(listOf("tt-master"), icons(level = 1), "a new character sees the easiest")
        assertEquals(listOf("tt-master"), icons(level = 3), "and still not the difficulty-5 one")
        assertEquals(
            listOf("tt-master", "tratchoum"),
            icons(level = 4),
            "level 4 reaches difficulty 5",
        )
    }

    /** What the list says under itself. Counted over the same window test — see `lockedByLevel`. */
    @Test
    fun theOnesHeldBackAreCounted() {
        assertEquals(1, catalog.lockedByLevel(FF14, hour = 23, level = 1))
        assertEquals(0, catalog.lockedByLevel(FF14, hour = 23, level = 4))
        // Trachtoum is out of its window at noon, so it is not "locked" — it is simply not around.
        assertEquals(0, catalog.lockedByLevel(FF14, hour = 12, level = 1))
    }

    /**
     * An opponent behind an achievement is off the list until it is held.
     *
     * The default matters as much as the filter: [NpcCatalog.available] takes no achievements at
     * all unless it is given some, so a caller that forgets a profile under-lists. Getting that
     * backwards would offer a match the server refuses.
     */
    @Test
    fun anUnearnedOpponentIsNotListed() {
        fun icons(earned: Set<String>) =
            catalog.available(FF8, hour = 12, level = ANY_LEVEL, earned = earned).map { it.iconId }

        assertEquals(listOf("kid"), icons(emptySet()), "unearned by default")
        assertEquals(listOf("kid"), icons(setOf("ac-cmp-balamb")), "and by the wrong achievement")
        assertEquals(
            listOf("kid", "ishtar"),
            icons(setOf(CARD_CLUB)),
            "winning the Card Club is what opens her",
        )
    }

    /**
     * The unearned are counted apart from the level-locked, and the two say different things.
     *
     * Folding them together would tell a player that Ishtar "opens up as you level", which is the
     * one thing levelling will never do — see `NpcCatalog.lockedByAchievement`.
     */
    @Test
    fun theUnearnedAreCountedApartFromTheLevelLocked() {
        assertEquals(1, catalog.lockedByAchievement(FF8, earned = emptySet()))
        assertEquals(0, catalog.lockedByAchievement(FF8, earned = setOf(CARD_CLUB)))
        assertEquals(0, catalog.lockedByAchievement(FF14, earned = emptySet()), "none in ff14")

        // The FFVIII table declares difficulty 0 throughout, so nothing there is level-locked —
        // which is what makes this the cleanest place to show the two counts are independent.
        assertEquals(0, catalog.lockedByLevel(FF8, hour = 12, level = 1))
    }

    /** `ignoreUnknownKeys`, so a field this build does not know about does not break parsing. */
    @Test
    fun anUnknownFieldIsIgnored() {
        val extended = """
            {"npcs":[{"id":1,"name":"n","iconID":"i","formats":["f"],"futureField":42}]}
        """.trimIndent()

        assertEquals(1, NpcCatalogParser.parse(extended).all.single().id)
    }

    /** Every field but the three identifying ones has a default, so a sparse entry still loads. */
    @Test
    fun aSparseEntryLoadsWithDefaults() {
        val sparse = """{"npcs":[{"id":9,"name":"n","iconID":"i"}]}"""

        val npc = NpcCatalogParser.parse(sparse).all.single()

        assertEquals(NpcLevel.NONE, npc.level)
        assertEquals(0, npc.matchFee)
        assertTrue(npc.ruleKeys.isEmpty())
        assertTrue(npc.itemRewards.isEmpty())
        assertEquals(Availability.Always, npc.availability)
    }
}

/**
 * A level high enough that [NpcCatalog.available]'s gate cannot bite.
 *
 * The tests that pass it are about the **hour** window or about a named opponent, and would
 * otherwise be asserting the level rule by accident. The gate has its own cases above.
 */
/** The fixture's two formats. Ids, because that is what an opponent declares now. */
private const val FF14: String = "ff14-standard"
private const val FF8: String = "ff8-standard"

private const val ANY_LEVEL: Int = 99

/** What the fixture's one gated opponent is waiting on. `AchievementCatalog.CAMPAIGN_CARD_CLUB`.
 * Spelled out rather than referenced, so this stays a test of the *wire shape*.
 */
private const val CARD_CLUB: String = "ac-cmp-cc"
