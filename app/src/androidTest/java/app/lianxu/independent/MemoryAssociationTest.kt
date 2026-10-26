package app.lianxu.independent

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** All databases, turns and credentials are synthetic and distinct from the app's main store. */
class MemoryAssociationTest {
    private val c get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store;private lateinit var repo:ProcessingRepository;private lateinit var name:String
    private lateinit var old:StoredMemory;private lateinit var source:SourceDialogue
    @Before fun setup()=runBlocking{
        name="associations-${System.nanoTime()}.db";store=Store(c,name);repo=ProcessingRepository(store)
        store.add(listOf(Friend("a","Synthetic A","Synthetic"),Friend("b","Synthetic B","Synthetic")),false)
        old=repo.manualSave("a",null,"garden walk",MemoryCategory.EXPERIENCE_MEMORY,false,true,MemoryAbout.BOTH)
        val ledger=ChatLedger(store);val t=ledger.begin("a","The garden walk changed because it rained.","mock");ledger.finish(t.id,t.attempt,"We will rearrange it.")
        source=repo.sources("a").single()
    }
    @After fun close(){store.close();c.deleteDatabase(name)}
    private fun op()=JSONObject().put("id","one").put("op","ADD").put("target","new:one").put("category","STATE_FACT").put("about","BOTH")
        .put("text","Rain changed plans").put("tags",JSONArray()).put("evidence",JSONArray().put(JSONObject().put("turn",source.source.turnId).put("version",source.source.version).put("side","user").put("quote",source.user.text)))
    private fun link(target:StoredMemory=old)=JSONObject().put("target",target.id).put("version",target.version).put("kind","explicit_cause")
        .put("reason","garden walk was rearranged because of rain").put("quote",source.user.text).put("target_quote",target.text)
    private suspend fun work():Pair<MemoryBatchRepository,MemoryWork>{val b=MemoryBatchRepository(repo);val w=b.prepare("a",listOf(source),"mock",associationIds=setOf(old.id));b.beginAttempt(w,"test");return b to w}
    private suspend fun graph()=repo.read{MemoryAssociations(repo).read(it,"a")}
    private suspend fun count(db:SQLiteDatabase,table:String)=db.rawQuery("SELECT COUNT(*) FROM $table",null).use{it.moveToFirst();it.getInt(0)}
    @Test fun receiptPublishesOnceAndRestartKeepsEvidenceAndRecall()=runBlocking{
        val(b,w)=work();val operation=op().put("links",JSONArray().put(link()));val id=b.apply(w,operation)
        assertTrue(graph().isEmpty());assertEquals(id,b.apply(w,JSONObject(operation.toString())));assertEquals(1,repo.history("a",id).size)
        assertTrue(b.complete(w,setOf("one"),1,w.id));assertEquals(1,graph().size);assertEquals(1,graph().single().sourceVersion)
        assertEquals(source.user.text,graph().single().quote);assertEquals(old.text,graph().single().targetQuote)
        assertTrue(repo.references("a","garden",99).valid.any{it.memoryId==id&&it.label.startsWith("关联")})
        store.close();store=Store(c,name);repo=ProcessingRepository(store);assertEquals(1,graph().size)
        assertEquals(2,repo.references("a","garden",100).valid.count{it.related});assertEquals(2,store.messages().count{it.friendId=="a"})
    }
    @Test fun failedIncompleteSummaryKeepsRawAndRetryDoesNotDuplicate()=runBlocking{
        val(b,w)=work();val operation=op().put("links",JSONArray().put(link()));val id=b.apply(w,operation);b.fail(w,"INCOMPLETE")
        assertTrue(graph().isEmpty());assertEquals(1,repo.recentSources("a").size);assertEquals(0L,repo.cursor("a"));assertEquals(2,store.messages().count{it.friendId=="a"})
        b.beginAttempt(w,"retry");assertEquals(id,b.apply(w,operation));assertTrue(b.complete(w,setOf("one"),1,w.id))
        assertEquals(1,graph().size);assertEquals(1,repo.history("a",id).size)
    }
    @Test fun malformedCrossRoleAndUnprovedLinksCannotBreakSummary()=runBlocking{
        val other=repo.manualSave("b",null,"garden walk",MemoryCategory.STATE_FACT,false,true,MemoryAbout.BOTH)
        val(b,w)=work();val bad=link().put("quote","not in the source dialogue")
        val id=b.apply(w,op().put("links",JSONArray().put(bad).put(link(other)).put(link().put("kind","guessed_cause"))))
        assertTrue(b.complete(w,setOf("one"),1,w.id));assertTrue(graph().isEmpty());assertTrue(repo.memories("a").any{it.id==id});assertTrue(repo.recentSources("a").isEmpty())
    }
    @Test fun optionalStorageFailureRollsBackOnlyLinks()=runBlocking{
        val(b,w)=work();b.apply(w,op().put("links",JSONArray().put(link())))
        repo.tx{it.execSQL("CREATE TRIGGER synthetic_reject_links BEFORE INSERT ON preferences WHEN NEW.key LIKE 'memory-associations-v1:%' BEGIN SELECT RAISE(ABORT,'synthetic optional write failure'); END")}
        assertTrue(b.complete(w,setOf("one"),1,w.id));assertTrue(graph().isEmpty());assertEquals(2,repo.memories("a").size);assertTrue(repo.recentSources("a").isEmpty())
    }
    @Test fun editingTargetInvalidatesVersionedLinksAndDoesNotRewriteHistory()=runBlocking{
        val(b,w)=work();val id=b.apply(w,op().put("links",JSONArray().put(link())));b.complete(w,setOf("one"),1,w.id)
        repo.manualSave("a",old.id,"garden walk rescheduled",old.category,false,true,MemoryAbout.BOTH)
        assertFalse(repo.references("a","garden",99).valid.any{it.memoryId==id});assertEquals(2,repo.history("a",old.id).size)
        assertEquals(1,graph().single().targetVersion)
    }
    @Test fun withdrawnSourceAndDisputedTargetAreExcluded()=runBlocking{
        val(b,w)=work();val id=b.apply(w,op().put("links",JSONArray().put(link())));b.complete(w,setOf("one"),1,w.id)
        val changes=SourceChanges(repo);changes.confirm(changes.preview("a",source.source.turnId,"user","",true))
        assertFalse(repo.references("a","garden",99).valid.any{it.memoryId==id});assertTrue(repo.references("b","garden",99).valid.isEmpty())
        repo.tx{repo.candidate(it,"a",old.id,old.copy(version=2),"Pending synthetic confirmation","synthetic-pending")}
        assertFalse(repo.references("a","garden",100).valid.any{it.memoryId==old.id})
    }
    @Test fun changedTargetDuringSummarySkipsAssociationWithoutLosingSummary()=runBlocking{
        val(b,w)=work();b.apply(w,op().put("links",JSONArray().put(link())))
        repo.manualSave("a",old.id,"garden walk moved",old.category,false,true,MemoryAbout.BOTH)
        assertTrue(b.complete(w,setOf("one"),1,w.id));assertTrue(graph().isEmpty());assertEquals(2,repo.memories("a").size)
    }
    private suspend fun twoHopChain():Pair<String,String>{
        val(b,w)=work();val bridge=b.apply(w,op().put("links",JSONArray().put(link())));b.complete(w,setOf("one"),1,w.id)
        val ledger=ChatLedger(store);val t=ledger.begin("a","The afternoon was rearranged because the garden walk changed.","mock");ledger.finish(t.id,t.attempt,"We will keep the plan updated.")
        source=repo.recentSources("a").single()
        val target=repo.memories("a").first{it.id==bridge}
        val next=b.prepare("a",listOf(source),"mock",associationIds=setOf(bridge));b.beginAttempt(next,"test")
        val id=b.apply(next,op().put("text","Afternoon rearranged").put("links",JSONArray().put(link(target))))
        assertTrue(b.complete(next,setOf("one"),1,next.id));return bridge to id
    }
    @Test fun persistedTwoHopEvidenceWorksAndWithdrawnBridgeCannotBeTraversed()=runBlocking{
        val first=source;val(bridge,deep)=twoHopChain()
        assertEquals(2,graph().size);assertTrue(repo.references("a","garden",99).valid.any{it.memoryId==deep})
        assertTrue(repo.references("b","garden",99).valid.isEmpty())
        val changes=SourceChanges(repo);changes.confirm(changes.preview("a",first.source.turnId,"user","",true))
        val refs=repo.references("a","garden",100).valid;assertFalse(refs.any{it.memoryId==bridge||it.memoryId==deep})
    }
    @Test fun editedBridgeVersionStopsSecondHop()=runBlocking{
        val(bridge,deep)=twoHopChain();val m=repo.memories("a").first{it.id==bridge}
        repo.manualSave("a",bridge,"Updated plans",m.category,false,true,MemoryAbout.BOTH)
        assertFalse(repo.references("a","garden",100).valid.any{it.memoryId==deep})
    }
    @Test fun secondEdgeMustPassOriginalEvidenceCheck()=runBlocking{
        val(_,deep)=twoHopChain()
        repo.tx{db->val graph=MemoryAssociations(repo);val a=JSONArray(graph.raw(db,"a"));for(i in 0 until a.length()){val e=a.getJSONObject(i);if(e.getString("source")==deep)e.put("quote","not in the actual source")}
            db.execSQL("UPDATE preferences SET value=? WHERE key=?",arrayOf(a.toString(),MemoryAssociations.key("a")))}
        assertFalse(repo.references("a","garden",100).valid.any{it.memoryId==deep})
    }
    @Test fun oneSummaryRequestHasBoundedScoredCandidatesAndNoExtraModelCall()=runBlocking{
        repeat(130){repo.manualSave("a",null,"garden "+"filler ".repeat(10),MemoryCategory.STATE_FACT,false,true,MemoryAbout.BOTH)}
        val vault=ModelVault(c,"association-test-${System.nanoTime()}");val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        try{
            val config=ModelConfig("memory","Synthetic","custom","https://example.invalid/v1","memory",contextTokens=64000,outputTokens=2048)
            vault.save(config,"synthetic-key-only");vault.setPurpose(ModelPurpose.MEMORY,config.id);var calls=0
            val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
                calls++;val d=JSONObject(request.messages.last().content);val candidates=d.getJSONArray("association_candidates")
                assertTrue(candidates.length() in 1..12);assertTrue(d.getJSONArray("existing_memories").length()<=32)
                assertTrue((0 until candidates.length()).any{candidates.getJSONObject(it).getString("id")==old.id})
                val operation=op().put("links",JSONArray().put(link()))
                return TextChatResult.Success(JSONObject().put("batch_id",d.getString("batch_id")).put("character_id","a").put("operations",JSONArray().put(operation)).put("operation_count",1).put("complete",true).put("end_marker",d.getString("batch_id")).toString())
            }}
            assertTrue(MemoryEngine(scope,repo,ModelAccess(vault),client,{}).ensureRoom("a",forceOne=true).success)
            assertEquals(1,calls);assertEquals(1,graph().size)
        }finally{scope.cancel();vault.list().forEach{vault.delete(it.id)}}
    }
}

