package com.tripletriad.model

import com.tripletriad.time.isoDate
import com.tripletriad.time.utcDayNumber
import com.tripletriad.time.utcWeekNumber
import com.tripletriad.time.utcWeekStart
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * What one finished match was, as far as a quest is concerned.
 *
 * The reason a quest cannot reuse [Requirement]: that reads a [GameSave], and a save records
 * *lifetime* totals — `npcWinsTotal`, `rulesWins[key]`, `cards.size`. That is right for an
 * achievement, which is a ratchet, and structurally incapable of expressing "three wins **today**",
 * because no field on the save is date-bucketed and no expression over one can mean "since
 * midnight".
 *
 * So a daily objective observes the *match* instead, and the counting is incremental. Both keys
 * here are the ones the rest of the codebase already uses — the NPC **icon** id, as `NPC_W` and
 * `NpcCatalog.byIcon` do because numeric ids are not unique across tables, and the AS3 rule
 * constant, as `RULES_W` and [GameRules.activeRuleKeys] do — so nothing introduces a third naming
 * scheme.
 *
 * @property isPvp whether this was a match against another player. Always false today:
 *   [com.tripletriad.data.MatchRewards.credit] takes an `Npc` and is PvE by construction. Player
 *   versus player builds its own event when it exists.
 */
data class MatchEvent(
    val result: MatchResult,
    val opponentIconId: String,
    val ruleKeys: List<String>,
    val isPvp: Boolean = false,
)

/**
 * What a daily quest asks for.
 *
 * A sealed hierarchy rather than a predicate, for the reason [Requirement] is one: a quest is
 * *data* both ends hold, and `RewardSummary` sends ids on the strength of that. A lambda could not
 * be sent, compared, or listed in a catalogue.
 *
 * [credits] returns units, not a boolean, so a "win three" quest and a "win one" quest are the same
 * code with a different [target].
 */
sealed interface Objective {
    /** How many units complete it. */
    val target: Int

    /** How many units [event] contributes — 0 or 1. */
    fun credits(event: MatchEvent): Int

    /** Finish [target] matches, won or lost. The one that cannot be failed, only skipped. */
    data class MatchesPlayed(override val target: Int) : Objective {
        override fun credits(event: MatchEvent): Int = 1
    }

    data class MatchesWon(override val target: Int) : Objective {
        override fun credits(event: MatchEvent): Int = if (event.result == MatchResult.WIN) 1 else 0
    }

    /**
     * Beat one named opponent, by [Npc.iconId].
     *
     * Feasibility is the catalogue's problem, not this type's: an opponent behind an availability
     * window or a level gate can be unreachable today, so only opponents that are always reachable
     * get one of these. See [DailyQuestCatalog].
     */
    data class BeatOpponent(val iconId: String, override val target: Int = 1) : Objective {
        override fun credits(event: MatchEvent): Int =
            if (event.result == MatchResult.WIN && event.opponentIconId == iconId) 1 else 0
    }

    /** Win with [ruleKey] in force — the key [GameRules.activeRuleKeys] produces. */
    data class WinWithRule(val ruleKey: String, override val target: Int = 1) : Objective {
        override fun credits(event: MatchEvent): Int =
            if (event.result == MatchResult.WIN && ruleKey in event.ruleKeys) 1 else 0
    }

    /**
     * Play a match against another player.
     *
     * **Sealed now, drawn later.** Player versus player does not exist yet, so this is in the
     * hierarchy and out of [DailyQuestCatalog.assignable] — see there. Sealing it now is the cheap
     * moment: every `when` over `Objective` and every stored id is decided once, rather than in a
     * later release that also has to think about a catalogue changing mid-day.
     */
    /**
     * A class rather than an object, so a week can ask for five of them.
     *
     * Nothing serialises an [Objective] — a log stores quest ids and both ends hold the catalogue —
     * so the target is free to move without a version. `PlayPvpMatch()` with the default is exactly
     * the object this replaced.
     */
    data class PlayPvpMatch(override val target: Int = 1) : Objective {
        override fun credits(event: MatchEvent): Int = if (event.isPvp) 1 else 0
    }
}

/** What finishing a quest pays. The same three currencies a match already pays in. */
data class QuestReward(val mgp: Int = 0, val xp: Int = 0, val item: Item? = null)

/**
 * One quest definition — the daily counterpart of [Achievement].
 *
 * @property labelKey i18n key. Interpolated for the objectives that name something: a
 *   [Objective.BeatOpponent] label takes the opponent's name as `{0}`.
 */
data class DailyQuest(
    val id: String,
    val labelKey: String,
    val iconId: String,
    val objective: Objective,
    val reward: QuestReward,
)

/**
 * A character's quests for one UTC day.
 *
 * ### Why this is on the save
 *
 * Because [com.tripletriad.data.MatchRewards.credit] is a pure `GameSave -> GameSave` and is the
 * **one authoritative credit path**, run by the client optimistically and by the server against the
 * stored profile. State that is not on the save cannot be read or written there, and putting it
 * elsewhere would force a second credit path — the thing that arrangement exists to prevent. It
 * would also leave an offline profile, which has no server at all, with nowhere to keep progress.
 *
 * The server keeps the whole profile as one JSONB document and writes it in the same transaction as
 * the match row, so quest progress and the match that produced it commit or fail together.
 *
 * ### Why the assignment is recorded and not only derived
 *
 * [DailyQuestCatalog.forDay] is deterministic, so both ends can compute today's draw with no
 * endpoint and no write — which is what lets the screen show them before a single match. But the
 * moment one is credited the draw is **pinned** here, and from then on the record is the truth. A
 * catalogue that changed under a player's feet at noon would otherwise move the goalposts on quests
 * they had already half-finished.
 *
 * @property period the stretch this record is about, as `YYYY-MM-DD`. A day for the daily log and
 *   the **Monday** of the week for the weekly one — see [questWeekOf]. Empty on a profile that has
 *   never been credited. The serial name is still `DAY`, which is what the field was called when a
 *   day was the only period there was; renaming it on the wire would strand every stored profile
 *   for no gain.
 * @property questIds the period's draw, pinned.
 * @property progress quest id to units credited in this period.
 * @property completed quest id to the instant it paid out — deliberately the exact shape of
 *   `ACHIEVEMENTS`, so the two read the same way.
 */
@Serializable
data class QuestLog(
    @SerialName("DAY") val period: String = "",
    @SerialName("IDS") val questIds: List<String> = emptyList(),
    @SerialName("PROGRESS") val progress: Map<String, Int> = emptyMap(),
    @SerialName("DONE") val completed: Map<String, Long> = emptyMap(),
) {
    /** Units credited towards [questId] in this period. */
    fun progressOf(questId: String): Int = progress[questId] ?: 0

    /** Whether [questId] has already paid out in this period. */
    fun isCompleted(questId: String): Boolean = questId in completed

    /**
     * This record if it is already about [to], or a fresh one drawn from [draw] if it is not.
     *
     * The rollover, and the only place it happens — for both periods, which is the whole reason
     * this type stopped being called `DailyQuests`. Progress and completions go with the old
     * period: that is what "daily" and "weekly" both mean, and carrying either across is how a
     * quest pays twice.
     */
    fun rolledTo(to: String, draw: () -> List<String>): QuestLog =
        if (period == to) this else QuestLog(period = to, questIds = draw())
}

/**
 * The quests that can be drawn, and the draw itself.
 *
 * ### Why this is Kotlin and not JSON
 *
 * The same reason [AchievementCatalog] is: a quest carries an [Objective], which is an evaluation
 * rule rather than a value, and `RewardSummary` sends ids only *because* both ends hold this
 * catalogue. A JSON file would also need a third copy under the server's own resources, beside the
 * two of `cards.json` — a duplication the server's `Catalogs` already flags as an unsolved problem.
 *
 * ### The draw
 *
 * [forDay] is a seeded sample over [assignable], and its inputs are the UTC day and the character's
 * creation date. Both ends compute the same three quests with no request and no write.
 *
 * `creationDate` is the per-character salt because it is the only identifier that is on the save,
 * set once by the server, and never changes. Not the username, which is case-normalised and one day
 * renameable; not an account id, which `:core` does not have and an offline profile does not
 * either. And nothing volatile — a level or a purse would be an assignment that changes in the
 * middle of the day a player is working through it.
 */
object DailyQuestCatalog {
    /** How many quests a day offers. Three is enough to choose between and few enough to finish. */
    const val PER_DAY: Int = 3

    /**
     * Every quest, including ones that cannot currently be drawn.
     *
     * The rewards are calibrated against what a match pays: a win is 64 MGP at the median of the
     * FFXIV table (48 for FFVIII, 182 at the top), the cheapest card on the shelf is 120 and a
     * bronze pack 520. So a day's three quests come to roughly three to five matches' worth —
     * enough to be worth returning for, not enough to make the shop irrelevant.
     */
    val all: List<DailyQuest> = listOf(
        quest("q-play-3", "APP_QUEST_PLAY_3", Objective.MatchesPlayed(3), mgp = 150),
        quest("q-play-5", "APP_QUEST_PLAY_5", Objective.MatchesPlayed(5), mgp = 220),
        quest("q-win-1", "APP_QUEST_WIN_1", Objective.MatchesWon(1), mgp = 150),
        quest("q-win-3", "APP_QUEST_WIN_3", Objective.MatchesWon(3), mgp = 250),
        quest("q-win-5", "APP_QUEST_WIN_5", Objective.MatchesWon(5), mgp = 400),
        quest("q-beat-tt-master", "APP_QUEST_BEAT", Objective.BeatOpponent(TT_MASTER), mgp = 200),
        quest("q-beat-jonas", "APP_QUEST_BEAT", Objective.BeatOpponent("jonas"), mgp = 200),
        quest("q-beat-maisenta", "APP_QUEST_BEAT", Objective.BeatOpponent("maisenta"), mgp = 200),
        quest("q-rule-same", "APP_QUEST_RULE", Objective.WinWithRule("RULE_SAME"), mgp = 200),
        quest("q-rule-plus", "APP_QUEST_RULE", Objective.WinWithRule("RULE_PLUS"), mgp = 200),
        quest("q-rule-open", "APP_QUEST_RULE", Objective.WinWithRule("RULE_ALL_OPEN"), mgp = 150),
        quest("q-rule-three-open", "APP_QUEST_RULE", Objective.WinWithRule(THREE_OPEN), mgp = 150),
        quest("q-rule-swap", "APP_QUEST_RULE", Objective.WinWithRule("RULE_SWAP"), mgp = 200),
        quest("q-rule-chaos", "APP_QUEST_RULE", Objective.WinWithRule("RULE_CHAOS"), mgp = 200),
        quest("q-rule-ascension", "APP_QUEST_RULE", Objective.WinWithRule(ASCENSION), mgp = 200),
        quest("q-rule-fallen-ace", "APP_QUEST_RULE", Objective.WinWithRule(FALLEN_ACE), mgp = 220),
        quest("q-rule-elemental", "APP_QUEST_RULE", Objective.WinWithRule(ELEMENTAL), mgp = 220),
        quest("q-pvp-1", "APP_QUEST_PVP_1", Objective.PlayPvpMatch(), mgp = 250),
    )

    /**
     * Those a day may actually draw, which is now all of them.
     *
     * This used to exclude `q-pvp-1`: player versus player did not exist, and a quest that cannot
     * be finished is a third of a day's offer wasted, every day, for every player. **PvP ships**,
     * so the exclusion is history rather than policy.
     *
     * The property stays rather than collapsing into [all]. They answer different questions — what
     * exists, and what may be offered today — and they will part again the moment a quest is
     * retired, seasonal, or gated on something a profile has not reached.
     *
     * ⚠️ **A drawn PvP quest is finishable only with a server.** An offline profile can be offered
     * one it cannot complete, and that is the price of the filter going: the alternative is a draw
     * that depends on connectivity, which would change under a player the moment their train went
     * into a tunnel. A fixed draw they cannot finish today is better than a shifting one.
     */
    val assignable: List<DailyQuest> get() = all

    private val byId: Map<String, DailyQuest> = all.associateBy { it.id }

    operator fun get(id: String): DailyQuest? = byId[id]

    /**
     * The [PER_DAY] quests [characterCreatedAt]'s character is offered on the UTC day holding [at].
     *
     * Deterministic: the same inputs give the same draw on every device and on the server, for as
     * long as [assignable] does not change. When it does, days already pinned in
     * [QuestLog.questIds] keep what they were given — see there.
     */
    fun forDay(at: Long, characterCreatedAt: Long): List<DailyQuest> {
        val pool = assignable
        if (pool.size <= PER_DAY) return pool
        return pool.shuffled(Random(seedFor(at, characterCreatedAt))).take(PER_DAY)
    }

    /** [forDay] as ids, which is what gets pinned. */
    fun idsForDay(at: Long, characterCreatedAt: Long): List<String> =
        forDay(at, characterCreatedAt).map { it.id }

    /**
     * The draw's seed.
     *
     * The two inputs are mixed rather than added so that a character created on day *n* and one
     * created on day *n + 1* do not simply see each other's yesterday.
     */
    private fun seedFor(at: Long, characterCreatedAt: Long): Int =
        (utcDayNumber(at) * SEED_MIX xor characterCreatedAt).toInt()

    private fun quest(id: String, labelKey: String, objective: Objective, mgp: Int) =
        DailyQuest(id, labelKey, QUEST_ICON, objective, QuestReward(mgp = mgp))

    /** `tt-master` is the first opponent of the FFXIV table and is available at every hour. */
    private const val TT_MASTER = "tt-master"

    /*
     * The rules a day's draw may ask for, and why these and not the other seven.
     *
     * A quest is only a quest if it can be finished today. Which rules are on offer is a property
     * of `npcs.json` — an opponent brings their own list — so a "win with Order" drawn on a roster
     * where five opponents in a hundred and fifty-eight play it is a quest that expires unmet more
     * often than not. Counted over the shipped roster, these are the rules at least a dozen
     * opponents bring: Plus 57, Same 43, Three Open 22, Swap 19, Chaos 18, All Open 15,
     * Ascension 12, Fallen Ace 11, Elemental 11. Roulette can still produce any of them, which is
     * what keeps the thin end reachable at all rather than what makes it worth asking for.
     *
     * Named as constants only where the literal is long enough to wrap the call.
     */
    private const val THREE_OPEN = "RULE_THREE_OPEN"
    private const val ASCENSION = "RULE_ASCENSION"
    private const val FALLEN_ACE = "RULE_FALLEN_ACE"
    private const val ELEMENTAL = "RULE_ELEMENTAL"

    /** The same numeric FFXIV icon the Triple Team achievements use. */
    private const val QUEST_ICON = "000713"

    /** An odd multiplier, so the day and the creation date do not cancel in the low bits. */
    private const val SEED_MIX = 0x9E37_79B9L
}

/** Today's UTC day key, as [QuestLog.day] stores it. */
fun questDayOf(at: Long): String = isoDate(at)

/**
 * The week a quest counts in: the Monday of it, UTC — see [utcWeekStart].
 *
 * The same shape as [questDayOf] on purpose. Both are stored in a [QuestLog.period], both are read
 * by the same repository, and a support question about either one is answered by reading the field.
 */
fun questWeekOf(at: Long): String = utcWeekStart(at)

/**
 * The long haul, drawn once a week.
 *
 * ### One at a time, and why it is not three
 *
 * A day's three are a menu: miss one and the other two still pay. A week's is a *commitment* — it
 * is meant to sit at the top of the quest screen for seven days and be the thing the player is
 * working towards while the dailies come and go. Three of those would be a second daily list with a
 * longer fuse, and the screen would stop having a headline.
 *
 * ### The targets are the point
 *
 * Every objective here already exists — nothing new travels on the wire, and a client from before
 * this catalogue reads a weekly log it does not recognise as an empty draw rather than as an error.
 * What makes them weekly is the size: fifteen wins is a fortnight of casual play or three good
 * evenings, and the reward is worth roughly what a week of dailies pays, so neither crowds out the
 * other.
 *
 * The rules named are the ones the roster actually brings — the same measurement
 * [DailyQuestCatalog] made, and for the same reason: a week-long quest nobody can finish is worse
 * than a day-long one nobody can finish by exactly seven times.
 */
object WeeklyQuestCatalog {
    const val PER_WEEK: Int = 1

    val all: List<DailyQuest> = listOf(
        weekly("w-win-15", "APP_QUEST_WEEK_WIN", Objective.MatchesWon(15), mgp = 2_000),
        weekly("w-play-25", "APP_QUEST_WEEK_PLAY", Objective.MatchesPlayed(25), mgp = 1_500),
        weekly("w-rule-same-5", "APP_QUEST_WEEK_RULE", Objective.WinWithRule(SAME, 5), mgp = 1_800),
        weekly("w-rule-plus-5", "APP_QUEST_WEEK_RULE", Objective.WinWithRule(PLUS, 5), mgp = 1_800),
        weekly("w-beat-master-5", "APP_QUEST_WEEK_BEAT", Objective.BeatOpponent(MASTER, 5), 1_800),
        weekly("w-pvp-5", "APP_QUEST_WEEK_PVP", Objective.PlayPvpMatch(5), mgp = 2_200),
    )

    operator fun get(id: String): DailyQuest? = byId[id]

    /**
     * This week's draw, computed rather than stored — the same contract [DailyQuestCatalog.forDay]
     * has, and for the same reason: the screen can show the week's quest before a single match has
     * been credited, and both ends agree on it without an endpoint.
     */
    fun forWeek(at: Long, characterCreatedAt: Long): List<DailyQuest> =
        all.shuffled(Random(seedFor(at, characterCreatedAt))).take(PER_WEEK)

    fun idsForWeek(at: Long, characterCreatedAt: Long): List<String> =
        forWeek(at, characterCreatedAt).map { it.id }

    /**
     * Seeded from the **week** and the character, so two characters on one device are not handed
     * the same fortnight and one character is not handed the same quest twice running.
     *
     * **[utcWeekNumber] and not `utcDayNumber / 7`**, which is the same week only by accident: the
     * epoch is a Thursday, so dividing day numbers cuts the week on one and the draw would change
     * on the Thursday of every week the log is still counting. The period key and the seed have to
     * come from one definition of "which week", and that is it.
     *
     * A different mixing constant from the daily draw's, so a character created on a Monday is not
     * handed a week and a day that move in step.
     */
    private fun seedFor(at: Long, characterCreatedAt: Long): Int =
        (utcWeekNumber(at) * SEED_MIX xor characterCreatedAt).toInt()

    private val byId: Map<String, DailyQuest> = all.associateBy { it.id }

    private fun weekly(id: String, labelKey: String, objective: Objective, mgp: Int) =
        DailyQuest(id, labelKey, QUEST_ICON, objective, QuestReward(mgp = mgp))

    private const val SAME = "RULE_SAME"
    private const val PLUS = "RULE_PLUS"
    private const val MASTER = "tt-master"
    private const val QUEST_ICON = "000713"
    private const val SEED_MIX = 0x85EB_CA6BL
}
