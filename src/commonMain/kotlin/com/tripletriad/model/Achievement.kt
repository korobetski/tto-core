package com.tripletriad.model

/**
 * What a profile must do to earn an achievement.
 *
 * ### Why this is data and not a lambda
 *
 * `datas/Achievements.as` evaluates all 22 conditions **in its constructor**, against the global
 * `Game.PROFILE_DATAS`:
 *
 * ```actionscript
 * LIST = [
 *     { id:"ac-tt1", ..., condition:(Game.PROFILE_DATAS.NPC_W_TOTAL >= 1) },
 *     ...
 * ];
 * ```
 *
 * `condition` is therefore a **Boolean already computed at construction time**, not a test that can
 * be re-run — so an `Achievements` object is a snapshot of one instant, and `check()` on a stale
 * one silently reports stale results. Constructing it, reading `check()` and discarding it is the
 * only correct usage, which is exactly what the AS3 screens do.
 *
 * Modelling the requirement as a sealed type instead means the catalogue is a `val` that can be
 * declared once, evaluated against any [GameSave] at any time, and — the reason that matters —
 * asked
 * *how close* the profile is, which is what a progress bar in the achievements screen needs and
 * which a pre-computed Boolean cannot answer.
 */
sealed interface Requirement {
    /** How far along [save] is, and what it is aiming at. */
    fun progress(save: GameSave): Progress

    /** [current] out of [target]; earned once `current >= target`. */
    data class Progress(val current: Int, val target: Int) {
        val isMet: Boolean get() = current >= target

        /** 0f..1f, for a progress bar. */
        val fraction: Float get() = if (target <= 0) {
            1f
        } else {
            (current.toFloat() / target).coerceIn(
                0f,
                1f,
            )
        }
    }

    /** Defeat [count] NPCs in total. `NPC_W_TOTAL` — the Triple Team tier. */
    data class NpcWins(val count: Int) : Requirement {
        override fun progress(save: GameSave) = Progress(save.npcWinsTotal, count)
    }

    /**
     * Win the tournament [campaignKey], [count] times over.
     *
     * Not an AS3 requirement — the original's ladders recorded nothing at all. Reads
     * [GameSave.campaignWins] and not [GameSave.campaignRun], because a run is cleared the moment
     * it resolves and the question asked here is what a player has *ever* finished.
     */
    data class CampaignWins(val campaignKey: String, val count: Int = 1) : Requirement {
        override fun progress(save: GameSave) =
            Progress(save.campaignWins[campaignKey] ?: 0, count)
    }

    /**
     * Win [count] matches with [ruleKey] active. `RULES_W[ruleKey]` — the Wheel of Fortune tier,
     * which uses `RULE_ROULETTE`.
     *
     * The key is an AS3 rule constant; [GameRules.activeRuleKeys] is what produces them.
     */
    data class RuleWins(val ruleKey: String, val count: Int) : Requirement {
        override fun progress(save: GameSave) = Progress(save.rulesWins[ruleKey] ?: 0, count)
    }

    /** Own [count] cards. `CARDS.length` — the Triple-decker tier. */
    data class CardsOwned(val count: Int) : Requirement {
        // `size` on the map is the count of **distinct** cards, which is what this requirement
        // means and must keep meaning: "own 50 cards" is a collection milestone, and paying it out
        // for fifty copies of one common would be a different and worse achievement. Right here by
        // construction rather than by accident, and said so because summing the values reads like
        // the obvious fix.
        override fun progress(save: GameSave) = Progress(save.cards.size, count)
    }

    /** Hold [amount] MGP at once. `MGP` — the MGP Pot tier. */
    data class MgpHeld(val amount: Int) : Requirement {
        override fun progress(save: GameSave) = Progress(save.mgp, amount)
    }

    /**
     * Own [target] of the cards in [cardIds] — every one of them, unless a smaller number is
     * named.
     *
     * `ac-fob` (`Achievements.as:71`) was the sole instance and wanted the whole set: the thirteen
     * beast cards. [target] is what makes a *ladder* out of one collection — "13 of the 35
     * beasts", then 21, then 28, then all of them — instead of one all-or-nothing badge that a
     * player collecting a 35-card tribe sees no movement on for months. It is a count over one
     * pool rather than four nested [cardIds] lists because nesting would let a tier name a card
     * the tier above it does not, and progress that can go **down** as the ladder is climbed is
     * not something the screen can draw.
     *
     * The AS3 gated it on `MODE == 'ff14_'`, and that gate is **gone**. It existed because the ids
     * meant different cards in the other table — the collision global ids removed. An id names one
     * card now, so owning the thirteen is owning the thirteen, whoever you are.
     */
    data class CardSetOwned(
        val cardIds: List<Int>,
        val target: Int = cardIds.size,
    ) : Requirement {
        init {
            require(target in 1..cardIds.size) {
                "target must be 1..${cardIds.size}, was $target"
            }
        }

        override fun progress(save: GameSave): Progress =
            Progress(cardIds.count { save.ownsCard(it) }, target)
    }
}

/**
 * One achievement definition. `Achievements.list` and `Achievements.LIST` merged.
 *
 * The AS3 keeps these as **two parallel structures**: a static `list` of `{label, iconID}` for
 * display and an instance `LIST` of `{id, label, iconID, condition, item}` for checking, with the
 * id, label and icon duplicated between them (`Achievements.as:16-39` and `:45-72`). They agree
 * today by hand. One record here.
 *
 * @property labelKey i18n key, e.g. `STR_Triple_Team_I`. Mixed case in the original, kept verbatim
 *   because it is a lookup key in the locale bundles.
 * @property iconId texture name. Note the tiers reuse `card_r{n}_icon` to signal difficulty, and
 *   `ac-fob` uses a card thumbnail, named `card_thumb_<card id>` — it was `ff14_thumb_37`, and the
 *   prefix went with the collection that justified it. The client turns the id into an atlas frame
 *   name; see `thumbTextureId`.
 * @property reward granted into the bag on earning it.
 * @property mgpReward paid straight into the purse on earning it, rather than into the bag. There
 *   is no `MgpItem` to put there and there should not be one: every other bag entry is a thing to
 *   *decide* about — use it, sell it, keep it — and money is not. A pouch ([PouchItem]) is the one
 *   exception and it exists for a reason this does not share: an auction settles while nobody is
 *   watching, so the pouch is the only notice the seller gets. An achievement announces itself.
 */
data class Achievement(
    val id: String,
    val labelKey: String,
    val iconId: String,
    val requirement: Requirement,
    val reward: Item? = null,
    val mgpReward: Int = 0,
) {
    init {
        require(mgpReward >= 0) { "$id pays a negative $mgpReward MGP" }
    }

    /** Whether earning it grants anything — what the achievements screen shows a reward for. */
    val hasReward: Boolean get() = reward != null || mgpReward > 0

    /** Whether [save] has met the requirement, whether or not it has been recorded yet. */
    fun isEarnedBy(save: GameSave): Boolean = requirement.progress(save).isMet

    /** Progress towards it, for the achievements screen. */
    fun progressFor(save: GameSave): Requirement.Progress = requirement.progress(save)
}

/**
 * The achievements: the 22 of `datas/Achievements.as` in its order, plus what this port added.
 *
 * The additions are the three tournament keys at the end, and the collection ladders — four FFXIV
 * tribes of four rungs each and one FFVIII set of one, seventeen entries where the AS3 had the
 * single `ac-fob`. See [Collection] for how a ladder is built and why the beast family's first
 * rung keeps its old id.
 *
 * Transcribed from `LIST` (`:45-72`), whose inline comments state each threshold. Two things in the
 * original that are *not* reproduced, both bugs rather than behaviour:
 *
 * - **`ac-wof*` compares `undefined >= n`.** `RULES_W` starts as `{}`, so before a single roulette
 *   win `Game.PROFILE_DATAS.RULES_W['RULE_ROULETTE']` is `undefined` and every comparison is
 *   false — which happens to be right. [Requirement.RuleWins] reads a missing key as 0, the
 *   same answer by a defined route.
 * - **`ac-td5` wants 110 cards**, but the ff14 collection has 153 and the ff8 one 110. On an ff8
 *   profile it therefore means "own every card", and on an ff14 profile it does not. Transcribed as
 *   written; the threshold is the original's design decision, not an error to fix silently.
 */
object AchievementCatalog {
    /**
     * Tier numerals, as the label keys spell them.
     *
     * First member of the object on purpose: an `object`'s properties initialise top to bottom,
     * and the [Collection] declarations below read this in their `init` block. Declared after
     * them it is still null when they run, and the whole catalogue fails to load.
     */
    private val ROMAN = listOf("I", "II", "III", "IV", "V")

    /**
     * Every card of one FFXIV tribe, and the FFVIII companions.
     *
     * ### Why these are lists here and not a query over the card table
     *
     * `:core` has no card table — [Card] is a record, and the catalogue that holds them is loaded
     * from `cards.json` in the client, which `commonMain` here cannot see and a server verifying a
     * transcript does not load. So a requirement that means "every beast card" has to name them.
     *
     * That makes the lists a **copy of a fact that lives somewhere else**, which is the failure
     * mode worth naming: the day a card's `type` is corrected in `cards.json` these silently
     * disagree with it, and the achievement quietly asks for the wrong set. `CardBundleTest` in
     * the client is what keeps them honest — it re-derives each list from the bundled `type` field
     * and fails if it differs. Change one, run that test, change the other.
     *
     * ### Not the booster pools
     *
     * [BoosterType.BEAST] and its three siblings draw from a **subset** — thirteen beasts, twelve
     * primals, ten scions, six garleans — with hand-frozen weights, and they are the sets the AS3
     * shipped. Widening a pack changes its odds and its price; widening an achievement does not.
     * [BEAST_CARDS] and `BoosterType.BEAST.pool` were the same thirteen ids and are no longer the
     * same thing, which is why they are no longer the same list.
     */
    val BEAST_CARDS: List<Int> = listOf(
        270, 271, 272, 273, 274, 276, 283, 291, 292, 293,
        338, 339, 371, 373, 384, 415, 418, 439, 440, 441,
        461, 474, 504, 514, 533, 534, 541, 551, 587, 606,
        620, 663, 665, 700, 710,
    )

    /** Every card typed `primals` in the FFXIV table. */
    val PRIMAL_CARDS: List<Int> = listOf(
        296, 297, 298, 299, 308, 309, 310, 311, 317, 353,
        354, 393, 419, 424, 438, 446, 447, 453, 484, 561,
        573, 575, 577, 593, 596, 601, 602, 612, 660, 705,
    )

    /** Every card typed `garlean` in the FFXIV table. */
    val GARLEAN_CARDS: List<Int> = listOf(
        287, 288, 303, 307, 320, 375, 412, 416, 423, 427,
        428, 431, 432, 436, 448, 454, 457, 494, 500, 548,
        550, 555, 559, 569, 576, 581, 597, 603,
    )

    /** Every card typed `scions` in the FFXIV table — the Scions of the Seventh Dawn. */
    val SCION_CARDS: List<Int> = listOf(
        275, 302, 304, 305, 306, 312, 315, 316, 378, 394,
        421, 422, 455, 456, 473, 497, 507, 525, 526, 558,
        598, 711,
    )

    /**
     * The five FFVIII companions: PuPu, Chubby Chocobo, Angelo, Mini Mog, Chicobo.
     *
     * The one set here that is **not** a `type` in `cards.json` — the FFVIII table's `type` field
     * holds elements, not tribes (see [CardType]), so there is nothing for `CardBundleTest` to
     * re-derive this from. It is authored, and it is *nearly* [BoosterType.COMPANION]'s pool —
     * Gilgamesh has been taken out of both, being a Guardian Force rather than anyone's companion.
     * The one card that separates them is **PuPu**: a level 6 monster card, so no pack deals him,
     * which is the point. A badge that four packs can hand you is a badge nobody notices earning;
     * this one ends on a card that has to be played for.
     */
    val COMPANION_CARDS: List<Int> = listOf(PUPU, 2126, 2127, 2129, 2130)

    /**
     * The thirty-five `beast` cards, at thirteen — the AS3 threshold, and the whole set as it
     * then stood — then 21, 28 and all of them.
     */
    private val BEAST_FAMILY = Collection(
        idStem = "ac-fob",
        labelStem = "APP_AC_BEASTS",
        cards = BEAST_CARDS,
        tiers = listOf(13, 21, 28, 35),
        // `ff14_thumb_37` in the original: Memeroon, and the one icon the AS3 achievements screen
        // drew from the card atlas rather than the icon sheet.
        iconId = "card_thumb_${Card.idFor(block = 1, number = 37)}",
        firstReward = TOZOL_HUATOTL,
    )

    /** The thirty `primals` cards. The rungs are the beasts' 13/21/28/35 scaled to thirty. */
    private val PRIMAL_FAMILY = Collection(
        idStem = "ac-fop",
        labelStem = "APP_AC_PRIMALS",
        cards = PRIMAL_CARDS,
        tiers = listOf(11, 18, 24, 30),
        iconId = "card_thumb_$IFRIT",
        firstReward = ALEXANDER_PRIME,
    )

    /** The twenty-eight `garlean` cards. */
    private val GARLEAN_FAMILY = Collection(
        idStem = "ac-fog",
        labelStem = "APP_AC_GARLEANS",
        cards = GARLEAN_CARDS,
        tiers = listOf(10, 17, 23, 28),
        iconId = "card_thumb_$GAIUS_VAN_BAELSAR",
        firstReward = GRYNEWAHT,
    )

    /** The twenty-two `scions` cards — the Scions of the Seventh Dawn. */
    private val SCION_FAMILY = Collection(
        idStem = "ac-foh",
        labelStem = "APP_AC_SCIONS",
        cards = SCION_CARDS,
        tiers = listOf(8, 13, 18, 22),
        iconId = "card_thumb_$YSHTOLA",
        firstReward = ARENVALD_LENTINUS,
    )

    /** The twenty-two of `datas/Achievements.as`, in its order. */
    private val PORTED: List<Achievement> = listOf(
        // Triple Team — defeat n NPCs.
        tripleTeam("ac-tt1", "STR_Triple_Team_I", 1),
        tripleTeam("ac-tt2", "STR_Triple_Team_II", 30),
        tripleTeam("ac-tt3", "STR_Triple_Team_III", 300, reward = CardItem(331)),
        tripleTeam("ac-tt4", "STR_Triple_Team_IV", 3_000),
        tripleTeam("ac-tt5", "STR_Triple_Team_V", 7_777),

        // Wheel of Fortune — win n Roulette matches.
        roulette("ac-wof1", "STR_Wheel_Of_Fortune_I", 1, "card_r1_icon"),
        roulette("ac-wof2", "STR_Wheel_Of_Fortune_II", 10, "card_r2_icon"),
        roulette("ac-wof3", "STR_Wheel_Of_Fortune_III", 30, "card_r2_icon"),
        roulette("ac-wof4", "STR_Wheel_Of_Fortune_IV", 100, "card_r3_icon"),
        roulette("ac-wof5", "STR_Wheel_Of_Fortune_V", 300, "card_r4_icon", reward = CardItem(335)),
        roulette("ac-wof6", "STR_Always_Bet_On_Me", 1_000, "card_r5_icon"),

        // Triple-decker — collect n cards.
        collector("ac-td1", "STR_Triple_decker_I", 10, "card_r1_icon"),
        collector("ac-td2", "STR_Triple_decker_II", 30, "card_r2_icon"),
        collector("ac-td3", "STR_Triple_decker_III", 60, "card_r3_icon"),
        collector("ac-td4", "STR_Triple_decker_IV", 80, "card_r4_icon"),
        collector("ac-td5", "STR_Triple_decker_V", 110, "card_r5_icon"),

        // MGP Pot — hold n MGP.
        hoard("ac-mp1", "STR_MGP_POT_I", 1_000, "card_r1_icon"),
        hoard("ac-mp2", "STR_MGP_POT_II", 10_000, "card_r2_icon"),
        hoard("ac-mp3", "STR_MGP_POT_III", 100_000, "card_r3_icon"),
        hoard("ac-mp4", "STR_MGP_POT_IV", 400_000, "card_r4_icon"),
        hoard("ac-mp5", "STR_MGP_POT_V", 1_000_000, "card_r5_icon"),

    )

    /**
     * Collections — own n of one tribe.
     *
     * `ac-fob` is the AS3's single beast badge, kept under its own id and its own threshold of
     * thirteen so a profile that earned it stays earned; what changed under it is the pool it
     * counts against, which grew from those thirteen cards to all thirty-five the table types
     * `beast`. The three tiers above it, and the three families beside it, are new.
     */
    private val LADDERS: List<Achievement> =
        collection(BEAST_FAMILY, Legacy(id = "ac-fob", labelKey = "STR_FRIEND_OF_BEASTS")) +
            collection(PRIMAL_FAMILY) +
            collection(GARLEAN_FAMILY) +
            collection(SCION_FAMILY)

    /** What this port added that is not a ladder rung. */
    private val AUTHORED: List<Achievement> = listOf(
        // FFVIII's one collection badge. A single tier, not a ladder: five cards is a weekend,
        // and three intermediate rungs over five cards would be scaffolding around nothing.
        Achievement(
            id = "ac-foc",
            labelKey = "APP_AC_COMPANIONS",
            iconId = "card_thumb_2127",
            requirement = Requirement.CardSetOwned(COMPANION_CARDS),
            reward = CardItem(MOOBA),
        ),
        // Tournaments won. Authored here rather than ported: the original recorded no ladder
        // result at all, so none of these could have existed. Each is also a key that *opens*
        // something — see `Campaign.requiresAchievement` — which is why they are one per ladder
        // rather than a single tiered "win n tournaments".
        campaign(CAMPAIGN_BALAMB, "APP_AC_CAMPAIGN_BALAMB", "balamb"),
        campaign(CAMPAIGN_CARD_CLUB, "APP_AC_CAMPAIGN_CC", "cc"),
        campaign(CAMPAIGN_GOLD_SAUCER, "APP_AC_CAMPAIGN_GS", "gs"),
    )

    val all: List<Achievement> = PORTED + LADDERS + AUTHORED

    private val byId: Map<String, Achievement> = all.associateBy { it.id }

    operator fun get(id: String): Achievement? = byId[id]

    /**
     * Achievements [save] has now met but not yet been credited with.
     *
     * `Achievements.check()` (`:75-91`) without its side effects: the AS3 version writes the
     * timestamp into `PROFILE_DATAS`, pushes the reward into `BAG`, builds a Starling `Image` and
     * re-sorts the inventory, all from inside what reads like a query. Here the query returns what
     * changed and [com.tripletriad.data.AchievementRepository] decides what to do with it, which is
     * what makes the rule testable without a profile, a texture atlas and an inventory screen.
     */
    fun newlyEarned(save: GameSave): List<Achievement> =
        all.filter { !save.hasAchievement(it.id) && it.isEarnedBy(save) }

    /**
     * The AS3 names a first rung already ships under.
     *
     * `ac-fob` predates the ladder and is recorded in live profiles under that exact string;
     * renaming it to `ac-fob1` would un-earn it for everyone who has it, and it trims to the same
     * family key regardless. Its label key is kept for the same kind of reason — a translation of
     * `STR_FRIEND_OF_BEASTS` already ships in four languages.
     */
    private class Legacy(val id: String, val labelKey: String)

    /**
     * One collection ladder: [tiers] rungs over the same [cards].
     *
     * @property idStem rungs are `${idStem}1` … `${idStem}n`, so the client's family grouping —
     *   which is `id.trimEnd { it.isDigit() }` — lands them all under [idStem].
     * @property firstReward the card paid for the first rung — a rarity-3 card of the family, and
     *   in every case one its booster pack cannot draw, so the badge is the only way to it.
     */
    private class Collection(
        val idStem: String,
        val labelStem: String,
        val cards: List<Int>,
        val tiers: List<Int>,
        val iconId: String,
        val firstReward: Int,
    ) {
        init {
            require(tiers.size in 2..ROMAN.size) { "$idStem has ${tiers.size} tiers" }
            require(tiers == tiers.sorted()) { "$idStem tiers descend: $tiers" }
            require(tiers.last() == cards.size) { "$idStem's top tier is not the whole set" }
        }
    }

    /**
     * A ladder's rungs, as achievements.
     *
     * Only the ends pay: a card at the bottom, so a player who has stumbled into a third of a
     * tribe is told there is something to finish, and [COLLECTION_MGP] at the top, which is the
     * whole point of the ladder. The two middle rungs are progress markers and are meant to be —
     * paying every rung would make a tribe the cheapest MGP in the game.
     *
     * @param legacy what the first rung is *actually* called, for the one family that predates
     *   its own ladder; every other family's first rung is named off the stems.
     */
    private fun collection(family: Collection, legacy: Legacy? = null): List<Achievement> =
        List(family.tiers.size) { index ->
            val top = index == family.tiers.lastIndex
            Achievement(
                id = when {
                    index > 0 -> "${family.idStem}${index + 1}"
                    else -> legacy?.id ?: "${family.idStem}1"
                },
                labelKey = when {
                    index > 0 -> "${family.labelStem}_${ROMAN[index]}"
                    else -> legacy?.labelKey ?: "${family.labelStem}_I"
                },
                iconId = family.iconId,
                requirement = Requirement.CardSetOwned(family.cards, family.tiers[index]),
                reward = if (index == 0) CardItem(family.firstReward) else null,
                mgpReward = if (top) COLLECTION_MGP else 0,
            )
        }

    private fun tripleTeam(id: String, labelKey: String, wins: Int, reward: Item? = null) =
        Achievement(id, labelKey, NPC_ICON, Requirement.NpcWins(wins), reward)

    private fun campaign(id: String, labelKey: String, campaignKey: String) =
        Achievement(id, labelKey, NPC_ICON, Requirement.CampaignWins(campaignKey))

    private fun roulette(
        id: String,
        labelKey: String,
        wins: Int,
        iconId: String,
        reward: Item? = null,
    ) = Achievement(id, labelKey, iconId, Requirement.RuleWins(ROULETTE_KEY, wins), reward)

    private fun collector(id: String, labelKey: String, cards: Int, iconId: String) =
        Achievement(id, labelKey, iconId, Requirement.CardsOwned(cards))

    private fun hoard(id: String, labelKey: String, mgp: Int, iconId: String) =
        Achievement(id, labelKey, iconId, Requirement.MgpHeld(mgp))

    /** `Achievements.as:17` — a numeric FFXIV icon id, not a descriptive name. */
    private const val NPC_ICON = "000713"

    /**
     * The tournament achievements, named because they are *keys*: a ladder or an opponent names
     * one in `requiresAchievement` to stay shut until it is held, so these ids are referred to
     * from data files and must not be spelled out twice.
     */
    const val CAMPAIGN_BALAMB: String = "ac-cmp-balamb"

    const val CAMPAIGN_CARD_CLUB: String = "ac-cmp-cc"

    const val CAMPAIGN_GOLD_SAUCER: String = "ac-cmp-gs"

    /** The `tripleTriadRules.as` constant, as `RULES_W` keys it. */
    private const val ROULETTE_KEY = "RULE_ROULETTE"

    /**
     * What completing any one tribe pays.
     *
     * The same figure for all four, though the tribes are 22 to 35 cards: the *card* rewards
     * differ in how hard they are to reach and the money does not need to as well, and a player
     * choosing which tribe to finish should be choosing on the cards, not on the payout.
     */
    private const val COLLECTION_MGP = 5_000

    // The first-rung cards, by name, because an id in a reward is unreadable and the wrong one is
    // invisible. Each is a rarity-3 card of its own family that its family's booster cannot draw.
    private const val TOZOL_HUATOTL = 418

    private const val ALEXANDER_PRIME = 419

    private const val GRYNEWAHT = 448

    private const val ARENVALD_LENTINUS = 473

    /** The FFVIII secret card, hidden in the collection screen until it is owned. */
    private const val MOOBA = 2159

    /**
     * FFVIII's PuPu, `STR_FF8_CARD_48` — not FFXIV's, `STR_FF14_CARD_392`, which is card 649 and a
     * different card of the same alien.
     */
    private const val PUPU = 2096

    // Family icons: one card of the family, drawn from the card atlas the way `ac-fob` always was.
    private const val IFRIT = 296

    private const val GAIUS_VAN_BAELSAR = 320

    private const val YSHTOLA = 305
}
