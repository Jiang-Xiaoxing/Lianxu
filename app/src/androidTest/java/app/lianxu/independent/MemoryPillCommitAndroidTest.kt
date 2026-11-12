package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicInteger

/** 只有整批提交成功才立胶囊：一条一颗、计数正确、失败不出现、重试补齐且不重复。 */
class MemoryPillCommitAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String;private lateinit var store:Store;private lateinit var repo:ProcessingRepository
    private lateinit var ledger:ChatLedger;private lateinit var vault:ModelVault;private lateinit var scope:CoroutineScope
    private val calls=AtomicInteger()
    @Volatile private var broken=false
    private val original="明天还要上班，得加班"

    @Before fun prepare():Unit=runBlocking{
        name="pill-commit-${System.nanoTime()}.db";store=Store(context,name);repo=ProcessingRepository(store);ledger=ChatLedger(store)
        store.add(listOf(Friend("a","A","Synthetic")),false)
        vault=ModelVault(context,name);val c=ModelConfig("synthetic","Synthetic","custom","https://example.invalid/v1","fake",64000,2048)
        vault.save(c,"synthetic-only");vault.setPurpose(ModelPurpose.MEMORY,c.id);scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        Unit
    }
    @After fun close():Unit=runBlocking{scope.cancel();vault.list().forEach{vault.delete(it.id)};store.close();context.deleteDatabase(name);Unit}

    private fun add(id:String,target:String,text:String)=JSONObject().put("id",id).put("op","ADD").put("target",target)
        .put("category","STATE_FACT").put("about","USER").put("text",text).put("evidence",JSONArray().put("S1"))
    private fun receipt(d:JSONObject,ops:List<JSONObject>):TextChatResult {
        val array=JSONArray().apply{ops.forEach{put(it)}}
        return TextChatResult.Success(JSONObject().put("batch_id",d.getString("batch_id")).put("character_id","a").put("operations",array)
            .put("operation_count",ops.map{it.getString("id")}.toSet().size).put("complete",true).put("end_marker",d.getString("batch_id")).toString())
    }
    private fun engine(ops:(JSONObject)->List<JSONObject>)=MemoryEngine(scope,repo,ModelAccess(vault),object:TextChatClient{
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
            calls.incrementAndGet()
            if(broken)return TextChatResult.Failure(ChatFailure.STREAM_PROTOCOL)
            val d=JSONObject(request.messages.last().content)
            return receipt(d,ops(d))
        }
    },{},300000)
    private fun one(d:JSONObject)=listOf(add("one","N1",original))
    private fun two(d:JSONObject)=listOf(add("one","N1",original),add("two","N2","加班到很晚"))
    private suspend fun completedTurn():ChatTurn{val t=ledger.begin("a",original,"synthetic");assertTrue(ledger.finish(t.id,t.attempt,"那就别熬太晚"));return t}
    private fun pills()=ChatMemoryPills.read(store.readableDatabase,"a")

    @Test fun aBlockingSummaryPillSitsBetweenTheBlockedMessageAndTheResumedReply():Unit=runBlocking {
        completedTurn()
        val pending=ledger.begin("a","今天又加班了","synthetic")
        val run=engine(::one).ensureRoom("a",forceOne=true)
        assertTrue(run.code,run.success)
        val pill=pills().single()
        assertEquals("已整理 1 条记忆",pill.text)
        assertEquals("胶囊要落在被挡住的这条消息后面",pending.userMessageId,pill.afterMessageId)
        assertTrue(ledger.finish(pending.id,pending.attempt,"那就早点睡"))
        val replyId=ledger.all().first{it.id==pending.id}.assistantMessageId!!
        assertTrue("胶囊必须在恢复回复之前",replyId>pill.afterMessageId)
    }

    @Test fun twoSavedMemoriesCountTwo():Unit=runBlocking {
        completedTurn()
        assertTrue(engine(::two).ensureRoom("a",forceOne=true).success)
        assertEquals("已整理 2 条记忆",pills().single().text)
        assertEquals(2,pills().single().savedCount)
    }

    @Test fun aFailedBatchWritesNoPillAndOneRetryAddsExactlyOne():Unit=runBlocking {
        completedTurn();broken=true
        val failed=engine(::one).ensureRoom("a",forceOne=true)
        assertFalse(failed.success)
        assertTrue("失败不能立胶囊",pills().isEmpty())
        broken=false
        assertTrue(engine(::one).ensureRoom("a",forceOne=true,manualRetry=true).success)
        assertEquals(1,pills().size)
        // 再读几次、再重建列表也不会多出来。
        assertEquals(1,pills().size)
        assertEquals("已整理 1 条记忆",pills().single().text)
    }

    @Test fun reopeningTheDatabaseKeepsOnePillWithItsCount():Unit=runBlocking {
        completedTurn()
        assertTrue(engine(::two).ensureRoom("a",forceOne=true).success)
        store.close();store=Store(context,name);repo=ProcessingRepository(store)
        assertEquals(1,pills().size);assertEquals(2,pills().single().savedCount)
    }
}
