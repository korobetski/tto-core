package com.tripletriad.protocol

import com.tripletriad.model.Capture
import com.tripletriad.model.CardColor
import kotlinx.serialization.Serializable

/**
 * The two pieces of a refereed board that both kinds of match put on the wire.
 *
 * ### Why they moved out of `PvpMatch.kt`
 *
 * They were written for player-versus-player because that was the only refereed match there was. It
 * is not any more: the environment match is refereed too, and a `PveMatchView` holding a type
 * called `PvpCell` would be a name that actively misleads — the reader's first question would be
 * which player-versus-player match it came from, and the answer is none.
 *
 * Nothing about either type was ever specific to two people. A cell is a card and an owner; a
 * placement is what one move did. Both are true of a match against a program.
 *
 * The old names survive as aliases in `PvpMatch.kt`, so no caller had to be touched and **no
 * payload changed**: an alias is the same type, and kotlinx.serialization writes field names rather
 * than class names for a non-polymorphic class. A rename in the source, a no-op on the wire.
 */

/** A card in a cell, as two integers: which card, and whose it is now. */
@Serializable
data class BoardCell(val cardId: Int, val owner: CardColor)

/**
 * One placement, as [com.tripletriad.model.PlayResult] travels.
 *
 * ### Why the captures have to be sent rather than recomputed
 *
 * A client that runs the engine learns what a move flipped as a side effect of making it. A
 * refereed client never runs the engine at all — and against a program, *neither* placement is one
 * it made. Recomputing from two consecutive boards would recover **which** cards changed hands but
 * not [Capture.kind] or [Capture.wave], and those are the whole content of the Same, Plus and Combo
 * announcements: a flip is a flip on the board, and only the resolution knows it was a combo.
 *
 * So the engine's own answer is what travels. This is the one place the protocol carries something
 * derived rather than something stated, and it earns it: the derivation is not reversible.
 *
 * @property cardId the card placed, by id, as everything else on this wire is.
 * @property handIndex which slot it came out of. Not a leak — the card is face up on the board
 *   now — and it is what [com.tripletriad.model.HandVisibility.afterPlaying] needs to keep an Open
 *   hand lined up once the hand closes the gap behind it.
 */
@Serializable
data class Placement(
    val player: CardColor,
    val cardId: Int,
    val position: Int,
    val captures: List<Capture> = emptyList(),
    val handIndex: Int = 0,
)
