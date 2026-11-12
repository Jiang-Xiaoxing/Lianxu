package app.lianxu.independent

import android.content.ContentValues
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.*
import kotlinx.coroutines.*
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
        f.store.setPreference(RolePromptStore.key("a"),RolePromptStore.encode(RolePrompts(expression="合成语气",examples=listOf(DialogueExample(source.user.text,requireNotNull(source.assistant).text)),examplesEnabled=true)))
        f.store.setPreference("role-examples:a",JSONObject().put("state","ACTIVE").put("optedIn",true).put("selected",org.json.JSONArray().put(JSONObject().put("id",source.requestId).put("revision",1).put("pair",JSONObject().put("user",source.user.text).put("assistant",requireNotNull(source.assistant).text)))).toString())
        f.store.setPreference("memory-associations-v1:a","""[{"character":"a","source":"memory-a","source_version":1,"target":"memory-b","target_version":1,"kind":"explicit_reference","reason":"合成关联","quote":"合成原话","target_quote":"合成事实","batch":"old-batch"}]""")
        f.store.setPreference("memory-reference-v1:a:${source.user.id}","""{"format":1,"memoryIds":["memory-a"]}""")
        f.store.setPreference("nickname","合成用户");f.store.setPreference("compact","true")
        RoleAssets(f.repo).put("a",RoleAssets.Asset("avatar.png","avatar","image/png",byteArrayOf(1,2,3,4)))
        RoleAssets(f.repo).put("a",RoleAssets.Asset("attachments/note.bin","attachment","application/octet-stream",byteArrayOf(9,8,7)))
        f.vault.save(config(),"synthetic-key-original");f.vault.setPurpose(ModelPurpose.CHAT,"service")
    }
    /** 带密钥的备份必须用备份密码封存（至少 BackupKeySeal.MIN_PASSWORD 位）；这里用一条 16 位以上的合成密码。 */
    private fun pw()="合成备份密码-synthetic-0123456789".toCharArray()
    private fun pack(export:BackupRepository.Export):BackupArchive.Package=BackupArchive.read(ByteArrayOutputStream().also{BackupArchive.write(export.root,export.files,it)}.toByteArray().inputStream())
    private suspend fun roundTrip(target:Fixture,all:Boolean=false,keys:Boolean=false):List<String>{val p=pack(f.backup.export(if(all)null else "a",keys,if(keys)pw()else null));return target.backup.commit(target.backup.preview(p,if(all)"REPLACE"else"ADD"),all,password=if(keys)pw()else null)}
    @Test fun completeRoleRoundTripPreservesSourcesExamplesAssociationsAssets()=runBlocking{
        val other=fixture();val id=roundTrip(other).single();val memories=other.repo.memories(id);assertEquals(2,memories.size);assertTrue(memories.all{it.friendId==id&&it.evidence.all{e->e.source.characterId==id}})
        assertEquals(listOf("avatar.png","attachments/note.bin").sorted(),RoleAssets(other.repo).list(id).map{it.path}.sorted());assertArrayEquals(byteArrayOf(1,2,3,4),RoleAssets(other.repo).list(id).first{it.kind=="avatar"}.bytes)
        val prompts=RolePromptStore.decode(other.store.preference(RolePromptStore.key(id),""));assertEquals("合成语气",prompts.expression);assertTrue(prompts.examplesEnabled)
        assertTrue(other.store.preference("role-examples:$id","").contains("ACTIVE"));assertEquals(1,other.repo.read{MemoryAssociations(other.repo).read(it,id)}.size)
        val friend=other.store.friends().single{it.id==id}
        val reply=(CompanionPrompt.build(friend,prompts,emptyList(),"本轮独立问题") as PromptBuild.Ready).request.messages
        assertFalse(reply.any{"[语气示例]" in it.content});assertEquals(listOf("本轮独立问题"),reply.filter{it.role=="user"}.map{it.content})
        assertTrue(other.vault.list().isEmpty())
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
        assertTrue(other.repo.read{db->db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name IN ('relationship_state','relationship_history','relationship_jobs')",null).use{!it.moveToFirst()}})
    }
    @Test fun independentOutputBudgetsRoundTripAndLegacyBackupStillInherits()=runBlocking{
        val separate=config().copy(contextTokens=65536,outputTokens=4096,memoryOutputTokens=16384)
        f.vault.save(separate,"synthetic-key-original")
        val target=fixture();roundTrip(target,true,true)
        assertEquals(separate,target.vault.list().single());assertEquals(16384,target.vault.list().single().memoryOutputLimit())
        val legacy=f.backup.export(null,true,pw())
        legacy.root["models"].asJsonArray[0].asJsonObject["config"].asJsonObject.remove("memoryOutputTokens")
        val oldTarget=fixture();val p=pack(legacy)
        oldTarget.backup.commit(oldTarget.backup.preview(p,"REPLACE"),true,password=pw())
        val old=oldTarget.vault.list().single()
        assertEquals(65536,old.contextTokens);assertEquals(4096,old.outputTokens);assertEquals(4096,old.memoryOutputLimit())
    }
    @Test fun invalidMemoryBudgetIsRejectedBeforeRestoreChangesAnything()=runBlocking{
        val export=f.backup.export(null,true,pw())
        export.root["models"].asJsonArray[0].asJsonObject["config"].asJsonObject.addProperty("memoryOutputTokens",-1)
        val before=f.vault.stamp();val friends=f.store.friends()
        assertTrue(runCatching{f.backup.preview(pack(export),"REPLACE")}.isFailure)
        assertEquals(before,f.vault.stamp());assertEquals(friends,f.store.friends())
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
        val p=pack(f.backup.export(null,true,pw()));val before=other.store.friends().size;other.backup.commit(other.backup.preview(p,"ADD"))
        assertEquals(before+f.store.friends().size,other.store.friends().size);assertEquals("原昵称",other.store.preference("nickname",""));assertEquals("synthetic-local",other.vault.secret("service"));assertEquals("https://keep.invalid/v1",other.vault.list().single().baseUrl)
    }
    @Test fun cancelledAndFailedRestoreKeepDatabaseAndVaultAtomic()=runBlocking{
        val p=pack(f.backup.export(null,true,pw()));val plan=f.backup.preview(p,"REPLACE");val friends=f.store.friends();val before=f.vault.stamp()
        val lease=ChatAttemptLease();lease.cancel();assertTrue(runCatching{f.backup.commit(plan,true,lease,password=pw())}.isFailure)
        assertTrue(runCatching{f.backup.commit(plan,true,checkpoint={error("synthetic transaction interruption")},password=pw())}.isFailure)
        assertEquals(friends,f.store.friends());assertEquals(before,f.vault.stamp());assertEquals("synthetic-key-original",f.vault.secret("service"))
    }
    @Test fun stalePreviewAndMutatedPackageDoNotWrite()=runBlocking{
        val p=pack(f.backup.export("a"));val plan=f.backup.preview(p,"ADD");f.store.setPreference("nickname","新昵称");assertTrue(runCatching{f.backup.commit(plan)}.isFailure)
        val next=f.backup.preview(p,"ADD");p.roles[0].asJsonObject["character"].asJsonObject.addProperty("name","外部原包变化")
        assertNotEquals("外部原包变化",next.pack.roles[0].asJsonObject["character"].asJsonObject["name"].asString)
        next.pack.roles[0].asJsonObject["character"].asJsonObject.addProperty("name","篡改预览副本");assertTrue(runCatching{f.backup.commit(next)}.isFailure)
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
    /** 「内部判断提示」删掉后，旧全部备份和旧角色包里多出的 internalPrompt/internalEnabled/thinkingNone 仍能导入，只是不再使用。 */
    @Test fun oldBackupsWithRetiredInternalPromptFieldsStillImport()=runBlocking{
        val key=RolePromptStore.key("a");val raw=JSONObject(f.store.preference(key,"{}")).put("internalPrompt","旧的内部规则").put("internalEnabled",true).put("thinkingNone",false)
        f.store.setPreference(key,raw.toString())
        val other=fixture();val id=roundTrip(other).single()
        val restored=RolePromptStore.decode(other.store.preference(RolePromptStore.key(id),""))
        assertEquals(RolePromptStore.decode(raw.toString()),restored)
        val sent=(CompanionPrompt.build(other.store.friends().first{it.id==id},restored,emptyList(),"hi") as PromptBuild.Ready).request.messages
        assertFalse(sent.any{"内部判断提示" in it.content||"旧的内部规则" in it.content})
        val old=RoleArchiveRepository(f.store);val root=old.export("a",true)
        root["prompts"].asJsonObject.apply{addProperty("internalPrompt","旧的内部规则");addProperty("internalEnabled",true);addProperty("thinkingNone",false)}
        val p=BackupArchive.read(ByteArrayOutputStream().also{RoleArchive.write(root,it)}.toByteArray().inputStream())
        val count=f.store.friends().size;old.commit(old.preview(p.legacy!!,null));assertEquals(count+1,f.store.friends().size)
    }
    @Test fun legacyPackageStillRecognizedAndImportable()=runBlocking{
        val old=RoleArchiveRepository(f.store);val bytes=ByteArrayOutputStream().also{RoleArchive.write(old.export("a",true),it)}.toByteArray();val p=BackupArchive.read(bytes.inputStream());assertNotNull(p.legacy)
        val count=f.store.friends().size;old.commit(old.preview(p.legacy!!,null));assertEquals(count+1,f.store.friends().size)
    }
    @Test fun schemaFourUpgradeAddsResourceStorageWithoutChangingData()=runBlocking{
        val other=fixture();other.store.add(listOf(Friend("existing","合成旧角色","升级夹具")),false);other.store.writableDatabase.execSQL("DROP TABLE role_assets");other.store.writableDatabase.version=4;other.store.close()
        val reopened=Store(context,"${other.name}.db");try{assertEquals("existing",reopened.friends().single().id);RoleAssets(ProcessingRepository(reopened)).put("existing",RoleAssets.Asset("avatar.png","avatar","image/png",byteArrayOf(1)))}finally{reopened.close()}
    }
    @Test fun deletingOneRoleClearsAllItsDataAndKeepsOtherRoleAndGlobalConfig()=runBlocking{
        val beforeOther=f.backup.export("b").root.toString();val vault=f.vault.stamp()
        val plan=f.backup.previewRoleDeletion("a")
        f.backup.deleteRole(plan,true)
        assertEquals(listOf("b"),f.store.friends().map{it.id})
        assertEquals(beforeOther,f.backup.export("b").root.toString());assertEquals(vault,f.vault.stamp())
        assertEquals("合成用户",f.store.preference("nickname",""))
        assertTrue(f.repo.memories("a").isEmpty());assertTrue(f.store.messages().none{it.friendId=="a"})
        assertTrue(f.repo.read{db->db.rawQuery("PRAGMA foreign_key_check",null).use{!it.moveToFirst()}})
        assertEquals("",f.store.preference(RolePromptStore.key("a"),""));assertEquals("",f.store.preference("role-examples:a",""))
        assertTrue(RoleAssets(f.repo).list("a").isEmpty())
    }
    @Test fun cancelledFailedOrStaleDeletionCannotPartlyRemoveRole()=runBlocking{
        val before=f.backup.export("a").root.toString();val plan=f.backup.previewRoleDeletion("a")
        assertTrue(runCatching{f.backup.deleteRole(plan,false)}.isFailure)
        val cancelled=ChatAttemptLease();cancelled.cancel()
        assertTrue(runCatching{f.backup.deleteRole(plan,true,cancelled)}.isFailure)
        assertTrue(runCatching{f.backup.deleteRole(plan,true){error("synthetic transaction failure")}}.isFailure)
        assertEquals(before,f.backup.export("a").root.toString())
        f.store.updateRolePrompts("a"){it.copy(expression="变更后的合成表达")}
        assertTrue(runCatching{f.backup.deleteRole(plan,true)}.isFailure)
        assertTrue(f.store.friends().any{it.id=="a"})
    }
    @Test fun lateDeletedRoleReplyFailsWhileOtherRoleInflightReplyCanFinish()=runBlocking{
        val ledger=ChatLedger(f.store);val a=ledger.begin("a","合成在途甲","service");val b=ledger.begin("b","合成在途乙","service")
        val epoch=AppMutationGate.epoch();f.backup.deleteRole(f.backup.previewRoleDeletion("a"),true)
        assertEquals(epoch,AppMutationGate.epoch());assertFalse(ledger.finish(a.id,1,"不应出现的迟到回复"))
        assertTrue(ledger.finish(b.id,1,"其他角色正常完成"));assertTrue(f.store.messages().any{it.friendId=="b"&&it.text=="其他角色正常完成"})
        assertTrue(runCatching{f.store.updateRolePrompts("a"){it.copy(expression="迟到写入")}}.isFailure)
        assertEquals("",f.store.preference(RolePromptStore.key("a"),""))
    }
    @Test fun lateEmbeddingResultCannotRecreateDeletedRoleCache()=runBlocking{
        f.vault.setPurpose(ModelPurpose.EMBEDDING,"service")
        val started=CompletableDeferred<Int>();val reply=CompletableDeferred<List<DoubleArray>>()
        val index=MemoryVectorIndex(f.repo,ModelAccess(f.vault),object:MemoryEmbeddingClient{
            override suspend fun embed(credential:ModelCredential,texts:List<String>,timeout:Int):List<DoubleArray>{started.complete(texts.size);return reply.await()}
        })
        val work=async(Dispatchers.Default){index.index("a")}
        val count=withTimeout(5000){started.await()}
        f.backup.deleteRole(f.backup.previewRoleDeletion("a"),true)
        reply.complete(List(count){doubleArrayOf(1.0,0.5)})
        withTimeout(5000){work.await()}
        assertEquals("",f.store.preference("memory-vectors-v1:a",""));assertTrue(f.store.friends().none{it.id=="a"})
    }
    @Test fun fullBackupCarriesTheRecallMarkerAndRestoreNeverReopensRecall()=runBlocking{
        val marker=MemoryForgetting.RECALL_DEFAULT_MIGRATED
        assertEquals("新库建好就带着迁移标记","true",f.store.preference(marker,""))
        f.repo.saveMemoryRules(MemoryRules(proactiveRecall=false))
        // 平常用着就会有的显示回执、关系缓存和核心记忆挑选，也不能让全部备份停下。
        val turnId=ChatLedger(f.store).all().first{it.friendId=="a"}.id
        f.store.setPreference("chat_tool_display:$turnId","[]");f.store.setPreference("relationship-cache:a","head");f.store.setPreference("relationship-cache:a:feeling","{}");f.store.setPreference("memory-core-set:a","[]")
        val export=f.backup.export(null,false)
        assertEquals("全部备份不再因这个标记停下，并把它当全局设置带走","true",export.root["preferences"].asJsonObject[marker].asString)
        val other=fixture();other.backup.commit(other.backup.preview(pack(export),"REPLACE"),true)
        assertEquals("true",other.store.preference(marker,""));assertFalse("恢复后主动回想保持关闭",other.repo.memoryRules().first.proactiveRecall)
        // 没带标记的旧备份恢复后也补上标记，迁移不会再把主动回想打开。
        export.root["preferences"].asJsonObject.remove(marker)
        val old=fixture();old.store.writableDatabase.delete("preferences","key=?",arrayOf(marker))
        old.backup.commit(old.backup.preview(pack(export),"REPLACE"),true)
        assertEquals("true",old.store.preference(marker,""));assertFalse(old.repo.memoryRules().first.proactiveRecall)
    }
    private fun pills(turn:String):List<ChatToolActivity> {
        val saved=ChatToolActivity("pill-1",1,"嗯…我记住了","success","先写的一句",kind=ChatToolActivity.WRITE,tool="agent_memory_update",reasoningAt=3,reasoningKnown=true,operation="op-1",memoryId="memory-a")
        val read=ChatToolActivity("pill-2",1,"让我想一想","success",kind=ChatToolActivity.READ,tool="agent_recall_search")
        val running=ChatToolActivity("pill-3",1,"让我查一下聊天记录","running",kind=ChatToolActivity.READ,tool="agent_history_search")
        return listOf(saved,read,running).onEach{ChatToolJournal.put(f.store.writableDatabase,turn,it)}
    }
    @Test fun oldRoundToolPillsTravelWithTheBackupAndShowAfterRestore()=runBlocking{
        val turn=ChatLedger(f.store).all().first{it.friendId=="a"}
        val (saved,read,running)=pills(turn.id)
        // 全部备份、覆盖恢复：轮次和记忆都换了新编号，胶囊跟着轮次回来，记忆链接跟着记忆走。
        val other=fixture();other.backup.commit(other.backup.preview(pack(f.backup.export(null,false)),"REPLACE"),true)
        val id=other.store.friends().single{it.name=="合成甲"}.id
        val restored=ChatToolJournal.read(other.store.readableDatabase,ChatLedger(other.store).all().single{it.friendId==id}.id)
        val memory=other.repo.memories(id).single{it.text=="合成事实"}.id
        assertEquals(listOf(saved.copy(memoryId=memory),read,running.copy(state="unknown",detail="本轮已结束，操作结果未确认")),restored)
        assertTrue("恢复后已记下的胶囊照常能点开",restored.first().canOpenMemory)
        // 单个角色备份新增进来也一样带回。
        val third=fixture();val added=third.backup.commit(third.backup.preview(pack(f.backup.export("a")),"ADD")).single()
        assertEquals(3,ChatToolJournal.read(third.store.readableDatabase,ChatLedger(third.store).all().single{it.friendId==added}.id).size)
        // 关系缓存、核心挑选这些能重算的仍不进备份。
        f.store.setPreference("relationship-cache:a","head");f.store.setPreference("memory-core-set:a","[]")
        val keys=f.backup.export(null,false).root["roles"].asJsonArray.flatMap{it.asJsonObject["preferences"].asJsonObject.keySet()}
        assertTrue(ChatToolJournal.key(turn.id) in keys);assertTrue(keys.none{it.startsWith("relationship-cache:")||it.startsWith("memory-core-set:")})
    }
    @Test fun aTamperedToolPillIsRejectedBeforeRestoreWrites()=runBlocking{
        val turn=ChatLedger(f.store).all().first{it.friendId=="a"};pills(turn.id)
        val export=f.backup.export("a")
        export.root["roles"].asJsonArray[0].asJsonObject["preferences"].asJsonObject.addProperty(ChatToolJournal.key(turn.id),"""[{"id":"x","attempt":1,"title":"让我想一想","state":"running","extra":true}]""")
        val other=fixture();val before=other.store.friends()
        assertTrue(runCatching{other.backup.preview(pack(export),"ADD")}.isFailure)
        assertEquals(before,other.store.friends())
    }
}