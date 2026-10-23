package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.rpc.RpcTransport
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.NarrationSpeaker
import dev.hermeskotlin.core.voice.Recording
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.SpokenAudio
import dev.hermeskotlin.core.voice.VoiceActivity
import dev.hermeskotlin.core.voice.VoiceRecorder
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The narrator inside the chained voice chat: what is said over a long wait, when, and what is left unsaid once
 * the reply has the speaker. Its clock and its voice are the test's own, so five seconds is a step and a line
 * doesn't wait on a gateway.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VoiceNarrationTest {

    private val gateway = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    /** The gateway's socket: creates the chat and takes prompts; the test plays the turn's events. */
    private class Socket : RpcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED).apply {
            trySend("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
        }
        val submits = MutableStateFlow<List<String>>(emptyList())
        override val incoming: Flow<String> = inbound.receiveAsFlow()

        override suspend fun send(text: String) {
            val message = Json.parseToJsonElement(text).jsonObject
            val id = message["id"] ?: return
            val result = when (message["method"]?.jsonPrimitive?.content) {
                "session.create" -> """{"session_id":"rt1","stored_session_id":"stored-1","message_count":0,"messages":[],"info":{}}"""
                "prompt.submit" -> """{"status":"streaming"}""".also {
                    submits.update { seen -> seen + (message["params"]!!.jsonObject["text"]!!.jsonPrimitive.content) }
                }
                else -> "{}"
            }
            inbound.trySend("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        }

        override suspend fun close(code: Short, reason: String) = Unit

        fun event(type: String, payload: String = "{}") {
            inbound.trySend("""{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":"rt1","payload":$payload}}""")
        }
    }

    /** Hears one request, then listens for good. */
    private class OneRequest : VoiceRecorder {
        var recordings = 0
        override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording {
            if (recordings++ >= 1) awaitCancellation()
            onSpeech()
            return Recording(byteArrayOf(1, 2, 3), "audio/wav", heardSpeech = true)
        }

        override fun finish() = Unit
    }

    private class Player : SpeechPlayer {
        val clips = mutableListOf<SpokenAudio>()
        override suspend fun play(audio: SpokenAudio) {
            clips += audio
        }
    }

    /**
     * [spoken]: every line the gateway's voice was given. [voiced]: the first, once the gateway has it — a deferred
     * the test can wait on, since what goes out is a round trip rather than a state the chat reports.
     */
    private class Setup(
        val socket: Socket,
        val voice: VoiceController,
        val spoken: MutableList<String>,
        val voiced: CompletableDeferred<String>,
    )

    /**
     * A chained voice chat whose clock is the test's and whose narrator speaks into [spoken]: the cadence is the
     * test's to step through, and every line is there to read without a gateway round trip. Without a [speaker]
     * the narrator's lines go out over the gateway's voice, as they do on the phone, and land in [spoken] too.
     */
    private suspend fun TestScope.chat(speaker: Boolean = true): Setup {
        val spoken = mutableListOf<String>()
        val voiced = CompletableDeferred<String>()
        val client = createHttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/api/auth/ws-ticket" -> respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
                    "/api/audio/transcribe" -> respond("""{"ok":true,"transcript":"What's the weather?"}""", HttpStatusCode.OK, json)
                    "/api/audio/speak" -> {
                        val line = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject["text"]!!.jsonPrimitive.content
                        spoken += line
                        voiced.complete(line)
                        respond("""{"ok":true,"data_url":"data:audio/mpeg;base64,AAAA","mime_type":"audio/mpeg"}""", HttpStatusCode.OK, json)
                    }
                    else -> respond("""{"ok":true}""", HttpStatusCode.OK, json)
                }
            },
            PersistentCookiesStorage(InMemoryKeyValueStore()),
        )
        val socket = Socket()
        val connection = GatewayConnection(AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> socket }, backgroundScope)
        connection.start(gateway)
        connection.state.first { it is ConnectionState.Connected }
        val session = ChatSession(gateway, null, null, connection, SessionsApi(client), backgroundScope)
        session.start()
        val voice = VoiceController(
            AudioApi(client),
            OneRequest(),
            Player(),
            backgroundScope,
            backgroundScope,
            speechStreams = { _, _ -> null },
            // Both the narrator's clock and its voice are the test's, so a line comes when the test says so.
            clock = { testScheduler.currentTime },
            narrationSpeaker = if (speaker) NarrationSpeaker { spoken += it } else null,
        )
        voice.startChat(session, gateway, "work")
        socket.submits.first { it.isNotEmpty() }
        testScheduler.runCurrent()
        return Setup(socket, voice, spoken, voiced)
    }

    /** The agent takes the request and starts on the web; nothing else comes for a while. */
    private fun TestScope.worksOnTheWeb(setup: Setup) {
        setup.socket.event("message.start")
        setup.socket.event("tool.start", """{"tool_id":"t1","name":"web_search","context":"Chicago weather"}""")
        testScheduler.runCurrent()
    }

    /** Moves the narrator's clock on by [ms], running what comes due on the way. */
    private fun TestScope.step(ms: Long) {
        testScheduler.advanceTimeBy(ms)
        testScheduler.runCurrent()
    }

    @Test
    fun theWaitIsSaidAfterFiveSecondsThenEveryFifteenOrSo() = runTest {
        val setup = chat()
        worksOnTheWeb(setup)

        // Nothing over the first five seconds: the agent has only just been handed the request.
        step(4_900)
        assertTrue(setup.spoken.isEmpty())

        // Then a line about what it's at, and the chip says exactly what was said.
        step(100)
        assertEquals(5_000L, testScheduler.currentTime)
        assertEquals(listOf("Searching the web for Chicago weather."), setup.spoken)
        assertEquals("Searching the web for Chicago weather.", setup.voice.chat.value.working)

        // Nothing new: seventeen seconds on, and not the same words again.
        step(16_000)
        assertEquals(listOf("Searching the web for Chicago weather."), setup.spoken)
        step(1_000)
        assertEquals(
            listOf("Searching the web for Chicago weather.", "Still searching the web."),
            setup.spoken,
        )
        assertEquals(22_000L, testScheduler.currentTime)

        setup.voice.stopChat()
    }

    @Test
    fun aLongJobKeepsBeingAnnouncedRatherThanFallingSilent() = runTest {
        val setup = chat()
        worksOnTheWeb(setup)

        // Three minutes of one tool running, and the voice is still saying so, never the same words twice running.
        step(180_000)

        assertTrue(setup.spoken.size >= 9, "said only ${setup.spoken.size} lines in three minutes: ${setup.spoken}")
        assertEquals("Searching the web for Chicago weather.", setup.spoken.first())
        setup.spoken.zipWithNext().forEach { (one, next) -> assertTrue(one != next, "the same line twice: $one") }
        setup.voice.stopChat()
    }

    @Test
    fun aNewToolIsSaidSoonAfterItStarts() = runTest {
        val setup = chat()
        worksOnTheWeb(setup)
        step(5_000)

        // The search is done and a command starts: news, so it's said on the fast cadence, not the slow one.
        setup.socket.event("tool.complete", """{"tool_id":"t1","summary":"Sunny"}""")
        setup.socket.event("tool.start", """{"tool_id":"t2","name":"terminal","context":"pytest -q"}""")
        step(5_000)

        assertEquals(10_000L, testScheduler.currentTime)
        assertEquals(listOf("Searching the web for Chicago weather.", "Running a command now."), setup.spoken)
        assertEquals("Running a command now.", setup.voice.chat.value.working)
        setup.voice.stopChat()
    }

    @Test
    fun nothingIsSaidOnceTheReplySpeaks() = runTest {
        val setup = chat()
        worksOnTheWeb(setup)
        step(5_000)
        assertEquals(1, setup.spoken.size)

        // The reply has the voice: the chip goes with the narrator.
        setup.socket.event("message.delta", """{"text":"It's sunny. "}""")
        setup.socket.event("message.complete", """{"text":"It's sunny. ","status":"complete"}""")
        // The reply's own voice takes the speaker, and the chip goes with the narrator.
        setup.voice.chat.first { it.phase == VoicePhase.Listening && setup.spoken.size == 2 }

        // The agent goes back to work after the reply: nothing of the narrator's lands over it, or after it.
        setup.socket.event("message.start")
        setup.socket.event("tool.start", """{"tool_id":"t2","name":"terminal","context":"ls"}""")
        step(60_000)

        assertEquals(listOf("Searching the web for Chicago weather.", "It's sunny."), setup.spoken)
        setup.voice.stopChat()
    }

    @Test
    fun stoppingTheChatStopsTheNarrator() = runTest {
        val setup = chat()
        worksOnTheWeb(setup)
        step(5_000)
        val said = setup.spoken.size

        setup.voice.stopChat()
        step(60_000)

        assertEquals(said, setup.spoken.size)
        assertNull(setup.voice.chat.value.working)
    }

    /** Without a test's voice of its own, a line is spoken the way a reply is: through the gateway, aloud. */
    @Test
    fun aLineIsSaidWithTheVoiceTheReplyIsReadIn() = runTest {
        val setup = chat(speaker = false)
        worksOnTheWeb(setup)

        setup.voice.chat.first { it.working == "Searching the web for Chicago weather." }
        // The line goes to the gateway's voice, the one a reply is read in, rather than to a test's own.
        assertEquals("Searching the web for Chicago weather.", setup.voiced.await())
        setup.voice.stopChat()
    }
}
