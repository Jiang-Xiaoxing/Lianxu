package app.lianxu.independent

import android.content.ContentValues
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream

/** Only isolated synthetic databases, opaque fixture files and fake credentials. */
class CompleteBackupAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private data class Fixture(val store:Store,val repo:ProcessingRepository,val vault:ModelVault,val backup:BackupRepository,val name:String)
    private val fixtures=mutableListOf<Fixture>();private lateinit var f:Fixture
    private suspend fun fixture():Fixture {
        val name="backup-${System.nanoTime()}";val s=Store(context,"$name.db");val p=ProcessingRepository(s);val v=ModelVault(context,name,s)
        return Fixture(s,p,v,BackupRepository(s,v),name).also{fixtures+=it}
    }
    @Before fun setup()=runBlocking{f=fixture();f.store.add(listOf(Friend("a","合成甲","成年角色甲","原人设"),Friend("b","合成乙","另一角色")),false);seed()}
    @After fun cleanup(){fixtures.forEach{x->x.vault.list().forEach{x.vault.delete(it.id)};x.store.close();context.deleteDatabase("${x.name}.db");java.io.File(context.noBackupFilesDir,x.name).deleteRecursively()}}
    private fun config(id:String="service",endpoint:String="https://example.invalid/v1")=ModelConfig(id,"合成服务","custom",endpoint,"fake-model",32000,1024)
    private suspend fun turn(id:String="a",body:String="合成原话"):SourceDialogue {val l=ChatLedger(f.store);val t=l.begin(id,body,"service");check(l.finish(t.id,1,"合成回复，这是完整测试回答。"));return f.repo.sources(id).last()}
    private suspend fun seed(){
        val source=turn();turn("b","其他角色的原话")
        val m=StoredMemory("memory-a","a",MemoryCategory.STATE_FACT,"合成事实",core=true,locked=true,about=MemoryAbout.USER,evidence=listOf(MemoryEvidence(source.source,"user",source.user.text,source.user.text,source.user.created,source.zone)))
        val n=m.copy(id="memory-b",text="另一条合成事实")
        f.repo.tx{db->f.repo.saveMemory(db,m);f.repo.saveMemory(db,n);f.repo.candidate(db,"a",m.id,m,"需要确认","candidate-a")
            db.insertOrThrow("candidate_injections",null,ContentValues().apply{put("candidate_id","candidate-a");put("friend_id","a");put("user_message_id",source.user.id)})
        }
        f.store.setPreference(RolePromptStore.key("a"),RolePromptStore.encode(RolePrompts(expression="合成语气",examples=listOf(DialogueExample(source.user.text,source.assistant.text)),examplesEnabled=true)))
        f.store.setPreference("role-examples:a",Gson().toJson(RoleExamples(ExamplesState.ACTIVE,true,listOf(ExampleCandidate(source.requestId,1,DialogueExample(source.user.text,source.assistant.text))))))
        f.store.setPreference("memory-associations-v1:a","""[{"character":"a","source":"memory-a","source_version":1,"target":"memory-b","target_version":1,"kind":"explicit_reference","reason":"合成关联","quote":"合成原话","target_quote":"合成事实","batch":"old-batch"}]""")
        f.store.setPreference("memory-reference-v1:a:${source.user.id}","""{"format":1,"memoryIds":["memory-a"]}""")
        f.store.setPreference("nickname","合成用户");f.store.setPreference("compact","true")
        val r=RelationshipRepository(f.repo);r.setEnabled("a",true,"BACKFILL",true);val work=r.begin("a","assessment-a","fake-stamp","synthetic",true)!!
        r.complete(work,JSONObject().put("task_id",work.id).put("character_id","a").put("dimensions",JSONObject().put("TRUST",JSONObject().put("score",55).put("statement","合成关系依据"))).put("complete",true),{true},ChatAttemptLease())
        RoleAssets(f.repo).put("a",RoleAssets.Asset("avatar.png","avatar","image/png",byteArrayOf(1,2,3,4)))
        RoleAssets(f.repo).put("a",RoleAssets.Asset("attachments/note.bin","attachment","application/octet-stream",byteArrayOf(9,8,7)))
        f.vault.save(config(),"synthetic-key-original");f.vault.setPurpose(ModelPurpose.CHAT,"service")
    }
    private fun pack(export:BackupRepository.Export):BackupArchive.Package=BackupArchive.read(ByteArrayOutputStream().also{BackupArchive.write(export.root,export.files,it)}.toByteArray().inputStream())
    private suspend fun roundTrip(target:Fixture,all:Boolean=false,keys:Boolean=false):List<String>{val p=pack(f.backup.export(if(all)null else "a",keys));return target.backup.commit(target.backup.preview(p,if(all)"REPLACE"else"ADD"),all)}
    @Test fun completeRoleRoundTripPreservesSourcesExamplesAssociationsAssetsAndReview()=runBlocking{
        val other=fixture();val id=roundTrip(other).single();val memories=other.repo.memories(id);assertEquals(2,memories.size);assertTrue(memories.all{it.friendId==id&&it.evidence.all{e->e.source.characterId==id}})
        assertEquals(listOf("avatar.png","attachments/note.bin").sorted(),RoleAssets(other.repo).list(id).map{it.path}.sorted());assertArrayEquals(byteArrayOf(1,2,3,4),RoleAssets(other.repo).list(id).first{it.kind=="avatar"}.bytes)
        val prompts=RolePromptStore.decode(other.store.preference(RolePromptStore.key(id),""));assertEquals("合成语气",prompts.expression);assertTrue(prompts.examplesEnabled)
        assertNotNull(RoleExamplesRepository(other.store).get(id));assertEquals(1,other.repo.read{MemoryAssociations(other.repo).read(it,id)}.size)
        val assessment=RelationshipReviewRepository(other.repo).prepare(id,"new-config");assertEquals(id,assessment.work.friendId);assertTrue(assessment.work.targets.all{it.source.characterId==id});assertTrue(other.vault.list().isEmpty())
    }
    @Test fun roleReplaceOnlyTouchesExplicitTarget()=runBlocking{
        val oldB=f.store.friends().first{it.id=="b"};val p=pack(f.backup.export("a"));assertTrue(runCatching{f.backup.preview(p,"REPLACE")}.isFailure)
        val plan=f.backup.preview(p,"REPLACE","b");assertTrue(runCatching{f.backup.commit(plan)}.isFailure);assertEquals(oldB,f.store.friends().first{it.id=="b"})
        f.backup.commit(plan,true);assertEquals(2,f.repo.memories("b").size);assertEquals(2,f.repo.memories("a").size);assertEquals("synthetic-key-original",f.vault.secret("service"))
    }
    @Test fun resourcesLargerThanCursorWindowRoundTripWithoutBlobFailure()=runBlocking{
        val resource=ByteArray(3*1024*1024){((it*31+7)%251).toByte()}
        RoleAssets(f.repo).put("a",RoleAssets.Asset("attachments/large.bin","attachment","application/octet-stream",resource))
        assertArrayEquals(resource,RoleAssets(f.repo).list("a").single{it.path.endsWith("large.bin")}.bytes)
        val other=fixture();val id=roundTrip(other).single()
        assertEquals(RoleArchive.hash(resource),RoleArchive.hash(RoleAssets(other.repo).list(id).single{it.path.endsWith("large.bin")}.bytes))
    }
    @Test fun allWithKeysRestoresSettingsCredentialsAndResourcesWithoutCalls()=runBlocking{
        val other=fixture();roundTrip(other,true,true)
        assertEquals("synthetic-key-original",other.vault.secret("service"));assertEquals("service",other.vault.purposeId(ModelPurpose.CHAT));assertEquals("合成用户",other.store.preference("nickname",""))
        assertEquals(f.store.friends().size,other.store.friends().size);assertEquals(2,other.repo.read{db->db.rawQuery("SELECT count(*) FROM role_assets",null).use{it.moveToFirst();it.getInt(0)}})
        assertTrue(other.repo.read{db->db.rawQuery("SELECT 1 FROM relationship_jobs WHERE state='RUNNING'",null).use{!it.moveToFirst()}})
    }
    @Test fun noKeysExportNeverContainsFakeSecretAndExactEndpointRetainsLocalKey()=runBlocking{
        val export=f.backup.export(null,false);assertFalse(export.root.toString().contains("synthetic-key-original"))
        val other=fixture();other.vault.save(config(),"synthetic-destination-key")
        val p=pack(export);other.backup.commit(other.backup.preview(p,"REPLACE"),true)
        assertEquals("synthetic-destination-key",other.vault.secret("service"))
    }
    @Test fun endpointChangeNeverRebindsExistingKey()=runBlocking{
        val other=fixture();other.vault.save(config(endpoint="https://other.invalid/v1"),"synthetic-keep-local")
        roundTrip(other,true,false)
        assertEquals("",other.vault.secret("service"));val retained=other.vault.list().single{it.baseUrl=="https://other.invalid/v1"};assertEquals("synthetic-keep-local",other.vault.secret(retained.id));assertNotEquals(retained.id,other.vault.purposeId(ModelPurpose.CHAT))
    }
    @Test fun allAddOnlyImportsRolesAndKeepsSettingsAndCredentials()=runBlocking{
        val other=fixture();other.store.setPreference("nickname","原昵称");other.vault.save(config(endpoint="https://keep.invalid/v1"),"synthetic-local")
        val p=pack(f.backup.export(null,true));val before=other.store.friends().size;other.backup.commit(other.backup.preview(p,"ADD"))
        assertEquals(before+f.store.friends().size,other.store.friends().size);assertEquals("原昵称",other.store.preference("nickname",""));assertEquals("synthetic-local",other.vault.secret("service"));assertEquals("https://keep.invalid/v1",other.vault.list().single().baseUrl)
    }
    @Test fun cancelledAndFailedRestoreKeepDatabaseAndVaultAtomic()=runBlocking{
        val p=pack(f.backup.export(null,true));val plan=f.backup.preview(p,"REPLACE");val friends=f.store.friends();val before=f.vault.stamp()
        val lease=ChatAttemptLease();lease.cancel();assertTrue(runCatching{f.backup.commit(plan,true,lease)}.isFailure)
        assertTrue(runCatching{f.backup.commit(plan,true){error("synthetic transaction interruption")}}.isFailure)
        assertEquals(friends,f.store.friends());assertEquals(before,f.vault.stamp());assertEquals("synthetic-key-original",f.vault.secret("service"))
    }
    @Test fun stalePreviewAndMutatedPackageDoNotWrite()=runBlocking{
        val p=pack(f.backup.export("a"));val plan=f.backup.preview(p,"ADD");f.store.setPreference("nickname","新昵称");assertTrue(runCatching{f.backup.commit(plan)}.isFailure)
        val next=f.backup.preview(p,"ADD");p.roles[0].asJsonObject["character"].asJsonObject.addProperty("name","篡改");assertTrue(runCatching{f.backup.commit(next)}.isFailure)
    }
    @Test fun crossRoleBatchAndCandidateInjectionRejectedBeforeWriting()=runBlocking{
        val before=f.store.friends()
        for(kind in 0..1){val export=f.backup.export("a");val tables=export.root["roles"].asJsonArray[0].asJsonObject["tables"].asJsonObject
            if(kind==0){tables["memory_operations"].asJsonArray.add(JsonObject().apply{addProperty("batch_id","external-other-role-batch");addProperty("op_key","malicious");addProperty("json","{}");addProperty("digest","fake");add("result_id",JsonNull.INSTANCE);add("result_version",JsonNull.INSTANCE)})}
            else tables["candidate_injections"].asJsonArray[0].asJsonObject.addProperty("candidate_id","external-other-role-candidate")
            assertTrue(runCatching{f.backup.preview(pack(export),"ADD")}.isFailure)
        }
        assertEquals(before,f.store.friends())
    }
    @Test fun pendingChatIsRestoredAsInterruptedNotResumed()=runBlocking{
        ChatLedger(f.store).begin("a","尚未完成的合成消息","service");val other=fixture();val id=roundTrip(other).single()
        assertTrue(ChatLedger(other.store).all().filter{it.friendId==id}.none{it.state==TurnState.PENDING});assertTrue(other.store.messages().any{it.friendId==id&&it.text=="尚未完成的合成消息"})
    }
    @Test fun importedFailedBatchIsArchivedAndCannotReplayOrBlockNewBatches()=runBlocking{
        val batches=MemoryBatchRepository(f.repo);val work=batches.prepare("a",f.repo.sources("a"),"synthetic-stamp")
        batches.beginAttempt(work,"synthetic");batches.fail(work,"FORMAT")
        val other=fixture();val id=roundTrip(other).single();val restored=MemoryBatchRepository(other.repo)
        assertNull(restored.pending(id));val history=other.repo.read{db->db.rawQuery("SELECT state,json FROM memory_batches WHERE friend_id=?",arrayOf(id)).use{it.moveToFirst();it.getString(0) to MemoryWork.from(JSONObject(it.getString(1)))}}
        assertEquals("ARCHIVED",history.first);assertTrue(history.second.existingVersions.keys.all{key->other.repo.memories(id).any{it.id==key}})
        repeat(2){val l=ChatLedger(other.store);if(it>0){val t=l.begin(id,"新的合成原话","unused");check(l.finish(t.id,1,"新的合成回复。"))}
            val pending=other.repo.sources(id,other.repo.cursor(id));val next=restored.prepare(id,pending,"synthetic-new");restored.beginAttempt(next,"new-session");restored.complete(next,emptySet(),0,next.id);assertNull(restored.pending(id))
        }
    }
    @Test fun legacyPackageStillRecognizedAndImportable()=runBlocking{
        val old=RoleArchiveRepository(f.store);val bytes=ByteArrayOutputStream().also{RoleArchive.write(old.export("a",true),it)}.toByteArray();val p=BackupArchive.read(bytes.inputStream());assertNotNull(p.legacy)
        val count=f.store.friends().size;old.commit(old.preview(p.legacy!!,null));assertEquals(count+1,f.store.friends().size)
    }
    @Test fun schemaFourUpgradeAddsResourceStorageWithoutChangingData()=runBlocking{
        val other=fixture();other.store.writableDatabase.execSQL("DROP TABLE role_assets");other.store.writableDatabase.version=4;other.store.close()
        val reopened=Store(context,"${other.name}.db");try{assertTrue(reopened.friends().isNotEmpty());RoleAssets(ProcessingRepository(reopened)).put("seed-lin",RoleAssets.Asset("avatar.png","avatar","image/png",byteArrayOf(1)))}finally{reopened.close()}
    }
}
