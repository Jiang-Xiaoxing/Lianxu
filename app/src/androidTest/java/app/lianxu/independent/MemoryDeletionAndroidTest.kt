package app.lianxu.independent
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
class MemoryDeletionAndroidTest {
 @Test fun deletionPersistsAndRejectsDuringSummary():Unit=runBlocking {
  Store(InstrumentationRegistry.getInstrumentation().targetContext,"deletion-${System.nanoTime()}.db").use { s ->
   s.addConfigured(Friend("r","合成角色","成年虚构"),RolePrompts(characterDefinition="成年合成测试角色"))
   val repo=ProcessingRepository(s)
   val m=repo.manualSave("r",null,"喜欢茶",MemoryCategory.STATE_FACT,true,false,MemoryAbout.USER)
   val ticket=s.memorySummary.begin("r",ChatAttemptLease(),10000){}!!
   assertEquals("MEMORY_SUMMARIZING",(runCatching{repo.deleteMemory("r",m.id)}.exceptionOrNull() as ProcessingRejected).code)
   s.memorySummary.finish(ticket)
   repo.tx{db->
    db.execSQL("INSERT OR REPLACE INTO preferences(key,value) VALUES(?,?)",arrayOf("memory-vectors-v1:r","{\"${m.id}\":{},\"other\":{}}"))
    db.execSQL("INSERT OR REPLACE INTO preferences(key,value) VALUES(?,?)",arrayOf(MemoryAssociations.key("r"),"[{\"source\":\"${m.id}\",\"target\":\"other\"}]"))
   }
   repo.deleteMemory("r",m.id)
   repo.read{db->
    assertEquals("[]",MemoryAssociations(repo).raw(db,"r"))
    db.rawQuery("SELECT value FROM preferences WHERE key='memory-vectors-v1:r'",null).use{c->assertTrue(c.moveToFirst());assertFalse(org.json.JSONObject(c.getString(0)).has(m.id))}
    assertTrue(MemoryForgetting.core(repo,db,"r",repo.memories(db,"r"),emptyMap()).isEmpty())
    db.rawQuery("SELECT json FROM memory_current WHERE id=?",arrayOf(m.id)).use{c->assertTrue(c.moveToFirst());assertNotNull(StoredMemory.from(org.json.JSONObject(c.getString(0))).deletedAt)}
   }
   // 删除之后又在新聊天里说了同一件事：允许作为新条目重新记下（新编号，墓碑不复活）。
   suspend fun retry(r:ProcessingRepository,role:String,deletedId:String){
    val ledger=ChatLedger(r.store);val turn=ledger.begin(role,"喜欢茶","synthetic");check(ledger.finish(turn.id,turn.attempt,"记住了"))
    val src=r.sources(role).last()
    val batches=MemoryBatchRepository(r);val work=batches.prepare(role,r.sources(role),"synthetic");batches.beginAttempt(work,"test")
    batches.apply(work,org.json.JSONObject().put("id","again").put("op","ADD").put("target","new:again").put("text",m.text).put("category","STATE_FACT").put("about","USER")
     .put("evidence",org.json.JSONArray().put(org.json.JSONObject().put("turn",src.source.turnId).put("version",src.source.version).put("side","user").put("quote",src.user.text))))
    assertTrue(batches.complete(work,setOf("again"),1,work.id))
    val again=r.memories(role).single();assertNotEquals(deletedId,again.id);assertEquals(m.text,again.text)
    r.read{db->assertTrue(r.memory(db,role,deletedId)!!.userDeleted)}
   }
   val context=InstrumentationRegistry.getInstrumentation().targetContext
   val export=BackupRepository(s,ModelVault(context,"delete-source-${System.nanoTime()}",s)).export("r")
   val bytes=java.io.ByteArrayOutputStream().also{BackupArchive.write(export.root,export.files,it)}.toByteArray()
   retry(repo,"r",m.id)
   Store(context,"delete-restore-${System.nanoTime()}.db").use { target ->
    val backup=BackupRepository(target,ModelVault(context,"delete-target-${System.nanoTime()}",target))
    val role=backup.commit(backup.preview(BackupArchive.read(bytes.inputStream()),"ADD")).single()
    val restored=ProcessingRepository(target)
    assertTrue(restored.memories(role).isEmpty())
    var tomb=""
    restored.read{db->db.rawQuery("SELECT json,valid FROM memory_current WHERE friend_id=?",arrayOf(role)).use{c->assertTrue(c.moveToFirst());assertEquals(0,c.getInt(1));val t=StoredMemory.from(org.json.JSONObject(c.getString(0)));tomb=t.id;assertNotNull(t.deletedAt);assertEquals(ChatMemoryPeek.Deleted,memoryPeekOf(t))}}
    restored.read{db->assertNotNull(restored.deletedMatch(db,role,"new-id",m.text,emptyList()))}
    retry(restored,role,tomb)
   }
   assertTrue(repo.memories("r").none{it.id==m.id});assertEquals(1,repo.memories("r").size)
   repo.read { db ->
    db.rawQuery("SELECT valid,json FROM memory_current WHERE id=?",arrayOf(m.id)).use { c ->
     assertTrue(c.moveToFirst());assertEquals(0,c.getInt(0))
     val saved=StoredMemory.from(org.json.JSONObject(c.getString(1)))
     assertEquals(ChatMemoryPeek.Deleted,memoryPeekOf(saved))
     assertTrue(MemoryRecall.select("r","喜欢茶",listOf(saved),emptyList(),15).isEmpty())
    }
    db.rawQuery("SELECT COUNT(*) FROM memory_recall_state WHERE memory_id=?",arrayOf(m.id)).use{c->c.moveToFirst();assertEquals(0,c.getInt(0))}
    assertNotNull(repo.deletedMatch(db,"r","new-id",m.text,emptyList()))
   }
  }
 }
}
