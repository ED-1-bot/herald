package dev.hermeskotlin.core.voice

import android.util.Log
import java.util.Locale

/**
 * TEMPORARY: what barge-in sees on a phone, for tuning it against a real microphone — the level each frame reads,
 * the room's floor, how loud the voice's own echo comes back, the bar that has to be cleared, and the trip. It's
 * logged on the dev build only (it installs beside Herald, as `….dev`, built like a release so `BuildConfig.DEBUG`
 * can't tell them apart), and a few times a second rather than every 20 ms frame, so a whole reply fits in one
 * `adb logcat -s HeraldBargeIn`.
 *
 * Nothing reads it back. Tuning ends by deleting this file, its argument on [AndroidVoiceRecorder], and the calls
 * that feed it.
 */
internal class BargeInTrace(private val enabled: Boolean) {

    private var lastAt = -1L
    private var lastPlaying = false

    /** One frame as [BargeIn] takes it: every [EVERY_MS], and at once as the voice starts or stops sounding. */
    fun frame(level: Float, bargeIn: BargeIn, playing: Boolean, nowMs: Long) {
        if (!enabled) return
        // A new listen starts its own clock, from nought.
        if (nowMs < lastAt) lastAt = -1L
        val turned = playing != lastPlaying
        if (!turned && lastAt >= 0 && nowMs - lastAt < EVERY_MS) return
        lastAt = nowMs
        lastPlaying = playing
        val line = "heard ${of(level)} floor ${of(bargeIn.roomFloor)} echo ${of(bargeIn.echo)} " +
            "bar ${of(bargeIn.trigger(playing))} playing=$playing"
        Log.d(TAG, line)
    }

    /** The bar was cleared long enough: someone is talking over the voice. */
    fun trip(level: Float, bargeIn: BargeIn, nowMs: Long) {
        if (!enabled) return
        lastAt = nowMs
        val line = "TRIPPED at ${of(level)}: bar ${of(bargeIn.trigger(playing = true))} " +
            "echo ${of(bargeIn.echo)} floor ${of(bargeIn.roomFloor)}"
        Log.d(TAG, line)
    }

    private fun of(level: Float) = String.format(Locale.ROOT, "%.3f", level)

    private companion object {
        const val TAG = "HeraldBargeIn"
        const val EVERY_MS = 200L
    }
}
