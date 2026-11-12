package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** 预演（试写、整份校验）只在 SAVEPOINT 里跑：退出后一行都不留，也不会立胶囊；真正提交才立。 */
class MemoryPillDryRunAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String;private lateinit var store:Store;private lateinit var repo:ProcessingRepository
    private lateinit var batches:MemoryBatchRepository;private lateinit var work:MemoryWork;private lateinit var op:JSONObject
    private val original="明天还要上班，得加班"

    @Before fun prepare():Unit=runBlocking{
        name="pill-dry-${System.nanoTime()}.db";store=Store(context,name);repo=ProcessingRepository(store)
        batches=MemoryBatchRepository(repo)
        store.add(listOf(Friend("a","A","Synthetic")),false)
        val ledger=ChatLedger(store);val turn=ledger.begin("a",original,"synthetic");check(ledger.finish(turn.id,1,"那就别熬太晚"))
        val source=repo.sources("a").last()
        work=batches.prepare("a",listOf(source),"synthetic");batches.beginAttempt(work,"test")
        val raw=JSONObject().put("id","one").put("op","ADD").put("target","N1")
            .put("category","STATE_FACT").put("about","USER").put("text",original)
            .put("evidence",JSONArray().put("S1"))
        op=MemoryNumberedReferences(work.targets,work.existingVersions.keys).normalize(raw)
        Unit
    }
    @After fun close():Unit=runBlocking{store.close();context.deleteDatabase(name);Unit}

    private fun db()=store.writableDatabase
    private fun pills()=ChatMemoryPills.read(db(),"a")
    private fun operationRows()=db().rawQuery("SELECT COUNT(*) FROM memory_operations WHERE batch_id=?",arrayOf(work.id)).use{it.moveToFirst();it.getInt(0)}
    private fun state()=db().rawQuery("SELECT state FROM memory_batches WHERE id=?",arrayOf(work.id)).use{it.moveToFirst();it.getString(0)}

    @Test fun aCheckedResponseLeavesNoPillNoMemoryAndNoOperations()=runBlocking{
        val issues=batches.check(work,listOf(op),listOf(op),1,work.id){true}
        assertTrue("试写应全部通过：$issues",issues.isEmpty())
        assertTrue("校验不能立胶囊",pills().isEmpty())
        assertTrue("校验不能写入记忆",repo.memories("a").isEmpty())
        assertEquals("校验的操作必须回滚干净",0,operationRows())
        assertEquals("RUNNING",state())
    }

    @Test fun aSemanticPreviewRollsBackAndOnlyARealCommitAddsOnePill()=runBlocking{
        val snapshot=batches.previewSemantic(work,listOf(op)){true}
        assertTrue(snapshot.base.isEmpty())
        assertEquals(1,snapshot.fresh.size)
        assertTrue("预演不能立胶囊",pills().isEmpty())
        assertTrue("预演不能写入记忆",repo.memories("a").isEmpty())
        assertEquals(0,operationRows())
        assertEquals("预演后批次必须还停在 RUNNING","RUNNING",state())

        val lease=ChatAttemptLease()
        batches.applySemantic(work,listOf(op),listOf(op),snapshot,1,work.id,{true},lease)
        assertEquals(1,pills().size)
        assertEquals("已整理 1 条记忆",pills().single().text)
        assertEquals("COMMITTED",state())
        assertEquals(1,repo.memories("a").size)
    }
}
