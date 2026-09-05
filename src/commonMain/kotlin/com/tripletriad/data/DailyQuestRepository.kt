package com.tripletriad.data

import com.tripletriad.model.DailyQuest
import com.tripletriad.model.DailyQuestCatalog
import com.tripletriad.model.GameSave
import com.tripletriad.model.MatchEvent
import com.tripletriad.model.QuestLog
import com.tripletriad.model.Requirement
import com.tripletriad.model.WeeklyQuestCatalog
import com.tripletriad.model.questDayOf
import com.tripletriad.model.questWeekOf

/**
 * One quest and how far along it is — the daily counterpart of [AchievementStatus].
 *
 * [Requirement.Progress] is reused rather than reinvented: the *evaluation* rule is what differs
 * between an achievement and a quest, and a progress bar reads the same either way.
 *
 * @property completedAt when it paid out, or null.
 */
data class DailyQuestStatus(
    val quest: DailyQuest,
    val progress: Requirement.Progress,
    val completedAt: Long?,
) {
    val isCompleted: Boolean get() = completedAt != null
}

/** The profile after a credit, and what it paid out. */
data class QuestAward(val save: GameSave, val completed: List<DailyQuest>)

/**
 * Rolling the day over, counting a match towards the day's quests, and paying for the ones it
 * finished.
 *
 * Deliberately shaped like [AchievementRepository], because it sits beside it in the one credit
 * path and a reader who knows that one should not have to learn a second vocabulary. The one real
 * difference is the input: an achievement reads the whole [GameSave], a quest reads a [MatchEvent],
 * for the reason `MatchEvent` documents.
 */
/**
 * Which of the two stretches a repository is working in.
 *
 * ### One rule, two periods
 *
 * Crediting a quest is the same six steps whichever it is: roll the log over if the period has
 * turned, advance every unfinished quest the event credits, complete the ones that reached their
 * target, pay them, and write the log back. Two repositories would be two copies of those steps,
 * and the first thing that would drift between them is what "already completed" means.
 *
 * So the differences are named here and nowhere else: the catalogue, the period key, and where on
 * the profile the log lives.
 */
internal enum class QuestPeriod {
    DAILY,
    WEEKLY,
    ;

    fun keyAt(at: Long): String = when (this) {
        DAILY -> questDayOf(at)
        WEEKLY -> questWeekOf(at)
    }

    fun draw(at: Long, createdAt: Long): List<String> = when (this) {
        DAILY -> DailyQuestCatalog.idsForDay(at, createdAt)
        WEEKLY -> WeeklyQuestCatalog.idsForWeek(at, createdAt)
    }

    fun quest(id: String): DailyQuest? = when (this) {
        DAILY -> DailyQuestCatalog[id]
        WEEKLY -> WeeklyQuestCatalog[id]
    }

    fun logOf(save: GameSave): QuestLog = when (this) {
        DAILY -> save.quests
        WEEKLY -> save.weekly
    }

    fun written(save: GameSave, log: QuestLog): GameSave = when (this) {
        DAILY -> save.withQuests(log)
        WEEKLY -> save.withWeeklyQuests(log)
    }
}

class WeeklyQuestRepository : QuestRepository(QuestPeriod.WEEKLY)

class DailyQuestRepository : QuestRepository(QuestPeriod.DAILY)

/**
 * The rule, once. See [QuestPeriod] for what the two subclasses differ by.
 *
 * `open` rather than a function taking a period, so a call site reads as the thing it is about —
 * `DailyQuestRepository().credit(...)` — which is what every existing caller already says.
 */
abstract class QuestRepository internal constructor(private val period: QuestPeriod) {
    // No constructor parameter for the catalogue, unlike [AchievementRepository]: that one takes a
    // `List<Achievement>` a test can substitute, and this one's equivalent would be an object with
    // no state that only ever has one value. A test that needs a known draw controls the seed —
    // the day and the creation date — which has the merit of exercising the shipped content.

    /**
     * The day's quests and their progress, without writing anything.
     *
     * What a screen renders. When the record is for an older day — or for none, on a profile that
     * has never been credited — the day's draw is **derived** and shown at zero, so a player who
     * has not played yet still sees what is on offer. The record is written by [credit], on the
     * first match of the day and not before.
     */
    fun statuses(save: GameSave, at: Long): List<DailyQuestStatus> {
        val now = period.keyAt(at)
        val record = period.logOf(save).takeIf { it.period == now }
        val ids = record?.questIds ?: period.draw(at, save.creationDate)

        return ids.mapNotNull { id ->
            period.quest(id)?.let { quest ->
                DailyQuestStatus(
                    quest = quest,
                    progress = Requirement.Progress(
                        current = record?.progressOf(id) ?: 0,
                        target = quest.objective.target,
                    ),
                    completedAt = record?.completed?.get(id),
                )
            }
        }
    }

    /**
     * Counts [event] towards today's quests and pays for any it finished.
     *
     * The order inside is the whole of it: roll the day over first, so a match played after
     * midnight counts towards the new day's quests rather than yesterday's; then count; then pay.
     *
     * Idempotent in the same sense [AchievementRepository.credit] is — a quest already in
     * `completed` is skipped, so a queue that drains twice cannot pay twice. It is **not**
     * idempotent per match, and must not be: two matches are two units, which is the point.
     *
     * ### One round is enough, and that is a property of the data
     *
     * Paying a quest cannot complete another. No [com.tripletriad.model.Objective] reads the save
     * at all — they read a [MatchEvent] — so MGP, XP or a bag item cannot move any of them.
     * `DailyQuestRepositoryTest` pins that, so the day a reward-reading objective is added, the
     * assumption fails loudly rather than paying one match late.
     *
     * @param at epoch millis. Decides the day, and is recorded as the moment each quest paid.
     */
    fun credit(save: GameSave, event: MatchEvent, at: Long): QuestAward {
        val rolled = period.logOf(save).rolledTo(period.keyAt(at)) {
            period.draw(at, save.creationDate)
        }

        val progress = rolled.progress.toMutableMap()
        val completed = rolled.completed.toMutableMap()
        val finished = mutableListOf<DailyQuest>()

        // The three skips are folded into one filter rather than three `continue`s: already paid,
        // not in the catalogue any more, and not advanced by this match are all "nothing to do".
        val advanced = rolled.questIds
            .filterNot { it in completed }
            .mapNotNull { id -> period.quest(id)?.let { id to it } }
            .map { (id, quest) -> Triple(id, quest, quest.objective.credits(event)) }
            .filter { (_, _, credited) -> credited > 0 }

        for ((id, quest, credited) in advanced) {
            val total = (progress[id] ?: 0) + credited
            progress[id] = total
            if (total >= quest.objective.target) {
                completed[id] = at
                finished += quest
            }
        }

        var updated = period.written(
            save,
            rolled.copy(progress = progress.toMap(), completed = completed.toMap()),
        )
        for (quest in finished) {
            updated = updated.withMgp(quest.reward.mgp).withXp(quest.reward.xp.toLong())
            quest.reward.item?.let { updated = Inventory.add(updated, it) }
        }
        return QuestAward(updated, finished)
    }
}
