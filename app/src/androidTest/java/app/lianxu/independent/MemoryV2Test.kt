package app.lianxu.independent

import android.content.ContentValues
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

class MemoryV2Test {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store;private lateinit var repo:ProcessingRepository;private lateinit var name:String
    @Before fun setup()=runBlocking{name="memory-v2-${System.nanoTime()}.db";store=Store(context,name);repo=ProcessingRepository(store);store.add(listOf(Friend("a","A","Synthetic"),Friend("b","B","Synthetic")),false);val l=ChatLedger(store);val t=l.begin("a","2000-01-01 was the plan. I like the garden and tea.","mock");l.finish(t.id,1,"We can discuss it.");Unit}
    @After fun close(){store.close();context.deleteDatabase(name)}
    private fun op(s:SourceDialogue)=JSONObject().put("id","one").put("op","ADD").put("target","new:one").put("category","COMMITMENT_OPEN").put("about","BOTH").put("tags",JSONArray().put("garden")).put("text","Garden plan").put("evidence",JSONArray().put(JSONObject().put("turn",s.source.turnId).put("version",s.source.version).put("side","user").put("quote",s.user.text)))
    @Test fun legacyRowsStayByteIdenticalAndNewFacetsRoundTrip()=runBlocking{
        val old=repo.manualSave("a",null,"Legacy promise",MemoryCategory.PROMISE,true,true);val raw=repo.read{db->db.rawQuery("SELECT json FROM memory_current WHERE id=?",arrayOf(old.id)).use{it.moveToFirst();it.getString(0)}}
        for(c in MemoryCategory.entries.filter{it.modern})for(a in MemoryAbout.entries){val m=repo.manualSave("a",null,"$c-$a",c,false,true,a,listOf("Tag","tag"),"2000-01-01");assertEquals(a,m.about);assertEquals(MemoryLife.ACTIVE,m.life);assertEquals(listOf("Tag"),repo.memories("a").first{it.id==m.id}.tags)}
        store.close();store=Store(context,name);repo=ProcessingRepository(store)
        assertEquals(raw,repo.read{db->db.rawQuery("SELECT json FROM memory_current WHERE id=?",arrayOf(old.id)).use{it.moveToFirst();it.getString(0)}});assertEquals(13,repo.memories("a").size);assertNull(repo.memories("a").first{it.id==old.id}.about)
    }
    @Test fun tagsRecallAcrossCategoriesAndBothNeverSharesAcrossRoles()=runBlocking{
        repo.manualSave("a",null,"First content",MemoryCategory.STATE_FACT,false,true,MemoryAbout.BOTH,listOf("telescope"));repo.manualSave("a",null,"Second content",MemoryCategory.PREFERENCE_BOUNDARY,false,true,MemoryAbout.CHARACTER,listOf("telescope"));repo.manualSave("b",null,"OTHER ROLE SECRET",MemoryCategory.STATE_FACT,true,true,MemoryAbout.BOTH,listOf("telescope"))
        val refs=repo.references("a","telescope",99).valid;assertEquals(2,refs.size);assertTrue(refs.all{"OTHER ROLE" !in it.text});assertTrue(refs.any{"关于双方" in it.text});assertTrue(refs.any{"关于角色" in it.text})
    }
    @Test fun structuredOperationsKeepSubjectTagsHistoryAndExplicitLifecycle()=runBlocking{
        val s=repo.sources("a").single();val b=MemoryBatchRepository(repo);val w=b.prepare("a",listOf(s),"mock");b.beginAttempt(w,"test");val add=op(s).put("due_expression","2000-01-01");val id=b.apply(w,add);assertEquals("2000-01-01",repo.memories("a").single().dueDate);assertEquals(MemoryLife.ACTIVE,repo.memories("a").single().life)
        val update=op(s).put("id","two").put("op","UPDATE").put("target","new:one").put("text","Changed current fact").put("category","STATE_FACT").put("tags",JSONArray().put("tea"));b.apply(w,update)
        val complete=JSONObject(update.toString()).put("id","three").put("op","COMPLETE");b.apply(w,complete);assertTrue(b.complete(w,setOf("one","two","three"),3,w.id))
        val m=repo.memories("a").single();assertEquals(MemoryLife.COMPLETED,m.life);assertEquals(MemoryAbout.BOTH,m.about);assertEquals(listOf("tea"),m.tags);assertEquals(3,repo.history("a",id).size);assertEquals("Garden plan",repo.history("a",id).last().text)
    }
    @Test fun newProtocolRejectsLegacyAndMissingSubjectWithoutRetiring()=runBlocking{
        val s=repo.sources("a").single();val b=MemoryBatchRepository(repo);val w=b.prepare("a",listOf(s),"mock");b.beginAttempt(w,"test")
        for(bad in listOf(op(s).put("category","USER_PROFILE"),op(s).apply{remove("about")},op(s).put("due_date","2000-01-01"))){try{b.apply(w,bad);fail()}catch(_:Exception){}}
        assertTrue(repo.memories("a").isEmpty());assertEquals(0L,repo.cursor("a"))
        val old=repo.manualSave("a",null,"Old fact",MemoryCategory.USER_PROFILE,false,true);try{b.apply(w,op(s).put("op","UPDATE").put("target",old.id));fail()}catch(_:ProcessingRejected){};assertEquals("Old fact",repo.memories("a").single().text)
    }
    @Test fun oldAcceptedPartialOperationCanFinishWithoutRewritingLegacy()=runBlocking{
        val s=repo.sources("a").single();val old=repo.manualSave("a",null,"Already saved in 0.12",MemoryCategory.USER_PROFILE,false,true);val b=MemoryBatchRepository(repo);val w=b.prepare("a",listOf(s),"mock");b.beginAttempt(w,"test");val operation=op(s).put("category","USER_PROFILE").apply{remove("about");remove("tags")}
        repo.tx{db->db.insertOrThrow("memory_operations",null,ContentValues().apply{put("batch_id",w.id);put("op_key","one");put("json",canonicalJson(operation));put("digest",stableDigest(canonicalJson(operation)));put("result_id",old.id);put("result_version",old.version)})}
        assertEquals(old.id,b.apply(w,operation));assertTrue(b.complete(w,setOf("one"),1,w.id));assertEquals(old,repo.memories("a").single())
    }
}
