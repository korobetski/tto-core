package com.tripletriad.model

import kotlin.random.Random

/**
 * The rule roulette — `tripleTriadRules.roulette` (`tripleTriadRules.as:36-114`).
 *
 * It **adds** one to three random rules to a rule set rather than generating one from scratch.
 * The only caller passes the opponent's own rules in and reassigns the result
 * (`BaseMatchScreen.as:64-66`):
 *
 * ```actionscript
 * if (RULES.ROULETTE) {
 *     RULES = tripleTriadRules.roulette(Game.PROFILE_DATAS.MODE, RULES);
 * }
 * ```
 *
 * So an opponent that declares `RULE_ROULETTE` plays with everything it already declared *plus*
 * the draw. The roulette can never take a rule away, and eleven of the 85 shipped opponents use
 * it. The signature's `gameRules = null` branch — which would build a default set — is never
 * reached; [augment] covers it anyway, since `augment(GameRules(), …)` is exactly that.
 *
 * See [game-rules.md](../../../../../../../docs/analysis/game-rules.md) § 11.
 */
object Roulette {
    /** `1 + tools.rand(2)` (`:53`), inclusive at both ends. */
    const val MIN_DRAWS: Int = 1
    const val MAX_DRAWS: Int = 3

    /**
     * The pools this used to hold are **gone**, and the note is worth keeping.
     *
     * A `Map<CardCollection, List<String>>` lived here and was the legal rule set per collection,
     * not merely a list of roulette candidates — Same Wall and Elemental were FFVIII
     * only, seven others FFXIV only, six common to both, and `NpcBundleTest` held the shipped
     * opponents to it. That is a property of the **pool of cards being played with**, which is what
     * a format is, so it moved to [com.tripletriad.data.Format.rules] and the caller hands it in.
     *
     * One behaviour of the original did not survive and did not survive here either: the AS3
     * indexes its pool with `tools.rand(length - 1)`, which halves the odds of the first and last
     * entry (§ 15.6), so **All Open and Three Open were drawn half as often as everything else**.
     * The draw below is uniform, which means generated rule sets do not match the original's
     * distribution. Deliberate, and recorded because it looks like a bug when found later.
     */

    /**
     * [rules] plus one to three rules drawn from [pool].
     *
     * Drawn **with replacement**, as the original is: the same rule twice yields fewer than three
     * effective additions, and two draws sharing a slot overwrite each other, so `RULE_ORDER` then
     * `RULE_CHAOS` leaves only Chaos. Both follow from [GameRules.withRuleKey] and neither is
     * worked around — the number of *draws* is what the original randomises, not the number of new
     * rules.
     *
     * [GameRules.roulette] is set on the result. The AS3 only sets it on the branch that builds a
     * fresh rule set, because the live path had it set already — that is what gated the call. It
     * matters because `Save.DATAS.RULES_W['RULE_ROULETTE']` is what the Wheel of Fortune
     * achievements count ([AchievementCatalog]), so a set that went through the roulette has to say
     * so or a win under it goes uncounted.
     */
    fun augment(
        rules: GameRules,
        pool: List<String>,
        random: Random = Random.Default,
    ): GameRules {
        require(pool.isNotEmpty()) { "a roulette needs a pool to draw from" }
        val draws = random.nextInt(MIN_DRAWS, MAX_DRAWS + 1)
        var result = rules.copy(roulette = true)
        repeat(draws) {
            result = result.withRuleKey(pool[random.nextInt(pool.size)])
        }
        return result
    }
}
