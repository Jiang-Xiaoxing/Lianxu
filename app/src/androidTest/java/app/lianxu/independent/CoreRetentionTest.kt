package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Production SQLite maintenance with synthetic conversations; no model or network. */
class CoreRetentionTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store
    private lateinit var repo:ProcessingRepository
    private lateinit var name:String
    private lateinit var source:SourceDialogue
    @Before fun setup()=runBlocking {
        name="core-retention-${System.nanoTime()}.db";store=Store(context,name);repo=ProcessingRepository(store)
        store.add(listOf(Friend("a","Synthetic","Synthetic")),false)
        val ledger=ChatLedger(store);val turn=ledger.begin("a","合成原话：边界和约定持续有效。","mock")
        assertTrue(ledger.finish(turn.id,turn.attempt,"合成回复，不连接模型。"))
        source=repo.sources("a").single();repo.savePolicy("a",ProcessingPolicy(coreGoal=100))
    }
    @After fun close(){store.close();context.deleteDatabase(name)}
    private fun entry(id:String,category:MemoryCategory,at:Long=1L)=StoredMemory(id,"a",category,"$id "+"合成内容".repeat(40),core=true,
        evidence=listOf(MemoryEvidence(source.source,"user",source.user.text,source.user.text,at,source.zone)),about=if(category.modern)MemoryAbout.USER else null)
    private suspend fun save(vararg entries:StoredMemory)=repo.tx{db->entries.forEach{repo.saveMemory(db,it)}}

    @Test fun oldBoundaryAndUnfinishedPromiseSurviveNewEpisodeAndPastDueDate()=runBlocking {
        val boundary=entry("boundary",MemoryCategory.PREFERENCE_BOUNDARY)
        val promise=entry("promise",MemoryCategory.COMMITMENT_OPEN).copy(dueDate="2000-01-01")
        val episode=entry("episode",MemoryCategory.EXPERIENCE_MEMORY,999999L)
        save(boundary,promise,episode);val result=repo.maintainCore("a")
        assertFalse(result.overGoal);assertTrue(result.protectedIds.isEmpty())
        val current=repo.memories("a").associateBy{it.id}
        assertEquals(boundary,current["boundary"]);assertEquals(promise,current["promise"])
        assertEquals(episode,current["episode"])
        val now=AppServices.companionClock()
        repo.tx{MemoryForgetting.put(it,episode,MemoryRecallState(now,0,120.0,80.0))}
        assertTrue(repo.references("a","episode",source.user.id).valid.any{it.memoryId=="episode"})
        repo.tx{MemoryForgetting.put(it,episode,MemoryRecallState(now,0,120.0,10.0))}
        assertFalse(repo.references("a","episode",source.user.id).valid.any{it.memoryId=="episode"})
        assertEquals(listOf(1),repo.history("a","episode").map{it.version})
    }
    @Test fun expiredAndDisputedCoreDoNotPushUsableEpisodesOut()=runBlocking {
        repo.savePolicy("a",ProcessingPolicy(coreGoal=1000))
        val kept=entry("keep",MemoryCategory.EXPERIENCE_MEMORY).copy(text="有效的普通经历")
        val expired=entry("expired",MemoryCategory.EXPERIENCE_MEMORY).copy(text="过期".repeat(1000),expiresAt=1L)
        val disputed=entry("disputed",MemoryCategory.STATE_FACT).copy(text="待确认".repeat(1000))
        save(kept,expired,disputed)
        repo.tx{db->repo.candidate(db,"a",disputed.id,disputed.copy(version=2),"合成冲突","conflict")}
        val before=repo.memories("a");val result=repo.maintainCore("a")
        assertFalse(result.overGoal);assertTrue(result.entries.isEmpty())
        assertEquals(before,repo.memories("a"))
    }
    @Test fun invalidMissingAndEndedSourcesDoNotCountAsCurrentCore()=runBlocking {
        save(entry("invalid",MemoryCategory.STATE_FACT).copy(valid=false),
            entry("missing",MemoryCategory.STATE_FACT).copy(evidence=emptyList()),
            entry("ended",MemoryCategory.COMMITMENT_OPEN).copy(life=MemoryLife.COMPLETED))
        val before=repo.memories("a");assertEquals(0,repo.maintainCore("a").used);assertEquals(before,repo.memories("a"))
    }
    @Test fun manualLockedAndPendingEpisodesRemainProtected()=runBlocking {
        val manual=entry("manual",MemoryCategory.EXPERIENCE_MEMORY).copy(manual=true)
        val locked=entry("locked",MemoryCategory.EXPERIENCE_MEMORY).copy(locked=true)
        save(manual,locked)
        val batches=MemoryBatchRepository(repo);val work=batches.prepare("a",listOf(source),"mock");batches.beginAttempt(work,"synthetic")
        val op=JSONObject().put("id","episode").put("op","ADD").put("target","new:episode").put("category","EXPERIENCE_MEMORY").put("about","USER")
            .put("text","进行中批次的合成经历").put("core",true).put("evidence",JSONArray().put(JSONObject().put("turn",source.source.turnId).put("version",source.source.version).put("side","user").put("quote",source.user.text)))
        val pending=batches.apply(work,op);val before=repo.memories("a")
        val result=repo.maintainCore("a");assertEquals(setOf("manual"),result.protectedIds)
        assertEquals(before,repo.memories("a"))
    }
    @Test fun stableAndUnclassifiedLegacyCoreAreNotReinterpretedForCapacity()=runBlocking {
        val fact=entry("fact",MemoryCategory.STATE_FACT)
        val legacy=entry("legacy",MemoryCategory.USER_PROFILE)
        save(fact,legacy);val before=repo.memories("a")
        val result=repo.maintainCore("a");assertFalse(result.overGoal);assertEquals(result.used,result.protected)
        assertEquals(before,repo.memories("a"));assertTrue(result.protectedIds.isEmpty())
    }
}
