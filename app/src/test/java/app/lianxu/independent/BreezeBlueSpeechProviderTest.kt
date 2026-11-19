package app.lianxu.independent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.io.OutputStream
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

class BreezeBlueSpeechProviderTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun missingSpeechRecordLeavesVaultAndLegacySelectionsAlone() {
        val root = folder.root
        val dir = java.io.File(root, "breezeblue-speech")
        assertNull(ModelVault.existingSecret(root, "breezeblue-speech", "breezeblue-tts"))
        assertFalse(dir.exists())
        assertTrue(dir.mkdir())
        val selections = java.io.File(dir, "purpose-selections")
        selections.writeText("""{"relationship":"fixture"}""")
        assertNull(ModelVault.existingSecret(root, "breezeblue-speech", "breezeblue-tts"))
        assertEquals("""{"relationship":"fixture"}""", selections.readText())
        assertEquals(listOf("purpose-selections"), dir.list()!!.toList())
    }

    private val frame = ByteArray(576).apply {
        byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x98.toByte(), 0xc0.toByte()).copyInto(this)
    }
    private open class Connection(url: URL, private val status: Int, private val input: InputStream,
                             private val length: Long = -1) : HttpsURLConnection(url) {
        val sent = ByteArrayOutputStream()
        val closed = CountDownLatch(1)
        var readBody = false
        override fun connect() = Unit
        override fun disconnect() { closed.countDown() }
        override fun usingProxy() = false
        override fun getCipherSuite() = ""
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream(): OutputStream = sent
        override fun getInputStream(): InputStream { readBody = true; return input }
        override fun getResponseCode() = status
        override fun getContentType() = "audio/mpeg"
        override fun getContentLengthLong() = length
    }

    private suspend fun failure(block: suspend () -> Unit): BreezeBlueSpeechException {
        try { block() } catch (e: BreezeBlueSpeechException) { return e }
        throw AssertionError("expected a sanitized speech failure")
    }

    @Test fun onePostContainsOnlyTheUtteranceAndRequestedVoice() = runBlocking {
        val opened = mutableListOf<Connection>()
        val provider = BreezeBlueSpeechProvider({ "fixture-only" }, HttpsConnectionFactory { url ->
            Connection(url, 200, frame.inputStream()).also(opened::add)
        })
        val sentence = "今天天气不错。"
        val audio = provider.synthesize(sentence, "voc_a b?c")
        assertEquals(576, audio.size)
        assertArrayEquals(byteArrayOf(0xff.toByte(),0xfb.toByte(),0x98.toByte(),0xc0.toByte()),audio.copyOf(4))
        val c = opened.single()
        assertEquals("https://api.breeze.blue/v1/text-to-speech/voc_a%20b%3Fc?output_format=mp3&delivery=sync", c.url.toString())
        assertFalse(c.instanceFollowRedirects); assertFalse(c.useCaches)
        assertEquals("POST", c.requestMethod); assertEquals("fixture-only", c.getRequestProperty("xi-api-key"))
        assertTrue(c.connectTimeout in 1..30000 && c.readTimeout in 1..30000)
        val body = JSONObject(c.sent.toString("UTF-8"))
        assertEquals(setOf("model_id", "text"), body.keys().asSequence().toSet())
        assertEquals(sentence, body.getString("text"))
        assertEquals("breeze-tts-2", body.getString("model_id"))
        assertEquals("audio/mpeg", c.getRequestProperty("Accept"))
        assertEquals(0L,c.closed.count)
    }

    @Test fun unicodeLimitsAndMissingSpeechCredentialsNeverOpenAConnection() = runBlocking {
        var lookups=0
        val provider=BreezeBlueSpeechProvider({lookups++;null},HttpsConnectionFactory { throw AssertionError("network") })
        for ((text,voice) in listOf("测试" to "../other", "测试" to "..", "\uD800" to "v", "好" to "\uDC00", "  " to "v","😀".repeat(301) to "v","好" to "", "好" to "v\n","好" to "A".repeat(257))) {
            assertEquals(BreezeBlueSpeechException.Reason.INVALID_INPUT,failure{provider.synthesize(text,voice)}.reason)
        }
        assertEquals(0,lookups)
        assertEquals(BreezeBlueSpeechException.Reason.MISSING_CREDENTIAL,failure{provider.synthesize("😀".repeat(300),"v")}.reason)
        assertEquals(1,lookups)
        val unsafeKey=BreezeBlueSpeechProvider({"fixture\r\nheader"},HttpsConnectionFactory {throw AssertionError("network")})
        assertEquals(BreezeBlueSpeechException.Reason.MISSING_CREDENTIAL,failure{unsafeKey.synthesize("好","v")}.reason)
    }

    @Test fun redirectsAndHttpErrorsDoNotConsumeOrExposeTheBody() = runBlocking {
        for (status in listOf(202,301,302,307,308,401,429,500)) {
            lateinit var c:Connection
            val provider=BreezeBlueSpeechProvider({"fixture-only"},HttpsConnectionFactory {
                Connection(it,status,"private-fixture".byteInputStream()).also{opened->c=opened}
            })
            val error=failure{provider.synthesize("测试","voice")}
            assertEquals(BreezeBlueSpeechException.Reason.HTTP,error.reason);assertEquals(status,error.code)
            assertFalse(c.instanceFollowRedirects);assertFalse(c.readBody);assertEquals(0L,c.closed.count)
        }
    }

    @Test fun boundedResponseChecksMimeSizeAndFrameBoundaries() = runBlocking {
        suspend fun rejected(bytes: ByteArray, length: Long = -1, mime: String = "audio/mpeg") = failure {
            BreezeBlueSpeechProvider({ "fixture-only" }, HttpsConnectionFactory { url ->
                object : Connection(url, 200, bytes.inputStream(), length) {
                    override fun getContentType() = mime
                }
            }).synthesize("测试", "v")
        }.reason
        for (bad in listOf(byteArrayOf(), byteArrayOf(1, 2), "error".toByteArray(), frame.copyOf(575),
                frame + frame.copyOf(5), "ID3".toByteArray()))
            assertEquals(BreezeBlueSpeechException.Reason.INVALID_AUDIO, rejected(bad))
        assertEquals(BreezeBlueSpeechException.Reason.INVALID_AUDIO, rejected(frame, mime = "application/json"))
        assertEquals(BreezeBlueSpeechException.Reason.INVALID_AUDIO, rejected(frame, frame.size + 1L))
        assertEquals(BreezeBlueSpeechException.Reason.TOO_LARGE, rejected(frame, Long.MAX_VALUE))
        assertEquals(BreezeBlueSpeechException.Reason.TOO_LARGE, rejected(ByteArray(2 * 1024 * 1024 + 1)))
    }

    @Test fun transportErrorsAreSanitized() = runBlocking {
        for (cause in listOf(SocketTimeoutException("private fixture"), IOException("private fixture"))) {
            val provider = BreezeBlueSpeechProvider({ "fixture-only" }, HttpsConnectionFactory { throw cause })
            val error = failure { provider.synthesize("测试", "v") }
            assertEquals(if (cause is SocketTimeoutException) BreezeBlueSpeechException.Reason.TIMEOUT
                else BreezeBlueSpeechException.Reason.NETWORK, error.reason)
            assertNull(error.cause)
            assertFalse(error.toString().contains("private fixture"))
        }
    }

    @Test fun cancellationClosesInFlightRequest() = runBlocking {
        val started=CompletableDeferred<Unit>()
        lateinit var c:Connection
        val input=object:InputStream(){
            override fun read():Int {
                started.complete(Unit)
                if(!c.closed.await(5,TimeUnit.SECONDS))throw AssertionError("disconnect missing")
                throw IOException("closed")
            }
        }
        val provider=BreezeBlueSpeechProvider({"fixture-only"},HttpsConnectionFactory{url->Connection(url,200,input).also{c=it}})
        val job=launch{provider.synthesize("测试","v")}
        withTimeout(5000){started.await();job.cancelAndJoin()}
        assertTrue(job.isCancelled);assertTrue(c.closed.await(1,TimeUnit.SECONDS))
    }

    @Test fun returnsOfflineEncodedMp3Unchanged() = runBlocking {
        val tone = requireNotNull(javaClass.getResourceAsStream("/breezeblue/tone.mp3")).use { it.readBytes() }
        val provider = BreezeBlueSpeechProvider({ "fixture-only" }, HttpsConnectionFactory {
            Connection(it, 200, tone.inputStream(), tone.size.toLong())
        })
        assertArrayEquals(tone, provider.synthesize("测试", "voc_fixture"))
    }
    @Test fun id3AndMultipleCompleteFramesAreAccepted() = runBlocking {
        val body = byteArrayOf(73, 68, 51, 4, 0, 0, 0, 0, 0, 0) + frame + frame
        val provider = BreezeBlueSpeechProvider({ "fixture-only" }, HttpsConnectionFactory {
            Connection(it, 200, body.inputStream())
        })
        assertEquals(10 + 2 * 576, provider.synthesize("测试", "v").size)
    }

    @Test fun blockedConcurrentWritesAreDisconnectedAtDeadline() = runBlocking {
        val started = CountDownLatch(4)
        val finished = CountDownLatch(4)
        val emergencyRelease = CountDownLatch(1)
        val watchdog = java.util.Timer("test-stalled-write", true)
        watchdog.schedule(object : java.util.TimerTask() {
            override fun run() { emergencyRelease.countDown() }
        }, 8000)
        val provider = BreezeBlueSpeechProvider({ "fixture-only" }, HttpsConnectionFactory { url ->
            object : Connection(url, 200, ByteArrayInputStream(byteArrayOf())) {
                override fun getOutputStream() = object : OutputStream() {
                    override fun write(b: Int) {
                        started.countDown()
                        try {
                            // Simulate a socket write that ignores interruption and only exits on disconnect.
                            while (closed.count != 0L && emergencyRelease.count != 0L) {
                                try { closed.await(100, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
                            }
                            throw IOException("disconnected")
                        } finally {
                            finished.countDown()
                        }
                    }
                }
            }
        }, networkTimeoutMillis = 1200)
        try {
            val before = System.nanoTime()
            val errors = List(4) { async { failure { provider.synthesize("测试", "v") }.reason } }.awaitAll()
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 5000)
            assertEquals(0L, started.count)
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertTrue(errors.all { it == BreezeBlueSpeechException.Reason.TIMEOUT })
            val healthy = BreezeBlueSpeechProvider({ "fixture-only" }, HttpsConnectionFactory {
                Connection(it, 200, frame.inputStream())
            }, networkTimeoutMillis = 1200)
            assertEquals(576, healthy.synthesize("测试", "v").size)
        } finally {
            watchdog.cancel()
            emergencyRelease.countDown()
        }
    }

    @Test fun cancellationDuringStreamAcquisitionSkipsPayloadAndDisconnectsBlockedWrite() = runBlocking {
        // Handle a transport that ignores interrupts but unblocks on disconnect.
        for (stallBeforeWrite in listOf(true, false)) {
            val entered = CompletableDeferred<Unit>()
            val exited = CountDownLatch(1)
            val writes = java.util.concurrent.atomic.AtomicInteger()
            val provider = BreezeBlueSpeechProvider({ "fixture-only" }, HttpsConnectionFactory { url ->
                object : Connection(url, 200, ByteArrayInputStream(byteArrayOf())) {
                    fun waitForDisconnect() {
                        entered.complete(Unit)
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                        while (closed.count != 0L) {
                            val left = deadline - System.nanoTime()
                            if (left <= 0) throw AssertionError("disconnect missing")
                            try {
                                closed.await(left, TimeUnit.NANOSECONDS)
                            } catch (_: InterruptedException) {
                                // Deliberately model blocking I/O which ignores interruption.
                            }
                        }
                    }
                    override fun getOutputStream(): OutputStream {
                        if (stallBeforeWrite) waitForDisconnect()
                        return object : OutputStream() {
                            override fun write(b: Int) {
                                writes.incrementAndGet()
                                waitForDisconnect()
                                throw IOException("connection closed")
                            }
                            override fun close() { exited.countDown() }
                        }
                    }
                }
            })
            val job = launch { provider.synthesize("测试", "v") }
            withTimeout(5000) { entered.await(); job.cancelAndJoin() }
            assertTrue(exited.await(2, TimeUnit.SECONDS))
            assertEquals(if (stallBeforeWrite) 0 else 1, writes.get())
        }
    }

    @Test fun saturatingTransportReturnsBusyWithoutOpeningExtraConnections() = runBlocking {
        val release = CountDownLatch(1)
        val busy = CountDownLatch(4)
        val opened = java.util.concurrent.atomic.AtomicInteger()
        val body = frame
        val provider = BreezeBlueSpeechProvider({ "fixture-only" }, HttpsConnectionFactory { url ->
            opened.incrementAndGet()
            object : Connection(url, 200, body.inputStream()) {
                override fun getOutputStream(): OutputStream {
                    check(release.await(8, TimeUnit.SECONDS))
                    return sent
                }
            }
        })
        val requests = List(12) {
            async(kotlinx.coroutines.Dispatchers.Default) {
                try {
                    provider.synthesize("测试", "v")
                    true
                } catch (e: BreezeBlueSpeechException) {
                    assertEquals(BreezeBlueSpeechException.Reason.BUSY, e.reason)
                    busy.countDown()
                    false
                }
            }
        }
        try {
            assertTrue(busy.await(5, TimeUnit.SECONDS))
            assertTrue(opened.get() <= 4)
        } finally {
            release.countDown()
        }
        assertEquals(8, requests.awaitAll().count { it })
        assertEquals(8, opened.get())
    }
}
