package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.*
import kotlin.concurrent.thread

/** One-request loopback TLS server. Reads and validates the real POST body; never a trust-all socket. */
class TextTlsMock(val status:Int=200,val response:String="{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"【Mock HTTP】合成回复\",\"reasoning_content\":\"not-user-output\"}}]}",val delay:Long=0,val contentType:String="application/json"):AutoCloseable {
    private val ks=KeyStore.getInstance("PKCS12").apply{InstrumentationRegistry.getInstrumentation().context.assets.open("localhost-test.p12").use{load(it,"synthetic-test-only".toCharArray())}}
    private val km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply{init(ks,"synthetic-test-only".toCharArray())}
    private val tls=SSLContext.getInstance("TLS").apply{init(km.keyManagers,null,null)}
    private val trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply{init(ks)}
    private val clientTls=SSLContext.getInstance("TLS").apply{init(null,trust.trustManagers,null)}
    private val server=tls.serverSocketFactory.createServerSocket(0,5,InetAddress.getByName("127.0.0.1")) as SSLServerSocket
    val base="https://localhost:${server.localPort}/v1"
    val calls=AtomicInteger()
    @Volatile var headers="";private set
    @Volatile var body="";private set
    private val worker=thread(isDaemon=true){try{server.accept().use{socket->
        calls.incrementAndGet();val input=socket.getInputStream();val header=ByteArrayOutputStream()
        while(header.size()<65536){val b=input.read();if(b<0)break;header.write(b);if(header.size()>=4&&header.toByteArray().takeLast(4)==listOf<Byte>(13,10,13,10))break}
        headers=header.toString("UTF-8")
        val size=Regex("(?im)^content-length: (\\d+)").find(headers)?.groupValues?.get(1)?.toInt()?:0;require(size<=2*1024*1024)
        val data=ByteArray(size);DataInputStream(input).readFully(data);body=String(data,Charsets.UTF_8)
        if(delay>0)Thread.sleep(delay)
        val bytes=response.toByteArray(Charsets.UTF_8);socket.getOutputStream().apply{write(("HTTP/1.1 $status Result\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nLocation: https://example.invalid/never-follow\r\nConnection: close\r\n\r\n").toByteArray());write(bytes);flush()}
    }}catch(_:Exception){}}
    fun client(timeout:Int=3000)=HttpsTextChatClient(HttpsConnectionFactory{url->(url.openConnection() as HttpsURLConnection).apply{sslSocketFactory=clientTls.socketFactory}},timeout)
    internal fun embeddingClient()=HttpsMemoryEmbeddingClient(HttpsConnectionFactory{url->(url.openConnection() as HttpsURLConnection).apply{sslSocketFactory=clientTls.socketFactory}})
    override fun close(){server.close();worker.join(2000)}
}

class LiveChatFoundationTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val request=TextChatRequest(listOf(WireMessage("system","合成规则"),WireMessage("user","合成输入"),WireMessage("system","合成时间尾段")))
    @Test fun actualTlsPostAllPresetsAndAuthDifferences():Unit=runBlocking{
        for(p in ProviderPresets.all)TextTlsMock().use{s->
            val cfg=ModelConfig("mock","合成",p.id,s.base,"synthetic-model")
            assertEquals("【Mock HTTP】合成回复",(s.client().complete(cfg,"synthetic-key-only",request) as TextChatResult.Success).text)
            assertTrue(s.headers.startsWith("POST /v1/chat/completions HTTP/1.1"))
            assertTrue(s.headers.contains(p.authHeader+": "+if(p.authHeader=="Authorization")"Bearer synthetic-key-only" else "synthetic-key-only",true))
            val json=JSONObject(s.body);assertEquals("synthetic-model",json.getString("model"));assertFalse(json.getBoolean("stream"));assertFalse(json.has("tools"))
            assertEquals("合成时间尾段",json.getJSONArray("messages").getJSONObject(2).getString("content"))
            assertTrue(json.has(if(p.id in setOf("openai","mimo"))"max_completion_tokens" else "max_tokens"))
        }
    }
    @Test fun errorsTimeoutNoThoughtOrToolSuccess():Unit=runBlocking{
        for((code,error) in listOf(401 to ChatFailure.UNAUTHORIZED,429 to ChatFailure.RATE_LIMIT,500 to ChatFailure.SERVER,302 to ChatFailure.REDIRECT))TextTlsMock(code,"synthetic-key-only raw-secret-error").use{s->
            val r=s.client().complete(ModelConfig("a","a","custom",s.base,"synthetic"),"synthetic-key-only",request)
            assertEquals(error,(r as TextChatResult.Failure).reason);assertFalse(r.toString().contains("synthetic-key-only"));assertEquals(1,s.calls.get())
        }
        for((body,error) in listOf(
            "not-json" to ChatFailure.FORMAT,
            "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}]}" to ChatFailure.TRUNCATED,
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"<think>private</think>\"}}]}" to ChatFailure.FORMAT,
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"unused\",\"tool_calls\":[{}]}}]}" to ChatFailure.TOOLS_UNAVAILABLE
        ))TextTlsMock(response=body).use{s->assertEquals(error,(s.client().complete(ModelConfig("a","a","custom",s.base,"synthetic"),"synthetic-key-only",request) as TextChatResult.Failure).reason)}
        TextTlsMock(delay=600).use{s->assertEquals(ChatFailure.TIMEOUT,(s.client(100).complete(ModelConfig("a","a","custom",s.base,"synthetic"),"synthetic-key-only",request) as TextChatResult.Failure).reason)}
        TextTlsMock().use{s->assertEquals(ChatFailure.TLS,(HttpsTextChatClient(timeoutMillis=2000).complete(ModelConfig("a","a","custom",s.base,"synthetic"),"synthetic-key-only",request) as TextChatResult.Failure).reason)}
    }
    @Test fun clientCancellationReturnsBeforeLateServer():Unit=runBlocking{
        TextTlsMock(delay=900).use{s->
            var accepted=0
            val job=launch{val result=s.client().complete(ModelConfig("a","a","custom",s.base,"synthetic"),"synthetic-key-only",request);if(result is TextChatResult.Success)accepted++}
            withTimeout(3000){while(s.calls.get()==0)delay(10)}
            withTimeout(500){job.cancelAndJoin()};delay(1000);assertEquals(0,accepted)
        }
    }
    @Test fun ledgerMigrationRetryCancellationRestartAndIsolation():Unit=runBlocking{
        val name="ledger-${System.nanoTime()}.db";var store=Store(context,name)
        try{
            val a=Friend("a","甲","合成");val b=Friend("b","乙","合成");store.add(listOf(a,b),false)
            store.exchange(a,"legacy-user","legacy-demo");val old=store.messages()
            store.writableDatabase.execSQL("DROP TABLE chat_turns");store.writableDatabase.version=1;store.close();store=Store(context,name)
            assertEquals(old,store.messages());var ledger=ChatLedger(store,"session-old")
            assertTrue(ledger.completedMessages("a").isEmpty())
            val first=ledger.begin("a","only-a","cfg","same-id");assertEquals(first,ledger.begin("a","only-a","cfg","same-id"))
            val lease=ChatAttemptLease();lease.cancel();assertFalse(ledger.finish(first.id,first.attempt,"late",lease));ledger.cancel(first.id,first.attempt)
            val second=ledger.retry(first.id,"cfg")!!;assertEquals(2,second.attempt);assertFalse(ledger.finish(first.id,first.attempt,"late-old"))
            val snapshot=ledger.context(second.id);assertTrue(ledger.finish(second.id,second.attempt,"only-a-reply",expectedStamp=snapshot.stamp));assertFalse(ledger.finish(second.id,second.attempt,"duplicate"))
            val other=ledger.begin("b","only-b","cfg");ledger.finish(other.id,other.attempt,"only-b-reply")
            assertEquals(listOf("only-a","only-a-reply"),ledger.completedMessages("a").map{it.text});assertTrue(ledger.completedMessages("b").none{it.text.contains("only-a")})
            val interrupted=ledger.begin("a","restart-input","cfg");store.close();store=Store(context,name);ledger=ChatLedger(store,"session-new");assertEquals(1,ledger.recoverInterrupted());assertEquals(TurnState.INTERRUPTED,ledger.all().single{it.id==interrupted.id}.state)
            val resumed=ledger.retry(interrupted.id,"cfg")!!;val contextBefore=ledger.context(resumed.id)
            store.writableDatabase.execSQL("UPDATE messages SET body='edited' WHERE id=?",arrayOf(resumed.userMessageId));assertFalse(ledger.finish(resumed.id,resumed.attempt,"stale",expectedStamp=contextBefore.stamp));ledger.fail(resumed.id,resumed.attempt,ChatFailure.SOURCE_CHANGED)
            assertTrue(store.messages().containsAll(old))
        }finally{store.close();context.deleteDatabase(name)}
    }
}
