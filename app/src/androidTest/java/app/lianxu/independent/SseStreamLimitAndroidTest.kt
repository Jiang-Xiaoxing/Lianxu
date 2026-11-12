package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * 事件流读不动时要分清是哪一种：读超限、根本不是事件流、内容校验不过。
 * 同时保证长但正常的事件流不再被 2MB 老闸门误杀，失败后同一批还能补上且原话不动。
 */
class SseStreamLimitAndroidTest {
    private val key="synthetic-key-only-for-test"
    private fun config()=ModelConfig("synthetic","Synthetic","custom","https://example.invalid/v1","fake",64000,2048)
    private fun request()=TextChatRequest(listOf(WireMessage("user","synthetic")),256)
    private fun client(body:String,mime:String)=HttpsTextChatClient(HttpsConnectionFactory{url->ProviderMockConnection(url,body,mime)},20000)
    private fun frame(content:String)=
        "data: "+JSONObject().put("choices",JSONArray().put(JSONObject().put("index",0)
            .put("delta",JSONObject().put("content",content)).put("finish_reason",JSONObject.NULL))).toString()+"\n\n"
    private fun done()="data: [DONE]\n\n"
    private fun finish()="data: "+JSONObject().put("choices",JSONArray().put(JSONObject().put("index",0)
        .put("delta",JSONObject()).put("finish_reason","stop"))).toString()+"\n\n"

    @Test fun aLongButValidStreamOverTheOldTwoMegabyteCapStillSucceeds():Unit=runBlocking {
        val answer="记忆写入完成"
        val stream=StringBuilder();var bytes=0
        while(bytes<=2*1024*1024+64*1024){val piece=frame("记");stream.append(piece);bytes+=piece.length}
        stream.append(frame(answer)).append(finish()).append(done())
        val received=StringBuilder();var chunks=0
        val result=client(stream.toString(),"text/event-stream").stream(config(),key,request()){chunk->chunks++;received.append(chunk)}
        assertTrue("读到 2MB 不能再被误判成格式错误：$result",result is TextChatResult.Success)
        assertTrue("长流的尾巴要完整收到：$received",received.endsWith(answer))
        assertTrue("2MB 以上的事件流要整段收完，不能半路丢（收到 ${received.length} 字、$chunks 块）",received.length>20000)
    }

    @Test fun byteOrderMarkAndCarriageReturnStreamsStillParse():Unit=runBlocking {
        val raw=("\uFEFF"+frame("你")+frame("好")+finish()+done()).replace("\n","\r")
        val received=StringBuilder()
        val result=client(raw,"text/event-stream").stream(config(),key,request()){received.append(it)}
        assertTrue("BOM 与只有回车的换行都不能被误判：$result",result is TextChatResult.Success)
        assertEquals("你好",received.toString())
    }

    @Test fun aBodyThatNeverFramedSseFailsFastAsProtocolNotFormat():Unit=runBlocking {        val html="<!DOCTYPE html><html><body>gateway blocked this request</body></html>\n".repeat(2048)
        val started=System.currentTimeMillis()
        val result=client(html,"text/event-stream").stream(config(),key,request()){}
        val elapsed=System.currentTimeMillis()-started
        assertEquals("不是事件流要说成协议问题，不能笼统写格式错误",ChatFailure.STREAM_PROTOCOL,(result as TextChatResult.Failure).reason)
        assertTrue("不能等读满整个上限才发现（用了 ${elapsed}ms）",elapsed<15000)
    }

    @Test fun readingPastTheWireCapIsNamedAsALimit():Unit=runBlocking {
        val padding="a".repeat(300)
        val stream=StringBuilder();var bytes=0
        while(bytes<=9*1024*1024){
            val piece="data: "+JSONObject().put("id",padding).put("choices",JSONArray().put(JSONObject().put("index",0)
                .put("delta",JSONObject()).put("finish_reason",JSONObject.NULL))).toString()+"\n\n"
            stream.append(piece);bytes+=piece.length
        }
        val result=client(stream.toString(),"text/event-stream").stream(config(),key,request()){}
        assertEquals(ChatFailure.STREAM_LIMIT,(result as TextChatResult.Failure).reason)
    }
}

/** 同一批：先因为「不是事件流」失败，修好之后手动重试能补上，原话不动、不重复写入。 */
class MemoryStreamFailureAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String;private lateinit var store:Store;private lateinit var repo:ProcessingRepository
    private lateinit var vault:ModelVault;private lateinit var scope:CoroutineScope
    private val calls=AtomicInteger()
    private val original="明天还要上班，得加班"

    @Before fun setup()=runBlocking {
        name="stream-${System.nanoTime()}";store=Store(context,"$name.db");repo=ProcessingRepository(store)
        store.add(listOf(Friend("a","A","Synthetic")),false)
        val l=ChatLedger(store);val t=l.begin("a",original,"synthetic");assertTrue(l.finish(t.id,t.attempt,"那就别熬太晚"))
        vault=ModelVault(context,name);val c=ModelConfig("synthetic","Synthetic","custom","https://example.invalid/v1","fake",64000,2048)
        vault.save(c,"synthetic-only");vault.setPurpose(ModelPurpose.MEMORY,c.id);scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    }
    @After fun close()=runBlocking{scope.cancel();vault.list().forEach{vault.delete(it.id)};store.close();context.deleteDatabase("$name.db");Unit}

    private fun add(id:String,target:String,text:String,evidence:String="S1")=JSONObject().put("id",id).put("op","ADD").put("target",target)
        .put("category","STATE_FACT").put("about","USER").put("text",text).put("evidence",JSONArray().put(evidence))
    private fun receipt(d:JSONObject,ops:List<JSONObject>):TextChatResult {
        val array=JSONArray().apply{ops.forEach{put(it)}}
        return TextChatResult.Success(JSONObject().put("batch_id",d.getString("batch_id")).put("character_id","a").put("operations",array)
            .put("operation_count",ops.map{it.getString("id")}.toSet().size).put("complete",true).put("end_marker",d.getString("batch_id")).toString())
    }
    private fun unreadable()=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
        calls.incrementAndGet();return TextChatResult.Failure(ChatFailure.STREAM_PROTOCOL)
    }}
    private fun oversize()=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
        calls.incrementAndGet();return TextChatResult.Failure(ChatFailure.STREAM_LIMIT)
    }}
    private fun working()=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
        calls.incrementAndGet();val d=JSONObject(request.messages.last().content)
        return receipt(d,listOf(add("one","N1",original)))
    }}
    private fun engine(c:TextChatClient)=MemoryEngine(scope,repo,ModelAccess(vault),c,{},300000)
    private fun count(state:String)=store.readableDatabase.rawQuery("SELECT COUNT(*) FROM memory_batches WHERE state=?",arrayOf(state)).use{it.moveToFirst();it.getInt(0)}

    @Test fun unreadableStreamIsNotAutoRetriedAndNamesTheCause():Unit=runBlocking {
        val run=engine(unreadable()).ensureRoom("a",forceOne=true)
        assertFalse(run.success);assertEquals("MODEL_STREAM_PROTOCOL",run.code)
        assertEquals("事件流读不动不该自动重试三次",1,calls.get())
        val failure=MemoryBatchRepository(repo).failure("a")
        assertNotNull(failure);assertEquals("MODEL_STREAM_PROTOCOL",failure!!.code)
        assertTrue(MemoryRepairFeedback.reason(failure.code).contains("事件流"))
        val notice=memoryFailureText(failure)
        assertTrue(notice,notice.contains("原话"))
        assertEquals(original,repo.sources("a").single().user.text)
        assertEquals("原话仍要留在最近窗口",1,repo.recentSources("a").size)
        assertTrue("失败时没有写入任何记忆",repo.memories("a").isEmpty())
        assertEquals(0,count("COMMITTED"))
    }

    @Test fun theSameBatchRecoversOnManualRetryWithoutLosingOrDuplicatingTheOriginal():Unit=runBlocking {
        engine(unreadable()).ensureRoom("a",forceOne=true)
        val fixed=engine(working())
        val retry=fixed.ensureRoom("a",forceOne=true,manualRetry=true)
        assertTrue(retry.code,retry.success)
        assertEquals(1,repo.memories("a").size)
        assertEquals(original,repo.sources("a").single().user.text)
        val after=calls.get()
        fixed.ensureRoom("a",forceOne=true,manualRetry=true)
        assertEquals("重试不能重复写入",1,repo.memories("a").size)
        assertEquals(1,count("COMMITTED"))
        assertEquals("没有原话可总结时不该再请求模型",after,calls.get())
    }

    @Test fun anOversizedStreamIsAlsoNamedAndRecoversTheSameWay():Unit=runBlocking {
        val run=engine(oversize()).ensureRoom("a",forceOne=true)
        assertFalse(run.success);assertEquals("MODEL_STREAM_LIMIT",run.code)
        assertEquals("读超限也不该自动重试三次",1,calls.get())
        assertTrue(MemoryRepairFeedback.reason("MODEL_STREAM_LIMIT").contains("读取上限"))
        assertTrue(memoryFailureText(MemoryBatchRepository(repo).failure("a")!!).contains("原话"))
        assertEquals(original,repo.sources("a").single().user.text)
        val retry=engine(working()).ensureRoom("a",forceOne=true,manualRetry=true)
        assertTrue(retry.code,retry.success)
        assertEquals(1,repo.memories("a").size)
    }
}
