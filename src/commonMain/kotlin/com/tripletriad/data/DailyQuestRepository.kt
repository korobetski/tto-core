package com.tripletriad.data

import com.tripletriad.model.DailyQuest
import com.tripletriad.model.DailyQuestCatalog
import com.tripletriad.model.GameSave
import com.tripletriad.model.MatchEvent
import com.tripletriad.model.Requirement
import com.tripletriad.model.questDayOf

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
class DailyQuestRepository {
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
        val today = questDayOf(at)
        val record = save.quests.takeIf { it.day == today }
        val ids = record?.questIds ?: DailyQuestCatalog.idsForDay(at, save.creationDate)

        return ids.mapNotNull { id ->
            DailyQuestCatalog[id]?.let { quest ->
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
        val today = questDayOf(at)
        val rolled = save.quests.rolledTo(today) {
            DailyQuestCatalog.idsForDay(at, save.creationDate)
        }

        val progress = rolled.progress.toMutableMap()
        val completed = rolled.completed.toMutableMap()
        val finished = mutableListOf<DailyQuest>()

        // The three skips are folded into one filter rather than three `continue`s: already paid,
        // not in the catalogue any more, and not advanced by this match are all "nothing to do".
        val advanced = rolled.questIds
            .filterNot { it in completed }
            .mapNotNull { id -> DailyQuestCatalog[id]?.let { id to it } }
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

        var updated = save.withQuests(
            rolled.copy(progress = progress.toMap(), completed = completed.toMap()),
        )
        for (quest in finished) {
            updated = updated.withMgp(quest.reward.mgp).withXp(quest.reward.xp.toLong())
            quest.reward.item?.let { updated = Inventory.add(updated, it) }
        }
        return QuestAward(updated, finished)
    }
}
