package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.CopyOnWriteArrayList

/** Whole-batch invalidation, bounded repair and retry dedup. Synthetic isolated databases; never calls a provider. */
class MemoryBatchRepairAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String;private lateinit var store:Store;private lateinit var repo:ProcessingRepository
    private lateinit var vault:ModelVault;private lateinit var scope:CoroutineScope
    private val requests=CopyOnWriteArrayList<JSONObject>()
    private var turn=0L
    @Before fun setup()=runBlocking {
        name="batch-repair-${System.nanoTime()}";store=Store(context,"$name.db");repo=ProcessingRepository(store)
        store.add(listOf(Friend("a","A","Synthetic")),false)
        val l=ChatLedger(store);val t=l.begin("a","synthetic coffee fact","synthetic");assertTrue(l.finish(t.id,t.attempt,"synthetic reply"))
        turn=repo.sources("a").single().source.turnId
        vault=ModelVault(context,name);val c=ModelConfig("synthetic","Synthetic","custom","https://example.invalid/v1","fake",64000,2048)
        vault.save(c,"synthetic-only");vault.setPurpose(ModelPurpose.MEMORY,c.id);scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    }
    @After fun close()=runBlocking {scope.cancel();vault.list().forEach{vault.delete(it.id)};store.close();context.deleteDatabase("$name.db");Unit}
    private fun add(id:String,target:String,text:String,evidence:String="S1")=JSONObject().put("id",id).put("op","ADD").put("target",target).put("category","STATE_FACT").put("about","USER").put("text",text).put("evidence",JSONArray().put(evidence))
    private fun unknownMemory()=JSONObject().put("id","one").put("op","UPDATE").put("target","M7").put("category","STATE_FACT").put("about","USER").put("text","synthetic coffee fact").put("evidence",JSONArray().put("S1"))
    private fun receipt(d:JSONObject,ops:List<JSONObject>):TextChatResult {
        val array=JSONArray().apply{ops.forEach{put(it)}}
        return TextChatResult.Success(JSONObject().put("batch_id",d.getString("batch_id")).put("character_id","a").put("operations",array).put("operation_count",ops.map{it.getString("id")}.toSet().size).put("complete",true).put("end_marker",d.getString("batch_id")).toString())
    }
    private fun client(reply:(Int,JSONObject)->List<JSONObject>)=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
        val d=JSONObject(request.messages.last().content);requests+=d;return receipt(d,reply(requests.size,d))
    }}
    private fun engine(c:TextChatClient)=MemoryEngine(scope,repo,ModelAccess(vault),c,{},300000)
    private fun count(state:String)=store.readableDatabase.rawQuery("SELECT COUNT(*) FROM memory_batches WHERE state=?",arrayOf(state)).use{it.moveToFirst();it.getInt(0)}
    private fun sources(i:Int)=requests[i].getJSONArray("target_sources").toString()

    @Test fun editedSourceDuringSummaryInvalidatesWholeBatchAndResummarizesLatestContent()=runBlocking {
        val run=engine(client{n,_->
            if(n==1){
                // The user edits the cited message after the request was sent (raw write: the UI is locked during a summary).
                store.writableDatabase.execSQL("UPDATE messages SET body='synthetic tea fact' WHERE id=?",arrayOf(turn))
                store.writableDatabase.execSQL("UPDATE chat_turns SET source_revision=source_revision+1 WHERE friend_id='a' AND user_message_id=?",arrayOf(turn))
                listOf(add("one","N1","synthetic coffee fact"))
            }else listOf(add("one","N1","synthetic tea fact"))
        }).ensureRoom("a",forceOne=true)
        assertTrue(run.code,run.success);assertEquals(2,requests.size)
        assertTrue(sources(0).contains("synthetic coffee fact"));assertTrue(sources(1).contains("synthetic tea fact"));assertFalse(sources(1).contains("coffee"))
        assertNotEquals(requests[0].getString("batch_id"),requests[1].getString("batch_id"))
        assertEquals(listOf("synthetic tea fact"),repo.memories("a").map{it.text})
        assertEquals(1,count("INVALIDATED"));assertEquals(1,count("COMMITTED"));assertNull(MemoryBatchRepository(repo).failure("a"))
    }
    @Test fun withdrawnSourceDuringSummaryInvalidatesWholeBatchAndNothingIsPublished()=runBlocking {
        val run=engine(client{_,_->
            store.writableDatabase.execSQL("INSERT OR REPLACE INTO message_state(message_id,revision,deleted) VALUES(?,2,1)",arrayOf(turn))
            listOf(add("one","N1","synthetic coffee fact"))
        }).ensureRoom("a",forceOne=true)
        assertTrue(run.code,run.success);assertEquals(1,requests.size)
        assertTrue(repo.memories("a").isEmpty());assertEquals(1,count("INVALIDATED"));assertEquals(0,count("COMMITTED"))
        assertEquals("SOURCE_EDITED",store.readableDatabase.rawQuery("SELECT error FROM memory_batches WHERE state='INVALIDATED'",null).use{it.moveToFirst();it.getString(0)})
    }
    @Test fun nonexistentNumberIsSentBackForRepairThenTheWholeBatchCommits()=runBlocking {
        val run=engine(client{n,_->if(n==1)listOf(add("one","N1","synthetic coffee fact","S9")) else listOf(add("one","N1","synthetic coffee fact"))}).ensureRoom("a",forceOne=true)
        assertTrue(run.code,run.success);assertEquals(2,requests.size)
        assertFalse(requests[0].has("validation_feedback"))
        val feedback=requests[1].getJSONObject("validation_feedback")
        assertEquals(2,feedback.getInt("attempt"));assertEquals(3,feedback.getInt("max_attempts"))
        assertTrue(feedback.getJSONArray("errors").toString(),feedback.getJSONArray("errors").toString().contains("S9"))
        assertEquals(listOf("synthetic coffee fact"),repo.memories("a").map{it.text});assertTrue(repo.recentSources("a").isEmpty())
    }
    @Test fun threeFailedAttemptsPublishNothingKeepRawVisibleFailureAndAllowManualRetry()=runBlocking {
        var good=false
        val e=engine(client{_,_->if(good)listOf(add("one","N1","synthetic coffee fact")) else listOf(unknownMemory())})
        val run=e.ensureRoom("a",forceOne=true)
        assertFalse(run.success);assertEquals("UNKNOWN_TARGET",run.code);assertEquals(3,requests.size)
        assertEquals(3,requests[2].getJSONObject("validation_feedback").getInt("attempt"))
        assertTrue(requests[1].getJSONObject("validation_feedback").toString().contains("M7"))
        assertTrue(repo.memories("a").isEmpty());assertEquals(1,repo.recentSources("a").size);assertEquals(0L,repo.cursor("a"));assertEquals(0,count("COMMITTED"))
        val failure=checkNotNull(MemoryBatchRepository(repo).failure("a"))
        assertEquals("UNKNOWN_TARGET",failure.code);assertEquals(3,failure.attempts);assertEquals(requests[0].getString("batch_id"),failure.batchId)
        val text=memoryFailureText(failure)
        listOf("总结失败","模型引用了不存在的记忆编号","已尝试 3 次","本批没有保存","原话仍留在最近窗口","手动重试","M7").forEach{assertTrue(text,text.contains(it))}
        // A non-manual run never re-sends a batch that already used its three attempts.
        assertEquals("MANUAL_RETRY",e.ensureRoom("a",forceOne=true).code);assertEquals(3,requests.size)
        good=true;val retry=e.ensureRoom("a",forceOne=true,manualRetry=true)
        assertTrue(retry.code,retry.success);assertEquals(listOf("synthetic coffee fact"),repo.memories("a").map{it.text});assertNull(MemoryBatchRepository(repo).failure("a"))
    }
    @Test fun retryAfterRejectedBatchSavesOnceAndExactRepeatsAreSkipped()=runBlocking {
        val run=engine(client{n,_->
            if(n==1)listOf(add("one","N1","synthetic coffee fact"),add("two","N2","synthetic coffee fact again","S9"))
            else listOf(add("one","N1","synthetic coffee fact"),add("one","N1","synthetic coffee fact"),add("two","N2","synthetic coffee fact"))
        }).ensureRoom("a",forceOne=true)
        assertTrue(run.code,run.success);assertEquals(2,requests.size)
        assertEquals(listOf("synthetic coffee fact"),repo.memories("a").map{it.text})
        assertEquals(1,count("COMMITTED"))
    }
}
