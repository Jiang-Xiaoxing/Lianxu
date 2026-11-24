package app.lianxu.independent

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import java.io.*
import java.net.URL
import java.nio.file.Files
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/** Real HTTPS adapter with synthetic connection objects; no sockets, provider or user data. */
class NetworkDiagnosticCorrelationTest {
    private val config=ModelConfig("fake","合成","custom","https://example.invalid/v1","fake-model",16000,2048)
    private val key="sk-synthetic-diagnostic-secret-123456789"
    private val request=TextChatRequest(listOf(WireMessage("user","synthetic input")),256,timeoutMillis=90000)
    private val reply="""{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}"""
    private val privateError="diagnostic-payload-secret https://never-log.invalid/?private=raw"
    private class Fake(url:URL,val input:InputStream,val type:String="application/json",val badDisconnect:Boolean=false,val onDisconnect:(()->Unit)?=null):HttpsURLConnection(url){
        val workerClosed=CountDownLatch(1)
        val sent=ByteArrayOutputStream()
        override fun getCipherSuite()=""
        override fun getLocalCertificates():Array<Certificate>?=null
        override fun getServerCertificates():Array<Certificate> = emptyArray()
        override fun connect(){}
        override fun disconnect(){onDisconnect?.invoke();if(Thread.currentThread().name=="lianxu-text-http")workerClosed.countDown();if(badDisconnect)throw IOException("private disconnect payload")}
        override fun usingProxy()=false
        override fun getOutputStream():OutputStream=sent
        override fun getResponseCode()=200
        override fun getContentType()=type
        override fun getInputStream()=input
    }
    @Test fun deepSeekStreamingToolReplyContinuesWithOriginalReasoning()=verifyDeepSeekContinuation(true)
    @Test fun deepSeekNonStreamingToolReplyContinuesWithOriginalReasoning()=verifyDeepSeekContinuation(false)

    @Test fun memoryNonStreamingReadsNdjsonLines()=verifySummaryWire(false,false)
    @Test fun memoryNonStreamingReadsWholeDocument()=verifySummaryWire(false,true)
    @Test fun memoryStreamingReadsNdjsonLines()=verifySummaryWire(true,false)

    private fun verifySummaryWire(stream:Boolean,document:Boolean)=runBlocking {
        val lines=listOf("""{"id":1}""","""{"id":2}""")
        val text=if(document) "{\n  \"id\": 1\n}" else lines.joinToString("\r\n")
        val field=if(stream)"delta" else "message"
        val choice=org.json.JSONObject().put(field,org.json.JSONObject().put("content",text)).put("finish_reason","stop")
        val json=org.json.JSONObject().put("choices",org.json.JSONArray().put(choice)).toString()
        val response=if(stream)"data: $json\n\ndata: [DONE]\n\n" else json
        val fake=Fake(URL(config.baseUrl),response.byteInputStream(),if(stream)"text/event-stream" else "application/json")
        val client=HttpsTextChatClient(HttpsConnectionFactory{fake})
        val credential=ModelCredential(config.copy(streamReplies=stream),key,"stamp")
        val records=mutableListOf<String>()
        val whole=readMemorySummary(client,credential,request,document,{}){records.add(it)}
        assertEquals(stream,org.json.JSONObject(fake.sent.toString("UTF-8")).getBoolean("stream"))
        if(document){
            assertTrue(records.isEmpty())
            assertEquals(1,org.json.JSONObject(whole).getInt("id"))
        }else{
            assertEquals(lines,records)
            assertEquals(listOf(1,2),records.map{org.json.JSONObject(it).getInt("id")})
        }
    }

    private fun verifyDeepSeekContinuation(stream:Boolean)=runBlocking {
        val deepSeek=config.copy(providerId="deepseek",baseUrl="https://api.deepseek.com/v1",modelId="deepseek-v4-pro")
        val tool="""{"index":0,"id":"lookup-1","type":"function","function":{"name":"lookup","arguments":"{}"}}"""
        val response=if(stream) listOf(
            """data: {"choices":[{"delta":{"reasoning_content":"first "}}]}""",
            """data: {"choices":[{"delta":{"reasoning_content":"second","tool_calls":[$tool]},"finish_reason":"tool_calls"}]}""",
            "data: [DONE]"
        ).joinToString("\n\n") else
            """{"choices":[{"message":{"reasoning_content":"first second","tool_calls":[$tool]},"finish_reason":"tool_calls"}]}"""
        val first=Fake(URL(deepSeek.baseUrl),response.byteInputStream(),if(stream)"text/event-stream" else "application/json")
        val second=Fake(URL(deepSeek.baseUrl),reply.byteInputStream())
        val connections=ArrayDeque(listOf(first,second))
        val client=HttpsTextChatClient(HttpsConnectionFactory{connections.removeFirst()})
        val lookup=request.copy(tools=listOf(mapOf("type" to "function","function" to mapOf("name" to "lookup"))))
        val result=(if(stream)client.streamEvents(deepSeek,key,lookup){} else client.complete(deepSeek,key,lookup)) as TextChatResult.Success
        val call=result.toolCalls.single()
        val next=request.copy(jsonObject=true,messages=request.messages+listOf(
            WireMessage("assistant",result.text,result.toolCalls,continuation=result.continuation),
            WireMessage("tool","found",toolCallId=call.id,name=call.name)))
        assertTrue(client.complete(deepSeek,key,next) is TextChatResult.Success)
        val wire=JsonParser.parseString(second.sent.toString("UTF-8")).asJsonObject
        assertEquals("first second",wire["messages"].asJsonArray[1].asJsonObject["reasoning_content"].asString)
        assertFalse(wire.has("response_format"))
    }

    private inner class Blocked:InputStream(){
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        override fun read():Int {
            entered.countDown();val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
            while(release.count>0&&System.nanoTime()<deadline)try{release.await(50,TimeUnit.MILLISECONDS)}catch(_:InterruptedException){}
            throw IOException(privateError)
        }
    }
    private fun journal(block:suspend(DiagnosticJournal)->Unit)=runBlocking {
        val base=File(System.getProperty("java.io.tmpdir")).canonicalFile
        val dir=Files.createTempDirectory(base.toPath(),"netdiag-").toFile();val journal=DiagnosticJournal(dir,"synthetic")
        Diagnostics.installForTest(journal)
        try{block(journal)}finally{Diagnostics.installForTest(null);journal.close();check(dir.canonicalFile.parentFile==base&&dir.name.startsWith("netdiag-"));dir.deleteRecursively()}
    }
    private fun rows(journal:DiagnosticJournal)=journal.export(true).toString(Charsets.UTF_8).lineSequence().mapNotNull{runCatching{JsonParser.parseString(it).asJsonObject}.getOrNull()}.toList()
    private fun named(rows:List<JsonObject>,name:String)=rows.filter{it.get("event")?.asString==name}
    private fun data(row:JsonObject)=row["data"].asJsonObject
    private fun terminals(rows:List<JsonObject>)=rows.filter{it["event"]?.asString in setOf("network.response","network.cancelled","network.failed")}
    private suspend fun context(block:suspend()->Unit)=withContext(Diagnostics.context("memory",trace="trace-correlation")){
        withContext(Diagnostics.linkedContext(mapOf("batch" to "batch-1","attempt" to 7,"attempt_id" to "attempt-7"))){block()}
    }
    @Test fun successSharesRequestTraceSpanPurposeAndCountsAcrossWorker(){journal{j->
        val fake=Fake(URL(config.baseUrl),ByteArrayInputStream(reply.toByteArray()))
        context{assertTrue(LoggedTextChatClient(HttpsTextChatClient(HttpsConnectionFactory{fake})).complete(config,key,request) is TextChatResult.Success)}
        assertTrue(fake.workerClosed.await(2,TimeUnit.SECONDS))
        val all=rows(j);val sent=named(all,"network.request").single();val id=data(sent)["request_id"].asString
        for(name in listOf("http.request_sent","http.response","http.first_byte","http.last_byte","network.response")){
            val row=named(all,name).single();val d=data(row)
            assertEquals(id,d["request_id"].asString);assertEquals("trace-correlation",row["trace"].asString)
            assertEquals(data(sent)["span"],d["span"]);assertEquals("memory",d["purpose"].asString)
            assertEquals("batch-1",d["batch"].asString);assertEquals(7,d["attempt"].asInt)
        }
        assertEquals(1,terminals(all).size)
        val response=data(named(all,"network.response").single());assertEquals(reply.toByteArray().size,response["response_bytes"].asInt)
        assertTrue(response["last_byte_ms"].asLong>=response["first_byte_ms"].asLong)
        assertEquals(90000,request.timeoutMillis)
        val metadata=all.filter{it["event"]?.asString in setOf("http.request_sent","http.first_byte","http.last_byte","http.transfer_end")}.joinToString()
        assertFalse(metadata.contains(key));assertFalse(metadata.contains("synthetic input"));assertFalse(metadata.contains("example.invalid"))
    }}
    @Test fun totalTimeoutThenLateAbortIsLinkedCleanupAndOnlyOneTerminal(){journal{j->
        val body=Blocked();val fake=Fake(URL(config.baseUrl),body)
        try{
            context{val result=LoggedTextChatClient(HttpsTextChatClient(HttpsConnectionFactory{fake},timeoutMillis=500)).complete(config,key,request) as TextChatResult.Failure;assertEquals(ChatFailure.TIMEOUT,result.reason)}
            assertEquals(0L,body.entered.count);body.release.countDown();assertTrue(fake.workerClosed.await(2,TimeUnit.SECONDS))
            val all=rows(j);val terminal=terminals(all).single();assertEquals("TIMEOUT",data(terminal)["reason"].asString)
            val late=named(all,"http.network_failed").single();assertTrue(all.indexOf(late)>all.indexOf(terminal))
            assertEquals(data(terminal)["request_id"],data(late)["request_id"])
            assertEquals("after_local_timeout",data(late)["cause_relation"].asString);assertTrue(data(late)["after_terminal_decision"].asBoolean)
            assertEquals("total_timeout",data(late)["cancel_source"].asString);assertFalse(all.joinToString().contains("diagnostic-payload-secret"))
        }finally{body.release.countDown()}
    }}
    @Test fun callerCancellationAndLateAbortKeepOneCancelledTerminal(){journal{j->
        val body=Blocked();val fake=Fake(URL(config.baseUrl),body)
        try{
            coroutineScope{
                val job=launch{context{LoggedTextChatClient(HttpsTextChatClient(HttpsConnectionFactory{fake},timeoutMillis=5000)).complete(config,key,request)}}
                assertTrue(withContext(Dispatchers.IO){body.entered.await(2,TimeUnit.SECONDS)})
                job.cancel();job.join()
            }
            body.release.countDown();assertTrue(fake.workerClosed.await(2,TimeUnit.SECONDS))
            val all=rows(j);val terminal=terminals(all).single();assertEquals("network.cancelled",terminal["event"].asString)
            assertEquals("caller_cancellation",data(terminal)["cancel_source"].asString)
            assertEquals("after_local_cancellation",data(named(all,"http.network_failed").single())["cause_relation"].asString)
        }finally{body.release.countDown()}
    }}
    @Test fun unpromptedBrokenResponseRemainsUnattributedRatherThanRemoteDiagnosis(){journal{j->
        val input=object:InputStream(){override fun read():Int=throw IOException(privateError)}
        context{val result=LoggedTextChatClient(HttpsTextChatClient(HttpsConnectionFactory{Fake(it,input)})).complete(config,key,request) as TextChatResult.Failure;assertEquals(ChatFailure.RESPONSE_BROKEN,result.reason)}
        val all=rows(j);assertEquals(1,terminals(all).size)
        val d=data(named(all,"http.network_failed").single());assertEquals("unattributed",d["cause_relation"].asString);assertEquals("none",d["cancel_source"].asString)
        assertFalse(all.joinToString().contains("diagnostic-payload-secret"))
    }}
    @Test fun emptySseKeepsItsRealRejectionWithZeroBytesAndOneTerminal(){journal{j->
        context{val result=LoggedTextChatClient(HttpsTextChatClient(HttpsConnectionFactory{Fake(it,ByteArrayInputStream(byteArrayOf()),"text/event-stream")})).streamEvents(config,key,request){} as TextChatResult.Failure;assertEquals(ChatFailure.TRUNCATED,result.reason)}
        val all=rows(j);assertEquals(1,terminals(all).size);assertTrue(named(all,"http.first_byte").isEmpty())
        assertEquals(0,data(named(all,"network.response").single())["response_bytes"].asInt)
        assertEquals(data(named(all,"network.response").single())["request_id"],data(named(all,"http.stream_rejected").first())["request_id"])
    }}
    @Test fun structuredRetryGetsDistinctRequestsAndValidationIsNotCommit(){journal{j->
        var calls=0;val timeouts=mutableListOf<Int>()
        val client=LoggedTextChatClient(object:TextChatClient{
            override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{timeouts+=request.timeoutMillis;return TextChatResult.Success(if(calls++==0)"{}"else"{\"ok\":true}")}
        })
        context{assertTrue(StructuredModelCall(client).generate("appraisal",config,key,request.messages){raw->val root=JsonParser.parseString(raw).asJsonObject;require(root.has("ok"));root["ok"].asBoolean})}
        val all=rows(j);val requests=named(all,"network.request");assertEquals(2,requests.size)
        assertEquals(2,requests.map{data(it)["request_id"].asString}.toSet().size);assertEquals(listOf(1,2),requests.map{data(it)["model_attempt"].asInt})
        requests.forEach{assertEquals("batch-1",data(it)["batch"].asString);assertEquals("appraisal",data(it)["stage"].asString)}
        assertEquals(2,terminals(all).size);assertEquals(listOf(120000,120000),timeouts)
        for(name in listOf("companion.structured.attempt","companion.structured.rejected","companion.structured.accepted"))named(all,name).forEach{
            assertEquals(7,data(it)["attempt"].asInt);assertTrue(data(it)["model_attempt"].asInt in 1..2)
        }
        assertEquals("validated_not_committed",data(named(all,"companion.structured.accepted").single())["commit_state"].asString)
        assertTrue(named(all,"memory.batch.committed").isEmpty())
    }}
    @Test fun directoryAndEmbeddingReuseRequestIdentityAndCounts(){journal{j->
        context{
            val dir=LoggedModelDirectoryClient(HttpsModelClient(HttpsConnectionFactory{Fake(it,ByteArrayInputStream("{\"data\":[{\"id\":\"model\"}]}".toByteArray()))}))
            assertTrue(dir.request(config,key,true) is ConfigResult.Success)
            val embedding=HttpsMemoryEmbeddingClient(HttpsConnectionFactory{Fake(it,ByteArrayInputStream("{\"data\":[{\"index\":0,\"embedding\":[0.5,0.25]}]}".toByteArray()))})
            assertEquals(1,embedding.embed(ModelCredential(config,key,"synthetic"),listOf("synthetic vector input"),1000).size)
        }
        val all=rows(j);val directory=data(named(all,"model.directory.result").single());val embedding=data(named(all,"embedding.http.result").single())
        assertNotEquals(directory["request_id"],embedding["request_id"]);assertTrue(directory["response_bytes"].asInt>0);assertTrue(embedding["response_bytes"].asInt>0)
        assertEquals("batch-1",embedding["batch"].asString);assertTrue(named(all,"model.http.abort_requested").isEmpty())
    }}
    @Test fun terminalMarkerAndThreadContextRestoreAreDeterministic(){journal{_->
        val state=NetworkDiagnosticState("request-test");assertTrue(state.finish("TIMEOUT"));assertFalse(state.finish("SUCCESS"))
        val old=Diagnostics.traceState.get();val captured=DiagnosticTraceState("trace","span",null,"memory",state)
        Diagnostics.onThread(captured){assertSame(state,Diagnostics.requestState())}
        assertEquals(old,Diagnostics.traceState.get())
    }}
    @Test fun disconnectExceptionIsLoggedWithoutReplacingSuccessfulReply(){journal{j->
        val fake=Fake(URL(config.baseUrl),ByteArrayInputStream(reply.toByteArray()),badDisconnect=true)
        context{val result=LoggedTextChatClient(HttpsTextChatClient(HttpsConnectionFactory{fake},timeoutMillis=500)).complete(config,key,request) as TextChatResult.Success;assertEquals("ok",result.text)}
        val all=rows(j);val terminal=terminals(all).single();val failures=named(all,"http.disconnect_failed")
        assertEquals("success",data(terminal)["status"].asString)
        assertTrue(failures.isNotEmpty());failures.forEach{failed->
            assertEquals(data(terminal)["request_id"],data(failed)["request_id"])
            assertEquals("IOException",data(failed)["error_type"].asString)
        }
        assertFalse(all.joinToString().contains("private disconnect payload"))
    }}
    @Test fun disconnectExceptionDoesNotReplacePrimaryChatFailure(){journal{j->
        val input=object:InputStream(){override fun read():Int=throw IOException(privateError)}
        context{val result=LoggedTextChatClient(HttpsTextChatClient(HttpsConnectionFactory{Fake(it,input,badDisconnect=true)},timeoutMillis=500)).complete(config,key,request) as TextChatResult.Failure;assertEquals(ChatFailure.RESPONSE_BROKEN,result.reason)}
        val all=rows(j);assertEquals("RESPONSE_BROKEN",data(terminals(all).single())["reason"].asString)
        assertTrue(named(all,"http.disconnect_failed").isNotEmpty());assertTrue(named(all,"http.total_timeout").isEmpty())
    }}
    @Test fun directoryAndEmbeddingKeepTheirResultsWhenDisconnectThrows(){journal{j->
        context{
            val directory=LoggedModelDirectoryClient(HttpsModelClient(HttpsConnectionFactory{Fake(it,ByteArrayInputStream("{\"data\":[{\"id\":\"model\"}]}".toByteArray()),badDisconnect=true)}))
            assertTrue(directory.request(config,key,true) is ConfigResult.Success)
            val brokenDirectory=LoggedModelDirectoryClient(HttpsModelClient(HttpsConnectionFactory{Fake(it,ByteArrayInputStream("{".toByteArray()),badDisconnect=true)}))
            assertEquals(ConfigError.FORMAT,(brokenDirectory.request(config,key,true) as ConfigResult.Failure).error)
            val embedding=HttpsMemoryEmbeddingClient(HttpsConnectionFactory{Fake(it,ByteArrayInputStream("{\"data\":[{\"index\":0,\"embedding\":[0.5,0.25]}]}".toByteArray()),badDisconnect=true)})
            assertEquals(1,embedding.embed(ModelCredential(config,key,"synthetic"),listOf("synthetic"),1000).size)
            val brokenEmbedding=HttpsMemoryEmbeddingClient(HttpsConnectionFactory{Fake(it,ByteArrayInputStream("{\"data\":[]}".toByteArray()),badDisconnect=true)})
            try{brokenEmbedding.embed(ModelCredential(config,key,"synthetic"),listOf("synthetic"),1000);fail()}catch(_:IllegalArgumentException){}
        }
        val all=rows(j);assertEquals(2,named(all,"model.directory.result").size)
        assertTrue(named(all,"model.http.disconnect_failed").isNotEmpty());assertTrue(named(all,"embedding.http.disconnect_failed").isNotEmpty())
        assertEquals(1,named(all,"embedding.http.result").size);assertEquals(1,named(all,"embedding.http.failed").size)
        assertEquals("IllegalArgumentException",data(named(all,"embedding.http.failed").single())["error_type"].asString)
    }}
    @Test fun primarySocketTimeoutIsNotCalledAfterTimeoutCleanup(){journal{j->
        val input=object:InputStream(){override fun read():Int=throw java.net.SocketTimeoutException(privateError)}
        context{val result=LoggedTextChatClient(HttpsTextChatClient(HttpsConnectionFactory{Fake(it,input)},timeoutMillis=5000)).complete(config,key,request) as TextChatResult.Failure;assertEquals(ChatFailure.TIMEOUT,result.reason)}
        val all=rows(j);val timeout=data(named(all,"http.timeout").single())
        assertEquals("primary_local_timeout",timeout["cause_relation"].asString);assertEquals("socket_timeout",timeout["cancel_source"].asString)
        assertFalse(timeout["after_terminal_decision"].asBoolean);assertEquals(1,terminals(all).size)
    }}
    @Test fun directoryCancellationStillRecordsTheDisconnectInducedReadException(){journal{j->
        val input=Blocked();val fake=Fake(URL(config.baseUrl),input,onDisconnect={input.release.countDown()})
        try{
            coroutineScope{
                val job=launch{context{LoggedModelDirectoryClient(HttpsModelClient(HttpsConnectionFactory{fake})).request(config,key,true)}}
                assertTrue(withContext(Dispatchers.IO){input.entered.await(2,TimeUnit.SECONDS)})
                job.cancelAndJoin()
            }
            val all=rows(j);val cancelled=named(all,"model.directory.cancelled").single();val readError=named(all,"model.http.failed").single()
            assertEquals(data(cancelled)["request_id"],data(readError)["request_id"]);assertEquals("IOException",data(readError)["error_type"].asString)
            assertTrue(named(all,"model.directory.result").isEmpty())
        }finally{input.release.countDown()}
    }}
    @Test fun embeddingValidationFailureIsNotMisclassifiedAsTransport(){journal{j->
        val client=HttpsMemoryEmbeddingClient(HttpsConnectionFactory{Fake(it,ByteArrayInputStream("{\"data\":[]}".toByteArray()))})
        context{try{client.embed(ModelCredential(config,key,"synthetic"),listOf("synthetic input"),1000);fail()}catch(_:IllegalArgumentException){}}
        val all=rows(j);assertEquals(1,named(all,"embedding.http.parse_failed").size);assertTrue(named(all,"embedding.http.transport_failed").isEmpty())
        assertEquals(1,named(all,"embedding.http.failed").size);assertTrue(named(all,"embedding.http.result").isEmpty())
    }}
    @Test fun timeoutDecisionKeepsTheSourceChosenByTheAtomicOperation(){journal{j->
        val cancelled=NetworkDiagnosticState("cancel-first")
        cancelled.cancel(CancellationException("synthetic"))
        val afterCancel=cancelled.timeout(true)
        assertFalse(afterCancel.primary);assertEquals("caller_cancellation",afterCancel.source)
        cancelled.failure("test.cancel_first",java.net.SocketTimeoutException(),afterCancel)
        val timedOut=NetworkDiagnosticState("timeout-first")
        val firstTimeout=timedOut.timeout(false)
        assertTrue(firstTimeout.primary);assertEquals("socket_timeout",firstTimeout.source)
        timedOut.stop("total_timeout") // Later confirmation must not rewrite this event's decision.
        timedOut.failure("test.timeout_first",java.net.SocketTimeoutException(),firstTimeout)
        val all=rows(j)
        val first=data(named(all,"test.cancel_first").single())
        assertEquals("caller_cancellation",first["cancel_source"].asString);assertEquals("after_local_cancellation",first["cause_relation"].asString)
        val second=data(named(all,"test.timeout_first").single())
        assertEquals("socket_timeout",second["cancel_source"].asString);assertEquals("primary_local_timeout",second["cause_relation"].asString)
    }}
    @Test fun embeddingRequestSizeLimitIsRecordedBeforeAnyBodyIsSent(){journal{j->
        val client=HttpsMemoryEmbeddingClient(HttpsConnectionFactory{Fake(it,ByteArrayInputStream(byteArrayOf()))})
        context{try{client.embed(ModelCredential(config,key,"synthetic"),listOf("x".repeat(270000)),1000);fail()}catch(_:IllegalArgumentException){}}
        val all=rows(j)
        assertEquals(1,named(all,"embedding.http.request_size_rejected").size)
        assertTrue(named(all,"embedding.http.request_sent").isEmpty());assertTrue(named(all,"embedding.http.transport_failed").isEmpty())
        assertEquals(1,named(all,"embedding.http.failed").size)
    }}
    @Test fun embeddingResponseSizeLimitIsRecordedSeparatelyFromTransport(){journal{j->
        val input=object:InputStream(){
            var remaining=16*1024*1024+1
            override fun read():Int=if(remaining-- >0)'x'.code else -1
            override fun read(b:ByteArray,off:Int,len:Int):Int {
                if(remaining<=0)return -1
                val n=minOf(len,remaining);java.util.Arrays.fill(b,off,off+n,'x'.code.toByte());remaining-=n;return n
            }
        }
        val client=HttpsMemoryEmbeddingClient(HttpsConnectionFactory{Fake(it,input)})
        context{try{client.embed(ModelCredential(config,key,"synthetic"),listOf("synthetic input"),1000);fail()}catch(_:IllegalArgumentException){}}
        val all=rows(j)
        assertEquals(1,named(all,"embedding.http.response_size_rejected").size);assertTrue(named(all,"embedding.http.transport_failed").isEmpty())
        assertEquals(16*1024*1024+1,data(named(all,"embedding.http.failed").single())["response_bytes"].asInt)
        assertEquals(1,named(all,"embedding.http.failed").size);assertTrue(named(all,"embedding.http.result").isEmpty())
    }}
}
