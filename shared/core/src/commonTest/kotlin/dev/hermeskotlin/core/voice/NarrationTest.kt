package dev.hermeskotlin.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The narrator's cadence and its words, driven off a clock the test moves: no device, no gateway and no waiting.
 * The loop is the one VoiceController runs — look what the narrator waits, move the clock on, ask for a line.
 */
class NarrationTest {

    private var now = 0L

    private fun narration() = Narration({ now })

    /** The loop, for [span]: what it said, and when. */
    private fun Narration.run(span: Long, activity: () -> NarrationActivity): List<Pair<Long, String>> {
        val said = mutableListOf<Pair<Long, String>>()
        while (now < span) {
            now += waitMs()
            if (now > span) break
            line(activity())?.let { said += now to it }
        }
        return said
    }

    @Test
    fun theFirstLineComesAtFiveSeconds() {
        val narration = narration()
        val tool = NarrationActivity(tool = "web_search", detail = "Chicago weather")

        // Nothing before the first line: the agent has only just been handed the request.
        assertEquals(5_000L, narration.waitMs())
        now = 4_999
        assertNull(narration.line(tool))
        now = 5_000
        assertEquals("Searching the web for Chicago weather.", narration.line(tool))
    }

    /** A job of a minute is still spoken for at the end of it: the narrator doesn't stop after a few lines. */
    @Test
    fun aLongJobSettlesToTheSlowCadenceAndKeepsGoing() {
        val narration = narration()
        val tool = NarrationActivity(tool = "terminal")

        assertEquals(
            listOf(
                5_000L to "Running a command now.",
                22_000L to "Still running a command.",
                39_000L to "Still working on it.",
                56_000L to "Still running a command.",
            ),
            narration.run(60_000) { tool },
        )
    }

    /** A tool starting is news: it's said at once, rather than once the slow cadence comes round again. */
    @Test
    fun aNewToolIsHeardAtTheFastCadenceAgain() {
        val narration = narration()
        val first = NarrationActivity(tool = "terminal")
        val second = NarrationActivity(tool = "web_search")

        assertEquals(
            listOf(
                5_000L to "Running a command now.",
                // The slow cadence, nothing new since.
                22_000L to "Still running a command.",
                // The second tool started at 24s and is spoken for on the next look, five seconds after the last line.
                27_000L to "Searching the web for that.",
            ),
            narration.run(30_000) { if (now < 24_000) first else second },
        )
    }

    /** Two tools of the same kind are two pieces of news, but not the same sentence twice running. */
    @Test
    fun theSameLineIsNeverSaidTwiceInARow() {
        val narration = narration()
        val first = NarrationActivity(tool = "terminal", detail = "ls -la")
        val second = NarrationActivity(tool = "terminal", detail = "pwd")

        assertEquals(
            listOf(5_000L to "Running a command now.", 10_000L to "Working on it now."),
            narration.run(12_000) { if (now < 10_000) first else second },
        )
    }

    /** With nothing to go on there's nothing to say: a turn that started no tool and has no status line is quiet. */
    @Test
    fun aTurnWithNothingToGoOnSaysNothing() {
        val narration = narration()
        assertTrue(narration.run(120_000) { NarrationActivity() }.isEmpty())
    }

    @Test
    fun aToolIsSaidTheWayItWouldBeSpoken() {
        assertEquals("Searching the web for that.", narrationLine(NarrationActivity(tool = "web_search")))
        assertEquals("Running the tests now.", narrationLine(NarrationActivity(tool = "run_tests")))
        assertEquals("Running a command now.", narrationLine(NarrationActivity(tool = "terminal")))
        assertEquals("Editing the code now.", narrationLine(NarrationActivity(tool = "patch")))
        assertEquals("Reading through the files now.", narrationLine(NarrationActivity(tool = "read_file")))
        assertEquals("Handing that to a subagent.", narrationLine(NarrationActivity(tool = "delegate_task")))
        // An MCP tool is read past its server's name.
        assertEquals(
            "Searching the web for melk.",
            narrationLine(NarrationActivity(tool = "mcp__gateway__ah_search_products", detail = "melk")),
        )
        // A tool this doesn't know is working on whatever it was given, in words.
        assertEquals("Working on the build now.", narrationLine(NarrationActivity(tool = "bake_the_build", detail = "the build")))
        // The still line says the same thing in the past of itself.
        assertEquals("Still searching the web.", narrationLine(NarrationActivity(tool = "web_search"), still = true))
        assertEquals("Still working on the build.", narrationLine(NarrationActivity(tool = "bake_the_build", detail = "the build"), still = true))
    }

    @Test
    fun aStatusLineIsSaidAsTheSentenceItIs() {
        assertEquals("Compacting the conversation.", narrationLine(NarrationActivity(status = "compacting the conversation")))
        assertEquals("Still compacting the conversation.", narrationLine(NarrationActivity(status = "compacting the conversation"), still = true))
        // Markup and arguments aren't words: what's left of them is, or nothing is.
        assertEquals(
            "Searching the web for that.",
            narrationLine(NarrationActivity(tool = "web_search", detail = """{"query":"x","limit":3}""")),
        )
        assertNull(narrationLine(NarrationActivity()))
    }
}
