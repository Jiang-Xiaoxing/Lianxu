package app.lianxu.independent

import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.cert.Certificate
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

class ChatVoiceDiagnosticsTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var journal: DiagnosticJournal
    private val turn = UUID.randomUUID().toString()
    private val sentence = "private-synthetic-dialogue"
    private val target = VoiceTarget("private-account", "private-voice", 1, provider = VoiceVendor.BREEZEBLUE)
    private val errorText = "Authorization: Bearer synthetic-key https://example.invalid/?token=query-fixture"

    @Before fun start() {
        journal = DiagnosticJournal(tmp.newFolder("journal"), "test")
        Diagnostics.installForTest(journal)
    }
    @After fun stop() {
        Diagnostics.installForTest(null)
        journal.close()
    }
    private fun voiceRows(bytes: ByteArray) = bytes.decodeToString().lineSequence().filter { it.isNotBlank() }
        .map { JsonParser.parseString(it).asJsonObject }.filter { it["event"]?.asString == "chat.voice" }.toList()
    private fun chat(attempt: Int) = ChatTurn(turn, "private-role", 1, 2, TurnState.COMPLETED, attempt, "c", null)
    private fun call(text: String) = TextChatResult.Success("", listOf(
        ChatToolCall("call", "send_short_voice", JSONObject().put("text", text).toString())
    ))
    private fun saveEvidence(name: String, bytes: ByteArray) {
        val out = File("build/reports/voice-diagnostics", name)
        requireNotNull(out.parentFile).mkdirs()
        out.writeBytes(bytes)
    }

    @Test fun offeredToolCanBeDeclinedOnlyOnASuccessfulModelResponse() {
        val voice = VoiceTrace(turn, 1)
        voice.offer(false, VoiceReason.ROLE_DISABLED)
        voice.offer(false, null)
        voice.offer(false, null, fallback = true)
        voice.offer(true, null)
        voice.response(false, TextChatResult.Success("private-response"))
        voice.response(true, TextChatResult.Failure(ChatFailure.NETWORK))
        voice.response(true, TextChatResult.Success("private-response"))
        voice.response(true, call(sentence))
        for (detailed in listOf(false, true)) {
            val rows = voiceRows(journal.export(detailed)).map { it["data"].asJsonObject }
            assertEquals(listOf("OMITTED", "OMITTED", "OMITTED", "OFFERED", "NOT_CALLED", "CALLED"),
                rows.map { it["status"].asString })
            assertEquals(listOf("ROLE_DISABLED", "FINAL_FILTER", "UNSUPPORTED_TOOLS"),
                rows.mapNotNull { it["reason"]?.asString })
            assertTrue(rows.all { it["turn"].asString == turn && it["attempt"].asInt == 1 })
            assertFalse(rows.toString().contains("private-response"))
            assertFalse(rows.toString().contains(sentence))
        }
    }

    @Test fun basicExportDistinguishesDeliveryFailuresCancellationAndVisibility() = runBlocking {
        val cache = tmp.newFolder("cache")
        val pcm = "private-synthetic-audio".toByteArray()
        val audio = ByteArrayOutputStream().also { out ->
            AudioInputStream(pcm.inputStream(), AudioFormat(8000f, 8, 1, true, false), pcm.size.toLong()).use {
                AudioSystem.write(it, AudioFileFormat.Type.WAVE, out)
            }
        }.toByteArray()
        fun decode(file: File) = AudioSystem.getAudioInputStream(file).use { check(it.read() >= 0) }
        suspend fun deliver(attempt: Int, vendor: VoiceVendor = target.provider,
                            file: File = File(cache, "$attempt.wav"), valid: () -> Boolean = { true },
                            surface: VoiceStatus = VoiceStatus.CHAT_VISIBLE, decoder: (File) -> Unit = ::decode,
                            synthesis: suspend () -> ByteArray = { audio }) {
            val selected = target.copy(provider = vendor)
            val trace = VoiceTrace(turn, attempt, vendor)
            var accepted: RequestedVoiceReply? = null
            val result = Diagnostics.onThread(DiagnosticTraceState("unrelated-trace", "span", null, null,
                links = mapOf("batch" to "private-correlation"))) {
                finishVoiceTool(call(sentence), "", selected, { selected }, trace) { accepted = it }
            }
            assertEquals(sentence, (result as TextChatResult.Success).text)
            assertEquals(RequestedVoiceReply(sentence, selected), accepted)
            val id = UUID.randomUUID().toString()
            ChatVoiceDelivery.submitFile(file, "private-role", id, valid, trace, { surface }, decoder, synthesis)
            withTimeout(10000) {
                ChatVoiceDelivery.pending.first { id !in it }
                while (voiceRows(journal.export(false)).none {
                    it["data"].asJsonObject.let { d -> d["attempt"].asInt == attempt && d["status"].asString == surface.name }
                }) delay(10)
            }
        }
        deliver(1, surface = VoiceStatus.APP_BACKGROUND)
        assertArrayEquals(audio, File(cache, "1.wav").readBytes())
        assertFalse(journal.export(false).decodeToString().contains("VOICE_RENDERED"))
        repeat(3) { ChatVoiceDiagnostics.shown(chat(1), VoiceStatus.VOICE_RENDERED) }
        deliver(2, surface = VoiceStatus.OTHER_PAGE) {
            throw BreezeBlueSpeechException(BreezeBlueSpeechException.Reason.HTTP, 429)
        }
        repeat(3) { ChatVoiceDiagnostics.shown(chat(2), VoiceStatus.TEXT_RENDERED) }
        deliver(3) { "private-invalid-audio".toByteArray() }
        val blockedParent = tmp.newFile("non-directory")
        deliver(4, file = File(blockedParent, "blocked.wav"))
        val blockedDestination = File(cache, "occupied")
        deliver(5, file = blockedDestination, decoder = {
            decode(it)
            check(blockedDestination.mkdir())
            File(blockedDestination, "child").createNewFile()
        })
        deliver(6) { throw CancellationException(errorText) }
        var checks = 0
        deliver(7, valid = { ++checks == 1 })
        Diagnostics.detailed = true
        deliver(8, VoiceVendor.OTHER) { throw IOException(errorText) }
        repeat(2) { ChatVoiceDiagnostics.shown(chat(1), VoiceStatus.UNAVAILABLE_RENDERED, VoiceReason.PLAYER_ERROR) }
        assertEquals(listOf("1.wav"), cache.listFiles()!!.filter { it.isFile }.map { it.name })

        val basic = journal.export(false)
        val rows = voiceRows(basic)
        assertTrue(rows.isNotEmpty())
        rows.forEach { row ->
            val data = row["data"].asJsonObject
            assertEquals(turn, row["trace"].asString)
            assertEquals(turn, data["turn"].asString)
            assertTrue(data["attempt"].asInt in 1..8)
            assertTrue(data.keySet().all { it in setOf("turn", "attempt", "provider", "stage", "status", "reason") })
        }
        fun expect(attempt: Int, stage: String, status: String, reason: String? = null) {
            val matches = rows.filter { it["data"].asJsonObject.let { d ->
                d["attempt"].asInt == attempt && d["stage"].asString == stage && d["status"].asString == status && d["reason"]?.asString == reason
            }}
            assertEquals("attempt $attempt $stage $status $reason", 1, matches.size)
            if (status in setOf("SUCCEEDED", "FAILED", "CANCELLED")) assertTrue(matches.single()["elapsed_ms"].asLong >= 0)
        }
        (1..8).forEach { expect(it, "TOOL", "ACCEPTED") }
        listOf("SYNTHESIS", "TEMP_WRITE", "DECODE", "CACHE").forEach { expect(1, it, "SUCCEEDED") }
        expect(1, "PRESENTATION", "AUDIO_READY")
        expect(1, "PRESENTATION", "APP_BACKGROUND")
        expect(1, "PRESENTATION", "VOICE_RENDERED")
        expect(1, "PRESENTATION", "UNAVAILABLE_RENDERED", "PLAYER_ERROR")
        expect(2, "PRESENTATION", "OTHER_PAGE")
        expect(2, "PRESENTATION", "TEXT_RENDERED")
        expect(2, "SYNTHESIS", "FAILED", "HTTP_429")
        expect(3, "DECODE", "FAILED", "DECODER_ERROR")
        expect(4, "TEMP_WRITE", "FAILED", "IO")
        expect(5, "CACHE", "FAILED", "RENAME_FAILED")
        expect(6, "SYNTHESIS", "CANCELLED")
        expect(7, "VALIDATE", "CANCELLED", "STALE_TARGET_OR_MESSAGE")
        expect(8, "SYNTHESIS", "FAILED", "IO")
        listOf("HTTP_429","DECODER_ERROR","IO","RENAME_FAILED",null,"STALE_TARGET_OR_MESSAGE","IO").forEachIndexed { i,reason ->
            expect(i+2,"PRESENTATION","TEXT_FALLBACK",reason)
        }
        assertFalse(rows.any { it["data"].asJsonObject.let { d ->
            d["attempt"].asInt in listOf(2,6,7,8) && d["stage"].asString == "DECODE"
        }})
        val stored = File(tmp.root, "journal/events-0.jsonl").readBytes()
        for (export in listOf(basic, journal.export(true), stored)) {
            val text = export.decodeToString()
            for (marker in listOf(sentence, "private-", "synthetic-key", "Authorization", "example.invalid",
                    "query-fixture", "unrelated-trace", "IOException", "CancellationException",
                    java.util.Base64.getEncoder().encodeToString(audio))) assertFalse(marker, text.contains(marker))
        }
        assertEquals(rows, voiceRows(Diagnostics.export(false)))
        journal.close()
        journal = DiagnosticJournal(File(tmp.root, "journal"), "test")
        Diagnostics.installForTest(journal)
        val reopened = journal.export(false)
        assertEquals(rows, voiceRows(reopened))
        saveEvidence("delivery-basic.jsonl", reopened)
    }

    @Test fun basicExportShowsRejectedAndUnconfiguredCallsAcrossResponseModes() = runBlocking {
        val request = TextChatRequest(listOf(WireMessage("user", sentence)), 256)
        val config = ModelConfig("fake","fixture","custom","https://example.invalid/v1","fixture",16000,2048)
        val function = JSONObject().put("name","send_short_voice").put("arguments",call(sentence).toolCalls.single().arguments)
        val tool = JSONObject().put("id","test_call").put("type","function").put("function",function).put("index",0)
        for (stream in listOf(false, true)) {
            val item = JSONObject().put("tool_calls",org.json.JSONArray().put(tool))
            val choice = JSONObject().put(if (stream) "delta" else "message",item).put("index",0).put("finish_reason","tool_calls")
            val json = JSONObject().put("choices",org.json.JSONArray().put(choice)).toString()
            val client = HttpsTextChatClient(HttpsConnectionFactory { url ->
                object : HttpsURLConnection(url) {
                    override fun getInputStream() = (if(stream) "data: $json\n\ndata: [DONE]\n\n" else json).byteInputStream()
                    override fun getOutputStream() = ByteArrayOutputStream()
                    override fun getResponseCode() = 200
                    override fun getContentType() = if(stream) "text/event-stream" else "application/json"
                    override fun getCipherSuite() = ""
                    override fun getLocalCertificates(): Array<Certificate>? = null
                    override fun getServerCertificates(): Array<Certificate> = emptyArray()
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy() = false
                }
            })
            val result = if(stream) client.streamEvents(config,"synthetic-key",request) {} else client.complete(config,"synthetic-key",request)
            assertEquals(ChatFailure.TOOLS_UNAVAILABLE,(result as TextChatResult.Failure).reason)
            assertTrue(result.rejectedVoiceCall)
            val reason = requireNotNull(voiceRejection(result,request))
            assertEquals(VoiceReason.NOT_OFFERED, reason)
            val configured = if(stream) target else null
            VoiceTrace(turn,if(stream) 2 else 1,configured?.provider ?: VoiceVendor.UNKNOWN).rejected(configured,reason)
        }
        val trace = VoiceTrace(turn,3,target.provider)
        assertTrue(finishVoiceTool(call(""),"",target,{target},trace){ fail() } is TextChatResult.Failure)
        val changed = finishVoiceTool(call(sentence),"",target,{null},trace.copy(attempt=4)){ fail() }
        assertEquals(sentence,(changed as TextChatResult.Success).text)
        val basic = journal.export(false)
        val events = voiceRows(basic).map { it["data"].asJsonObject }.map {
            listOf(it["attempt"].asString,it["stage"].asString,it["status"].asString,it["reason"].asString)
        }
        assertEquals(listOf(
            listOf("1","TOOL","NOT_CONFIGURED","NOT_OFFERED"), listOf("2","TOOL","REJECTED","NOT_OFFERED"),
            listOf("3","TOOL","REJECTED","INVALID_CALL"), listOf("4","TOOL","REJECTED","TARGET_CHANGED"),
            listOf("4","PRESENTATION","TEXT_FALLBACK","TARGET_CHANGED")
        ), events)
        assertFalse(basic.decodeToString().contains(sentence))
        saveEvidence("tool-basic.jsonl",basic)
    }

    @Test fun basicExportNeverWidensOtherEventsOrAdmitsUnknownVoiceFields() {
        val data = mapOf("turn" to turn,"attempt" to 4,"stage" to "malformed-stage","status" to "STARTED",
            "provider" to "mix-private-provider","reason" to "private-reason","arbitrary" to "private-detail")
        journal.record("unrelated.event",turn,data)
        journal.record("chat.voice",turn,data)
        val basic = journal.export(false).decodeToString()
        val rows = basic.trim().lines().drop(1).map { JsonParser.parseString(it).asJsonObject }
        assertFalse(rows.first().has("data"))
        assertEquals(setOf("turn","attempt","status"), rows.last()["data"].asJsonObject.keySet())
        assertFalse(basic.contains("private-"))
        assertTrue(journal.export(true).decodeToString().contains("private-detail"))
    }
}
