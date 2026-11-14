package app.lianxu.independent

import android.content.ContentValues
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/** Synthetic databases only; verifies the same operation/history tables used by real commits. */
class MemoryPillDetailsAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String
    private lateinit var store:Store
    private val db get()=store.writableDatabase
    private val repo get()=ProcessingRepository(store)
    @Before fun prepare()=runBlocking {
        name="pill-details-${System.nanoTime()}.db";store=Store(context,name)
        store.add(listOf(Friend("a","甲","合成角色"),Friend("b","乙","合成角色")),false)
        db.insertOrThrow("messages",null,ContentValues().apply{put("friend_id","a");put("body","合成原话");put("mine",1);put("created",1)})
        Unit
    }
    @After fun close(){store.close();context.deleteDatabase(name)}
    private fun batch(id:String="batch",friend:String="a",state:String="COMMITTED"){
        db.insertOrThrow("memory_batches",null,ContentValues().apply{put("id",id);put("friend_id",friend);put("json","{}");put("state",state)})
    }
    private fun memory(id:String,text:String,version:Int=1,friend:String="a") {
        runBlocking{repo.tx{repo.saveMemory(it,StoredMemory(id,friend,MemoryCategory.STATE_FACT,text,version=version,about=MemoryAbout.USER))}}
    }
    private fun op(key:String,id:String,version:Int,kind:String="ADD",saved:Boolean=true,batch:String="batch"){
        db.insertOrThrow("memory_operations",null,ContentValues().apply{
            put("batch_id",batch);put("op_key",key);put("json",JSONObject().put("id",key).put("op",kind).toString());put("digest","synthetic-$key")
            put("result_id",id);put("result_version",version);put("saved",if(saved)1 else 0)
        })
    }
    private fun pill(id:String="batch",count:Int=ChatMemoryPills.savedCount(db,id),friend:String="a")=ChatMemoryPills.record(db,friend,id,count)
    private fun read(friend:String="a",id:String="batch")=MemoryPillDetailReader.read(db,friend,id)
    private fun items()=(read() as MemoryPillDetails.Available).items
    @Test fun readsOnlyActualAddsAndUpdatesAtTheirHistoricalVersion(){
        batch();memory("new","新增时的内容");memory("old","修改前");memory("old","本批更新",2)
        op("add","new",1);op("update","old",2,"UPDATE");op("skip","unused",0,saved=false);op("life","old",2,"CANCEL")
        pill()
        assertEquals(listOf("新增时的内容","本批更新"),items().map{it.text})
        assertEquals(listOf(false,true),items().map{it.updated});assertEquals(2,ChatMemoryPills.savedCount(db,"batch"))
    }
    @Test fun laterEditsNeverReplaceTheBatchSnapshot()=runBlocking {
        batch();memory("m","批次原文");op("add","m",1);pill();repo.manualSave("a","m","后来的内容",MemoryCategory.STATE_FACT,false,false,MemoryAbout.USER)
        assertEquals("批次原文",items().single().text);assertEquals(1,items().single().version)
    }
    @Test fun oneMemoryWithSeveralWritesUsesItsFinalRelevantVersionOnce(){
        batch();memory("m","第一版");op("1","m",1)
        memory("m","第二版",2);op("2","m",2,"UPDATE")
        memory("m","第三版",3);op("3","m",3,"UPDATE");pill()
        val item=items().single();assertEquals("第三版",item.text);assertEquals(3,item.version);assertTrue(item.updated)
        assertEquals(1,ChatMemoryPills.savedCount(db,"batch"))
    }
    @Test fun aLaterLifeChangeDoesNotSelectANonContentOperation(){
        batch();memory("m","旧内容");memory("m","更新内容",2);op("update","m",2,"UPDATE")
        memory("m","更新内容",3);op("cancel","m",3,"CANCEL");pill()
        assertEquals(2,items().single().version);assertEquals("更新内容",items().single().text)
    }
    @Test fun aSavedAddThatReallyAdvancedAnExistingVersionIsMarkedUpdated(){
        batch();memory("m","旧内容");memory("m","合并内容",2);op("merged","m",2);pill()
        assertTrue(items().single().updated)
    }
    @Test fun missingOperationsCannotFallBackToCurrentMemories(){
        batch();memory("m","不能冒充本批");pill(count=1)
        assertEquals(MemoryPillDetails.Unavailable,read())
    }
    @Test fun missingHistoryCannotFallBackToTheCurrentVersion(){
        batch();memory("m","当时内容");op("add","m",1);pill();memory("m","最新内容",2)
        db.delete("memory_history","memory_id=? AND version=1",arrayOf("m"))
        assertEquals(MemoryPillDetails.Unavailable,read())
    }
    @Test fun aDeletedMemoryNeverReadsOrDisplaysItsOldHistory()=runBlocking {
        batch();memory("m","应隐藏的内容");op("add","m",1);pill();repo.deleteMemory("a","m")
        db.update("memory_history",ContentValues().apply{put("json","not valid JSON")},"memory_id=? AND version=1",arrayOf("m"))
        val item=items().single();assertTrue(item.deleted);assertNull(item.text)
    }
    @Test fun aForeignRoleCannotReadTheBatch(){
        batch();memory("m","甲的内容");op("add","m",1);pill()
        assertEquals(MemoryPillDetails.Unavailable,read(friend="b"))
    }
    @Test fun anOperationPointingToAnotherRoleIsRejected(){
        batch();memory("foreign","乙的内容",friend="b");op("add","foreign",1);pill()
        assertEquals(MemoryPillDetails.Unavailable,read())
    }
    @Test fun aMissingRoleCannotReadEvenOrphanedLegacyRecords(){
        batch();memory("m","不应泄露");op("add","m",1);pill()
        // Synthetic corruption/old import only: preserve the orphan rows to exercise the role guard.
        db.setForeignKeyConstraintsEnabled(false)
        try{db.delete("friends","id=?",arrayOf("a"))}finally{db.setForeignKeyConstraintsEnabled(true)}
        assertEquals(MemoryPillDetails.Unavailable,read())
    }
    @Test fun aPillCannotExposeAnUncommittedBatch(){
        batch(state="RUNNING");memory("m","尚未提交");op("add","m",1);pill()
        assertEquals(MemoryPillDetails.Unavailable,read())
    }
    @Test fun zeroWritesWithARecordedSkipAreAnEmptyDetailAndKeepTheOriginalWording(){
        batch();op("skip","not-saved",0,saved=false);pill()
        assertTrue(items().isEmpty());assertEquals("记忆已整理",ChatMemoryPills.read(db,"a").single().text)
    }
    @Test fun anOldZeroPillWithoutOperationsIsUnavailable(){
        batch();pill(count=0)
        assertEquals(MemoryPillDetails.Unavailable,read())
    }
    @Test fun mismatchedHistoryIdentityAndMismatchedPillCountsAreUnavailable(){
        batch();memory("m","内容");op("add","m",1);pill()
        val history=JSONObject(db.rawQuery("SELECT json FROM memory_history WHERE memory_id='m'",null).use{it.moveToFirst();it.getString(0)}).put("character","b")
        db.update("memory_history",ContentValues().apply{put("json",history.toString())},"memory_id=?",arrayOf("m"))
        assertEquals(MemoryPillDetails.Unavailable,read())
        history.put("character","a");db.update("memory_history",ContentValues().apply{put("json",history.toString())},"memory_id=?",arrayOf("m"))
        db.update("chat_memory_pills",ContentValues().apply{put("saved_count",2)},"batch_id=?",arrayOf("batch"))
        assertEquals(MemoryPillDetails.Unavailable,read())
    }
    @Test fun viewingDetailsDoesNotWriteOrDuplicateThePill(){
        batch();memory("m","原文");op("add","m",1);pill()
        fun changes()=db.rawQuery("SELECT total_changes()",null).use{it.moveToFirst();it.getLong(0)}
        val before=changes();repeat(3){assertEquals("原文",items().single().text)}
        assertEquals(before,changes());assertEquals(1,ChatMemoryPills.read(db,"a").size)
    }
    @Test fun losingTheFinalOperationForTheSameIdCannotShowAnEarlierVersion(){
        batch();memory("m","第一版");op("1","m",1);memory("m","本批最终版",2);op("2","m",2,"UPDATE");pill()
        db.delete("memory_operations","batch_id=? AND op_key=?",arrayOf("batch","2"))
        assertEquals(MemoryPillDetails.Unavailable,read())
    }
    @Test fun aLaterCommittedBatchDoesNotReplaceThisBatchesHistory(){
        batch();memory("m","第一批原文");op("1","m",1);pill()
        batch("later");memory("m","后续批次原文",2);op("2","m",2,"UPDATE",batch="later");pill("later")
        assertEquals("第一批原文",items().single().text)
        assertEquals("后续批次原文",(read(id="later") as MemoryPillDetails.Available).items.single().text)
    }
    @Test fun malformedDeletionMarkersCannotExposeHistoricalText(){
        batch();memory("m","不能误露的正文");op("1","m",1);pill()
        val raw=db.rawQuery("SELECT json FROM memory_current WHERE id='m'",null).use{it.moveToFirst();JSONObject(it.getString(0))}
        db.update("memory_current",ContentValues().apply{put("json",raw.put("userDeleted",1).toString())},"id=?",arrayOf("m"))
        assertEquals(MemoryPillDetails.Unavailable,read())
        raw.put("userDeleted",false);db.update("memory_current",ContentValues().apply{put("json",raw.toString())},"id=?",arrayOf("m"))
        db.update("memory_history",ContentValues().apply{put("json",raw.put("userDeleted",1).toString())},"memory_id=?",arrayOf("m"))
        assertEquals(MemoryPillDetails.Unavailable,read())
    }
    @Test fun twoCommittedBatchesCannotClaimTheSameSnapshot(){
        batch();memory("m","来源有歧义");op("1","m",1);pill()
        batch("other");op("2","m",1,batch="other");pill("other")
        assertEquals(MemoryPillDetails.Unavailable,read())
        assertEquals(MemoryPillDetails.Unavailable,read(id="other"))
    }
    @Test fun missingOrNonStringHistoryTextIsUnavailable(){
        batch();memory("m","原文");op("1","m",1);pill()
        val raw=db.rawQuery("SELECT json FROM memory_history WHERE memory_id='m'",null).use{it.moveToFirst();JSONObject(it.getString(0))}
        listOf(JSONObject.NULL,42,"").forEach{invalid->
            db.update("memory_history",ContentValues().apply{put("json",raw.put("text",invalid).toString())},"memory_id=?",arrayOf("m"))
            assertEquals(MemoryPillDetails.Unavailable,read())
        }
    }
}
