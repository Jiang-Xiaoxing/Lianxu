package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Synthetic databases and model replies only. */
class RelationshipReviewAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store;private lateinit var repo:ProcessingRepository
    private lateinit var relations:RelationshipRepository;private lateinit var review:RelationshipReviewRepository
    private lateinit var name:String
    @Before fun setup()=runBlocking{
        name="relation-review-${System.nanoTime()}.db";store=Store(context,name);repo=ProcessingRepository(store)
        relations=RelationshipRepository(repo);review=RelationshipReviewRepository(repo)
        store.add(listOf(Friend("a","合成甲","成年测试角色"),Friend("b","合成乙","独立角色")),false)
        relations.setEnabled("a",true,null,true)
        assess("one",50);assess("two",99)
    }
    @After fun cleanup(){store.close();context.deleteDatabase(name)}
    private fun response(w:RelationshipWork,score:Int)=JSONObject().put("task_id",w.id).put("character_id",w.friendId).put("complete",true).put("dimensions",JSONObject().put("TRUST",JSONObject().put("score",score).put("statement","本批合成依据")))
    private suspend fun turn(){val t=ChatLedger(store).begin("a","合成原话","unused");check(ChatLedger(store).finish(t.id,1,"合成回复"))}
    private suspend fun assess(id:String,score:Int){turn();val w=relations.begin("a",id,"synthetic","test",true)!!;check(relations.complete(w,response(w,score),{true},ChatAttemptLease()))}
    private suspend fun candidate(score:Int=49):RelationshipCandidate{val p=review.prepare("a","synthetic");return review.preview(p,response(p.work,score)){true}}
    @Test fun rollbackAppendsAndPreservesCursorChatMemoryAndOtherRole()=runBlocking{
        repo.manualSave("a",null,"合成手工记忆",MemoryCategory.STATE_FACT,true,true,MemoryAbout.USER)
        val old=relations.state("a");val messages=store.messages();val memories=repo.memories("a");val history=relations.history("a").size
        val p=review.previewRollback("a",1);assertEquals(old,relations.state("a"))
        check(review.rollback(p));val next=relations.state("a")
        assertEquals(50,next.values.getValue(RelationshipDimension.TRUST).score);assertEquals(old.cursor,next.cursor)
        assertEquals(old.version+1,next.version);assertEquals(history+1,relations.history("a").size)
        assertEquals(messages,store.messages());assertEquals(memories,repo.memories("a"));assertTrue(relations.state("b").values.isEmpty());assertTrue(repo.sources("a",next.cursor).isEmpty())
    }
    @Test fun cancelledRollbackAndCrossRoleHistoryNeverWrite()=runBlocking{
        val before=relations.state("a");val p=review.previewRollback("a",1);val lease=ChatAttemptLease();lease.cancel()
        assertFalse(review.rollback(p,lease));assertEquals(before,relations.state("a"))
        assertTrue(runCatching{review.previewRollback("b",1)}.isFailure);assertTrue(relations.state("b").values.isEmpty())
    }
    @Test fun candidateIsReadOnlyAndAdoptionReplacesRatherThanAccumulates()=runBlocking{
        val before=relations.state("a");assertEquals(53,before.values.getValue(RelationshipDimension.TRUST).score)
        val c=candidate();assertEquals(before,relations.state("a"));assertEquals(50,c.plan.work.previous.values.getValue(RelationshipDimension.TRUST).score)
        assertEquals(1,c.plan.work.targets.size);assertEquals(49,c.after.values.getValue(RelationshipDimension.TRUST).score)
        check(review.adopt(c,{true}));assertEquals(49,relations.state("a").values.getValue(RelationshipDimension.TRUST).score)
        val again=candidate(0);assertEquals(50,again.plan.work.previous.values.getValue(RelationshipDimension.TRUST).score)
        assertEquals(47,again.after.values.getValue(RelationshipDimension.TRUST).score);check(review.adopt(again,{true}))
        assertEquals(before.cursor,relations.state("a").cursor)
    }
    @Test fun duplicateAdoptionAndCancelledAdoptionDoNotWrite()=runBlocking{
        val c=candidate();val before=relations.state("a");val lease=ChatAttemptLease();lease.cancel()
        assertFalse(review.adopt(c,{true},lease));assertEquals(before,relations.state("a"))
        check(review.adopt(c,{true}));val saved=relations.state("a")
        assertTrue(runCatching{review.adopt(c,{true})}.isFailure);assertEquals(saved,relations.state("a"))
    }
    @Test fun changedSourceRejectsCandidate()=runBlocking{
        val c=candidate();val s=c.plan.work.targets.single();val changes=SourceChanges(repo)
        changes.confirm(changes.preview("a",s.source.turnId,"user","更改合成原话",false))
        val before=relations.state("a");assertTrue(runCatching{review.adopt(c,{true})}.isFailure);assertEquals(before,relations.state("a"))
    }
    @Test fun changedConfigPolicyAndCharacterRejectCandidates()=runBlocking{
        val c=candidate();val before=relations.state("a")
        assertTrue(runCatching{review.adopt(c,{false})}.isFailure)
        repo.savePolicy("a",repo.policy("a").first)
        assertTrue(runCatching{review.adopt(c,{true})}.isFailure)
        val newer=candidate();repo.tx{it.execSQL("UPDATE friends SET prompt='修改合成角色' WHERE id='a'")}
        assertTrue(runCatching{review.adopt(newer,{true})}.isFailure);assertEquals(before,relations.state("a"))
    }
    @Test fun rollbackInvalidatesCandidateAndLateBackgroundResult()=runBlocking{
        val c=candidate();turn();val pending=relations.begin("a","late","synthetic","test",true)!!
        check(review.rollback(review.previewRollback("a",1)));val restored=relations.state("a")
        assertTrue(runCatching{review.adopt(c,{true})}.isFailure)
        assertTrue(runCatching{relations.complete(pending,response(pending,99),{true},ChatAttemptLease())}.isFailure)
        assertEquals(restored,relations.state("a"));assertEquals(1,repo.sources("a",restored.cursor).size)
    }
    @Test fun malformedReplyAndMissingSnapshotCannotChangeSavedState()=runBlocking{
        val before=relations.state("a");val p=review.prepare("a","synthetic")
        assertTrue(runCatching{review.preview(p,response(p.work,40).put("character_id","b")){true}}.isFailure)
        repo.tx{it.delete("relationship_jobs","friend_id=?",arrayOf("a"))}
        assertTrue(runCatching{review.prepare("a","synthetic")}.isFailure);assertEquals(before,relations.state("a"))
    }
    @Test fun failedAdoptionRollsBackHistoryAndCurrentStateTogether()=runBlocking{
        val c=candidate();val before=relations.state("a");val history=relations.history("a")
        repo.tx{it.execSQL("CREATE TRIGGER synthetic_fail BEFORE UPDATE ON relationship_jobs BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")}
        assertTrue(runCatching{review.adopt(c,{true})}.isFailure)
        assertEquals(before,relations.state("a"));assertEquals(history,relations.history("a"))
    }
    @Test fun modelRequestUsesOriginalBatchAndFailureOrCancellationIsReadOnly()=runBlocking{
        val namespace="review-${System.nanoTime()}";val vault=ModelVault(context,namespace)
        val config=ModelConfig("r","合成模型","custom","https://example.invalid/v1","synthetic",contextTokens=32000,outputTokens=2048)
        vault.save(config,"synthetic-only");vault.setPurpose(ModelPurpose.RELATIONSHIP,"r")
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var calls=0
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
            calls++;val j=JSONObject(request.messages.last().content)
            assertEquals(50,j.getJSONObject("previous").getJSONObject("values").getJSONObject("TRUST").getInt("score"))
            assertEquals(1,j.getJSONArray("pending_dialogue").length());entered.complete(Unit)
            withContext(NonCancellable){release.await()};return TextChatResult.Failure(ChatFailure.TIMEOUT)
        }}
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val engine=RelationshipEngine(scope,repo,ModelAccess(vault),client,{})
        try{val before=relations.state("a");val job=scope.async{engine.reassess("a")};entered.await();engine.cancel("a");release.complete(Unit);job.join()
            assertTrue(job.isCancelled);assertEquals(1,calls);assertEquals(before,relations.state("a"))
            assertTrue(runCatching{engine.reassess("a")}.isFailure);assertEquals(2,calls);assertEquals(before,relations.state("a"))
        }finally{scope.cancel();vault.delete("r")}
    }
}
