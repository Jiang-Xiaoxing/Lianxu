package app.lianxu.independent
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
/** Dedicated emulator only: leave new-format synthetic records for an external real-process restart. */
class MemoryV2RestartSeedTest {
    @Test fun seedV2WithoutTouchingLegacyOrCallingNetwork()=runBlocking{
        val c=InstrumentationRegistry.getInstrumentation().targetContext;val store=Store(c,"lianxu.db");val repo=ProcessingRepository(store);val id="synthetic-memory-v2-restart"
        store.add(listOf(Friend(id,"Synthetic v2 restart","Dedicated emulator data")),false)
        if(repo.memories(id).isEmpty()){
            for((i,category) in MemoryCategory.entries.filter{it.modern}.take(3).withIndex())repo.manualSave(id,null,"New-format synthetic $category",category,i==0,true,MemoryAbout.entries[i],listOf("restart","custom"))
            val l=ChatLedger(store);val t=l.begin(id,"A synthetic plan on 2000-01-01.","no-network");l.finish(t.id,t.attempt,"Synthetic saved reply")
            val s=repo.sources(id).single();val b=MemoryBatchRepository(repo);val w=b.prepare(id,listOf(s),"synthetic");b.beginAttempt(w,"seed")
            b.apply(w,JSONObject().put("id","one").put("op","ADD").put("target","new:one").put("category","COMMITMENT_OPEN").put("about","BOTH").put("tags",JSONArray().put("restart")).put("text","Synthetic unfinished plan").put("due_expression","2000-01-01").put("evidence",JSONArray().put(JSONObject().put("turn",s.source.turnId).put("version",s.source.version).put("side","user").put("quote",s.user.text))))
            assertTrue(b.complete(w,setOf("one"),1,w.id))
        }
        val rows=repo.memories(id);assertEquals(4,rows.size);assertEquals(4,rows.map{it.category}.distinct().size);assertTrue(rows.all{it.conversationId==id});assertEquals(MemoryLife.ACTIVE,rows.single{it.category==MemoryCategory.COMMITMENT_OPEN}.life);store.close()
    }
}
