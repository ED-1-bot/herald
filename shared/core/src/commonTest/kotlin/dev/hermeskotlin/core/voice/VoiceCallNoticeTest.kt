package dev.hermeskotlin.core.voice

import dev.hermeskotlin.core.chat.SessionUsage
import dev.hermeskotlin.core.chat.TurnUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceCallNoticeTest {

    @Test
    fun aTurnThatHasSpentNothingHasNoLineToShow() {
        assertNull(voiceCallDetail(null))
        // The agent was rebuilt mid-turn, so the totals can't be told apart: an empty turn, not a negative one.
        assertNull(voiceCallDetail(TurnUsage(0, 0, 0, 0)))
        assertNull(voiceCallDetail(TurnUsage(0, 0, 12, 3)))
    }

    @Test
    fun whatTheTurnSpentIsCountedInThousands() {
        assertEquals("2.4k in · 1.1k out", voiceCallDetail(TurnUsage(2_400, 1_100, 0, 1)))
        // Under a thousand is written out; the pace is left off while there isn't one.
        assertEquals("840 in · 96 out", voiceCallDetail(TurnUsage(840, 96, 0, 1)))
    }

    @Test
    fun thePaceGoesOnTheEndOfTheLine() {
        assertEquals("2.4k in · 1.1k out · 96 tok/s", voiceCallDetail(TurnUsage(2_400, 1_100, 0, 1), perSecond = 96))
        // A pace of zero is still a pace: it says the turn has stalled rather than that it's unknown.
        assertEquals("840 in · 96 out · 0 tok/s", voiceCallDetail(TurnUsage(840, 96, 0, 1), perSecond = 0))
    }

    @Test
    fun contextOnlyShowsWhenTheGatewaySaysHowFullItIs() {
        assertNull(voiceCallContext(null))
        assertNull(voiceCallContext(SessionUsage(input = 900, output = 40)))
        assertEquals("36% context", voiceCallContext(SessionUsage(input = 900, output = 40, contextPercent = 36)))
    }

    @Test
    fun thePaceNeedsAMomentToDivideBy() {
        assertNull(tokensPerSecond(output = 0, seconds = 12.0))
        // Under a second, a burst of tokens reads as a rate thousands long: better nothing.
        assertNull(tokensPerSecond(output = 400, seconds = 0.4))
        assertEquals(96, tokensPerSecond(output = 960, seconds = 10.0))
        assertEquals(7, tokensPerSecond(output = 20, seconds = 3.0))
    }
}
