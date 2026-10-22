package dev.hermeskotlin.core.voice

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BargeInTest {

    /** A [BargeIn] fed 20 ms frames, the clock carried from one stretch to the next. */
    private class Mic(val bargeIn: BargeIn = BargeIn()) {
        var now = 0L

        /** Feeds [ms] of [level]; when it tripped or finished in that stretch, and which, or null. */
        fun feed(level: Float, ms: Long, playing: Boolean = false): Pair<Long, BargeIn.Step>? {
            val until = now + ms
            while (now < until) {
                now += 20
                val step = bargeIn.onFrame(level, now, playing)
                if (step == BargeIn.Step.Tripped || step == BargeIn.Step.Done) return now to step
            }
            return null
        }
    }

    @Test
    fun speechThatHoldsCutsIn() {
        val mic = Mic()
        assertNull(mic.feed(0.01f, 1_000))
        val (at, step) = assertNotNull(mic.feed(0.2f, 1_000))
        assertEquals(BargeIn.Step.Tripped, step)
        // Most of 300 ms of it, not the first frame.
        assertTrue(at - 1_000 in 240L..340L, "tripped ${at - 1_000} ms in")
    }

    @Test
    fun aBlipDoesNotCutIn() {
        val mic = Mic()
        assertNull(mic.feed(0.01f, 1_000))
        assertNull(mic.feed(0.3f, 120))
        assertNull(mic.feed(0.01f, 2_000))
    }

    @Test
    fun theVoicesBleedAloneDoesNotCutIn() {
        val mic = Mic()
        assertNull(mic.feed(0.01f, 1_000))
        // Over the quiet room's bar, under the one while the voice plays.
        assertNull(mic.feed(0.07f, 5_000, playing = true))
        assertNotNull(mic.feed(0.2f, 1_000, playing = true))
    }

    @Test
    fun theVoicesFirstMomentDoesNotCutIn() {
        val mic = Mic()
        assertNull(mic.feed(0.01f, 1_000))
        // The voice starting is loud for a moment, inside the grace.
        assertNull(mic.feed(0.2f, 400, playing = true))
        assertNull(mic.feed(0.02f, 1_000, playing = true))
    }

    @Test
    fun theFloorIsNotMeasuredOverTheVoice() {
        val mic = Mic()
        assertNull(mic.feed(0.01f, 1_000))
        assertNull(mic.feed(0.08f, 5_000, playing = true))
        // Had the bleed lifted the floor, quiet speech after the voice stopped would be out of reach.
        assertNull(mic.feed(0.01f, 300))
        assertNotNull(mic.feed(0.06f, 1_000))
    }

    @Test
    fun speechStaysWithinReachInALoudRoom() {
        val bargeIn = BargeIn()
        val mic = Mic(bargeIn)
        assertNull(mic.feed(0.1f, 1_000))
        assertEquals(BargeIn.TRIGGER_CEILING_LEVEL, bargeIn.trigger(playing = true))
        // The voice is quiet under the room's noise, so the bar stays at the cap.
        assertNull(mic.feed(0.1f, 1_000, playing = true))
        assertNotNull(mic.feed(0.3f, 1_000, playing = true))
    }

    /** A loudspeaker with no echo cancelling: its own voice comes back, and mustn't pass for someone talking. */
    @Test
    fun aLoudEchoRaisesTheBarAboveItself() {
        val bargeIn = BargeIn()
        val mic = Mic(bargeIn)
        assertNull(mic.feed(0.01f, 1_000))
        // Syllables well over the stock bar, the whole reply long.
        repeat(20) {
            assertNull(mic.feed(0.2f, 200, playing = true))
            assertNull(mic.feed(0.08f, 200, playing = true))
        }
        assertTrue(bargeIn.trigger(playing = true) > 0.2f)
        // Someone talking clearly over it still cuts in.
        assertNotNull(mic.feed(0.9f, 1_000, playing = true))
    }

    /**
     * The echo comes back louder than the bar may go over it: the bar stops under the user's own speech anyway, so
     * their voice still cuts in, and the echo only holds the voice for a moment where it trips it.
     */
    @Test
    fun anEchoLouderThanTheBarMayGoLeavesSpeechInReach() {
        // Half of what this phone hears the user say, at their loudest.
        val bargeIn = BargeIn(speechLevel = 0.4f)
        val mic = Mic(bargeIn)
        assertNull(mic.feed(0.01f, 1_000))
        // Syllables over the bar, the whole reply long: they may not lift it above half their ordinary speech.
        repeat(10) {
            assertNull(mic.feed(0.35f, 120, playing = true), "cut in on its echo")
            assertNull(mic.feed(0.1f, 280, playing = true))
        }
        assertEquals(0.4f * BargeIn.REACHABLE_SHARE, bargeIn.trigger(playing = true), 0.0001f)
        // Their ordinary speech, quieter than the echo, still reaches the bar.
        assertNotNull(mic.feed(0.25f, 1_000, playing = true))
    }

    /**
     * Turned right up with no echo cancelling to speak of: syllables louder than the bar may go over them. The bar
     * can't climb above speech's own reach to cover them, so it trips — but its echo stops with the voice, nobody
     * went on talking, and the voice is only held a moment before going on ([NOBODY_AFTER_MS]).
     */
    @Test
    fun anEchoTheBarCantCoverHoldsTheVoiceForAMoment() {
        val bargeIn = BargeIn()
        val mic = Mic(bargeIn)
        assertNull(mic.feed(0.01f, 1_000))
        val (_, step) = assertNotNull(mic.feed(0.6f, 2_000, playing = true))
        assertEquals(BargeIn.Step.Tripped, step)
        // The voice held, and its echo with it: nobody is there.
        assertEquals(BargeIn.Step.Done, assertNotNull(mic.feed(0.02f, 3_000)).second)
        assertFalse(bargeIn.spoke)
    }

    /**
     * Its loud syllables stand over the bar the voice's softer echo leaves: the bar may not climb past where
     * someone talking can reach it, so those syllables are held to a moment's trip like any loud echo.
     */
    @Test
    fun anEchoWhoseLoudSyllablesStandOverTheBarDoesNotCutIn() {
        val mic = Mic()
        assertNull(mic.feed(0.01f, 1_000))
        repeat(20) {
            assertNull(mic.feed(0.28f, 120, playing = true))
            assertNull(mic.feed(0.12f, 280, playing = true))
        }
    }

    /** It starts softly and gets loud once the grace is over, past the echo it learnt first. */
    @Test
    fun aVoiceThatGetsLouderDoesNotCutInOnItself() {
        val mic = Mic()
        assertNull(mic.feed(0.01f, 1_000))
        assertNull(mic.feed(0.15f, 600, playing = true))
        repeat(15) {
            assertNull(mic.feed(0.28f, 120, playing = true))
            assertNull(mic.feed(0.12f, 280, playing = true))
        }
        // Someone talking over it, and staying there, still cuts in.
        assertNotNull(mic.feed(0.9f, 1_000, playing = true))
    }

    /** However loud the speaker, the bar stays where someone talking can reach it. */
    @Test
    fun aVeryLoudEchoLeavesTheBarInReach() {
        val bargeIn = BargeIn()
        val mic = Mic(bargeIn)
        assertNull(mic.feed(0.01f, 1_000))
        // A loudspeaker's syllables, each louder than the bar may go, with quieter ones between them.
        repeat(20) {
            assertNull(mic.feed(0.8f, 120, playing = true))
            assertNull(mic.feed(0.15f, 280, playing = true))
        }
        assertEquals(BargeIn.TRIGGER_CEILING_LEVEL, bargeIn.trigger(playing = true))
        assertNotNull(mic.feed(0.5f, 1_000, playing = true))
    }

    /** Someone talking a little under the bar isn't learnt as the echo, so it doesn't climb away as they try. */
    @Test
    fun talkingUnderTheBarDoesNotRaiseIt() {
        val bargeIn = BargeIn()
        val mic = Mic(bargeIn)
        assertNull(mic.feed(0.01f, 1_000))
        repeat(5) {
            assertNull(mic.feed(0.15f, 200, playing = true))
            assertNull(mic.feed(0.07f, 200, playing = true))
        }
        val bar = bargeIn.trigger(playing = true)
        assertNull(mic.feed(bar * 0.9f, 2_000, playing = true))
        assertEquals(bar, bargeIn.trigger(playing = true))
        // Then a little louder, and it cuts in.
        assertNotNull(mic.feed(bar * 1.15f, 1_000, playing = true))
    }

    /** A listen that starts over the voice never heard the room quiet; its hum, once the voice holds, isn't someone. */
    @Test
    fun aRoomsHumOnceTheVoiceHoldsIsNotSomeone() {
        val bargeIn = BargeIn()
        val mic = Mic(bargeIn)
        assertEquals(BargeIn.Step.Tripped, assertNotNull(mic.feed(0.6f, 2_000, playing = true)).second)
        assertEquals(BargeIn.Step.Done, assertNotNull(mic.feed(0.045f, 2_000)).second)
        assertFalse(bargeIn.spoke)
    }

    /** The room's floor carries over from the chat's earlier listen, so a louder room's hum isn't someone either. */
    @Test
    fun theRoomsFloorCarriesOverToTheNextListen() {
        val first = BargeIn()
        val quiet = Mic(first)
        assertNull(quiet.feed(0.06f, 1_000))
        assertTrue(first.roomFloor > 0.05f)

        val next = BargeIn(roomFloor = first.roomFloor)
        val mic = Mic(next)
        assertEquals(BargeIn.Step.Tripped, assertNotNull(mic.feed(0.6f, 2_000, playing = true)).second)
        assertEquals(BargeIn.Step.Done, assertNotNull(mic.feed(0.09f, 2_000)).second)
        assertFalse(next.spoke)
    }

    /** Echo can't come before the voice: someone already talking when it starts isn't learnt as its echo. */
    @Test
    fun someoneAlreadyTalkingAsTheVoiceStartsStillCutsIn() {
        val mic = Mic()
        assertNull(mic.feed(0.01f, 1_000))
        assertNull(mic.feed(0.3f, 100))
        val (at, _) = assertNotNull(mic.feed(0.3f, 4_000, playing = true))
        // Once the grace is over and the speech has held.
        assertTrue(at - 1_100 <= 1_000, "tripped ${at - 1_100} ms in")
    }

    @Test
    fun aLowerMultiplierCutsInSooner() {
        val sensitive = Mic(BargeIn(BargeInSettings(multiplier = 1.5)))
        assertNull(sensitive.feed(0.01f, 1_000))
        assertNull(sensitive.feed(0.02f, 1_000, playing = true))
        assertNotNull(sensitive.feed(0.04f, 2_000, playing = true))

        val stock = Mic(BargeIn(BargeInSettings(multiplier = BargeIn.DEFAULT_MULTIPLIER)))
        assertNull(stock.feed(0.01f, 1_000))
        assertNull(stock.feed(0.02f, 1_000, playing = true))
        assertNull(stock.feed(0.04f, 2_000, playing = true))
    }

    /** Set for a quiet headset microphone, it still mustn't cut in on the voice's own echo. */
    @Test
    fun aLowerMultiplierStaysAboveTheVoicesEcho() {
        for (multiplier in listOf(1.5, 1.0, 0.5)) {
            val mic = Mic(BargeIn(BargeInSettings(multiplier = multiplier)))
            assertNull(mic.feed(0.01f, 1_000))
            repeat(10) {
                assertNull(mic.feed(0.2f, 200, playing = true), "cut in on its echo at $multiplier")
                assertNull(mic.feed(0.1f, 200, playing = true), "cut in on its echo at $multiplier")
            }
        }
    }

    /** Its own echo stops when the voice holds: nobody goes on, and that's known well before a whole pause. */
    @Test
    fun nobodyGoingOnOnceTheVoiceHoldsEndsItSoon() {
        val bargeIn = BargeIn(silenceMs = 1_250)
        val mic = Mic(bargeIn)
        assertNull(mic.feed(0.01f, 1_000))
        val (trippedAt, _) = assertNotNull(mic.feed(0.2f, 1_000))
        // The echo dies away as the speaker's buffer and the room empty.
        assertNull(mic.feed(0.15f, 200))
        val (at, step) = assertNotNull(mic.feed(0.01f, 3_000))
        assertEquals(BargeIn.Step.Done, step)
        assertFalse(bargeIn.spoke)
        assertTrue(at - trippedAt <= BargeIn.NOBODY_AFTER_MS + 20, "done ${at - trippedAt} ms after the trip")
    }

    @Test
    fun someoneGoingOnOnceTheVoiceHoldsIsHeardToTheirPause() {
        val bargeIn = BargeIn(silenceMs = 1_000)
        val mic = Mic(bargeIn)
        assertNull(mic.feed(0.01f, 1_000))
        assertNotNull(mic.feed(0.2f, 1_000))
        assertNull(mic.feed(0.2f, 1_500))
        assertTrue(bargeIn.spoke)
        assertEquals(BargeIn.Step.Done, assertNotNull(mic.feed(0.01f, 3_000)).second)
    }

    @Test
    fun theUtteranceEndsOnAPause() {
        val mic = Mic(BargeIn(silenceMs = 1_000))
        assertNull(mic.feed(0.01f, 1_000))
        assertNotNull(mic.feed(0.2f, 400))
        // Words with short gaps between them carry on.
        assertNull(mic.feed(0.2f, 1_500))
        assertNull(mic.feed(0.01f, 500))
        assertNull(mic.feed(0.2f, 500))
        val trippedAt = mic.now
        val (at, step) = assertNotNull(mic.feed(0.01f, 3_000))
        assertEquals(BargeIn.Step.Done, step)
        // A second from the first quiet frame.
        assertEquals(1_020L, at - trippedAt)
    }

    @Test
    fun aRatioMatchesPythons() {
        fun ratio(a: String, b: String) = sequenceRatio(a.toList(), b.toList())
        assertClose(0.7857142857142857, ratio("hello world", "hello there world"))
        assertClose(0.8636363636363636, ratio("the quick brown fox", "the quick brown fox jumps"))
        assertClose(0.0, ratio("abc", ""))
        assertClose(1.0, ratio("", ""))
        // Past 200 characters, the commonest ones can't start a match (difflib's autojunk).
        assertClose(0.10223642172523961, ratio("aaab the weather", "a".repeat(150) + "b".repeat(120) + " the weather today is sunny"))
    }

    @Test
    fun itsOwnVoiceIsAnEcho() {
        val reply = "The build finished in four minutes and every test passed. Do you want me to deploy it now?"
        assertTrue(isTtsEcho("The build finished in four minutes", reply))
        assertTrue(isTtsEcho("every test passed do you want", reply))
        assertTrue(isTtsEcho("the build finished in 4 minutes and every test passed. do you want me to deploy it now", reply))
    }

    /** Every stretch of a long reply is still looked at: the echo is found wherever it comes, and only there. */
    @Test
    fun anEchoIsFoundAnywhereInALongReply() {
        val filler = "Then the job went on to the next stage and wrote its results to the shared drive. "
        val reply = filler.repeat(60) + "The backup finished overnight and all three drives check out. " + filler.repeat(20)
        assertTrue(isTtsEcho("the backup finished overnight and all three drives", reply))
        assertFalse(isTtsEcho("Wait, check the staging logs first", reply))
    }

    @Test
    fun someoneCuttingInIsNotAnEcho() {
        val reply = "The build finished in four minutes and every test passed. Do you want me to deploy it now?"
        assertFalse(isTtsEcho("Wait, check the staging logs first", reply))
        assertFalse(isTtsEcho("yes", reply))
        assertFalse(isTtsEcho("no", reply))
        assertFalse(isTtsEcho("anything", ""))
    }

    /**
     * A phone lying on a table: its call microphone gives quiet speech a small fraction of what a browser's gives, and
     * on the levels Desktop's fixed divisor made it read under every bar there is. Measured against the voice the
     * phone has actually heard, the same speech stands clear of them.
     */
    @Test
    fun quietSpeechOnAPhoneIsMeasuredAgainstTheVoiceItHeard() {
        val levels = MicLevel()
        // A turn of the user talking: their ordinary speech near RMS 2 400 of 32 767, asides near 1 200.
        repeat(50) { levels.heard(2_400f) }
        repeat(50) { levels.heard(1_200f) }
        // Their loudest is what 1.0 means, whatever the microphone gives.
        assertEquals(1f, levels.level(2_400f), 0.001f)
        // Quiet speech, the phone on the table: 0.046 on the old scale, at the bar rather than over it.
        val quiet = levels.level(500f)
        assertTrue(quiet > BargeIn.PLAYBACK_MIN_LEVEL * 3, "quiet speech read $quiet")
        // And the middle of what they say is where the bar stops, under the loudest of it.
        assertEquals(0.5f, levels.speechLevel, 0.01f)
    }

    /** Before this phone has heard the user, the loud speech a browser's microphone gives stands in: as tuned. */
    @Test
    fun untilThePhoneHasHeardTheUserTheLevelsStayAsTuned() {
        val levels = MicLevel()
        val browserLoud = 42f * 256f
        assertEquals(0.5f, levels.level(browserLoud / 2f), 0.001f)
        assertEquals(1f, levels.level(browserLoud), 0.001f)
        assertEquals(BargeIn.DEFAULT_SPEECH_LEVEL, levels.speechLevel, 0.001f)
    }

    @Test
    fun aLevelRunsFromSilenceToLoudSpeech() {
        val levels = MicLevel()
        assertEquals(0f, levels.level(0f))
        assertEquals(0f, levels.level(-3f))
        assertEquals(1f, levels.level(40_000f))
        // Nothing that arrives can make the reference nonsense: a jolt of nothing, or a sample that isn't one.
        levels.heard(0f)
        levels.heard(Float.NaN)
        assertEquals(1f, levels.level(42f * 256f), 0.001f)
    }

    /** The whole of it: quiet speech, from a phone that has heard the user, cuts in over the voice's own echo. */
    @Test
    fun quietSpeechCutsInOverTheVoiceOnAPhoneLyingOnATable() {
        val levels = MicLevel()
        repeat(50) { levels.heard(2_400f) }
        repeat(50) { levels.heard(1_200f) }
        val bargeIn = BargeIn(speechLevel = levels.speechLevel)
        val mic = Mic(bargeIn)
        // A quiet room, then the voice through the speaker with most of it taken out by the echo cancelling.
        assertNull(mic.feed(levels.level(60f), 1_000))
        repeat(5) {
            assertNull(mic.feed(levels.level(200f), 200, playing = true))
            assertNull(mic.feed(levels.level(80f), 200, playing = true))
        }
        // Speaking quietly over it, and it cuts in.
        assertNotNull(mic.feed(levels.level(500f), 1_000, playing = true))
    }

    private fun assertClose(expected: Double, actual: Double) = assertTrue(abs(expected - actual) < 1e-12, "expected $expected, was $actual")
}
