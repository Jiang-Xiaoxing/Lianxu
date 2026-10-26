package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Cross-scenario regressions. Original 0.11 reproducer is preserved under evidence-memory-012. */
class MemoryAudit012Test {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store;private lateinit var repo:ProcessingRepository;private lateinit var name:String
    @Before fun setup()=runBlocking{name="audit012-${System.nanoTime()}.db";store=Store(context,name);repo=ProcessingRepository(store);store.add(listOf(Friend("a","Alpha","Synthetic"),Friend("b","Beta","Synthetic")),false);val l=ChatLedger(store);val t=l.begin("a","I like the synthetic garden.","mock");l.finish(t.id,t.attempt,"Synthetic response");Unit}
    @After fun close(){store.close();context.deleteDatabase(name)}
    private fun op(s:SourceDialogue)=JSONObject().put("id","one").put("op","ADD").put("target","new:one").put("category","STATE_FACT").put("about","USER").put("tags",JSONArray()).put("text","Likes garden").put("core",true).put("evidence",JSONArray().put(JSONObject().put("turn",s.source.turnId).put("version",s.source.version).put("side","user").put("quote",s.user.text)))
    private suspend fun rejected(code:String,block:suspend()->Unit){try{block();fail("Expected $code")}catch(e:ProcessingRejected){assertEquals(code,e.code)}}
    private fun proposed(s:SourceDialogue)=StoredMemory("proposed","a",MemoryCategory.USER_PROFILE,"Old candidate fact",evidence=listOf(MemoryEvidence(s.source,"user",s.user.text,s.user.text,s.user.created,s.zone)))

    @Test fun partialLockReviewPersistsAndStillRequiresReceipt()=runBlocking{
        val s=repo.sources("a").single();var batches=MemoryBatchRepository(repo);var w=batches.prepare("a",listOf(s),"mock");batches.beginAttempt(w,"test");val operation=op(s);val id=batches.apply(w,operation)
        repo.setLock("a",id,true);batches.apply(w,operation)
        rejected("BATCH_REVIEW_REQUIRED"){batches.complete(w,setOf("one"),1,w.id)}
        rejected("STALE_TASK"){batches.keepCurrent("b",w.id,id,2)}
        batches.keepCurrent("a",w.id,id,2);assertEquals(0L,repo.cursor("a"));assertTrue(batches.reviews("a").single().acknowledged)
        rejected("FORMAT"){batches.complete(w,setOf("one"),1,"wrong")};assertEquals(0L,repo.cursor("a"))
        batches.fail(w,"INTERRUPTED");store.close();store=Store(context,name);repo=ProcessingRepository(store);batches=MemoryBatchRepository(repo)
        assertTrue(batches.reviews("a").single().acknowledged);w=batches.prepare("a",listOf(s),"mock");batches.beginAttempt(w,"restart");batches.apply(w,operation)
        assertTrue(batches.complete(w,setOf("one"),1,w.id));val m=repo.memories("a").single();assertTrue(m.locked);assertEquals(2,m.version);assertEquals(s.source.turnId,repo.cursor("a"))
    }
    @Test fun manualEditAfterAcknowledgementNeedsFreshDecisionAndCannotHideSourceChange()=runBlocking{
        val s=repo.sources("a").single();val b=MemoryBatchRepository(repo);val w=b.prepare("a",listOf(s),"mock");b.beginAttempt(w,"test");val id=b.apply(w,op(s))
        repo.manualSave("a",id,"First manual edit",MemoryCategory.STATE_FACT,true,true,MemoryAbout.USER);b.keepCurrent("a",w.id,id,2)
        repo.manualSave("a",id,"Second manual edit",MemoryCategory.STATE_FACT,true,true,MemoryAbout.USER)
        rejected("BATCH_REVIEW_REQUIRED"){b.complete(w,setOf("one"),1,w.id)};assertFalse(b.reviews("a").single().acknowledged)
        rejected("SOURCE_CHANGED"){b.keepCurrent("a",w.id,id,2)};b.keepCurrent("a",w.id,id,3)
        rejected("INCOMPLETE"){b.complete(w,emptySet(),0,w.id)}
        assertTrue(b.complete(w,setOf("one"),1,w.id));assertEquals("Second manual edit",repo.memories("a").single().text)
        assertEquals(1,repo.read{db->db.rawQuery("SELECT result_version FROM memory_operations WHERE batch_id=?",arrayOf(w.id)).use{it.moveToFirst();it.getInt(0)}})
        // A separate, still pending batch cannot be cleared by a prior review after a source edit.
        val l=ChatLedger(store);val t=l.begin("a","Second source","mock");l.finish(t.id,t.attempt,"Second reply");val s2=repo.sources("a").last();val w2=b.prepare("a",listOf(s2),"mock");b.beginAttempt(w2,"test");val id2=b.apply(w2,op(s2));repo.setLock("a",id2,true);b.keepCurrent("a",w2.id,id2,2)
        val changes=SourceChanges(repo);changes.confirm(changes.preview("a",s2.source.turnId,"user","changed",false))
        rejected("SOURCE_CHANGED"){b.complete(w2,setOf("one"),1,w2.id)};assertEquals(s.source.turnId,repo.cursor("a"))
    }
    @Test fun withdrawalInvalidatesCandidatesAndPreviewRaceAndAcceptanceRecheck()=runBlocking{
        val s=repo.sources("a").single();repo.tx{repo.candidate(it,"a",null,proposed(s),"Synthetic","old")}
        val changes=SourceChanges(repo);val first=changes.preview("a",s.source.turnId,"user","",true);assertEquals(1,first.candidates.size);assertTrue(repo.candidates("a").single().valid)
        repo.tx{repo.candidate(it,"a",null,proposed(s).copy(id="another"),"Synthetic","late")}
        rejected("SOURCE_CHANGED"){changes.confirm(first)};assertEquals(s.user.text,repo.sources("a").single().user.text)
        val fresh=changes.preview("a",s.source.turnId,"user","",true);assertEquals(2,fresh.candidates.size);changes.confirm(fresh)
        assertTrue(repo.candidates("a").all{!it.valid});assertTrue(repo.reference("a","garden",99).second.isEmpty())
        rejected("CANDIDATE_SOURCE_INVALID"){repo.resolveCandidate("a","old",true)};assertTrue(repo.memories("a").isEmpty())
        store.close();store=Store(context,name);repo=ProcessingRepository(store);assertFalse(repo.candidates("a").first().valid)
        assertEquals(s.user.text,repo.candidates("a").first().proposed.evidence.single().snapshot)
    }
    @Test fun acceptedCandidateKeepsEvidenceAndLegacyCandidateCannotResurrect()=runBlocking{
        val s=repo.sources("a").single();val m=repo.manualSave("a",null,"Protected original",MemoryCategory.USER_PROFILE,true,true)
        repo.tx{repo.candidate(it,"a",m.id,proposed(s),"Synthetic","valid")};repo.setLock("a",m.id,false)
        rejected("SOURCE_CHANGED"){repo.resolveCandidate("a","valid",true)}
        repo.resolveCandidate("a","valid",true,2);val accepted=repo.memories("a").single();assertTrue(accepted.manual);assertEquals(1,accepted.evidence.size)
        repo.tx{db->repo.candidate(db,"a",null,proposed(s),"Synthetic","legacy");val j=db.rawQuery("SELECT json FROM memory_candidates WHERE id='legacy'",null).use{it.moveToFirst();JSONObject(it.getString(0))};j.remove("sourceValid");j.remove("targetVersion");db.execSQL("UPDATE memory_candidates SET json=? WHERE id='legacy'",arrayOf(j.toString()))}
        val change=SourceChanges(repo);change.confirm(change.preview("a",s.source.turnId,"assistant","",true))
        assertFalse(repo.memories("a").single().valid);assertTrue(repo.memories("a").single().manual)
        assertTrue(repo.reference("a","garden",100).first.isEmpty());assertTrue(repo.reference("a","garden",100).second.isEmpty())
        rejected("CANDIDATE_SOURCE_INVALID"){repo.resolveCandidate("a","legacy",true)}
    }
    @Test fun pendingReviewMakesNoRequestThenReceiptAndHumanCoreChoiceRestoreChat()=runBlocking{
        val s=repo.sources("a").single();val batches=MemoryBatchRepository(repo);val work=batches.prepare("a",listOf(s),"mock");batches.beginAttempt(work,"test");val id=batches.apply(work,op(s));repo.setLock("a",id,true);batches.fail(work,"BATCH_REVIEW_REQUIRED")
        val vault=ModelVault(context,"audit-pipeline-${System.nanoTime()}");val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val chat=ModelConfig("c","mock","custom","https://example.invalid/v1","chat",contextTokens=4096,outputTokens=1024);vault.save(chat,"synthetic-key-only");vault.save(chat.copy(id="m",modelId="memory",contextTokens=32000),"synthetic-key-only");vault.setPurpose(ModelPurpose.MEMORY,"m")
        var calls=0
        try{
            val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls++;if(config.modelId=="chat")return TextChatResult.Success("Chat mock response");val j=JSONObject(request.messages.last().content);return TextChatResult.Success(JSONObject().put("batch_id",j.getString("batch_id")).put("character_id","a").put("operations",JSONArray()).put("operation_count",1).put("complete",true).put("end_marker",j.getString("batch_id")).toString())}}
            val access=ModelAccess(vault);val memory=MemoryEngine(scope,repo,access,client,{})
            assertEquals("BATCH_REVIEW_REQUIRED",memory.ensureRoom("a",forceOne=true,manualRetry=true).code);assertEquals(0,calls)
            batches.keepCurrent("a",work.id,id,2);assertTrue(memory.ensureRoom("a",forceOne=true,manualRetry=true).success);assertEquals(1,calls)
            val huge=repo.manualSave("a",null,"z".repeat(7000),MemoryCategory.PROMISE,true,true)
            val ledger=ChatLedger(store);val runner=ConversationRunner(scope,store,vault,client,repo,memory,RelationshipEngine(scope,repo,access,client,{}),{},{})
            withContext(Dispatchers.Main){assertTrue(runner.send("a","Hi",{}))}
            withTimeout(10000){while(ledger.all().size<2||ledger.all().last().state==TurnState.PENDING)delay(10)}
            assertEquals(ChatFailure.CORE_CAPACITY,ledger.all().last().failure);assertEquals(1,calls);assertEquals(huge,repo.memories("a").first{it.id==huge.id})
            repo.chooseCore("a",huge.id,huge.version,false);withContext(Dispatchers.Main){runner.retry(ledger.all().last())}
            withTimeout(10000){while(ledger.all().last().state!=TurnState.COMPLETED)delay(10)}
            assertEquals(2,calls);assertTrue(repo.memories("a").first{it.id==huge.id}.locked);assertEquals(huge.text,repo.memories("a").first{it.id==huge.id}.text)
        }finally{scope.cancel();vault.list().forEach{vault.delete(it.id)}}
    }
    @Test fun coreMaintenanceDemotesOnlyAutomaticAndSuppliesTargetToModel()=runBlocking{
        repo.savePolicy("a",ProcessingPolicy(coreGoal=100));val s=repo.sources("a").single();val proof=proposed(s).evidence
        repo.tx{db->repo.saveMemory(db,StoredMemory("auto","a",MemoryCategory.USER_PROFILE,"Automatic core ".repeat(30),core=true,evidence=proof));repo.saveMemory(db,StoredMemory("locked","a",MemoryCategory.USER_PROFILE,"Locked core ".repeat(30),core=true,locked=true,evidence=proof))}
        val manual=repo.manualSave("a",null,"Manual core ".repeat(50),MemoryCategory.PROMISE,true,true);val before=repo.memories("a").filter{it.id!="auto"}
        val status=repo.maintainCore("a");assertTrue(status.overGoal);assertFalse(repo.memories("a").first{it.id=="auto"}.core);assertEquals(before,repo.memories("a").filter{it.id!="auto"});assertEquals(2,repo.history("a","auto").size)
        val ns="core012-${System.nanoTime()}";val vault=ModelVault(context,ns);val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main);val c=ModelConfig("m","mock","custom","https://example.invalid/v1","memory",contextTokens=32000);vault.save(c,"synthetic-key-only");vault.setPurpose(ModelPurpose.MEMORY,"m")
        var requested=false
        try{val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{val j=JSONObject(request.messages.last().content);val core=j.getJSONObject("core_maintenance");assertEquals(100,core.getInt("goal_tokens"));assertTrue(core.getInt("protected_tokens")>100);assertTrue(core.getJSONArray("protected_ids").toString().contains(manual.id));requested=true;return TextChatResult.Success(JSONObject().put("batch_id",j.getString("batch_id")).put("character_id","a").put("operations",JSONArray()).put("operation_count",0).put("complete",true).put("end_marker",j.getString("batch_id")).toString())}}
            assertTrue(MemoryEngine(scope,repo,ModelAccess(vault),client,{}).ensureRoom("a",forceOne=true).success);assertTrue(requested);assertEquals(before,repo.memories("a").filter{it.id!="auto"})
            repo.chooseCore("a",manual.id,manual.version,false);val now=repo.memories("a").first{it.id==manual.id};assertTrue(now.manual&&now.locked);assertFalse(now.core);assertEquals(manual.text,now.text)
        }finally{scope.cancel();vault.delete("m")}
    }
}
