package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream

/** 旧备份（没有胶囊表、没有 saved 列）真跑恢复；同时确认新备份会把胶囊和计数原样带回来。 */
class MemoryPillBackupCompatAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private data class Fixture(val store:Store,val repo:ProcessingRepository,val vault:ModelVault,val backup:BackupRepository,val name:String)
    private val fixtures=mutableListOf<Fixture>()

    private fun fixture():Fixture{
        val name="pill-backup-${System.nanoTime()}";val store=Store(context,"$name.db")
        val vault=ModelVault(context,name,store);val repo=ProcessingRepository(store)
        return Fixture(store,repo,vault,BackupRepository(store,vault),name).also{fixtures+=it}
    }
    @After fun cleanup(){fixtures.forEach{f->f.vault.list().forEach{f.vault.delete(it.id)};f.store.close();context.deleteDatabase("${f.name}.db");java.io.File(context.noBackupFilesDir,f.name).deleteRecursively()}}

    /** 真写入一条记忆、一个已提交批次和一颗胶囊。 */
    private suspend fun seed(f:Fixture){
        f.store.add(listOf(Friend("a","合成甲","成年角色甲","")),false)
        val ledger=ChatLedger(f.store);val turn=ledger.begin("a","明天还要上班，得加班","synthetic")
        check(ledger.finish(turn.id,1,"那就别熬太晚"))
        val source=f.repo.sources("a").last()
        val memory=StoredMemory("memory-a","a",MemoryCategory.STATE_FACT,"明天要上班",about=MemoryAbout.USER,
            evidence=listOf(MemoryEvidence(source.source,"user",source.user.text,source.user.text,source.user.created,source.zone)))
        f.repo.tx{db->
            f.repo.saveMemory(db,memory)
            db.execSQL("INSERT INTO memory_batches(id,friend_id,json,state,attempt,error,next_retry,owner_session,config_stamp) VALUES('batch-1','a','{}','COMMITTED',1,NULL,0,NULL,'synthetic')")
            db.execSQL("INSERT INTO memory_operations(batch_id,op_key,json,digest,result_id,result_version,saved) VALUES('batch-1','one','{\"op\":\"ADD\"}','d1','memory-a',1,1)")
            ChatMemoryPills.record(db,"a","batch-1",1)
            Unit
        }
    }
    private fun pack(root:JsonObject,files:Map<String,ByteArray>)=BackupArchive.read(ByteArrayOutputStream().also{BackupArchive.write(root,files,it)}.toByteArray().inputStream())

    @Test fun anOldBackupWithoutThePillTableOrSavedColumnStillRestores()=runBlocking{
        val source=fixture();seed(source)
        val export=source.backup.export("a")
        // 模拟旧包：角色里没有 chat_memory_pills，memory_operations 行也没有 saved 字段。
        val root=export.root.deepCopy();var stripped=0
        root["roles"].asJsonArray.forEach{role->
            val tables=role.asJsonObject["tables"].asJsonObject
            tables.remove("chat_memory_pills")
            tables["memory_operations"].asJsonArray.forEach{if(it.asJsonObject.remove("saved")!=null)stripped++}
        }
        assertEquals(1,stripped)
        val target=fixture()
        val plan=target.backup.preview(pack(root,export.files),"ADD")
        val id=target.backup.commit(plan).single()
        val db=target.store.readableDatabase
        assertEquals("旧包没有胶囊表，恢复后不能凭空出现",0,ChatMemoryPills.read(db,id).size)
        val saved=db.rawQuery("SELECT saved FROM memory_operations",null).use{assertTrue(it.moveToFirst());it.getInt(0)}
        assertEquals("缺 saved 列时按 0 读，代表当时没有记录",0,saved)
        assertEquals(1,target.repo.memories(id).size)
        assertEquals(1,target.repo.memories(id).single().version)
    }

    @Test fun aCurrentBackupCarriesThePillAndItsCountBack()=runBlocking{
        val source=fixture();seed(source)
        val export=source.backup.export("a")
        val target=fixture()
        val plan=target.backup.preview(pack(export.root,export.files),"ADD")
        val id=target.backup.commit(plan).single()
        val pill=ChatMemoryPills.read(target.store.readableDatabase,id).single()
        assertEquals(1,pill.savedCount)
        assertEquals("已整理 1 条记忆",pill.text)
        val saved=target.store.readableDatabase.rawQuery("SELECT saved FROM memory_operations",null).use{assertTrue(it.moveToFirst());it.getInt(0)}
        assertEquals(1,saved)
    }
}
