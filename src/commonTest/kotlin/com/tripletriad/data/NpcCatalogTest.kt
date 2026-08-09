package com.tripletriad.data

import com.tripletriad.model.Availability
import com.tripletriad.model.CardCollection
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
    /** Copied from the extractor's output for `NPCs.as` entry 1 and entry 8. */
    private val json = """
        {
          "ff14": [
            {
              "id": 1,
              "name": "STR_NPC_TT_Master",
              "iconID": "tt-master",
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
              "rules": ["RULE_THREE_OPEN"],
              "fetishesCards": [41, 49, 39],
              "cards": [38, 22, 47, 20],
              "level": "STR_NPC_LEVEL_AVERAGE",
              "matchFee": 20,
              "MGPReward": { "w": 47, "d": 18, "l": 7 },
              "itemRewards": [],
              "difficulty": 5,
              "availability": { "begins": 20, "ends": 8 }
            }
          ],
          "ff8": [
            {
              "id": 1,
              "name": "STR_NPC_KID",
              "iconID": "kid",
              "rules": ["RULE_ALL_OPEN"],
              "fetishesCards": [],
              "cards": [1, 2, 3],
              "level": "STR_NPC_LEVEL_NOVICE",
              "matchFee": 5,
              "MGPReward": { "w": 15, "d": 7, "l": 2 },
              "itemRewards": [],
              "difficulty": 0
            }
          ]
        }
    """.trimIndent()

    private val catalog = NpcCatalogParser.parse(json)

    @Test
    fun everyFieldOfTheExtractorsShapeIsRead() {
        val master = catalog.ff14.first()

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
        assertEquals(Availability.Always, catalog.ff14.first().availability)
        assertTrue(catalog.ff14.first().availability.isAlwaysAvailable)
    }

    @Test
    fun aDeclaredAvailabilityIsReadAsHours() {
        val window = catalog.ff14[1].availability

        assertEquals(20, window.begins)
        assertEquals(8, window.ends)
        assertTrue(window.wrapsMidnight)
    }

    @Test
    fun collectionsAreSelectedByMode() {
        assertEquals(2, catalog.collection(CardCollection.FF14).size)
        assertEquals(1, catalog.collection(CardCollection.FF8).size)
        assertEquals(3, catalog.all.size)
    }

    /** Ids repeat across the ff8 table, so lookup is by icon. */
    @Test
    fun opponentsAreFoundByIconWithinTheirCollection() {
        assertEquals("STR_NPC_TT_Master", catalog.byIcon("tt-master", CardCollection.FF14)?.nameKey)
        assertNull(catalog.byIcon("tt-master", CardCollection.FF8), "collections do not leak")
        assertNull(catalog.byIcon("no-such-npc", CardCollection.FF14))
    }

    /** `NPCs.toListCollection()` sorts on difficulty, then match fee, then name. */
    @Test
    fun theAvailableListIsSortedAsTheAs3ListIs() {
        val available = catalog.available(CardCollection.FF14, hour = 21, level = ANY_LEVEL)

        assertEquals(listOf("tt-master", "tratchoum"), available.map { it.iconId })
    }

    @Test
    fun anOpponentOutsideItsWindowIsNotListed() {
        // Trachtoum runs 20:00-08:00, so it is absent at noon and present at 23:00.
        assertEquals(
            listOf("tt-master"),
            catalog.available(CardCollection.FF14, hour = 12, level = ANY_LEVEL).map {
                it.iconId
            },
        )
        assertTrue(
            catalog.available(CardCollection.FF14, hour = 23, level = ANY_LEVEL)
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
            catalog.available(CardCollection.FF14, hour = 23, level = level).map { it.iconId }

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
        assertEquals(1, catalog.lockedByLevel(CardCollection.FF14, hour = 23, level = 1))
        assertEquals(0, catalog.lockedByLevel(CardCollection.FF14, hour = 23, level = 4))
        // Trachtoum is out of its window at noon, so it is not "locked" — it is simply not around.
        assertEquals(0, catalog.lockedByLevel(CardCollection.FF14, hour = 12, level = 1))
    }

    /** `ignoreUnknownKeys`, so a field this build does not know about does not break parsing. */
    @Test
    fun anUnknownFieldIsIgnored() {
        val extended = """
            {"ff14":[{"id":1,"name":"n","iconID":"i","futureField":42}],"ff8":[]}
        """.trimIndent()

        assertEquals(1, NpcCatalogParser.parse(extended).ff14.single().id)
    }

    /** Every field but the three identifying ones has a default, so a sparse entry still loads. */
    @Test
    fun aSparseEntryLoadsWithDefaults() {
        val sparse = """{"ff14":[{"id":9,"name":"n","iconID":"i"}],"ff8":[]}"""

        val npc = NpcCatalogParser.parse(sparse).ff14.single()

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
private const val ANY_LEVEL: Int = 99
