package app.lianxu.independent

import com.google.gson.JsonObject
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class TextStreamEventsTest {
    private val config=ModelConfig("c","合成","custom","https://example.invalid/v1","m")
    private val request=TextChatRequest(listOf(WireMessage("user","合成请求")))
    @Test fun completeOnlyFallbackUsesOneCallAndNeverInventsReasoning()=runBlocking {
        var calls=0
        val client=object:TextChatClient {
            override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {calls++;return TextChatResult.Success("合成正文",displayReasoning="原生合成思考")}
        }
        val events=mutableListOf<TextStreamEvent>();val result=client.streamEvents(config,"synthetic",request){events+=it}
        assertEquals(1,calls);assertEquals(listOf(TextStreamEvent.Reasoning("原生合成思考"),TextStreamEvent.Content("合成正文")),events)
        assertEquals("原生合成思考",(result as TextChatResult.Success).displayReasoning)
        val noReasoning=object:TextChatClient {override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest)=TextChatResult.Success("只有正文")}
        events.clear();noReasoning.streamEvents(config,"synthetic",request){events+=it}
        assertEquals(listOf(TextStreamEvent.Content("只有正文")),events)
    }
    @Test fun completeFailureAndCancellationCannotBecomeStreamSuccess()=runBlocking {
        val events=mutableListOf<TextStreamEvent>()
        val failure=object:TextChatClient {override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest)=TextChatResult.Failure(ChatFailure.TIMEOUT)}
        assertEquals(TextChatResult.Failure(ChatFailure.TIMEOUT),failure.streamEvents(config,"synthetic",request){events+=it});assertTrue(events.isEmpty())
        val entered=CompletableDeferred<Unit>();var returned=false
        val pending=object:TextChatClient {override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {entered.complete(Unit);awaitCancellation()}}
        val job=launch {pending.streamEvents(config,"synthetic",request){events+=it};returned=true}
        entered.await();job.cancelAndJoin();assertFalse(returned);assertTrue(events.isEmpty())
        val late=object:TextChatClient {override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {currentCoroutineContext().cancel();return TextChatResult.Success("late reply",displayReasoning="late thought")}}
        val lateJob=launch {late.streamEvents(config,"synthetic",request){events+=it};returned=true}
        lateJob.join();assertTrue(lateJob.isCancelled);assertFalse(returned);assertTrue(events.isEmpty())
    }
    @Test fun coalescerPreservesWhitespaceOrderAndFinalTail() {
        var now=0L;val emitted=mutableListOf<TextStreamEvent>()
        val relay=TextStreamEventCoalescer({emitted+=it},{now})
        relay.append(TextStreamEvent.Reasoning("first"));assertEquals(1,emitted.size)
        relay.append(TextStreamEvent.Reasoning(" "));relay.append(TextStreamEvent.Reasoning("second"));assertEquals(1,emitted.size)
        relay.append(TextStreamEvent.Content("reply"));assertEquals(3,emitted.size)
        relay.append(TextStreamEvent.Content("\n"));relay.flush()
        assertEquals(listOf(TextStreamEvent.Reasoning("first"),TextStreamEvent.Reasoning(" second"),TextStreamEvent.Content("reply"),TextStreamEvent.Content("\n")),emitted)
        now=100_000_000L;relay.append(TextStreamEvent.Content("tail"));assertEquals(TextStreamEvent.Content("tail"),emitted.last())
    }
    @Test fun typedDiagnosticsRecordReasoningOnlyInDetailedMode()=runBlocking {
        val dir=Files.createTempDirectory("reasoning-diagnostic-test-").toFile();val journal=DiagnosticJournal(dir,"test");Diagnostics.installForTest(journal)
        try{
            fun client()=LoggedTextChatClient(object:TextChatClient {
                override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("Must use typed stream")
                override suspend fun streamEvents(config:ModelConfig,key:String,request:TextChatRequest,onEvent:suspend(TextStreamEvent)->Unit):TextChatResult {
                    onEvent(TextStreamEvent.Reasoning("private-native-fragment"));onEvent(TextStreamEvent.Content("public-reply"))
                    return TextChatResult.Success("public-reply",displayReasoning="private-native-final")
                }
            })
            for(fail in listOf(false,true)){
                val client=if(fail)LoggedTextChatClient(object:TextChatClient {
                    override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("Must use typed stream")
                    override suspend fun streamEvents(config:ModelConfig,key:String,request:TextChatRequest,onEvent:suspend(TextStreamEvent)->Unit):TextChatResult {
                        onEvent(TextStreamEvent.Reasoning("private-native-fragment"));onEvent(TextStreamEvent.Content("public-reply"))
                        return TextChatResult.Failure(ChatFailure.TRUNCATED)
                    }
                })else client()
                val events=mutableListOf<TextStreamEvent>();val result=client.streamEvents(config,"synthetic",request){events+=it}
                assertEquals(fail,result is TextChatResult.Failure);assertTrue(events.first() is TextStreamEvent.Reasoning)
            }
            val simple=journal.export(true).toString(Charsets.UTF_8)
            assertFalse("默认不记录思考原文",simple.contains("private-native-final"))
            assertFalse("流式分片不逐片落盘",simple.contains("private-native-fragment"))
            assertTrue("状态字段必须存在",simple.contains("\"reasoning_status\""))
            assertTrue("失败前已流出的思考要标明 partial，不能当作未返回",simple.contains("\"partial\""))
            Diagnostics.detailed=true
            client().streamEvents(config,"synthetic",request){}
            val detailed=journal.export(true).toString(Charsets.UTF_8)
            assertTrue("详细模式记录界面展示的思考",detailed.contains("private-native-final"))
            assertFalse("仍然不逐片落盘",detailed.contains("private-native-fragment"))
            assertTrue(detailed.contains("public-reply"));assertTrue(detailed.contains("TRUNCATED"))
        }finally{Diagnostics.installForTest(null);journal.close();require(dir.name.startsWith("reasoning-diagnostic-test-"));dir.deleteRecursively()}
    }
    @Test fun nativeReasoningAliasesAreRedactedInNestedObjectsAndEscapedJson(){
        val redactor=DiagnosticRedactor()
        for(field in listOf("reasoning_content","thinking_content","reasoning","thinking","displayReasoning")){
            val value=JsonObject().apply{addProperty(field,"private \"quoted\" tail\nline");addProperty("content","public")}
            val nested=JsonObject().apply{add("result",value)}
            for(clean in listOf(redactor.clean(nested).toString(),redactor.text(value.toString()))){
                assertFalse(clean.contains("private"));assertFalse(clean.contains("quoted"));assertFalse(clean.contains("tail"));assertFalse(clean.contains("line"));assertTrue(clean.contains("public"))
            }
        }
    }
}
