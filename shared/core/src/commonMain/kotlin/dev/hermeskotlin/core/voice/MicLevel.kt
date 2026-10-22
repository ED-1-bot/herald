package dev.hermeskotlin.core.voice

/**
 * The call microphone's frames in the levels barge-in is tuned for, where 1.0 is loud speech: a frame's RMS against
 * the loudest the user's own voice has been on this phone, rather than against a fixed count of full scale.
 *
 * What it replaces divided every frame by 42 × 256 — Desktop's 42, for 8-bit audio — which only means anything on a
 * browser's microphone. A phone lying on a table gives quiet speech an RMS near 500 of 32 767, which read 0.046:
 * at or under [BargeIn.PLAYBACK_MIN_LEVEL], so someone speaking softly could never cut in, however long they went
 * on. Against the voice this microphone actually hears, the same speech stands a third of the way up, while the
 * room's hum stays near the bottom where the detector keeps its floor. The scale is still linear, so the
 * proportions the detector's multipliers were tuned for hold; only what 1.0 means is measured, not assumed.
 *
 * The loudest speech ([LOUD_PERCENTILE]) is 1.0, so no phone's speech can sit out of the detector's reach however
 * quiet its microphone is. The middle of it ([TYPICAL_PERCENTILE]) is [speechLevel]: what the bar the detector
 * raises over the voice's echo has to stay under, so ordinary speech still clears it. Until the user has been heard,
 * [DEFAULT_LOUD_RMS] stands in — Desktop's loud speech, which is what the detector's constants were tuned against.
 */
internal class MicLevel {

    /** Where the user's voice has been on this microphone, raw RMS: the newest [SPEECH_SAMPLES] of it. */
    private val samples = ArrayDeque<Float>()
    private var sinceRead = 0
    private var loudRms = DEFAULT_LOUD_RMS
    private var typicalRms = DEFAULT_LOUD_RMS * DEFAULT_SPEECH_LEVEL

    /**
     * The user's own speech in the levels barge-in works in, 1.0 being their loudest; [DEFAULT_SPEECH_LEVEL] until
     * this microphone has heard them. Over a loud echo the bar climbs, and this is where it stops.
     */
    val speechLevel: Float get() = (typicalRms / loudRms).coerceIn(MIN_SPEECH_LEVEL, 1f)

    /** One frame's [rms] as a level, 1.0 at the loudest speech this microphone has heard. */
    fun level(rms: Float): Float = (rms / loudRms).coerceIn(0f, 1f)

    /**
     * A frame the user's own voice was in, as [VoiceRecorder.record] measures it: what speech sounds like on this
     * phone. A turn or two of theirs sets what 1.0 is, and with it the bar barge-in raises over the voice's echo.
     */
    fun heard(rms: Float) {
        if (rms <= 0f || !rms.isFinite()) return
        samples.addLast(rms)
        if (samples.size > SPEECH_SAMPLES) samples.removeFirst()
        // Read twice a second at most, not every frame: what speech sounds like doesn't move that fast.
        if (++sinceRead >= REFRESH_EVERY) read()
    }

    private fun read() {
        sinceRead = 0
        val sorted = samples.sorted()
        loudRms = sorted[rank(sorted, LOUD_PERCENTILE)].coerceAtLeast(MIN_LOUD_RMS)
        typicalRms = sorted[rank(sorted, TYPICAL_PERCENTILE)].coerceAtMost(loudRms)
    }

    private fun rank(sorted: List<Float>, fraction: Float) =
        ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.lastIndex)

    private companion object {
        /** 42 × 256: where a browser's microphone has loud speech, and what the levels used to divide by. */
        const val DEFAULT_LOUD_RMS = 42f * 256f
        /** Ordinary speech, as a share of the loudest, until this microphone has heard the user. */
        const val DEFAULT_SPEECH_LEVEL = 0.5f
        /** However quiet the microphone, speech is never counted as less than this: it's all noise below. */
        const val MIN_LOUD_RMS = 256f
        const val MIN_SPEECH_LEVEL = 0.1f
        const val LOUD_PERCENTILE = 0.9f
        const val TYPICAL_PERCENTILE = 0.5f
        /** Ten seconds of 20 ms frames. */
        const val SPEECH_SAMPLES = 500
        /** Frames between readings: twice a second at most. */
        const val REFRESH_EVERY = 25
    }
}
