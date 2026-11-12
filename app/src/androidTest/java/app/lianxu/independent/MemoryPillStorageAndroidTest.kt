package app.lianxu.independent

import android.content.ContentValues
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import kotlinx.coroutines.runBlocking

/** 计数规则与「一批一颗」的存储约束：都在真实数据库上验证。 */
class MemoryPillStorageAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String;private lateinit var store:Store

    @Before fun prepare():Unit=runBlocking{
        name="pill-${System.nanoTime()}.db";store=Store(context,name)
        store.add(listOf(Friend("a","A","Synthetic")),false)
        message("原话")
        Unit
    }
    @After fun close():Unit=runBlocking{store.close();context.deleteDatabase(name);Unit}
    private fun db()=store.writableDatabase
    private fun message(text:String):Long=db().insertOrThrow("messages",null,ContentValues().apply{put("friend_id","a");put("body",text);put("mine",1);put("created",System.currentTimeMillis())})
    private fun op(batch:String,key:String,kind:String,resultId:String,version:Int,saved:Boolean){
        val json=JSONObject().put("id",key).put("op",kind).put("target","N1").toString()
        db().insertOrThrow("memory_operations",null,ContentValues().apply{
            put("batch_id",batch);put("op_key",key);put("json",json);put("digest","d-$batch-$key")
            put("result_id",resultId);put("result_version",version);put("saved",if(saved)1 else 0)
        })
    }

    @Test fun savedCountCoversRealWritesAndIgnoresSkipsCandidatesAndLifeChanges(){
        op("b1","add","ADD","m1",1,true)          // 新增，真写入
        op("b1","up","UPDATE","m2",3,true)        // 更新，真写入
        op("b1","same","UPDATE","m3",2,false)     // 没有改动，跳过
        op("b1","cancel","CANCEL","m4",4,true)    // 只改状态/撤回，不算内容更新
        op("b1","candidate","ADD","m5",0,false)   // 受保护转候选，没落库
        op("b1","dedup","ADD","m6",7,false)       // 去重命中未选中的旧记忆，版本高但没写
        assertEquals(2,ChatMemoryPills.savedCount(db(),"b1"))
    }

    @Test fun theSameMemoryTouchedTwiceCountsOnce(){
        op("b1","x","ADD","m1",1,true)
        op("b1","y","UPDATE","m1",2,true)
        assertEquals(1,ChatMemoryPills.savedCount(db(),"b1"))
    }

    @Test fun recordingUsesTheNewestMessageAndNeverDuplicatesABatch(){
        ChatMemoryPills.record(db(),"a","b1",2)
        ChatMemoryPills.record(db(),"a","b1",2)
        val pills=ChatMemoryPills.read(db(),"a")
        assertEquals(1,pills.size)
        assertEquals(2,pills.single().savedCount)
        assertEquals("已整理 2 条记忆",pills.single().text)
        val newest=db().rawQuery("SELECT MAX(id) FROM messages WHERE friend_id='a'",null).use{it.moveToFirst();it.getLong(0)}
        assertEquals(newest,pills.single().afterMessageId)
    }

    @Test fun aLaterBatchLandsAfterItsOwnNewestMessage(){
        ChatMemoryPills.record(db(),"a","b1",1)
        val second=message("后面的原话")
        ChatMemoryPills.record(db(),"a","b2",0)
        val pills=ChatMemoryPills.read(db(),"a")
        assertEquals(listOf("b1","b2"),pills.map{it.batchId})
        assertEquals(second,pills.last().afterMessageId)
        assertEquals("记忆已整理",pills.last().text)
    }

    @Test fun pillsSurviveReopeningTheDatabaseExactlyOnce(){
        ChatMemoryPills.record(db(),"a","b1",3)
        store.close();store=Store(context,name)
        val pills=ChatMemoryPills.read(db(),"a")
        assertEquals(1,pills.size);assertEquals(3,pills.single().savedCount)
    }
}
