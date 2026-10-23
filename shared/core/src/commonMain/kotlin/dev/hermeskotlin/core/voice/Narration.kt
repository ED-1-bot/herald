package dev.hermeskotlin.core.voice

import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.ToolActivity
import kotlin.time.TimeSource

/**
 * Says what the narrator decided should be said, out loud. The chained voice chat speaks it with the gateway's
 * voice, the one the reply is read in; a test has its own, so the cadence is driven without a gateway.
 */
fun interface NarrationSpeaker {
    suspend fun say(line: String)
}

/**
 * What Hermes is at, as the narrator has it: the tool it's running (and what for), or the gateway's own status
 * line. Two of these are one activity when every part of them is, which is how a line already said is told
 * from news.
 */
data class NarrationActivity(
    val tool: String? = null,
    /** What the tool is on, when the gateway said it in words rather than arguments. */
    val detail: String? = null,
    /** The turn's latest `status.update` line. */
    val status: String? = null,
) {
    /** Nothing to go on yet: no tool started and nothing said about what the agent is at. */
    val quiet: Boolean get() = tool == null && status == null

    /** What makes two of these the same activity. */
    val key: String get() = "${tool.orEmpty()}\u0000${detail.orEmpty()}\u0000${status.orEmpty()}"
}

/**
 * Milliseconds from a fixed point that never goes back. The narrator measures gaps on this, not the time of
 * day, so a clock that jumps on a network or daylight-saving change can't make it wait or fire twice.
 */
fun monotonicMs(): Long = MONOTONIC_ORIGIN.elapsedNow().inWholeMilliseconds

private val MONOTONIC_ORIGIN = TimeSource.Monotonic.markNow()

/**
 * When to say what while a turn runs, and what to say: the phone's answer to a long silence while Hermes works.
 *
 * A line comes about every [fastMs] at first, so the start of a turn is never quiet, and backs off to about
 * every [slowMs] once nothing new arrives; a tool or a status that comes in counts as news and brings the fast
 * cadence back. It goes on for as long as the turn does, rather than for a few lines, since the wait it covers
 * can run into minutes. What it says is always the latest activity, and never the same words twice in a row.
 *
 * The clock is given to it, so a test drives the cadence without waiting. What the loop does with the line is
 * the caller's business: the chained chat speaks it, a live call hands it to the vendor's voice.
 */
class Narration(
    private val clock: () -> Long,
    private val fastMs: Long = FAST_MS,
    private val slowMs: Long = SLOW_MS,
) {
    /** When the loop may ask again; null until it has asked once, which is a fast interval off. */
    private var dueAt: Long? = null
    /** The last line said, and the activity it came from, so the same again isn't said twice. */
    private var said: String? = null
    private var saidKey: String? = null
    private var saidAt = 0L
    /** The activity the loop was last given, to tell one just arrived from one still running. */
    private var seenKey: String? = null

    /** How long the loop waits before it asks for a line again. */
    fun waitMs(): Long {
        val now = clock()
        val due = dueAt ?: (now + fastMs).also { dueAt = it }
        // Never longer than the fast cadence, however far off the next line is: a tool starting meanwhile is news,
        // and is only noticed when the loop looks. Looking says nothing.
        return (due - now).coerceIn(0L, fastMs)
    }

    /**
     * The line for [activity] now, or null when this isn't the moment for one: news that was just said, or
     * nothing new before the slow interval is up. Called with the same activity again and again, it keeps
     * saying that the agent is still at it, at the slow cadence, however long the job runs.
     */
    fun line(activity: NarrationActivity): String? {
        val now = clock()
        val key = activity.key
        // News is worth a word soon, not on the slow cadence a long job has settled into; but not over the heels of
        // the last line, which would sound like a stutter.
        if (key != seenKey) {
            seenKey = key
            dueAt = maxOf(now, saidAt + fastMs)
        }
        val due = dueAt ?: return null
        if (now < due) return null
        val news = key != saidKey && !activity.quiet
        if (!news && now - saidAt < slowMs) {
            dueAt = saidAt + slowMs
            return null
        }
        val base = narrationLine(activity, still = !news)
        val line = base?.takeUnless { it == said }
            ?: base?.let { narrationLine(activity, still = !news, other = true) }
        if (line == null) {
            // Nothing worth saying (no tool or status yet, or the same words as the last time). A job running on
            // is asked again at the slow cadence, so it's never the last thing said.
            dueAt = if (news) now + fastMs else maxOf(now + fastMs, saidAt + slowMs)
            if (news) saidKey = key
            return null
        }
        said = line
        saidKey = key
        saidAt = now
        dueAt = now + slowMs
        return line
    }

    private companion object {
        /** The cadence at the start of a turn: often enough that the first silence is short. */
        const val FAST_MS = 5_000L
        /** What a long job settles into, so a wait of minutes isn't a voice in the ear. */
        const val SLOW_MS = 17_000L
    }
}

/**
 * What the turn running in [chat] is at, its reply starting at message [from]: the tool it has running now,
 * else the gateway's status line. The tool comes first because it's the concrete thing; the status is what
 * the agent has to say about itself when no tool is running.
 */
fun narrationActivity(chat: ChatState, from: Int): NarrationActivity {
    val running = chat.messages.drop(from).filterIsInstance<ChatMessage.Assistant>()
        .flatMap { it.tools }
        .lastOrNull { it.running }
    if (running != null) return NarrationActivity(tool = running.name, detail = detailOf(running))
    return NarrationActivity(status = chat.status?.takeIf { it.isNotBlank() })
}

/**
 * What [tool] is on, in words worth hearing: what the gateway calls it, else what it was given when that reads
 * as words (a query, a path) rather than as the arguments of a call.
 */
private fun detailOf(tool: ToolActivity): String? =
    tool.detail?.takeIf { it.isNotBlank() }
        ?: tool.input?.takeUnless { it.trimStart().let { text -> text.startsWith("{") || text.startsWith("[") } }

/**
 * A line for [activity] the way the voice chat says things: a sentence about what Hermes is at, not a label.
 * [still] is the one that stands in for a line already said, so a job running on isn't the same words again;
 * [other] is the plainest thing there is to say, for when that line is the one just heard. Null when there's
 * nothing to go on: an activity with no tool and no status has nothing to say.
 */
fun narrationLine(activity: NarrationActivity, still: Boolean = false, other: Boolean = false): String? {
    val subject = subject(activity.detail)
    // The same words twice in a row say nothing the first didn't, so there is always a plainer line to fall back on.
    if (other) return if (still) "Still working on it." else "Working on it now."
    activity.tool?.let { tool ->
        val doing = doing(tool, subject)
        if (still) return "Still ${doing.gerund}."
        val tail = if (doing.tail.isEmpty()) "" else " ${doing.tail}"
        return "${doing.gerund.replaceFirstChar { it.uppercase() }}$tail."
    }
    val status = activity.status?.let(::spokenStatus) ?: return null
    return if (still) "Still ${status.replaceFirstChar { it.lowercase() }}" else status
}

/** A tool as it sounds while it runs: [gerund] after "Still", and the words that close the line when it's news. */
private class Doing(val gerund: String, val tail: String)

/**
 * How a tool sounds, from its name: the verb a person would use for it, not the tool's own. A name an MCP
 * server gave is read past the server's prefix (`mcp__gateway__ah_search_products` asks about products), and
 * one nothing here knows is "working on" whatever it is on, which is the honest thing to say.
 */
private fun doing(tool: String, subject: String?): Doing {
    val name = tool.substringAfterLast("__").lowercase()
    val words = name.split('_', '-', '.', ' ').filter { it.isNotEmpty() }.toSet()
    fun any(vararg keys: String) = keys.any { it in words }
    return when {
        // A search of the web says what it's looking for; one of the phone's own files has nothing to add.
        any("search", "find", "lookup") ->
            if (any("file", "files", "session")) Doing("looking through the files", "now")
            else Doing("searching the web", if (subject != null) "for $subject" else "for that")
        any("extract", "browse", "browser", "fetch", "crawl", "scrape") -> Doing("looking that page up", "now")
        any("test", "tests", "pytest") -> Doing("running the tests", "now")
        any("execute", "code", "python", "script") -> Doing("running some code", "now")
        any("terminal", "shell", "bash", "exec", "command", "run") -> Doing("running a command", "now")
        any("write", "edit", "patch", "apply", "save", "replace") -> Doing("editing the code", "now")
        any("read", "view", "open", "load", "list", "file") -> Doing("reading through the files", "now")
        any("git") -> Doing("sorting out the repository", "now")
        any("delegate", "subagent", "agent", "task") -> Doing("handing that to a subagent", "")
        any("image", "picture", "draw", "render", "camera") -> Doing("making the picture", "now")
        any("todo", "plan", "goal", "cron") -> Doing("planning the steps", "now")
        any("memory", "note", "notes") -> Doing("checking its notes", "now")
        else -> Doing("working on ${subject ?: "it"}", "now")
    }
}

/**
 * [detail] as words a line can carry: one short, plain phrase. An address, a set of arguments or a wall of text
 * is nothing to hear, and a path is only its file's name.
 */
private fun subject(detail: String?): String? {
    val line = detail?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
    if (line.any { it in SUBJECT_UNSAFE } || "://" in line) return null
    val words = line.substringAfterLast('/').replace(WHITESPACE, " ").trim().trimEnd('.', ',', ';', ':')
    if (words.isEmpty() || words.none { it.isLetterOrDigit() }) return null
    if (words.length <= SUBJECT_CHARS) return words
    return words.take(SUBJECT_CHARS).substringBeforeLast(' ').trimEnd().ifEmpty { words.take(SUBJECT_CHARS) } + "…"
}

/**
 * A `status.update` line as it should sound. The gateway writes these for the screen: markup and addresses go,
 * it's cut to a clause, and it ends like the sentence it is spoken as.
 */
private fun spokenStatus(status: String): String? {
    val text = speakableText(status).trim().trimEnd(':').trim()
    if (text.isEmpty()) return null
    val cut = if (text.length <= STATUS_CHARS) text else text.take(STATUS_CHARS).substringBeforeLast(' ').trimEnd() + "…"
    val sentence = cut.replaceFirstChar { it.uppercase() }
    return if (sentence.last() in SENTENCE_ENDS) sentence else "$sentence."
}

/** Long enough for a query or a file name, short enough to hear inside one breath. */
private const val SUBJECT_CHARS = 48
private const val STATUS_CHARS = 120
private const val SENTENCE_ENDS = ".!?…"
private const val SUBJECT_UNSAFE = "{}[]\"`<>|\\"
private val WHITESPACE = Regex("""\s+""")
