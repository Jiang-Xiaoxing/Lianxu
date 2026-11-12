package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** Independent synthetic databases, mock clients and Keystore entries; never provider traffic. */
class ProcessingEngineTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store;private lateinit var repo:ProcessingRepository;private lateinit var ledger:ChatLedger
    private lateinit var vault:ModelVault;private lateinit var scope:CoroutineScope;private lateinit var name:String
    @Before fun prepare()=runBlocking{
        name="processing-${System.nanoTime()}.db";store=Store(context,name);repo=ProcessingRepository(store);ledger=ChatLedger(store)
        store.add(listOf(Friend("a","合成甲","独立角色甲"),Friend("b","合成乙","独立角色乙")),false)
        vault=ModelVault(context,"processing-${System.nanoTime()}");scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        listOf(ModelPurpose.CHAT,ModelPurpose.MEMORY).forEach{p->val c=ModelConfig(p.key,p.title,"custom","https://example.invalid/v1",p.key,contextTokens=32000,outputTokens=2048);vault.save(c,"synthetic-key-only");vault.setPurpose(p,c.id)}
    }
    @After fun close(){scope.cancel();vault.list().forEach{vault.delete(it.id)};store.close();context.deleteDatabase(name)}
    private suspend fun turn(id:String="a",text:String="合成原话",reply:String="合成回复"):ChatTurn {val t=ledger.begin(id,text,"chat");assertTrue(ledger.finish(t.id,t.attempt,reply));return ledger.all().first{it.id==t.id}}
    private fun op(s:SourceDialogue,id:String="one",target:String="new:one",kind:String="ADD",text:String="用户喜欢合成花园")=JSONObject().put("id",id).put("target",target).put("op",kind).put("category","STATE_FACT").put("about","USER").put("tags",JSONArray()).put("text",text).put("core",true).put("date_expression",JSONObject.NULL).put("evidence",JSONArray().put(JSONObject().put("turn",s.source.turnId).put("version",s.source.version).put("side","user").put("quote",s.user.text)))
    private suspend fun await(test:suspend()->Boolean){withTimeout(10000){while(!test())delay(10)}}

    @Test fun partialReceiptRetryIsDurableAndOnlyWholeBatchRetires()=runBlocking{
        assertTrue(ProcessingRepository.keywordScore("合成花园","用户喜欢合成花园")>0)
        turn(text="我喜欢合成花园，也喜欢合成茶。");turn("b","同样的合成原话")
        val s=repo.sources("a").single();val batches=MemoryBatchRepository(repo);var w=batches.prepare("a",listOf(s),"synthetic-config")
        batches.beginAttempt(w,"old-process");val first=op(s);val second=op(s,"two","new:two",text="用户喜欢合成茶")
        batches.apply(w,first);batches.apply(w,second);batches.fail(w,"INCOMPLETE")
        assertEquals(2,repo.memories("a").size);assertEquals(0L,repo.cursor("a"));assertEquals(1,repo.recentSources("a").size)
        val old=w;w=batches.prepare("a",listOf(s),"synthetic-config");batches.beginAttempt(w,"second-owner");batches.fail(old,"CANCELLED")
        assertEquals("RUNNING",repo.memoryStatus("a").state);batches.fail(w,"INTERRUPTED")
        assertTrue(repo.memories("b").isEmpty());store.close();store=Store(context,name);repo=ProcessingRepository(store);ledger=ChatLedger(store)
        val reopened=MemoryBatchRepository(repo);assertEquals(2,reopened.appliedOperations(w).size);reopened.beginAttempt(w,"new-process")
        reopened.apply(w,first);reopened.apply(w,second);assertEquals(2,repo.memories("a").size)
        try{reopened.complete(w,setOf("one","two"),2,"bad-marker");fail()}catch(_:ProcessingRejected){}
        assertEquals(0L,repo.cursor("a"));assertTrue(reopened.complete(w,setOf("one","two"),2,w.id))
        assertEquals(s.source.turnId,repo.cursor("a"));assertTrue(repo.recentSources("a").isEmpty());assertEquals(2,store.messages().count{it.friendId=="a"})
    }
    @Test fun lockedConflictPreviewCancelAndSourceVersionInvalidateWholeBatch()=runBlocking{
        turn(text="我喜欢合成花园。");val s=repo.sources("a").single()
        val m=repo.manualSave("a",null,"人工原文",MemoryCategory.STATE_FACT,true,true,MemoryAbout.USER)
        val batches=MemoryBatchRepository(repo);val w=batches.prepare("a",listOf(s),"synthetic-config");batches.beginAttempt(w,"test")
        batches.apply(w,op(s,target=m.id,kind="UPDATE",text="试图覆盖人工内容"))
        assertEquals("人工原文",repo.memories("a").single().text);assertEquals(1,repo.candidates("a").size)
        val source=SourceChanges(repo);val impact=source.preview("a",s.source.turnId,"user","修改后的原话",false)
        assertEquals(s.user.text,repo.sources("a").single().user.text) // preview/cancel makes no write
        source.confirm(impact)
        try{batches.complete(w,setOf("one"),1,w.id);fail()}catch(e:ProcessingRejected){assertEquals("SOURCE_CHANGED",e.code)}
        assertEquals(0L,repo.cursor("a"));assertEquals(2L,repo.sources("a").single().source.version)
        assertEquals(s.user.text,source.history("a",s.user.id).last().body)
        assertEquals("人工原文",repo.memories("a").single().text)
    }
    /**
     * 2026-11-02 requirement change approved by 小星: an invalid item rejects the whole batch, nothing is saved, and the
     * same batch is re-sent with the specific validation errors. Previously the two valid items were saved item by item
     * and the resend listed them under already_saved_operations (expected 2); now that list is empty and the feedback names "bad".
     */
    @Test fun engineResendsSameBatchAfterPartialValidationAndKeepsOtherRoleSeparate()=runBlocking{
        repo.saveMemoryRules(MemoryRules(2,1,15));repeat(3){turn(text="x".repeat(290),reply="y".repeat(90))};turn("b","x".repeat(290),"y".repeat(90))
        val calls=AtomicInteger();var batchId=""
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
            assertEquals("memory",config.modelId);val data=JSONObject(request.messages.last().content);val n=calls.incrementAndGet()
            if(n==1)batchId=data.getString("batch_id") else{assertEquals(batchId,data.getString("batch_id"));assertEquals(0,data.getJSONArray("already_saved_operations").length())
                assertTrue(data.getJSONObject("validation_feedback").getJSONArray("errors").toString().contains("bad"));assertTrue(repo.memories("a").isEmpty())}
            val s=SourceDialogue.from(data.getJSONArray("target_dialogue").getJSONObject(0));val ops=JSONArray().put(op(s)).put(op(s,"two","new:two",text="另一条合成事实"))
            if(n==1)ops.put(JSONObject().put("id","bad"))
            return TextChatResult.Success(JSONObject().put("batch_id",batchId).put("character_id","a").put("operations",ops).put("operation_count",ops.length()).put("complete",true).put("end_marker",batchId).toString())
        }}
        val engine=MemoryEngine(scope,repo,ModelAccess(vault),client,{})
        val result=engine.ensureRoom("a",forceOne=true);assertTrue(result.toString(),result.success);assertEquals(2,calls.get());assertEquals(2,repo.memories("a").size)
        assertEquals(2,repo.recentSources("a").size);assertEquals(1,repo.recentSources("b").size);assertTrue(repo.memories("b").isEmpty())
        assertEquals(6,store.messages().count{it.friendId=="a"})
    }
    /**
     * 2026-11-02 requirement change approved by 小星 (whole-batch atomic commit): a stream that drops before the end
     * receipt saves nothing. Previously the two streamed rows were kept and the retry only sent the receipt
     * (already_saved_operations=2, memories=2 before retry); now the retry sees 0 saved, re-sends both rows plus the receipt.
     */
    @Test fun actualTlsStreamDisconnectKeepsTwoValidRowsAndRetriesSameReceipt()=runBlocking{
        turn(text="我喜欢合成花园和合成茶。");val source=repo.sources("a").single();val work=MemoryBatchRepository(repo).prepare("a",listOf(source),"before-real-tls")
        fun line(op:JSONObject)=JSONObject().put("type","operation").put("batch_id",work.id).put("character_id","a").put("operation",op).toString()+"\n"
        fun event(text:String)="data: "+JSONObject().put("choices",JSONArray().put(JSONObject().put("index",0).put("delta",JSONObject().put("content",text)).put("finish_reason",JSONObject.NULL))).toString()+"\n\n"
        val body=event(line(op(source)))+event(line(op(source,"two","new:two",text="喜欢合成茶"))) // deliberately no stop / DONE / batch marker
        TextTlsMock(response=body,contentType="text/event-stream").use{server->
            vault.save(vault.list().single{it.id=="memory"}.copy(baseUrl=server.base),"synthetic-key-only")
            val calls=AtomicInteger()
            val client=object:TextChatClient{
                override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("stream only")
                override suspend fun stream(config:ModelConfig,key:String,request:TextChatRequest,onChunk:suspend(String)->Unit):TextChatResult {
                    if(calls.incrementAndGet()==1)return server.client().stream(config,key,request,onChunk)
                    assertEquals(0,repo.memories("a").size);assertEquals(0L,repo.cursor("a"));assertEquals(1,repo.recentSources("a").size)
                    val data=JSONObject(request.messages.last().content);assertEquals(work.id,data.getString("batch_id"));assertEquals(0,data.getJSONArray("already_saved_operations").length())
                    val end=JSONObject().put("type","complete").put("batch_id",work.id).put("character_id","a").put("operation_count",2).put("complete",true).put("end_marker",work.id)
                    onChunk(line(op(source)));onChunk(line(op(source,"two","new:two",text="喜欢合成茶")));onChunk(end.toString()+"\n");return TextChatResult.Success("mock-receipt")
                }
            }
            val result=MemoryEngine(scope,repo,ModelAccess(vault),client,{}).ensureRoom("a",forceOne=true)
            assertTrue(result.toString(),result.success);assertEquals(2,calls.get());assertEquals(1,server.calls.get());assertEquals(2,repo.memories("a").size);assertTrue(repo.recentSources("a").isEmpty())
        }
    }
    @Test fun sourceRetractionInvalidatesLockedEvidenceAndPreservesSnapshotHistory()=runBlocking{
        turn(text="那年我去过合成花园。");val source=repo.sources("a").single();val batches=MemoryBatchRepository(repo);val work=batches.prepare("a",listOf(source),"synthetic")
        batches.beginAttempt(work,"test");val id=batches.apply(work,op(source).put("date_expression","那年"));assertTrue(batches.complete(work,setOf("one"),1,work.id));repo.setLock("a",id,true)
        assertNull(repo.memories("a").single().eventDate);val changes=SourceChanges(repo);val impact=changes.preview("a",source.source.turnId,"user","",true)
        assertEquals(1,impact.memories.size);assertTrue(repo.memories("a").single().valid);changes.confirm(impact)
        val memory=repo.memories("a").single();assertFalse(memory.valid);assertTrue(memory.locked);assertEquals(1,repo.candidates("a").size)
        assertTrue(repo.reference("a","合成花园",999).first.isEmpty());assertEquals(source.user.text,memory.evidence.single().snapshot)
        assertEquals("【已撤回】",store.messages().first{it.id==source.user.id}.text);assertEquals(source.user.text,changes.history("a",source.user.id).last().body)
        assertEquals(3,repo.history("a",id).size)
    }
    @Test fun runnerUsesMemoryBeforeChatAndInjectsSavedFactsOnlyForOwner()=runBlocking{
        repo.saveMemoryRules(MemoryRules(1,1,15));repeat(2){turn(text="合成原话".repeat(400),reply="合成旧回复".repeat(1800))}
        vault.list().single{it.modelId=="memory"}.let{vault.save(it.copy(contextTokens=64000),"synthetic-key-only")}
        val input="新用户原话"+"z".repeat(240)
        var sawMemory=false;var sawChat=false;var structuredCalls=0;var schema=0
        var replyRequest:TextChatRequest?=null;var replyModel="";var memoryBeforeReply=false
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
            syntheticCompanionStage(request)?.let{structuredCalls++;return it}
            if(config.modelId=="memory"){
                sawMemory=true;val data=JSONObject(request.messages.last().content);schema=data.getInt("memory_schema");val s=SourceDialogue.from(data.getJSONArray("target_dialogue").getJSONObject(0));val id=data.getString("batch_id")
                return TextChatResult.Success(JSONObject().put("batch_id",id).put("character_id","a").put("operations",JSONArray().put(op(s))).put("operation_count",1).put("complete",true).put("end_marker",id).toString())
            }
            replyModel=config.modelId;memoryBeforeReply=sawMemory;replyRequest=request;sawChat=true
            return TextChatResult.Success("合成客户端真实链路回复")
        }}
        val access=ModelAccess(vault);val memory=MemoryEngine(scope,repo,access,client,{})
        val runner=ConversationRunner(scope,store,vault,client,repo,memory,{},{});
        // Overflow actual request capacity: merely exceeding the preferred round count no longer
        // blocks a reply that can carry the original history intact.
        withContext(Dispatchers.Main){assertTrue(runner.send("a",input,{}))}
        await{val rows=ledger.all();rows.size==3&&rows.last().state!=TurnState.PENDING};assertTrue(ledger.all().last().toString(),sawChat);assertEquals(TurnState.COMPLETED,ledger.all().last().state)
        assertEquals(0,structuredCalls);assertEquals(2,schema);assertEquals("chat",replyModel);assertTrue(memoryBeforeReply)
        assertFalse(CompanionRecord.NAME in replyRequest!!.tools.toString())
        assertTrue(replyRequest!!.messages.any{"用户喜欢合成花园" in it.content})
        assertEquals(input,replyRequest!!.latestUserText())
        assertEquals(1,replyRequest!!.messages.count{it.role=="user"&&it.content.startsWith(input)})
        assertTrue(repo.memories("b").isEmpty());assertTrue(ledger.completedMessages("b").isEmpty())
        assertEquals("2024-12-25",MemoryDates.resolve("去年圣诞节",Instant.parse("2025-01-01T00:00:00Z").toEpochMilli(),"Asia/Shanghai"));assertNull(MemoryDates.resolve("那年",0,"UTC"))
    }
}
