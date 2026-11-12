package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * 记忆服务余额不足（HTTP 402）：一次就停、把原因说清、原话不动；
 * 充值或换模型后手动重试能补上失败批次并解开聊天；重试不会重复写入。
 */
class MemoryPaymentRecoveryAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String;private lateinit var store:Store;private lateinit var repo:ProcessingRepository
    private lateinit var vault:ModelVault;private lateinit var scope:CoroutineScope
    private val calls=AtomicInteger()
    @Volatile private var lastMemoryModel=""
    private var turn=0L
    private val original="明天还要上班，得加班"

    @Before fun setup()=runBlocking {
        name="payment-${System.nanoTime()}";store=Store(context,"$name.db");repo=ProcessingRepository(store)
        store.add(listOf(Friend("a","A","Synthetic")),false)
        val l=ChatLedger(store);val t=l.begin("a",original,"synthetic");assertTrue(l.finish(t.id,t.attempt,"那就别熬太晚"))
        turn=repo.sources("a").single().source.turnId
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
    /** Every summary request fails with the provider's 402 payment response. */
    private fun paying()=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
        calls.incrementAndGet();return TextChatResult.Failure(ChatFailure.PAYMENT,402)
    }}
    /** The provider works again (recharged or switched): one valid memory for the batch. */
    private fun working()=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
        calls.incrementAndGet();lastMemoryModel=config.id;val d=JSONObject(request.messages.last().content)
        return receipt(d,listOf(add("one","N1",original)))
    }}
    private fun engine(c:TextChatClient)=MemoryEngine(scope,repo,ModelAccess(vault),c,{},300000)
    private fun count(state:String)=store.readableDatabase.rawQuery("SELECT COUNT(*) FROM memory_batches WHERE state=?",arrayOf(state)).use{it.moveToFirst();it.getInt(0)}

    @Test fun paymentFailureCallsOnceSays402AndKeepsOriginalText():Unit=runBlocking {
        val run=engine(paying()).ensureRoom("a",forceOne=true)
        assertFalse(run.success);assertEquals("MODEL_PAYMENT",run.code)
        assertEquals("余额不足只能试一次，不能连续自动重试",1,calls.get())
        val failure=MemoryBatchRepository(repo).failure("a")
        assertNotNull(failure);assertEquals("MODEL_PAYMENT",failure!!.code)
        assertTrue(MemoryRepairFeedback.reason(failure.code).contains("402"))
        val notice=memoryFailureText(failure)
        assertTrue(notice,notice.contains("402"))
        assertTrue(notice,notice.contains("原话"))
        // 失败不截断、不移走原话。
        val source=repo.sources("a").single()
        assertEquals(original,source.user.text)
        assertEquals(1,repo.sources("a").size)
        assertEquals("原话仍要留在最近窗口",1,repo.recentSources("a").size)
        val body=store.readableDatabase.rawQuery("SELECT body FROM messages WHERE id=?",arrayOf(turn.toString())).use{it.moveToFirst();it.getString(0)}
        assertEquals(original,body)
        assertTrue("失败时没有写入任何记忆",repo.memories("a").isEmpty())
        assertEquals(0,count("COMMITTED"))
    }

    @Test fun manualRetryAfterFixingPaymentPublishesAndUnblocksChat():Unit=runBlocking {
        engine(paying()).ensureRoom("a",forceOne=true)
        assertTrue(repo.memories("a").isEmpty())
        // 换一个记忆模型（相当于换了服务商）。
        val switched=ModelConfig("memory2","合成记忆2","custom","https://example.invalid/v1","fake2",64000,2048)
        vault.save(switched,"synthetic-only");vault.setPurpose(ModelPurpose.MEMORY,switched.id)
        val fixed=engine(working())
        val retry=fixed.ensureRoom("a",forceOne=true,manualRetry=true)
        assertTrue(retry.code,retry.success)
        assertEquals("重试要用切换后的记忆模型","memory2",lastMemoryModel)
        assertEquals(1,repo.memories("a").size)
        assertEquals(1,count("COMMITTED"))
        // 补上以后，普通聊天不再要求总结，也不会再发请求。
        val before=calls.get()
        val after=fixed.ensureRoom("a")
        assertTrue(after.code,after.success)
        assertEquals("已解开阻断，不该再请求",before,calls.get())
    }

    @Test fun restartKeepsTheFailedBatchAndManualRetryStillWorks():Unit=runBlocking {
        engine(paying()).ensureRoom("a",forceOne=true)
        // 真重启：关掉数据库再重新打开，而不是只新建一个引擎对象。
        store.close();store=Store(context,"$name.db");repo=ProcessingRepository(store)
        val restarted=engine(working())
        restarted.recover()
        delay(300)
        assertTrue("恢复不能背着用户重试 402",repo.memories("a").isEmpty())
        assertEquals(1,calls.get())
        val retry=restarted.ensureRoom("a",forceOne=true,manualRetry=true)
        assertTrue(retry.code,retry.success)
        assertEquals(1,repo.memories("a").size)
    }

    @Test fun retryingAgainDoesNotDuplicateWhatWasAlreadyPublished():Unit=runBlocking {
        engine(paying()).ensureRoom("a",forceOne=true)
        val fixed=engine(working())
        assertTrue(fixed.ensureRoom("a",forceOne=true,manualRetry=true).success)
        val first=calls.get()
        fixed.ensureRoom("a",forceOne=true,manualRetry=true)
        assertEquals("重试不能重复写入",1,repo.memories("a").size)
        assertEquals("不能造出第二批提交",1,count("COMMITTED"))
        assertEquals("没有原话可总结时不该再请求模型",first,calls.get())
    }
}
