package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File

/** Upgrade/import fixtures only: no real provider, user database or phone. */
class RetiredRelationshipAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun seed(store:Store){
        store.add(listOf(Friend("a","合成甲","迁移测试")),false)
        val ledger=ChatLedger(store);val turn=ledger.begin("a","合成原话","unused")
        assertTrue(ledger.finish(turn.id,1,"合成回复"))
        ProcessingRepository(store).manualSave("a",null,"应保留的记忆",MemoryCategory.STATE_FACT,true,true,MemoryAbout.USER)
        CompanionRepository(store).beginInput("a")
        store.updateRolePrompts("a"){it.copy(initialRelationship="老朋友",characterDefinition="合成人设")}
    }
    private fun assertAbsent(store:Store){
        assertFalse(store.readableDatabase.rawQuery("SELECT 1 FROM sqlite_master WHERE name IN ('relationship_state','relationship_history','relationship_jobs')",null).use{it.moveToFirst()})
    }
    @Test fun versionEightUpgradeDeletesOnlySixDimensionalData()=runBlocking {
        val name="retire-upgrade-${System.nanoTime()}.db";var store=Store(context,name)
        try{
            seed(store);val before=store.messages();val memory=ProcessingRepository(store).memories("a")
            val db=store.writableDatabase;val currentVersion=db.version
            RetiredRelationshipData.tables.forEach{db.execSQL("CREATE TABLE $it(friend_id TEXT,json TEXT)");db.execSQL("INSERT INTO $it VALUES('a','old-six-data')")}
            db.execSQL("INSERT INTO processing_policies(friend_id,json,version) VALUES('a',?,1)",arrayOf<Any>("""{"core":1450,"relationshipEnabled":true,"relationshipAutomatic":true,"relationshipInterval":5,"historyChoice":"BACKFILL"}"""))
            val prompts=JSONObject(store.preference(RolePromptStore.key("a"),""));prompts.put("initialScores",JSONObject().put("TRUST",51))
            store.setPreference(RolePromptStore.key("a"),prompts.toString())
            db.version=8;store.close();store=Store(context,name)
            assertAbsent(store);assertEquals(currentVersion,store.readableDatabase.version)
            assertEquals(before,store.messages());assertEquals(memory,ProcessingRepository(store).memories("a"))
            val after=JSONObject(store.preference(RolePromptStore.key("a"),""))
            assertFalse(after.has("initialScores"));assertEquals("老朋友",after.getString("initialRelationship"));assertEquals("合成人设",after.getString("characterDefinition"))
            val policy=store.readableDatabase.rawQuery("SELECT json FROM processing_policies WHERE friend_id='a'",null).use{it.moveToFirst();JSONObject(it.getString(0))}
            assertEquals(setOf("core"),policy.keys().asSequence().toSet());assertEquals(1450,policy.getInt("core"))
            assertTrue(store.readableDatabase.rawQuery("SELECT 1 FROM companion_state WHERE role='a'",null).use{it.moveToFirst()})
        }finally{store.close();context.deleteDatabase(name)}
    }
    @Test fun oldBackupsCannotRestoreRemovedData()=runBlocking {
        val name="retire-import-${System.nanoTime()}";val store=Store(context,"$name.db");val vault=ModelVault(context,name,store)
        try{
            seed(store);val repo=ProcessingRepository(store);val backup=BackupRepository(store,vault)
            val old=backup.export("a")
            val role=old.root["roles"].asJsonArray[0].asJsonObject
            RetiredRelationshipData.tables.forEach{role["tables"].asJsonObject.add(it,JsonArray().apply{add(JsonObject().apply{addProperty("json","old-six-data")})})}
            val prefs=role["preferences"].asJsonObject
            val prompts=JSONObject(prefs[RolePromptStore.key("a")].asString).put("initialScores",JSONObject().put("TRUST",99))
            prefs.addProperty(RolePromptStore.key("a"),prompts.toString())
            val bytes=ByteArrayOutputStream().also{BackupArchive.write(old.root,old.files,it)}.toByteArray()
            val pack=BackupArchive.read(bytes.inputStream())
            val id=backup.commit(backup.preview(pack,"ADD")).single()
            assertAbsent(store);assertEquals(1,repo.memories(id).size);assertEquals(2,store.messages().count{it.friendId==id})
            assertEquals("老朋友",RolePromptStore.decode(store.preference(RolePromptStore.key(id),"")).initialRelationship)
            assertFalse(store.preference(RolePromptStore.key(id),"").contains("initialScores"))
            // The original portable v1 role format also strips retired fields on read.
            val legacy=RoleArchiveRepository(store).export("a",true)
            legacy["prompts"].asJsonObject.add("initialScores",JsonObject())
            RetiredRelationshipData.tables.forEach{legacy["records"].asJsonObject.add(it,JsonArray())}
            val legacyPack=RoleArchive.read(ByteArrayOutputStream().also{RoleArchive.write(legacy,it)}.toByteArray().inputStream())
            assertFalse(legacyPack.raw.contains("initialScores"));assertFalse(legacyPack.raw.contains("relationship_state"))
            val legacyRepo=RoleArchiveRepository(store);val copy=legacyRepo.commit(legacyRepo.preview(legacyPack,null))
            assertEquals(1,repo.memories(copy).size)
        }finally{vault.list().forEach{vault.delete(it.id)};store.close();context.deleteDatabase("$name.db");File(context.noBackupFilesDir,name).deleteRecursively()}
    }
    @Test fun obsoletePurposeIsRemovedWithoutChangingModelsKeysOrActiveRequestEpoch(){
        val name="retire-vault-${System.nanoTime()}";val vault=ModelVault(context,name)
        try{
            val model=ModelConfig("synthetic","合成模型","custom","https://example.invalid/v1","fake-model")
            vault.save(model,"synthetic-key-only");vault.setPurpose(ModelPurpose.CHAT,model.id)
            val file=File(context.noBackupFilesDir,"$name/purpose-selections")
            file.writeText(JSONObject(file.readText()).put("relationship",model.id).toString())
            val epoch=AppMutationGate.epoch();val next=ModelVault(context,name)
            assertEquals(epoch,AppMutationGate.epoch());assertEquals(listOf(model),next.list())
            assertEquals("synthetic-key-only",next.secret(model.id));assertEquals(model.id,next.purposeId(ModelPurpose.CHAT))
            assertFalse(JSONObject(file.readText()).has("relationship"))
            assertFalse(ModelPurpose.entries.any{it.key=="relationship"})
        }finally{vault.list().forEach{vault.delete(it.id)};File(context.noBackupFilesDir,name).deleteRecursively()}
    }
}
