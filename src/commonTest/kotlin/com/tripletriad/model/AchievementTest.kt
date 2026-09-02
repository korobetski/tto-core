package com.tripletriad.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [AchievementCatalog] against the 22 entries of `datas/Achievements.as`, and against this port's
 * own additions on top of them.
 *
 * The AS3 thresholds are pinned from the inline comments in its `LIST`, which state each one — the
 * only documentation of intent there is. The collection ladders have no such source and are pinned
 * as literals for the same reason: a threshold nobody wrote down is one that drifts.
 */
private const val GILGAMESH = 2128

private const val PUPU = 2096

class AchievementTest {
    /** Every collection rung this port authored — the AS3 shipped only `ac-fob`. */
    private val authoredCollections = setOf(
        "ac-fob2", "ac-fob3", "ac-fob4",
        "ac-fop1", "ac-fop2", "ac-fop3", "ac-fop4",
        "ac-fog1", "ac-fog2", "ac-fog3", "ac-fog4",
        "ac-foh1", "ac-foh2", "ac-foh3", "ac-foh4",
        "ac-foc",
    )

    @Test
    fun theCatalogueStillHoldsTheTwentyTwoAs3Achievements() {
        // The AS3 22 are the ones this port did not author: not a tournament key, and not one of
        // the collection ladder's added rungs. Counted this way rather than by total, so adding an
        // authored achievement cannot quietly pass while a ported one goes missing.
        val ported = AchievementCatalog.all
            .filterNot { it.requirement is Requirement.CampaignWins }
            .filterNot { it.id in authoredCollections }
        assertEquals(22, ported.size)
        assertEquals(
            AchievementCatalog.all.map { it.id }.distinct().size,
            AchievementCatalog.all.size,
            "ids must be unique — they are the save-file keys",
        )
        // `Achievements.list` (:16-39) and `LIST` (:45-72) are the same 22 ids in the same order.
        assertEquals("ac-tt1", ported.first().id)
        assertEquals("ac-fob", ported.last().id)
        assertNotNull(AchievementCatalog["ac-wof6"])
        assertEquals(null, AchievementCatalog["ac-nope"])
    }

    /**
     * Every rung the client's achievements screen groups into a family, and its threshold.
     *
     * Pinned as literals rather than derived from [AchievementCatalog] so that moving a tier is a
     * visible edit here. The family key is `id.trimEnd { it.isDigit() }`, which is why `ac-fob`
     * and `ac-fob2` are one family and why the first beast rung can keep its AS3 id.
     */
    @Test
    fun theCollectionLaddersRunFromTheirFirstRungToTheWholeSet() {
        val expected = mapOf(
            "ac-fob" to 13, "ac-fob2" to 21, "ac-fob3" to 28, "ac-fob4" to 35,
            "ac-fop1" to 11, "ac-fop2" to 18, "ac-fop3" to 24, "ac-fop4" to 30,
            "ac-fog1" to 10, "ac-fog2" to 17, "ac-fog3" to 23, "ac-fog4" to 28,
            "ac-foh1" to 8, "ac-foh2" to 13, "ac-foh3" to 18, "ac-foh4" to 22,
            "ac-foc" to 5,
        )

        val actual = AchievementCatalog.all
            .mapNotNull { achievement ->
                (achievement.requirement as? Requirement.CardSetOwned)
                    ?.let { achievement.id to it.target }
            }
            .toMap()

        assertEquals(expected, actual)
        assertEquals(authoredCollections + "ac-fob", actual.keys)
        for (id in expected.keys) {
            val set = (AchievementCatalog[id]!!.requirement as Requirement.CardSetOwned).cardIds
            assertTrue(expected.getValue(id) <= set.size, "$id asks for more cards than exist")
        }
    }

    /** A rung is only reachable if every rung below it is: the ladder must be monotone. */
    @Test
    fun eachRungOfALadderCountsTheSameCardsAsTheOneBelowIt() {
        val ladders = AchievementCatalog.all
            .filter { it.requirement is Requirement.CardSetOwned }
            .groupBy { it.id.trimEnd { character -> character.isDigit() } }

        for ((family, rungs) in ladders) {
            val sets = rungs.map { (it.requirement as Requirement.CardSetOwned).cardIds }
            assertEquals(1, sets.distinct().size, "$family's rungs count different pools")
            val targets = rungs.map { (it.requirement as Requirement.CardSetOwned).target }
            assertEquals(targets.sorted(), targets, "$family's rungs are out of order")
            assertEquals(targets.distinct(), targets, "$family has two rungs at one threshold")
        }
    }

    @Test
    fun theTournamentAchievementsAreEarnedByFinishingTheirLadder() {
        val balamb = assertNotNull(AchievementCatalog[AchievementCatalog.CAMPAIGN_BALAMB])
        val save = GameSave()

        assertFalse(balamb.isEarnedBy(save), "a fresh profile has won nothing")
        assertTrue(balamb.isEarnedBy(save.copy(campaignWins = mapOf("balamb" to 1))))
        assertFalse(
            balamb.isEarnedBy(save.copy(campaignWins = mapOf("cc" to 1))),
            "another ladder's win must not open this one",
        )
    }

    @Test
    fun theLabelKeysAreTheAs3I18nKeys() {
        assertEquals("STR_Triple_Team_I", AchievementCatalog["ac-tt1"]!!.labelKey)
        assertEquals("STR_Always_Bet_On_Me", AchievementCatalog["ac-wof6"]!!.labelKey)
        assertEquals("STR_Triple_decker_V", AchievementCatalog["ac-td5"]!!.labelKey)
        assertEquals("STR_FRIEND_OF_BEASTS", AchievementCatalog["ac-fob"]!!.labelKey)
        // `Achievements.as:17` — a numeric FFXIV icon id for the whole Triple Team tier.
        assertEquals("000713", AchievementCatalog["ac-tt3"]!!.iconId)
        assertEquals(
            "card_thumb_${Card.idFor(block = 1, number = 37)}",
            AchievementCatalog["ac-fob"]!!.iconId,
        )
    }

    /**
     * Every reward in the catalogue, named.
     *
     * `ac-tt3` and `ac-wof5` are the AS3's two; the rest are this port's collection ladders, which
     * pay a card at the bottom rung and MGP at the top. Pinned whole rather than by count, because
     * a reward silently attached to the wrong id is exactly the mistake a count cannot see.
     */
    @Test
    fun everyRewardIsTheOneItsAchievementIsMeantToPay() {
        val cards = AchievementCatalog.all
            .filter { it.reward != null }
            .associate { it.id to it.reward }

        assertEquals(
            mapOf(
                "ac-tt3" to CardItem(331),
                "ac-wof5" to CardItem(335),
                // Tozol Huatotl, Alexander Prime, Grynewaht, Arenvald Lentinus: one rarity-3 card
                // of each family, and Mooba for the FFVIII companions.
                "ac-fob" to CardItem(418),
                "ac-fop1" to CardItem(419),
                "ac-fog1" to CardItem(448),
                "ac-foh1" to CardItem(473),
                "ac-foc" to CardItem(2159),
            ),
            cards,
        )

        val mgp = AchievementCatalog.all
            .filter { it.mgpReward > 0 }
            .associate { it.id to it.mgpReward }

        assertEquals(
            mapOf("ac-fob4" to 5_000, "ac-fop4" to 5_000, "ac-fog4" to 5_000, "ac-foh4" to 5_000),
            mgp,
            "only a completed tribe pays MGP",
        )
        assertTrue(AchievementCatalog["ac-fob"]!!.hasReward)
        assertFalse(AchievementCatalog["ac-fob2"]!!.hasReward, "a middle rung pays nothing")
    }

    @Test
    fun npcWinTiersMatchTheAs3Thresholds() {
        val save = GameSave(npcWins = mapOf("jonas" to 30))

        assertTrue(AchievementCatalog["ac-tt1"]!!.isEarnedBy(save), "1 win")
        assertTrue(AchievementCatalog["ac-tt2"]!!.isEarnedBy(save), "30 wins")
        assertFalse(AchievementCatalog["ac-tt3"]!!.isEarnedBy(save), "300 wins")
        assertTrue(
            AchievementCatalog["ac-tt5"]!!.isEarnedBy(GameSave(npcWins = mapOf("a" to 7_777))),
        )
    }

    /**
     * `RULES_W` starts empty, so the AS3 compares `undefined >= 1` — false, which is right by
     * accident. A missing key reads as 0 here, which is right on purpose.
     */
    @Test
    fun rouletteTiersReadAMissingKeyAsZero() {
        assertFalse(AchievementCatalog["ac-wof1"]!!.isEarnedBy(GameSave()))

        val save = GameSave(rulesWins = mapOf("RULE_ROULETTE" to 300))

        assertTrue(AchievementCatalog["ac-wof5"]!!.isEarnedBy(save))
        assertFalse(AchievementCatalog["ac-wof6"]!!.isEarnedBy(save), "1000 wins")
    }

    @Test
    fun cardAndMgpTiersMatchTheAs3Thresholds() {
        val ten = GameSave(cards = (1..10).associateWith { 1 })
        val hundredAndTen = GameSave(cards = (1..110).associateWith { 1 })

        assertTrue(AchievementCatalog["ac-td1"]!!.isEarnedBy(ten))
        assertFalse(AchievementCatalog["ac-td2"]!!.isEarnedBy(ten))
        assertTrue(AchievementCatalog["ac-td5"]!!.isEarnedBy(hundredAndTen))
        assertTrue(AchievementCatalog["ac-mp1"]!!.isEarnedBy(GameSave(mgp = 1_000)))
        assertTrue(AchievementCatalog["ac-mp5"]!!.isEarnedBy(GameSave(mgp = 1_000_000)))
        assertFalse(AchievementCatalog["ac-mp5"]!!.isEarnedBy(GameSave(mgp = 999_999)))
    }

    /**
     * `ac-fob` still wants thirteen beast cards — but thirteen of **thirty-five**, not the whole
     * set. The threshold is the AS3's and a profile that earned it keeps it; the pool underneath
     * grew to every card the table types `beast`, which is what `ac-fob4` now completes.
     */
    @Test
    fun friendOfBeastsWantsThirteenOfTheThirtyFiveBeastCards() {
        assertEquals(35, AchievementCatalog.BEAST_CARDS.size)
        // The booster pool was this list and is now a subset of it — thirteen cards with frozen
        // weights. They are pinned as *different* here so that widening one cannot widen the other
        // by accident; see `AchievementCatalog.BEAST_CARDS`.
        assertTrue(
            AchievementCatalog.BEAST_CARDS.containsAll(BoosterType.BEAST.pool),
            "the pack draws from the tribe",
        )
        assertEquals(13, BoosterType.BEAST.pool.size, "the pack is still the AS3 thirteen")

        val complete = GameSave(
            cards = AchievementCatalog.BEAST_CARDS.associateWith { 1 },
        )
        assertTrue(AchievementCatalog["ac-fob"]!!.isEarnedBy(complete))
        assertTrue(AchievementCatalog["ac-fob4"]!!.isEarnedBy(complete), "the whole tribe")

        val thirteen =
            complete.copy(cards = AchievementCatalog.BEAST_CARDS.take(13).associateWith { 1 })
        assertTrue(AchievementCatalog["ac-fob"]!!.isEarnedBy(thirteen))
        assertFalse(AchievementCatalog["ac-fob2"]!!.isEarnedBy(thirteen), "21 beasts")

        val oneShort =
            complete.copy(cards = AchievementCatalog.BEAST_CARDS.take(12).associateWith { 1 })
        assertFalse(AchievementCatalog["ac-fob"]!!.isEarnedBy(oneShort))

        // The AS3 gated this on `MODE == 'ff14_'`. There is no mode, and there is no need for one:
        // the ids are global, so the thirteen beast cards are thirteen specific cards and holding
        // any other thirteen is not holding them.
        // Thirteen cards of another block, which is the closest thing to "the other table" that
        // still exists now that an id names exactly one card.
        val elsewhere = complete.copy(
            cards = (1..AchievementCatalog.BEAST_CARDS.size)
                .associate { Card.idFor(block = 8, number = it) to 1 },
        )
        assertFalse(
            AchievementCatalog["ac-fob"]!!.isEarnedBy(elsewhere),
            "other ids are other cards",
        )
    }

    @Test
    fun progressIsReportableBeforeTheThresholdIsMet() {
        val save = GameSave(npcWins = mapOf("jonas" to 15))

        val progress = AchievementCatalog["ac-tt2"]!!.progressFor(save)

        assertEquals(15, progress.current)
        assertEquals(30, progress.target)
        assertEquals(0.5f, progress.fraction)
        assertFalse(progress.isMet)
    }

    @Test
    fun progressForACardSetCountsWhatIsOwnedAgainstThatRungsTarget() {
        val save =
            GameSave(
                cards = AchievementCatalog.BEAST_CARDS.take(6).associateWith { 1 },
            )

        assertEquals(6, AchievementCatalog["ac-fob"]!!.progressFor(save).current)
        assertEquals(13, AchievementCatalog["ac-fob"]!!.progressFor(save).target)
        // The rung above counts the same six cards against a higher bar, which is what makes one
        // pool with four targets readable as a ladder.
        assertEquals(6, AchievementCatalog["ac-fob2"]!!.progressFor(save).current)
        assertEquals(21, AchievementCatalog["ac-fob2"]!!.progressFor(save).target)
    }

    @Test
    fun progressCountsOnlyTheCardsTheSetNames() {
        // Was `progressOnTheWrongModeIsZeroRatherThanPartial`, which asserted the mode gate: a
        // profile in the other collection scored 0 rather than partial credit. The gate is gone
        // with `MODE`, so what is left to state is that unrelated cards count for nothing.
        val unrelated = GameSave(cards = mapOf(Card.idFor(block = 8, number = 1) to 1))

        assertEquals(0, AchievementCatalog["ac-fob"]!!.progressFor(unrelated).current)
    }

    @Test
    fun theCompanionBadgeIsTheBoosterPoolPlusPuPu() {
        // The badge is the pack plus exactly one card the pack cannot deal, and both halves of
        // that matter. Gilgamesh must be in neither — he is a Guardian Force, and he was taken
        // out of the pack for the same reason he is not in the set. PuPu must be in the set and
        // out of the pack, so finishing the collection cannot be done at the shop counter.
        val pack = BoosterType.COMPANION.pool
        val set = AchievementCatalog.COMPANION_CARDS

        assertFalse(GILGAMESH in set, "a Guardian Force is not a companion")
        assertFalse(GILGAMESH in pack, "and the pack does not deal one either")
        assertEquals(emptyList(), pack - set.toSet(), "every card the pack deals is a companion")
        assertEquals(listOf(PUPU), set - pack.toSet(), "and the one no pack deals is PuPu")

        val save = GameSave(cards = AchievementCatalog.COMPANION_CARDS.associateWith { 1 })

        assertTrue(AchievementCatalog["ac-foc"]!!.isEarnedBy(save))
        assertFalse(
            AchievementCatalog["ac-foc"]!!.isEarnedBy(
                save.copy(cards = AchievementCatalog.COMPANION_CARDS.drop(1).associateWith { 1 }),
            ),
            "four of five is not the set",
        )
    }

    @Test
    fun aRungCannotAskForMoreCardsThanItsPoolHolds() {
        assertFailsWith<IllegalArgumentException> {
            Requirement.CardSetOwned(listOf(1, 2, 3), target = 4)
        }
        assertFailsWith<IllegalArgumentException> {
            Requirement.CardSetOwned(listOf(1, 2, 3), target = 0)
        }
    }

    @Test
    fun fractionIsCappedAtOne() {
        val save = GameSave(npcWins = mapOf("a" to 500))

        assertEquals(1f, AchievementCatalog["ac-tt2"]!!.progressFor(save).fraction)
    }

    @Test
    fun newlyEarnedExcludesWhatIsAlreadyRecorded() {
        val save = GameSave(npcWins = mapOf("jonas" to 1), achievements = mapOf("ac-tt1" to 5L))

        assertTrue(AchievementCatalog.newlyEarned(save).isEmpty())
        assertEquals(
            listOf("ac-tt1"),
            AchievementCatalog.newlyEarned(save.copy(achievements = emptyMap())).map { it.id },
        )
    }

    @Test
    fun aFreshProfileHasEarnedNothing() {
        assertTrue(AchievementCatalog.newlyEarned(GameSave.new(createdAt = 0)).isEmpty())
    }
}
