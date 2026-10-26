package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

/** Run only on the dedicated synthetic emulator, then kill/relaunch externally to verify persistence. */
class ProcessingRestartSeedTest {
    @Test fun seedDurableSyntheticProcessingStateWithoutNetwork()=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val store=Store(context,"lianxu.db");val repo=ProcessingRepository(store)
        val id="synthetic-restart-011";store.add(listOf(Friend(id,"合成·重启验收","专用模拟器数据，非真实用户。")),false)
        if(repo.sources(id).isEmpty()){
            val ledger=ChatLedger(store);val turn=ledger.begin(id,"合成重启原话","no-network");assertTrue(ledger.finish(turn.id,turn.attempt,"合成保存回复"))
            val memory=repo.manualSave(id,null,"手动确认的重启记忆",MemoryCategory.PROMISE,true,true)
            repo.manualSave(id,memory.id,"合成第二版本",MemoryCategory.PROMISE,true,true);repo.restore(id,memory.id,1)
            val relation=RelationshipRepository(repo);relation.setEnabled(id,true,"BACKFILL",true);val work=relation.begin(id,"restart-seed","synthetic-stamp","seed",true)!!
            relation.complete(work,JSONObject().put("task_id",work.id).put("character_id",id).put("dimensions",JSONObject().put("TRUST",JSONObject().put("score",51).put("statement","这是合成持久化记录。"))).put("complete",true),{true},ChatAttemptLease())
            relation.setEnabled(id,false,null,false)
            val batches=MemoryBatchRepository(repo);val batch=batches.prepare(id,repo.sources(id),"synthetic-stamp");batches.beginAttempt(batch,"seed");assertTrue(batches.complete(batch,emptySet(),0,batch.id))
        }
        assertEquals(3,repo.memories(id).single().version);assertEquals(51,RelationshipRepository(repo).state(id).values.getValue(RelationshipDimension.TRUST).score)
        assertTrue(repo.recentSources(id).isEmpty());assertFalse(repo.policy(id).first.relationshipEnabled);store.close()
    }
}
