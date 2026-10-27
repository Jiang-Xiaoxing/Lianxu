package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Private synthetic stores only. No provider calls, production data or user keys. */
class MemoryIntegrityTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store;private lateinit var repo:ProcessingRepository;private lateinit var name:String
    @Before fun setup()=runBlocking{name="integrity-${System.nanoTime()}.db";store=Store(context,name);repo=ProcessingRepository(store);store.add(listOf(Friend("a","A","Synthetic"),Friend("b","B","Synthetic")),false)}
    @After fun close(){store.close();context.deleteDatabase(name)}
    private suspend fun source(text:String="coffee and tea",friend:String="a"):SourceDialogue{val l=ChatLedger(store);val t=l.begin(friend,text,"synthetic");assertTrue(l.finish(t.id,t.attempt,"synthetic reply"));return repo.sources(friend).last()}
    private fun op(s:SourceDialogue,id:String="one",alias:String="new:one",text:String="No coffee")=JSONObject().put("id",id).put("op","ADD").put("target",alias).put("category","PREFERENCE_BOUNDARY").put("about","USER").put("tags",JSONArray()).put("text",text).put("core",false).put("evidence",JSONArray().put(JSONObject().put("turn",s.source.turnId).put("version",s.source.version).put("side","user").put("quote",s.user.text)))
    private suspend fun work(b:MemoryBatchRepository,s:SourceDialogue)=b.prepare("a",listOf(s),"synthetic").also{b.beginAttempt(it,"test")}
    private suspend fun rejected(code:String,action:suspend()->Unit){val e=runCatching{action()}.exceptionOrNull();assertEquals(code,(e as ProcessingRejected).code)}
    @Test fun duplicateAliasesAndRetryKeepOneVersionAndResolveLaterTargets()=runBlocking {
        val s=source();val b=MemoryBatchRepository(repo);val w=work(b,s);val first=op(s);val id=b.apply(w,first)
        assertEquals(id,b.apply(w,op(s,"two","new:two").put("core",true).put("tags",JSONArray().put("extra"))))
        assertEquals(id,b.apply(w,op(s,"three")));assertEquals(id,b.apply(w,first))
        assertEquals(1,repo.memories("a").size);assertEquals(1,repo.history("a",id).size);assertFalse(repo.memories("a").single().core)
        b.apply(w,op(s,"four","new:two","No coffee now").put("op","UPDATE"))
        assertEquals(2,repo.memories("a").single().version);assertEquals(1,repo.memories("a").single().evidence.size)
        assertTrue(b.complete(w,setOf("one","two","three","four"),4,w.id))
    }
    @Test fun sameMessageCanSaveSeveralFactsAndIndependentSourceCanSupportOne()=runBlocking {
        val s=source();val b=MemoryBatchRepository(repo);val w=work(b,s);val id=b.apply(w,op(s))
        b.apply(w,op(s,"two","new:two","Likes tea"));assertTrue(b.complete(w,setOf("one","two"),2,w.id));assertEquals(2,repo.memories("a").size)
        val next=source("coffee still makes sleep difficult");val fresh=work(b,next)
        b.apply(fresh,op(next,"next",id).put("op","UPDATE"));assertTrue(b.complete(fresh,setOf("next"),1,fresh.id))
        val m=repo.memories("a").first{it.id==id};assertEquals(2,m.evidence.size);assertEquals(2,m.version)
    }
    @Test fun lateSummaryUsingCorrectedInputCannotWriteAnyDerivedFact()=runBlocking {
        val m=repo.manualSave("a",null,"old-coffee-sentinel",MemoryCategory.PREFERENCE_BOUNDARY,false,true,MemoryAbout.USER,listOf("coffee"))
        val other=repo.manualSave("b",null,"other-role",MemoryCategory.STATE_FACT,false,true,MemoryAbout.USER)
        val s=source();val b=MemoryBatchRepository(repo);val w=work(b,s)
        repo.manualSave("a",m.id,"current-tea",m.category,false,true,MemoryAbout.USER,listOf("coffee"))
        rejected("SOURCE_CHANGED"){b.apply(w,op(s,text="derived-old-coffee-sentinel"))}
        assertEquals(1,repo.memories("a").size);assertEquals(other,repo.memories("b").single())
        val refs=repo.references("a","coffee",999);val prompt=CompanionPrompt.build(Friend("a","A","Synthetic"),RolePrompts(),emptyList(),"coffee",refs.valid,refs.pending) as PromptBuild.Ready
        assertFalse(prompt.request.messages.any{"old-coffee-sentinel" in it.content});assertEquals(0L,repo.cursor("a"))
    }
    @Test fun changedPartialResultBlocksNewWritesButReviewedReceiptCanFinish()=runBlocking {
        val s=source();val b=MemoryBatchRepository(repo);val w=work(b,s);val first=op(s);val id=b.apply(w,first)
        repo.manualSave("a",id,"current-tea",MemoryCategory.PREFERENCE_BOUNDARY,false,true,MemoryAbout.USER)
        assertEquals(id,b.apply(w,first))
        rejected("BATCH_REVIEW_REQUIRED"){b.apply(w,op(s,"late","new:late","old-derived"))}
        b.keepCurrent("a",w.id,id,2);assertTrue(b.complete(w,setOf("one"),1,w.id))
        assertEquals("current-tea",repo.memories("a").single().text)
    }
    private suspend fun stageTools(input:String,texts:List<String>):Pair<ChatTurn,MemoryTools>{
        repo.saveMemoryRules(MemoryRules(immediateWrite=true));val t=ChatLedger(store).begin("a",input,"synthetic")
        val tools=MemoryTools(repo,t,input,repo.references("a",input,t.userMessageId)){true};var calls=0
        val config=ModelConfig("synthetic","Synthetic","custom","https://example.invalid/v1","fake",64000,2048)
        val request=TextChatRequest(listOf(WireMessage("user",input)))
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=if(calls++==0)TextChatResult.Success("",texts.mapIndexed{i,text->ChatToolCall("call-$i","agent_memory_update",JSONObject().put("op","add").put("category","PREFERENCE_BOUNDARY").put("about","USER").put("content",text).put("tags",JSONArray().put("tag-$i")).put("evidence",JSONArray().put("S1")).toString())})else TextChatResult.Success("synthetic reply")}
        assertTrue(tools.run(config,"synthetic-only",request,client) is TextChatResult.Success);return t to tools
    }
    @Test fun toolsAndLaterSummaryDoNotDuplicateTheSameSourceFact()=runBlocking {
        val (turn,tools)=stageTools("coffee",listOf("No coffee","No coffee"))
        assertTrue(ChatLedger(store).finishWithMemory(turn.id,turn.attempt,"reply",onCompleted={tools.commit(it)}))
        val m=repo.memories("a").single();assertEquals(1,m.version)
        val s=repo.sources("a").single();val b=MemoryBatchRepository(repo);val w=work(b,s)
        assertEquals(m.id,b.apply(w,op(s)));assertTrue(b.complete(w,setOf("one"),1,w.id));assertEquals(1,repo.history("a",m.id).size)
    }
    @Test fun failedFinalReplyTransactionRollsBackToolsAndCanRetryWithoutLoss()=runBlocking {
        val (turn,tools)=stageTools("coffee and tea",listOf("No coffee","Likes tea"));val ledger=ChatLedger(store)
        runCatching{ledger.finishWithMemory(turn.id,turn.attempt,"reply",onCompleted={tools.commit(it);throw IllegalStateException("synthetic rollback")})}
        assertTrue(repo.memories("a").isEmpty())
        assertTrue(ledger.finishWithMemory(turn.id,turn.attempt,"reply",onCompleted={tools.commit(it)}));assertEquals(2,repo.memories("a").size)
    }
    @Test fun failedSourceCorrectionLeavesOriginalAndHistoryConsistent()=runBlocking {
        val s=source();val b=MemoryBatchRepository(repo);val w=work(b,s);val id=b.apply(w,op(s));b.complete(w,setOf("one"),1,w.id)
        val changes=SourceChanges(repo);val preview=changes.preview("a",s.source.turnId,"user","corrected",false)
        repo.tx{it.execSQL("CREATE TEMP TRIGGER integrity_failure BEFORE UPDATE ON memory_current BEGIN SELECT RAISE(ABORT,'synthetic rollback'); END")}
        assertTrue(runCatching{changes.confirm(preview)}.isFailure);repo.tx{it.execSQL("DROP TRIGGER integrity_failure")}
        assertEquals(s,repo.sources("a").single());assertEquals(1,repo.history("a",id).size);assertTrue(repo.memories("a").single().valid)
    }
    @Test fun retryReceiptDoesNotResendOldDerivedBodyAsCurrentContext()=runBlocking {
        val s=source();val b=MemoryBatchRepository(repo);val w=work(b,s);val id=b.apply(w,op(s,text="old-derived-sentinel"));b.fail(w,"INTERRUPTED")
        repo.manualSave("a",id,"current-tea",MemoryCategory.PREFERENCE_BOUNDARY,false,true,MemoryAbout.USER)
        b.keepCurrent("a",w.id,id,2)
        val vault=ModelVault(context,"integrity-vault-${System.nanoTime()}");val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try {
            val config=ModelConfig("synthetic","Synthetic","custom","https://example.invalid/v1","fake",64000,2048)
            vault.save(config,"synthetic-only");vault.setPurpose(ModelPurpose.MEMORY,config.id);var called=false
            val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
                called=true;assertFalse(request.messages.any{"old-derived-sentinel" in it.content})
                val data=JSONObject(request.messages.last().content);val saved=data.getJSONArray("already_saved_operations")
                assertEquals(1,saved.length());assertEquals(setOf("id"),saved.getJSONObject(0).keys().asSequence().toSet())
                return TextChatResult.Success(JSONObject().put("batch_id",data.getString("batch_id")).put("character_id","a").put("operations",JSONArray()).put("operation_count",1).put("complete",true).put("end_marker",data.getString("batch_id")).toString())
            }}
            assertTrue(MemoryEngine(scope,repo,ModelAccess(vault),client,{}).ensureRoom("a",forceOne=true,manualRetry=true).success)
            assertTrue(called);assertEquals("current-tea",repo.memories("a").single().text)
        }finally{scope.cancel();vault.list().forEach{vault.delete(it.id)}}
    }
}
