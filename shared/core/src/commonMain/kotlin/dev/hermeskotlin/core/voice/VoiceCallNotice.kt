package dev.hermeskotlin.core.voice

import dev.hermeskotlin.core.chat.SessionUsage
import dev.hermeskotlin.core.chat.TurnUsage
import dev.hermeskotlin.core.chat.compactCount
import kotlin.math.roundToInt

/**
 * What the ongoing notification of a held voice chat says, the way a phone call's own says who is on the line and
 * how long it has run: how the chat is going, and what it has spent so far.
 *
 * The words are made where the phases are known (the voice controller owns them); this carries them, and the
 * numbers are worked out here so they can be read without Android.
 */
data class VoiceCallNotice(
    /** The call's own line, e.g. "Talking with Hermes" or "Hermes is thinking…". */
    val line: String,
    /** What this turn has spent and how fast it came; null before it has spent anything. */
    val detail: String? = null,
    /** How full the model's context is, under [detail]; null when the gateway hasn't said. */
    val context: String? = null,
    /** The microphone is off, so the actions offer Unmute rather than Mute. */
    val muted: Boolean = false,
    /** When the chat began: the notification's timer counts from it. */
    val startedAt: Long = 0L,
)

/**
 * "2.4k in · 1.1k out · 96 tok/s": what [turn] spent, and [perSecond] the pace it came at. Null while the turn has
 * spent nothing, since a line of zeroes says less than an empty one.
 */
fun voiceCallDetail(turn: TurnUsage?, perSecond: Int? = null): String? {
    if (turn == null || !turn.any) return null
    val spent = "${compactCount(turn.input)} in · ${compactCount(turn.output)} out"
    return if (perSecond == null) spent else "$spent · $perSecond tok/s"
}

/** "36% context", or null when the gateway hasn't said how full the window is. */
fun voiceCallContext(usage: SessionUsage?): String? = usage?.contextPercent?.let { "$it% context" }

/**
 * [output] tokens over [seconds] of the turn so far, in tokens a second. Null before the turn has run a moment, and
 * while it has written nothing: a pace from a sample too short to divide by is noise, not a rate.
 */
fun tokensPerSecond(output: Long, seconds: Double): Int? {
    if (output <= 0 || seconds < 1.0) return null
    return (output / seconds).roundToInt()
}
