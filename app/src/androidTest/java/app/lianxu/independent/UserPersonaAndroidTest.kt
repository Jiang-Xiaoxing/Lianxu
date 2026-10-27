package app.lianxu.independent

import android.content.ContentValues
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream

/** Isolated app-private synthetic fixtures only; no provider connection or user's database. */
class UserPersonaAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private data class Fixture(val name:String,val store:Store,val vault:ModelVault) {
        val repo=ProcessingRepository(store)
        val backup=BackupRepository(store,vault)
    }
    private val fixtures=mutableListOf<Fixture>()
    private lateinit var f:Fixture
    private val persona=UserPersona("合成用户","小星","正在学习插画，喜欢散步。")
    private suspend fun fixture():Fixture {
        val name="persona-${System.nanoTime()}";val store=Store(context,"$name.db")
        return Fixture(name,store,ModelVault(context,name,store)).also{fixtures+=it}
    }
    @Before fun setup()=runBlocking {f=fixture()}
    @After fun close(){fixtures.forEach{x->x.vault.list().forEach{x.vault.delete(it.id)};x.store.close();context.deleteDatabase("${x.name}.db");java.io.File(context.noBackupFilesDir,x.name).deleteRecursively()}}
    private suspend fun add(id:String="a",opening:String="") {
        f.store.addConfigured(Friend(id,"合成同伴","原简介","原自写要求"),RolePrompts(characterDefinition="成年合成角色",expression="自然简短",opening=opening))
    }
    private fun pack(e:BackupRepository.Export)=BackupArchive.read(ByteArrayOutputStream().also{BackupArchive.write(e.root,e.files,it)}.toByteArray().inputStream())
    private suspend fun turn(input:String="真实合成原话"):ChatTurn {
        val l=ChatLedger(f.store);val t=l.begin("a",input,"synthetic");assertTrue(l.finish(t.id,t.attempt,"这是完整的合成回复。"));return t
    }
    @Test fun legacyNameAndIndependentProfileRoundTripWithoutCreatingMemories()=runBlocking {
        f.store.setPreference("nickname","旧昵称");assertEquals("旧昵称",f.store.persona().name)
        f.store.savePersona(persona);assertEquals(persona,f.store.persona());assertEquals(persona.name,f.store.preference("nickname",""))
        f.store.savePersona(UserPersona(introduction="没有昵称的合成介绍"));assertEquals("",f.store.preference("nickname",""))
        assertEquals("没有昵称的合成介绍",f.store.persona().introduction);assertTrue(f.store.friends().isEmpty())
    }
    @Test fun openingSeedsOnceAndIsNotAChatSourceOrExample()=runBlocking {
        f.store.savePersona(persona);add(opening="你好，{{user}}，我是{{character}}。")
        assertEquals(listOf("你好，小星，我是合成同伴。"),f.store.messages().map{it.text});assertFalse(f.store.messages().single().mine)
        assertTrue(ChatLedger(f.store).all().isEmpty());assertTrue(f.repo.sources("a").isEmpty());assertTrue(f.repo.memories("a").isEmpty())
        f.store.saveConfigured("a","合成新名字"){it.copy(opening="改后的开场")}
        assertEquals(1,f.store.messages().size);assertEquals("你好，小星，我是合成同伴。",f.store.messages().single().text)
        assertEquals("原自写要求",f.store.friends().single().prompt)
    }
    @Test fun characterEditsPreserveExamplesAdvancedSettingsAndOtherRoles()=runBlocking {
        add();add("b");val example=DialogueExample("合成示例问题","合成示例回答")
        f.store.updateRolePrompts("a"){it.copy(examples=listOf(example),extra="附加要求",internalPrompt="内部提示",internalEnabled=true)}
        val before=f.store.preference(RolePromptStore.key("b"),"")
        f.store.saveConfigured("a","新名字"){it.copy(characterDefinition="新的角色设定",expression="新的说话方式",opening="可选开场")}
        val p=RolePromptStore.decode(f.store.preference(RolePromptStore.key("a"),""))
        assertEquals(listOf(example),p.examples);assertEquals("附加要求",p.extra);assertTrue(p.internalEnabled);assertEquals("内部提示",p.internalPrompt)
        assertEquals(before,f.store.preference(RolePromptStore.key("b"),""));assertEquals("新名字",f.store.friends().first{it.id=="a"}.name)
    }
    @Test fun changingProfileRejectsLateReplyAndDoesNotChangeOtherStoredSources()=runBlocking {
        add();f.store.savePersona(persona);val first=turn();val original=f.repo.sources("a")
        val l=ChatLedger(f.store);val pending=l.begin("a","第二条合成原话","synthetic");val before=l.context(pending.id)
        f.store.savePersona(persona.copy(introduction="已修改的自我介绍"))
        assertFalse(l.finish(pending.id,1,"旧人设迟到结果",expectedStamp=before.stamp));assertEquals(original,f.repo.sources("a"))
        assertEquals(TurnState.COMPLETED,l.all().single{it.id==first.id}.state)
    }
    @Test fun retiredHistoryDoesNotReactivateTheOpening()=runBlocking {
        add(opening="初次开场");val first=turn()
        f.store.writableDatabase.insertOrThrow("memory_retired",null,ContentValues().apply{put("friend_id","a");put("source_user_id",first.userMessageId);put("source_version",1);put("batch_id","synthetic-retired")})
        val l=ChatLedger(f.store);val t=l.begin("a","总结后的新原话","synthetic");val snapshot=l.context(t.id)
        assertTrue(snapshot.recent.isEmpty());assertFalse(snapshot.openingEligible)
    }
    @Test fun realRunnerReceivesFullUserProfileAndCharacterStyleWithoutExtraCalls()=runBlocking {
        add();f.store.savePersona(persona)
        val cfg=ModelConfig("synthetic","合成服务","custom","https://example.invalid/v1","fake-model",64000,4096)
        f.vault.save(cfg,"synthetic-key-only");f.vault.setPurpose(ModelPurpose.CHAT,cfg.id)
        val seen=CompletableDeferred<TextChatRequest>();val finished=CompletableDeferred<Unit>();var calls=0
        val client=object:TextChatClient {override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {calls++;seen.complete(request);return TextChatResult.Success("合成回复，不调用任何外部服务。")}}
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val memory=MemoryEngine(scope,f.repo,ModelAccess(f.vault),client,{})
            val relationship=RelationshipEngine(scope,f.repo,ModelAccess(f.vault),client,{})
            val runner=ConversationRunner(scope,f.store,f.vault,client,f.repo,memory,relationship,{if(ChatLedger(f.store).all().any{it.state==TurnState.COMPLETED})finished.complete(Unit)},{})
            assertTrue(runner.send("a","你好"){})
            val request=withTimeout(5000){seen.await()};withTimeout(5000){finished.await()}
            val fixed=request.messages.first().content;assertTrue(persona.introduction in fixed);assertTrue("小星" in fixed);assertTrue("自然简短" in fixed)
            assertTrue(request.messages.last().turnContext);assertEquals(1,calls);assertTrue(f.repo.memories("a").isEmpty())
            assertEquals("你好",f.repo.sources("a").single().user.text)
        }finally{scope.cancel()}
    }
    @Test fun fullBackupRestoresProfileWhileAddAndRoleBackupKeepRecipientsProfile()=runBlocking {
        f.store.savePersona(persona);add(opening="合成开场");val all=pack(f.backup.export())
        val replace=fixture();replace.backup.commit(replace.backup.preview(all,"REPLACE"),true)
        assertEquals(persona,replace.store.persona());assertEquals(listOf("合成开场"),replace.store.messages().map{it.text})
        val add=fixture();val own=UserPersona("接收方","自己的称呼","保留介绍");add.store.savePersona(own)
        add.backup.commit(add.backup.preview(all,"ADD"));assertEquals(own,add.store.persona())
        val role=pack(f.backup.export("a"));assertFalse(role.root["preferences"].asJsonObject.has(UserPersona.KEY))
        val roleTarget=fixture();roleTarget.store.savePersona(own);val id=roleTarget.backup.commit(roleTarget.backup.preview(role,"ADD")).single()
        assertEquals(own,roleTarget.store.persona());assertEquals("成年合成角色",RolePromptStore.decode(roleTarget.store.preference(RolePromptStore.key(id),"")).characterDefinition)
        assertEquals(1,roleTarget.store.messages().size)
    }
    @Test fun oldFullBackupWithoutPersonaStillUsesLegacyNickname()=runBlocking {
        f.store.setPreference("nickname","旧用户");add();val old=pack(f.backup.export());val other=fixture()
        other.store.savePersona(persona);other.backup.commit(other.backup.preview(old,"REPLACE"),true)
        assertEquals(UserPersona("旧用户"),other.store.persona())
    }
    @Test fun legacyRoleTemplatesReadOldFieldsAndNewTemplateSeedsOnlyItsOwnOpening()=runBlocking {
        f.store.savePersona(persona);add(opening="你好{{user}}");val archive=RoleArchiveRepository(f.store)
        val old=archive.export("a",false);old["prompts"].asJsonObject.remove("characterDefinition");old["prompts"].asJsonObject.remove("opening")
        val oldId=archive.commit(archive.preview(RoleArchive.validate(old.toString().toByteArray()),null))
        assertEquals("",RolePromptStore.decode(f.store.preference(RolePromptStore.key(oldId),"")).characterDefinition);assertTrue(f.store.messages().none{it.friendId==oldId})
        val newPack=RoleArchive.validate(archive.export("a",false).toString().toByteArray());val newId=archive.commit(archive.preview(newPack,null))
        assertEquals(listOf("你好小星"),f.store.messages().filter{it.friendId==newId}.map{it.text});assertTrue(f.repo.sources(newId).isEmpty())
    }
    @Test fun invalidProfileAndCharacterEditsLeaveExistingDataUntouched()=runBlocking {
        f.store.savePersona(persona);add();val before=f.backup.export().root.toString()
        assertTrue(runCatching{f.store.savePersona(persona.copy(address="换\n行"))}.isFailure)
        assertTrue(runCatching{f.store.saveConfigured("a","新的名字"){it.copy(characterDefinition="字".repeat(8001))}}.isFailure)
        assertEquals(before,f.backup.export().root.toString())
    }
    @Test fun exampleSelectionCannotAdoptAfterTheCharacterDefinitionChanges()=runBlocking {
        add();repeat(20){turn("这是一条合成原话第${it}轮。")}
        val examples=RoleExamplesRepository(f.store);val selected=examples.claim("a")!!
        assertEquals("成年合成角色",selected.prompts.definition(selected.friend))
        f.store.saveConfigured("a","合成同伴"){it.copy(characterDefinition="修改后的角色设定")}
        assertFalse(examples.complete("a",selected,selected.candidates.take(2)){true})
        assertEquals(ExamplesState.FAILED,examples.get("a")!!.state)
        assertFalse(RolePromptStore.decode(f.store.preference(RolePromptStore.key("a"),"")).examplesEnabled)
    }
    @Test fun relationshipRequestUsesTheCurrentFullCharacterDefinition()=runBlocking {
        add();val definition="角色新设定".repeat(1000)
        f.store.saveConfigured("a","合成同伴"){it.copy(characterDefinition=definition)};turn()
        val cfg=ModelConfig("synthetic","合成关系服务","custom","https://example.invalid/v1","fake-model",64000,4096)
        f.vault.save(cfg,"synthetic-key-only");f.vault.setPurpose(ModelPurpose.RELATIONSHIP,cfg.id)
        RelationshipRepository(f.repo).setEnabled("a",true,"BACKFILL",true)
        val seen=CompletableDeferred<TextChatRequest>();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val client=object:TextChatClient {override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {seen.complete(request);return TextChatResult.Failure(ChatFailure.NETWORK)}}
        try {
            val job=RelationshipEngine(scope,f.repo,ModelAccess(f.vault),client,{}).evaluate("a")!!
            val request=withTimeout(5000){seen.await()};job.join()
            val data=org.json.JSONObject(request.messages.last().content)
            assertEquals(definition,data.getString("background"));assertEquals(1,data.getJSONArray("pending_dialogue").length())
            assertFalse("原自写要求" in data.getString("background"))
        }finally{scope.cancel()}
    }
}
