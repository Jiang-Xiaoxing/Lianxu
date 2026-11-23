package app.lianxu.independent

import java.io.File
import java.net.*
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.*
import com.sun.net.httpserver.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test

/** Compare the real HTTPS POST against both journal exports without contacting a provider. */
class VoiceOfferWireTest {
    @get:Rule val temp = TemporaryFolder()
    private fun loopbackKeys(): KeyStore {
        val file = File(temp.root, "loopback.p12")
        val binary = if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool"
        val keytool = File(System.getProperty("java.home"), "bin/$binary")
        val process = ProcessBuilder(keytool.path, "-genkeypair", "-alias", "loopback", "-keyalg", "RSA",
            "-keystore", file.path, "-storetype", "PKCS12", "-storepass", "fixture-only", "-keypass", "fixture-only",
            "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "2", "-noprompt")
            .redirectErrorStream(true).start()
        try {
            check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "loopback certificate creation failed" }
        } finally {
            process.destroyForcibly()
        }
        return KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, "fixture-only".toCharArray()) } }
    }

    @Test fun verifiesBothExportsAgainstLoopbackReceiver() = runBlocking {
        val pwd = "fixture-only".toCharArray()
        val keys = loopbackKeys()
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys,pwd) }
        val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        val tls = SSLContext.getInstance("TLS").apply { init(km.keyManagers,tm.trustManagers,null) }
        val server = HttpsServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.httpsConfigurator = HttpsConfigurator(tls)
        val received = LinkedBlockingQueue<Boolean>()
        server.createContext("/v1/chat/completions") { exchange ->
            val tools=JSONObject(exchange.requestBody.bufferedReader().use { it.readText() }).optJSONArray("tools")
            received.add((0 until (tools?.length() ?: 0)).any { tools!!.getJSONObject(it).getJSONObject("function").getString("name")=="send_short_voice" })
            val reply="""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}""".toByteArray()
            exchange.responseHeaders.set("Content-Type","application/json")
            exchange.sendResponseHeaders(200,reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.start()
        val dir = temp.newFolder("journal")
        val turn=UUID.randomUUID().toString()
        DiagnosticJournal(dir,"localhost").use { journal ->
            Diagnostics.installForTest(journal)
            try {
                val client=LoggedTextChatClient(HttpsTextChatClient(HttpsConnectionFactory { (it.openConnection(Proxy.NO_PROXY) as HttpsURLConnection).apply { sslSocketFactory=tls.socketFactory } }))
                val cfg=ModelConfig("fixture","test","custom","https://localhost:${server.address.port}/v1","local",65536,2048)
                for (offered in listOf(false,true)) {
                    val n=if(offered)2L else 1L
                    val req=TextChatRequest(listOf(WireMessage("user","body-canary-8765")),tools=if(offered)listOf(ChatVoiceDelivery.definition) else emptyList())
                    val res=withContext(Diagnostics.linkedContext(mapOf("turn" to turn,"attempt" to 1,"request_number" to n,"request_role" to "ordinary_chat"))) {
                        client.complete(cfg,"key-canary-8765",req)
                    }
                    assertTrue("result=$res",res is TextChatResult.Success)
                    val actual=received.poll(1,TimeUnit.SECONDS)
                    assertEquals(offered,actual)
                    for(detail in listOf(false,true)) {
                        val bytes=Diagnostics.export(detail)
                        val text=bytes.decodeToString()
                        assertFalse(text.contains("key-canary-8765"));assertFalse(text.contains("body-canary-8765"))
                        val rows=text.lineSequence().filter { it.isNotBlank() }.map(::JSONObject).toList()
                        val sent=rows.filter { it.optString("event")=="http.request_sent" }.map{it.getJSONObject("data")}.single { it.optLong("request_number")==n }
                        assertEquals(turn,sent.getString("turn"))
                        assertEquals(actual,sent.getBoolean("voice_tool_provided"))
                        val id=sent.getString("request_id")
                        assertTrue(ChatVoiceDiagnostics.validTurn(id))
                        val status=rows.filter { it.optString("event")=="http.response" }.map {it.getJSONObject("data")}.single{it.getString("request_id")==id}.getInt("status")
                        assertEquals(200,status)
                    }
                }
            } finally { server.stop(0); Diagnostics.installForTest(null) }
        }
        Unit
    }
}
