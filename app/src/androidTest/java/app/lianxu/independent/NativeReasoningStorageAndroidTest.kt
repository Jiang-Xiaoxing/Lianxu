package app.lianxu.independent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Isolated SQLite/archive fixtures; no UI, network or real role data. */
class NativeReasoningStorageAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private data class Fixture(var store:Store,val name:String)
    private val fixtures=mutableListOf<Fixture>()
    private fun fixture():Fixture {
        val name="reasoning-storage-${System.nanoTime()}"
        return Fixture(Store(context,"$name.db"),name).also{fixtures+=it}
    }
    private fun backup(f:Fixture)=BackupRepository(f.store,ModelVault(context,f.name,f.store))
    private fun pack(export:BackupRepository.Export)=BackupArchive.read(ByteArrayOutputStream().also{BackupArchive.write(export.root,export.files,it)}.toByteArray().inputStream())
    private suspend fun reply(f:Fixture,id:String,body:String="answer",reasoning:String="native thought"):ChatTurn {
        val ledger=ChatLedger(f.store);val turn=ledger.begin(id,"synthetic user","synthetic")
        check(ledger.finishWithMemory(turn.id,turn.attempt,body,onCompleted={},displayReasoning=reasoning))
        return ledger.all().single{it.id==turn.id}
    }
    private fun thoughtCount(f:Fixture)=f.store.readableDatabase.rawQuery("SELECT count(*) FROM message_reasoning",null).use{it.moveToFirst();it.getInt(0)}
    @After fun cleanup(){fixtures.forEach{f->f.store.close();context.deleteDatabase("${f.name}.db");val dir=java.io.File(context.noBackupFilesDir,f.name);require(dir.name.startsWith("reasoning-storage-"));dir.deleteRecursively()}}

    @Test fun upgradeAndFinalCommitKeepReasoningSeparateAtomicAndRemovedWithItsMessage()=runBlocking {
        val f=fixture();f.store.add(listOf(Friend("a","合成甲","成年合成角色")),false)
        val old=reply(f,"a",reasoning="")
        val db=f.store.writableDatabase;val currentVersion=db.version;db.execSQL("DROP TABLE message_reasoning");db.version=9;f.store.close()
        f.store=Store(context,"${f.name}.db")
        assertEquals(currentVersion,f.store.readableDatabase.version);assertTrue(f.store.messages().all{it.displayReasoning.isEmpty()});assertEquals(old.assistantMessageId,ChatLedger(f.store).all().single().assistantMessageId)

        val ledger=ChatLedger(f.store);val pending=ledger.begin("a","atomic request","synthetic")
        assertTrue(runCatching{ledger.finishWithMemory(pending.id,1,"atomic answer",onCompleted={error("synthetic rollback")},displayReasoning="atomic thought")}.isFailure)
        assertEquals(0,thoughtCount(f));assertTrue(f.store.messages().none{it.text=="atomic answer"});assertEquals(TurnState.PENDING,ledger.all().last().state)
        assertTrue(ledger.finishWithMemory(pending.id,1,"atomic answer",onCompleted={},displayReasoning="atomic thought"))
        assertFalse(ledger.finishWithMemory(pending.id,1,"duplicate answer",onCompleted={},displayReasoning="duplicate thought"))
        val assistant=f.store.messages().single{it.text=="atomic answer"};assertEquals("atomic thought",assistant.displayReasoning)
        assertTrue(f.store.messages().filter{it.mine}.all{it.displayReasoning.isEmpty()})
        assertTrue(ledger.completedMessages("a").all{it.displayReasoning.isEmpty()})
        assertTrue(ProcessingRepository(f.store).sources("a").all{it.user.displayReasoning.isEmpty()&&requireNotNull(it.assistant).displayReasoning.isEmpty()&&"thought" !in requireNotNull(it.assistant).text})

        f.store.writableDatabase.update("messages",ContentValues().apply{put("body","manually changed reply")},"id=?",arrayOf(assistant.id.toString()))
        assertEquals(0,thoughtCount(f))
        val retracted=reply(f,"a",body="retracted reply")
        f.store.writableDatabase.insertWithOnConflict("message_state",null,ContentValues().apply{put("message_id",retracted.assistantMessageId!!);put("revision",2);put("deleted",1)},SQLiteDatabase.CONFLICT_REPLACE)
        assertEquals(0,thoughtCount(f));assertEquals("",f.store.messages().single{it.id==retracted.assistantMessageId}.displayReasoning)
        val deleted=reply(f,"a",body="deleted reply")
        f.store.writableDatabase.delete("chat_turns","id=?",arrayOf(deleted.id))
        f.store.writableDatabase.delete("messages","id=?",arrayOf(deleted.assistantMessageId.toString()))
        assertEquals(0,thoughtCount(f));assertTrue(f.store.readableDatabase.rawQuery("PRAGMA foreign_key_check",null).use{!it.moveToFirst()})
    }

    @Test fun completeBackupsRemapReasoningButOldBackupsAndRoleTemplatesStayCompatible()=runBlocking {
        val source=fixture();source.store.add(listOf(Friend("a","合成甲","成年合成角色"),Friend("b","合成乙","其他合成角色")),false)
        val thought="provider thought: role=a, message_id=2; preserve literal text"
        val original=reply(source,"a",reasoning=thought);reply(source,"b",body="other answer",reasoning="other thought")
        assertFalse(RoleArchiveRepository(source.store).export("a",false).toString().contains(thought))

        for(all in listOf(false,true)){
            val target=fixture();target.store.add(listOf(Friend("existing","已有角色","保持编号不同")),false);reply(target,"existing",reasoning="preexisting thought")
            val repository=backup(target);val bundle=pack(backup(source).export(if(all)null else "a"))
            val ids=repository.commit(repository.preview(bundle,if(all)"REPLACE"else"ADD"),confirmed=all)
            val imported=target.store.messages().single{it.displayReasoning==thought}
            assertTrue(imported.friendId in ids);assertNotEquals(original.assistantMessageId!!,imported.id);assertEquals("answer",imported.text)
            assertEquals(imported.id,ChatLedger(target.store).all().single{it.friendId==imported.friendId}.assistantMessageId)
            assertTrue(ChatLedger(target.store).completedMessages(imported.friendId).all{it.displayReasoning.isEmpty()})
            val beforeOthers=target.store.messages().filter{it.friendId!=imported.friendId}
            repository.deleteRole(repository.previewRoleDeletion(imported.friendId),true)
            assertEquals(beforeOthers,target.store.messages());assertTrue(target.store.readableDatabase.rawQuery("SELECT 1 FROM message_reasoning WHERE message_id=?",arrayOf(imported.id.toString())).use{!it.moveToFirst()})
        }

        val legacy=backup(source).export("a");legacy.root["roles"].asJsonArray.forEach{it.asJsonObject["tables"].asJsonObject.remove("message_reasoning")}
        val oldTarget=fixture();val oldRepo=backup(oldTarget);oldRepo.commit(oldRepo.preview(pack(legacy),"ADD"))
        assertTrue(oldTarget.store.messages().all{it.displayReasoning.isEmpty()});assertEquals(0,thoughtCount(oldTarget))
        val invalid=backup(source).export("a");val tables=invalid.root["roles"].asJsonArray[0].asJsonObject["tables"].asJsonObject
        tables["message_reasoning"].asJsonArray[0].asJsonObject.addProperty("message_id",original.userMessageId)
        val before=oldTarget.store.friends();assertTrue(runCatching{oldRepo.preview(pack(invalid),"ADD")}.isFailure);assertEquals(before,oldTarget.store.friends())
    }
}
