package app.lianxu.independent

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** All vaults, roles and responses are synthetic. No real provider client is used. */
class RoleCreationPolishUiTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrument.targetContext
    private lateinit var namespace:String
    private lateinit var oldDb:String
    private lateinit var oldVault:String
    private lateinit var oldClient:()->TextChatClient
    private lateinit var app:AppState
    private lateinit var configState:ModelConfigState
    private lateinit var vault:ModelVault
    private var damagedSelections:ByteArray?=null
    private val calls=AtomicInteger()
    private val directoryCalls=AtomicInteger()
    private var directoryReply:CompletableDeferred<ConfigResult>?=null
    private var expectsDirectory=false
    private var testFailed=false
    private var expectedDirectoryKey="synthetic-not-a-real-credential"
    private val replies=ConcurrentLinkedQueue<CompletableDeferred<TextChatResult>>()
    private val gates=ConcurrentLinkedQueue<CompletableDeferred<TextChatResult>>()
    private val requests=ConcurrentLinkedQueue<TextChatRequest>()
    private val key="synthetic-not-a-real-credential"
    private val memoryKey="synthetic-memory-not-a-real-credential"
    private val visionKey="synthetic-vision-not-a-real-credential"
    private var scale by mutableFloatStateOf(1f)
    private val source=RoleCreationText("润色合成角色","喜欢看书； 话不多。","旧识","用短句。","","保持原设定。")
    private val polished get()=source.copy(definition="喜欢看书，话不多。")
    private fun config(id:String,name:String)=ModelConfig(id,name,"custom","https://example.invalid/v1","synthetic-chat-model",64000,2048)
    private val chat=config("polish-chat","合成聊天（仅测试）")
    private val memory=config("polish-memory","合成记忆（仅测试）")
    private val vision=config("polish-vision","合成识图（仅测试）")
    private fun pending()=CompletableDeferred<TextChatResult>().also{replies.add(it);gates.add(it)}
    private fun success(value:RoleCreationText=polished)=pending().apply{complete(TextChatResult.Success(Gson().toJson(value)))}

    @get:Rule(order=0)val fixture=object:TestWatcher(){
        override fun starting(description:Description){
            expectsDirectory=description.methodName.startsWith("lateConnectionResult")||description.methodName in setOf("savedProbeAndKeyRotationShareOneCommit","failedNewProbeWriteShowsUntestedImmediatelyOnReturn")
            namespace="role-polish-${System.nanoTime()}"
            oldDb=AppServices.databaseName;oldVault=AppServices.vaultNamespace;oldClient=AppServices.createTextClient
            AppServices.databaseName="$namespace.db";AppServices.vaultNamespace=namespace
            AppServices.createTextClient={object:TextChatClient{
                override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
                    assertTrue(config.id.startsWith("polish-")||config.name.startsWith("合成新聊天"));assertEquals(this@RoleCreationPolishUiTest.key,key)
                    calls.incrementAndGet();requests.add(request)
                    val gate=checkNotNull(replies.poll()){"No synthetic reply queued"}
                    return withContext(NonCancellable){gate.await()}
                }
            }}
            runBlocking{Store(context,"$namespace.db").use{store->
                LocalUpdateRepository(store).acknowledge(InstalledBuild.read(context).versionCode)
                val local=ModelVault(context,namespace,store)
                local.save(memory,memoryKey);local.setPurpose(ModelPurpose.MEMORY,memory.id)
                local.save(vision,visionKey);local.setPurpose(ModelPurpose.AUX_VISION,vision.id)
                if(description.methodName !in setOf("missingChatModelCanStillCreateFromOriginal","configurationReturnKeepsDraftAndOnlySelectsGlobalChat")){
                    local.save(chat,key);local.setPurpose(ModelPurpose.CHAT,chat.id)
                }
                if(description.methodName in setOf("damagedModelSelectionsStillAllowCreatingFromOriginal","trailingDamagedSelectionsAreNotMigratedOrAccepted","commentTailedSelectionsAreNotMigratedOrAccepted")){
                    val selections=File(context.noBackupFilesDir,"$namespace/purpose-selections")
                    damagedSelections=selections.readBytes()
                    selections.writeText(when(description.methodName){
                        "trailingDamagedSelectionsAreNotMigratedOrAccepted"->"{\"relationship\":\"old\",\"chat\":\"polish-chat\"}BROKEN"
                        "commentTailedSelectionsAreNotMigratedOrAccepted"->"{\"relationship\":\"old\",\"chat\":\"polish-chat\"}/*damaged*/"
                        else->"{damaged synthetic selections"
                    })
                }
            }}
        }
        override fun failed(error:Throwable,description:Description){testFailed=true}
        override fun finished(description:Description){
            if(::app.isInitialized)app.creationPolisher.cancel()
            gates.forEach{it.complete(TextChatResult.Failure(ChatFailure.NETWORK))}
            try{
                damagedSelections?.let{File(context.noBackupFilesDir,"$namespace/purpose-selections").writeBytes(it)}
                if(::vault.isInitialized)vault.list().forEach{vault.delete(it.id)}
                if(::app.isInitialized)app.processing.store.close()
                context.deleteDatabase("$namespace.db");File(context.noBackupFilesDir,namespace).deleteRecursively()
            }finally{AppServices.databaseName=oldDb;AppServices.vaultNamespace=oldVault;AppServices.createTextClient=oldClient}
            if(!testFailed)assertEquals("Only the delayed synthetic probe may request a directory",if(expectsDirectory)1 else 0,directoryCalls.get())
        }
    }
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>(effectContext=object:MotionDurationScale{override val scaleFactor get()=scale})
    @Before fun ready(){
        ui.activityRule.scenario.onActivity{activity->
            app=ViewModelProvider(activity)[AppState::class.java]
            configState=ViewModelProvider(activity,object:ViewModelProvider.Factory{
                @Suppress("UNCHECKED_CAST")override fun <T:ViewModel> create(type:Class<T>):T=ModelConfigState(activity.application,object:ModelDirectoryClient{
                    override suspend fun request(config:ModelConfig,key:String,fetchModels:Boolean):ConfigResult{
                        directoryCalls.incrementAndGet();assertEquals(expectedDirectoryKey,key)
                        return checkNotNull(directoryReply){"No network allowed"}.await()
                    }
                },namespace) as T
            })[ModelConfigState::class.java]
        }
        ui.waitUntil(15000){app.ready};ui.waitForIdle();vault=ModelVault(context,namespace,app.processing.store)
    }
    private fun editable(){ui.waitUntil(10000){ui.onAllNodes(hasTestTag("create-card") and hasStateDescription("可填写")).fetchSemanticsNodes().isNotEmpty()}}
    private fun next(){editable();ui.onNodeWithTag("create-next").performScrollTo().assertIsEnabled().performClick();ui.waitForIdle();editable()}
    private fun model(){
        ui.onNodeWithTag("first-encounter").performClick();ui.onNodeWithTag("first-encounter-create").performClick()
        editable()
        ui.onNodeWithTag("friend-name").performTextInput(source.name);next()
        ui.onNodeWithTag("friend-bio").performTextInput(source.definition);next()
        ui.onNodeWithTag("friend-relationship").performTextInput(source.relationship);next()
        ui.onNodeWithTag("friend-expression").performTextInput(source.expression)
        ui.onNodeWithTag("friend-prompt").performScrollTo().performTextInput(source.extra);next()
        ui.onNodeWithTag("create-step-model").assertExists();ui.waitUntil(10000){app.creationPolisher.checked}
    }
    private fun polish(){ui.onNodeWithTag("create-polish").performScrollTo().performClick();ui.waitUntil(15000){ui.onAllNodesWithTag("polish-definition").fetchSemanticsNodes().isNotEmpty()};ui.onNodeWithTag("polish-definition").assertExists()}
    private fun original(){ui.onNodeWithTag("create-use-original").performScrollTo().performClick();ui.onNodeWithTag("create-step-confirm").assertExists()}
    private fun create(){ui.onNodeWithTag("create-friend").performScrollTo().performClick();ui.waitUntil(15000){app.page=="chat"&&!app.busy}}
    private fun waitForSavedSyntheticKey(value:String){
        ui.onNodeWithTag("config-key-reveal").performScrollTo().performClick()
        ui.waitUntil(10000){ui.onNodeWithTag("config-key").fetchSemanticsNode().config[SemanticsProperties.EditableText].text==value}
    }
    private fun otherPurposes(){
        assertEquals(memory.id,vault.purposeId(ModelPurpose.MEMORY));assertEquals(vision.id,vault.purposeId(ModelPurpose.AUX_VISION))
        assertEquals(memory,vault.list().single{it.id==memory.id});assertEquals(vision,vault.list().single{it.id==vision.id})
        assertEquals(memoryKey,vault.secret(memory.id));assertEquals(visionKey,vault.secret(vision.id))
    }
    private fun shot(label:String){
        ui.waitForIdle();instrument.waitForIdleSync();SystemClock.sleep(150)
        val dir=File(context.getExternalFilesDir(null),"evidence-role-polish").apply{mkdirs()}
        val image=instrument.uiAutomation.takeScreenshot()
        File(dir,"$label.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }

    @Test fun existingGlobalChatIsReusedAndEditedPreviewCreatesOnlyAfterConfirmation(){
        model();assertEquals(0,calls.get());assertTrue(app.friends.isEmpty());shot("global-chat")
        success();polish();assertTrue(app.friends.isEmpty());shot("polished-editable")
        val edited="喜欢看书，话不多。会认真听我说完。"
        ui.onNodeWithTag("polish-definition").performTextReplacement(edited)
        ui.onNodeWithTag("create-select-original").performScrollTo().performClick();ui.onNodeWithText(source.definition).assertExists()
        ui.onNodeWithTag("create-select-polished").performScrollTo().performClick();ui.onNodeWithTag("polish-definition").assertTextContains(edited)
        create();assertEquals(1,app.friends.size)
        val p=runBlocking{app.rolePrompts(app.friends.single().id)}
        assertEquals(edited,p.characterDefinition);assertEquals(source.relationship,p.initialRelationship);assertEquals(source.extra,p.extra)
        assertEquals(1,calls.get());assertEquals(chat.id,vault.purposeId(ModelPurpose.CHAT));assertEquals(3,vault.list().size);otherPurposes()
        assertEquals(2,requests.single().messages.size);assertTrue(requests.single().tools.isEmpty())
        assertFalse(requests.single().messages.any{it.content.contains(key)})
    }
    @Test fun originalRemainsAnIndependentChoiceAfterPolishing(){
        model();success();polish();ui.onNodeWithTag("create-select-original").performScrollTo().performClick();create()
        assertEquals(source.definition,runBlocking{app.rolePrompts(app.friends.single().id)}.characterDefinition);assertEquals(1,calls.get());otherPurposes()
    }
    @Test fun missingChatModelCanStillCreateFromOriginal(){
        model();ui.onNodeWithText("尚未配置全局聊天模型").assertExists();original();create()
        assertEquals(source.definition,runBlocking{app.rolePrompts(app.friends.single().id)}.characterDefinition);assertEquals(0,calls.get());otherPurposes()
    }
    @Test fun configurationReturnKeepsDraftAndOnlySelectsGlobalChat(){
        model();ui.onNodeWithTag("create-model-config").performScrollTo().performClick()
        ui.onNodeWithTag("config-chat-context").assertExists()
        ui.onNodeWithTag("config-add").performScrollTo().performClick()
        ui.onNodeWithTag("provider-search").performTextInput("自定义");ui.onNodeWithTag("provider-custom").performClick()
        ui.onNodeWithTag("config-name").performTextReplacement("合成新聊天（仅测试）")
        ui.onNodeWithTag("config-model").performTextInput("synthetic-chat-model")
        ui.onNodeWithTag("config-url").performScrollTo().performTextInput("https://example.invalid/v1")
        ui.onNodeWithTag("config-key").performScrollTo().performTextInput(key)
        ui.activityRule.scenario.onActivity{activity->
            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(activity.window.decorView.windowToken,0)
        }
        ui.onNodeWithTag("config-save").performClick();ui.waitUntil(10000){!configState.saving&&!configState.purposes[ModelPurpose.CHAT].isNullOrBlank()}
        val chosen=requireNotNull(configState.purposes[ModelPurpose.CHAT]);assertNotEquals(memory.id,chosen);assertNotEquals(vision.id,chosen);otherPurposes()
        ui.activityRule.scenario.recreate();ui.onNodeWithTag("config-back").performClick()
        ui.onNodeWithTag("create-step-model").assertExists();ui.waitUntil(10000){app.creationPolisher.modelLabel?.contains("合成新聊天")==true}
        ui.onNodeWithTag("create-back").performClick();ui.onNodeWithTag("friend-expression").assertTextContains(source.expression)
        ui.onNodeWithTag("friend-prompt").performScrollTo().assertTextContains(source.extra)
        ui.onNodeWithTag("create-back").performClick();ui.onNodeWithTag("friend-relationship").assertTextContains(source.relationship)
        ui.onNodeWithTag("create-back").performClick();ui.onNodeWithTag("friend-bio").assertTextContains(source.definition)
        next();next();next();success();polish()
        ui.onNodeWithTag("create-select-original").performScrollTo().performClick();ui.onNodeWithText(source.definition).assertExists()
        assertEquals(chosen,vault.purposeId(ModelPurpose.CHAT));assertEquals(3,vault.list().size);assertTrue(vault.hasKey(chosen));otherPurposes()
        val encrypted=File(context.noBackupFilesDir,namespace).walkTopDown().filter{it.isFile&&it.extension=="enc"}.toList()
        assertEquals(3,encrypted.size);assertTrue(encrypted.all{file->!String(file.readBytes(),Charsets.UTF_8).contains(key)})
        assertTrue(app.friends.isEmpty());shot("configured-return-original")
    }
    @Test fun cancelAndOriginalContinuationIgnoreALateResponse(){
        model();val gate=pending()
        ui.onNodeWithTag("create-polish").performScrollTo().performClick();ui.waitUntil(10000){calls.get()==1}
        ui.onNodeWithTag("create-polish-cancel").performScrollTo().performClick();original()
        gate.complete(TextChatResult.Success(Gson().toJson(polished)));SystemClock.sleep(150);ui.waitForIdle()
        ui.onNodeWithTag("polish-definition").assertDoesNotExist();ui.onNodeWithText(source.definition).assertExists();create()
        assertEquals(source.definition,runBlocking{app.rolePrompts(app.friends.single().id)}.characterDefinition);assertEquals(1,calls.get());otherPurposes()
    }
    @Test fun lateOldRequestDoesNotOverwriteNewOriginalOrManualPreviewEdits(){
        model();val old=pending();ui.onNodeWithTag("create-polish").performScrollTo().performClick();ui.waitUntil(10000){calls.get()==1}
        ui.onNodeWithTag("create-back").performClick();ui.onNodeWithTag("friend-expression").performTextReplacement("新的原文表达。")
        next();success(polished.copy(expression="新的原文表达。"));polish()
        ui.onNodeWithTag("polish-definition").performTextReplacement("用户局部修改后的内容。")
        old.complete(TextChatResult.Success(Gson().toJson(polished)));SystemClock.sleep(150);ui.waitForIdle()
        ui.onNodeWithTag("polish-definition").assertTextContains("用户局部修改后的内容。")
        ui.onNodeWithTag("polish-expression").performScrollTo().assertTextContains("新的原文表达。")
        assertTrue(app.friends.isEmpty());assertEquals(2,calls.get());otherPurposes()
    }
    @Test fun failedRequestCanRetryAndNeverCreatesAutomatically(){
        model();pending().complete(TextChatResult.Failure(ChatFailure.NETWORK,detail="synthetic-private-detail"))
        ui.onNodeWithTag("create-polish").performScrollTo().performClick();ui.waitUntil(10000){!app.creationPolisher.running&&app.creationPolisher.message.isNotBlank()}
        ui.onNodeWithTag("create-polish-message").assertTextContains("原文",substring=true);assertFalse(app.creationPolisher.message.contains("synthetic-private-detail"));assertTrue(app.friends.isEmpty())
        success();polish();assertTrue(app.friends.isEmpty());assertEquals(2,calls.get());shot("retry-preview")
    }
    @Test fun changedGlobalChatRejectsAnInFlightOldModel(){
        model();val gate=pending();ui.onNodeWithTag("create-polish").performScrollTo().performClick();ui.waitUntil(10000){calls.get()==1}
        val alternate=config("polish-chat-alternate","合成替代聊天（仅测试）")
        vault.save(alternate,key);vault.setPurpose(ModelPurpose.CHAT,alternate.id)
        gate.complete(TextChatResult.Success(Gson().toJson(polished)))
        ui.waitUntil(10000){!app.creationPolisher.running&&app.creationPolisher.message.contains("已变化")}
        ui.onNodeWithTag("polish-definition").assertDoesNotExist();original();assertEquals(1,calls.get());otherPurposes()
    }
    @Test fun selectingAwayAndBackCannotReviveAnOldResponse(){
        model();val gate=pending();val originalStamp=vault.selectionStamp(ModelPurpose.CHAT)
        ui.onNodeWithTag("create-polish").performScrollTo().performClick();ui.waitUntil(10000){calls.get()==1}
        val alternate=config("polish-chat-alternate","合成替代聊天（仅测试）")
        vault.save(alternate,key);vault.setPurpose(ModelPurpose.CHAT,alternate.id);vault.setPurpose(ModelPurpose.CHAT,chat.id)
        assertNotEquals(originalStamp,vault.selectionStamp(ModelPurpose.CHAT))
        gate.complete(TextChatResult.Success(Gson().toJson(polished)))
        ui.waitUntil(10000){!app.creationPolisher.running&&app.creationPolisher.message.contains("已变化")}
        ui.onNodeWithTag("polish-definition").assertDoesNotExist();original();assertEquals(1,calls.get());otherPurposes()
    }
    @Test fun aStaleEditorCannotReadOrSendARotatedDifferentTargetKey(){
        ui.waitUntil(10000){configState.configs.any{it.id==chat.id}}
        val stale=configState.configs.single{it.id==chat.id}
        for(updated in listOf(chat.copy(baseUrl="https://different.example.invalid/v1"),chat.copy(providerId="deepseek"))){
            vault.save(updated,"synthetic-rotated-not-a-real-credential")
            assertEquals("",runBlocking{configState.savedKey(stale)})
            ui.runOnIdle{configState.request(stale,"",false)}
            ui.waitUntil(10000){!configState.testing&&configState.probe.phase==ProbePhase.FAILED}
            assertEquals(0,directoryCalls.get());assertEquals(0,calls.get());otherPurposes()
        }
    }
    @Test fun changingOnlyOtherPurposeDoesNotInvalidateGlobalChat(){
        val before=vault.selectionStamp(ModelPurpose.CHAT)
        vault.setPurpose(ModelPurpose.MEMORY,memory.id);vault.setPurpose(ModelPurpose.AUX_VISION,vision.id)
        assertEquals(before,vault.selectionStamp(ModelPurpose.CHAT));otherPurposes()
        model();success();polish();assertEquals(1,calls.get())
    }
    @Test fun staleEditorSaveKeepsTheCurrentSameTargetRotatedKey(){
        ui.waitUntil(10000){configState.configs.any{it.id==chat.id}}
        val stale=configState.configs.single{it.id==chat.id};val rotated="synthetic-current-rotated-credential"
        vault.save(chat,rotated)
        var saved=false
        ui.runOnIdle{configState.saveChatFromCreation(stale.copy(name="合成改名（仅测试）"),""){saved=true}}
        ui.waitUntil(10000){!configState.saving&&saved}
        assertEquals(rotated,vault.secret(chat.id));assertEquals(chat.id,vault.purposeId(ModelPurpose.CHAT));otherPurposes()
        assertEquals(0,calls.get())
    }
    @Test fun staleExplicitKeyEditCannotOverwriteANewerKey(){
        ui.waitUntil(10000){configState.configs.any{it.id==chat.id}}
        val stale=configState.configs.single{it.id==chat.id};val revision=configState.configRevisions[chat.id]
        val newer="synthetic-newer-credential-not-real";vault.save(chat,newer)
        ui.runOnIdle{configState.saveChatFromCreation(stale,"synthetic-older-editor-key",stale,revision){error("Must not overwrite")}}
        ui.waitUntil(10000){!configState.saving&&configState.status.contains("别处更改")}
        assertTrue(vault.secret(chat.id)==newer);assertEquals(chat,vault.list().single{it.id==chat.id});otherPurposes();assertEquals(0,calls.get())
    }
    @Test fun staleEditorCanReopenAndSaveAnExplicitNewKey(){
        model();ui.onNodeWithTag("create-model-config").performScrollTo().performClick()
        ui.onNodeWithTag("config-overview").performScrollToNode(hasTestTag("config-edit-${chat.id}"))
        ui.onNodeWithTag("config-edit-${chat.id}").performClick()
        waitForSavedSyntheticKey(key)
        val newer="synthetic-newer-external-key";vault.save(chat,newer)
        ui.onNodeWithTag("config-key").performScrollTo().performTextReplacement("synthetic-stale-key")
        ui.onNodeWithTag("config-save").performClick()
        ui.waitUntil(10000){!configState.saving&&configState.status.contains("别处更改")}
        assertEquals(newer,vault.secret(chat.id))
        ui.onNodeWithTag("config-back").performClick()
        ui.onNodeWithTag("config-overview").performScrollToNode(hasTestTag("config-edit-${chat.id}"))
        ui.onNodeWithTag("config-edit-${chat.id}").performClick()
        waitForSavedSyntheticKey(newer)
        ui.onNodeWithTag("config-key").performScrollTo().performTextReplacement("synthetic-reopened-key")
        ui.onNodeWithTag("config-save").performClick()
        ui.waitUntil(10000){!configState.saving&&configState.status.startsWith("已保存")}
        assertEquals("synthetic-reopened-key",vault.secret(chat.id));otherPurposes();assertEquals(0,calls.get())
    }
    private fun delayedConnectionAfterRotation(keepNewerRecord:Boolean){
        ui.waitUntil(10000){configState.configs.any{it.id==chat.id}}
        val gate=CompletableDeferred<ConfigResult>();directoryReply=gate
        ui.runOnIdle{configState.request(chat,"",false)};ui.waitUntil(10000){directoryCalls.get()==1}
        vault.save(chat,"synthetic-rotated-during-probe")
        val statuses=ModelStatusStore(app.processing.store,vault.statusNamespace)
        val newer=ModelTestRecord(1000L,chat.baseUrl,chat.modelId,false,"synthetic-newer-key-result")
        runBlocking{if(keepNewerRecord)statuses.saveTest(chat.id,newer)else statuses.clearTest(chat.id)}
        gate.complete(ConfigResult.Success())
        ui.waitUntil(10000){!configState.testing&&configState.status.contains("结果未采用")}
        assertEquals(ProbePhase.UNTESTED,configState.probe.phase)
        assertFalse(configState.connections.containsKey(chat.id));assertFalse(configState.tests.containsKey(chat.id))
        assertEquals(if(keepNewerRecord)newer else null,runBlocking{statuses.test(chat.id)})
        assertEquals("synthetic-rotated-during-probe",vault.secret(chat.id));otherPurposes();assertEquals(0,calls.get())
    }
    @Test fun lateConnectionResultCannotMarkRotatedKeyTested(){delayedConnectionAfterRotation(false)}
    @Test fun lateConnectionResultKeepsANewerKeysTestRecord(){delayedConnectionAfterRotation(true)}
    @Test fun lateEmbeddingResultCannotMarkRotatedKeyTested(){
        ui.waitUntil(10000){configState.configs.any{it.id==chat.id}}
        val started=CompletableDeferred<Unit>();val response=CompletableDeferred<List<DoubleArray>>()
        configState.embeddingClient=object:MemoryEmbeddingClient{
            override suspend fun embed(credential:ModelCredential,texts:List<String>,timeout:Int):List<DoubleArray>{
                assertEquals(key,credential.key);started.complete(Unit);return response.await()
            }
        }
        ui.runOnIdle{configState.testEmbedding(chat,"")};ui.waitUntil(10000){started.isCompleted}
        vault.save(chat,"synthetic-new-embedding-key")
        response.complete(listOf(doubleArrayOf(1.0,0.0)))
        ui.waitUntil(10000){!configState.testing&&configState.status.contains("结果未采用")}
        assertEquals(ProbePhase.UNTESTED,configState.probe.phase);assertFalse(configState.connections.containsKey(chat.id))
        assertNull(runBlocking{ModelStatusStore(app.processing.store,vault.statusNamespace).test(chat.id)});otherPurposes()
    }
    @Test fun embeddingStatusWriteFailureDoesNotCrashOrRetryTheProvider(){
        ui.waitUntil(10000){configState.configs.any{it.id==chat.id}}
        var requests=0
        configState.embeddingClient=object:MemoryEmbeddingClient{
            override suspend fun embed(credential:ModelCredential,texts:List<String>,timeout:Int):List<DoubleArray>{requests++;return listOf(doubleArrayOf(1.0))}
        }
        app.processing.store.writableDatabase.execSQL("CREATE TRIGGER synthetic_status_failure BEFORE INSERT ON preferences WHEN NEW.key LIKE 'model-status:%:test:%' BEGIN SELECT RAISE(FAIL, 'synthetic storage failure'); END")
        ui.runOnIdle{configState.testEmbedding(chat,"")}
        ui.waitUntil(10000){!configState.testing&&configState.status.contains("无法核对或保存测试记录")}
        assertEquals(1,requests);assertEquals(ProbePhase.UNTESTED,configState.probe.phase)
        ui.runOnIdle{configState.clearResult()};assertEquals("",configState.status);assertFalse(configState.busy)
        otherPurposes()
    }
    private fun atomicStatusCommit(keepProbe:Boolean){
        ui.waitUntil(10000){configState.configs.any{it.id==chat.id}}
        val draft=chat.copy(name="合成并发保存（仅测试）")
        if(keepProbe){
            directoryReply=CompletableDeferred<ConfigResult>().apply{complete(ConfigResult.Success())}
            ui.runOnIdle{configState.request(draft,key,false)}
            ui.waitUntil(10000){!configState.testing&&configState.probe.phase==ProbePhase.REACHABLE}
        }
        val statuses=ModelStatusStore(app.processing.store,vault.statusNamespace)
        val newer=ModelTestRecord(2000L,draft.baseUrl,draft.modelId,true,"synthetic-new-key-test")
        val record=File(context.noBackupFilesDir,"$namespace/${chat.id}.enc");val before=record.readBytes()
        val blocker=android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("$namespace.db").absolutePath,null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
        val started=java.util.concurrent.CountDownLatch(1);val entered=CompletableDeferred<Unit>();val rotated=CompletableDeferred<Unit>()
        val saved=java.util.concurrent.atomic.AtomicBoolean(false)
        var writer:Thread?=null
        blocker.beginTransactionNonExclusive()
        try{
            ui.runOnIdle{configState.save(draft,if(keepProbe)key else "synthetic-key-before-newer-save"){saved.set(true)}}
            if(keepProbe)ui.waitUntil(10000){runCatching{!before.contentEquals(record.readBytes())}.getOrDefault(false)}
            else ui.waitUntil(10000){Thread.getAllStackTraces().values.any{frames->frames.any{it.className==ModelStatusStore::class.java.name&&it.methodName.startsWith("clearTestNow")}}}
            writer=Thread{
                started.countDown()
                try{AppMutationGate.write{
                    entered.complete(Unit)
                    vault.save(draft,"synthetic-concurrent-new-key")
                    if(keepProbe)statuses.clearTestNow(chat.id)else statuses.saveTestNow(chat.id,newer)
                };rotated.complete(Unit)}catch(e:Throwable){rotated.completeExceptionally(e)}
            }.apply{start()}
            assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS));SystemClock.sleep(200)
            assertFalse("The second writer must not enter the configuration gate before the status commits",entered.isCompleted)
        }finally{blocker.endTransaction();blocker.close()}
        writer?.join(10000);runBlocking{withTimeout(10000){rotated.await()}}
        ui.waitUntil(10000){!configState.saving&&saved.get()}
        assertEquals("synthetic-concurrent-new-key",vault.secret(chat.id))
        assertEquals(if(keepProbe)null else newer,runBlocking{statuses.test(chat.id)})
        otherPurposes()
    }
    @Test fun savedProbeAndKeyRotationShareOneCommit(){atomicStatusCommit(true)}
    @Test fun clearingOldProbeCannotEraseANewerKeysResult(){atomicStatusCommit(false)}
    @Test fun failedOldStatusInvalidationLeavesTheOldKeyInForce(){
        ui.waitUntil(10000){configState.configs.any{it.id==chat.id}}
        val statuses=ModelStatusStore(app.processing.store,vault.statusNamespace)
        val original=ModelTestRecord(3000L,chat.baseUrl,chat.modelId,true,"")
        runBlocking{statuses.saveTest(chat.id,original)}
        app.processing.store.writableDatabase.execSQL("CREATE TRIGGER synthetic_status_delete_failure BEFORE DELETE ON preferences WHEN OLD.key LIKE 'model-status:%:test:%' BEGIN SELECT RAISE(FAIL, 'synthetic delete failure'); END")
        ui.runOnIdle{configState.save(chat,"synthetic-must-not-become-current-key"){error("Save must be rejected")}}
        ui.waitUntil(10000){!configState.saving&&configState.statusError}
        assertEquals(key,vault.secret(chat.id));assertEquals(original,runBlocking{statuses.test(chat.id)})
        ui.runOnIdle{configState.refresh()};ui.waitUntil(10000){configState.tests[chat.id]==original}
        assertEquals(key,vault.secret(chat.id));otherPurposes()
    }
    @Test fun failedNewProbeWriteShowsUntestedImmediatelyOnReturn(){
        model();ui.onNodeWithTag("create-model-config").performScrollTo().performClick()
        val statuses=ModelStatusStore(app.processing.store,vault.statusNamespace)
        val old=ModelTestRecord(4000L,chat.baseUrl,chat.modelId,true,"")
        runBlocking{statuses.saveTest(chat.id,old)};ui.runOnIdle{configState.refresh()}
        ui.waitUntil(10000){configState.tests[chat.id]==old}
        ui.onNodeWithTag("config-overview").performScrollToNode(hasTestTag("config-edit-${chat.id}"))
        ui.onNodeWithTag("config-edit-${chat.id}").performClick()
        waitForSavedSyntheticKey(key)
        expectedDirectoryKey="synthetic-tested-new-key"
        ui.onNodeWithTag("config-key").performScrollTo().performTextReplacement(expectedDirectoryKey)
        directoryReply=CompletableDeferred<ConfigResult>().apply{complete(ConfigResult.Success())}
        ui.runOnIdle{configState.request(chat,expectedDirectoryKey,false)}
        ui.waitUntil(10000){!configState.testing&&configState.probe.phase==ProbePhase.REACHABLE}
        app.processing.store.writableDatabase.execSQL("CREATE TRIGGER synthetic_pending_failure BEFORE INSERT ON preferences WHEN NEW.key LIKE 'model-status:%:test:%' BEGIN SELECT RAISE(FAIL, 'synthetic pending failure'); END")
        ui.onNodeWithTag("config-save").performClick()
        // Let a separately queued overview refresh race the partial save.
        ui.runOnIdle{configState.refresh()}
        ui.waitUntil(10000){!configState.saving&&configState.statusError}
        runBlocking{withTimeout(10000){configState.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll()}}
        assertEquals(expectedDirectoryKey,vault.secret(chat.id));assertNull(runBlocking{statuses.test(chat.id)})
        ui.onNodeWithTag("config-back").performClick()
        ui.onNodeWithTag("config-overview").performScrollToNode(hasTestTag("config-edit-${chat.id}"))
        ui.onNodeWithTag("config-probe-${chat.id}",useUnmergedTree=true).assertTextEquals("未测试")
        otherPurposes()
    }
    @Test fun anOpenOverviewObservesAnotherEditorsKeyRotation(){
        model();ui.onNodeWithTag("create-model-config").performScrollTo().performClick()
        val statuses=ModelStatusStore(app.processing.store,vault.statusNamespace)
        val old=ModelTestRecord(5000L,chat.baseUrl,chat.modelId,true,"")
        runBlocking{statuses.saveTest(chat.id,old)};ui.runOnIdle{configState.refresh()}
        ui.waitUntil(10000){configState.tests[chat.id]==old}
        ui.onNodeWithTag("config-overview").performScrollToNode(hasTestTag("config-edit-${chat.id}"))
        ui.onNodeWithTag("config-probe-${chat.id}",useUnmergedTree=true).assertTextContains("成功",substring=true)
        val holder=androidx.lifecycle.ViewModelStore();var saved=false
        try{
            val other=ViewModelProvider(holder,object:ViewModelProvider.Factory{
                @Suppress("UNCHECKED_CAST")override fun <T:ViewModel> create(type:Class<T>):T=ModelConfigState(ui.activity.application,object:ModelDirectoryClient{
                    override suspend fun request(config:ModelConfig,key:String,fetchModels:Boolean):ConfigResult=error("No network allowed")
                },namespace) as T
            })[ModelConfigState::class.java]
            ui.waitUntil(10000){other.configs.any{it.id==chat.id}}
            ui.runOnIdle{other.save(chat,"synthetic-other-window-new-key"){saved=true}}
            ui.waitUntil(10000){saved&&!other.saving&&configState.tests[chat.id]==null}
            ui.onNodeWithTag("config-probe-${chat.id}",useUnmergedTree=true).assertTextEquals("未测试")
            assertEquals("synthetic-other-window-new-key",vault.secret(chat.id));otherPurposes()
        }finally{ui.runOnIdle{holder.clear()}}
    }
    @Test fun damagedModelSelectionsStillAllowCreatingFromOriginal(){
        model();assertTrue(app.creationPolisher.modelReadFailed)
        ui.onNodeWithText("全局模型配置暂时无法读取").assertExists()
        ui.onNodeWithText("尚未配置全局聊天模型").assertDoesNotExist()
        ui.onNodeWithTag("create-polish").assertDoesNotExist()
        original();create();assertEquals(1,app.friends.size);assertEquals(0,calls.get())
        assertEquals("{damaged synthetic selections",File(context.noBackupFilesDir,"$namespace/purpose-selections").readText())
    }
    @Test fun staleEditorCannotOverwriteANewerGlobalModel(){
        ui.waitUntil(10000){configState.configs.any{it.id==chat.id}}
        val stale=configState.configs.single{it.id==chat.id};val changed=chat.copy(modelId="synthetic-newer-model")
        vault.save(changed,"synthetic-newer-model-credential")
        ui.runOnIdle{configState.saveChatFromCreation(stale.copy(name="合成只改名"),"",stale){error("Must not overwrite")}}
        ui.waitUntil(10000){!configState.saving&&configState.status.contains("别处更改")}
        assertEquals(changed,vault.list().single{it.id==chat.id});assertEquals("synthetic-newer-model-credential",vault.secret(chat.id));otherPurposes()
        assertEquals(0,calls.get())
    }
    @Test fun trailingDamagedSelectionsAreNotMigratedOrAccepted(){
        model();assertTrue(app.creationPolisher.modelReadFailed)
        val file=File(context.noBackupFilesDir,"$namespace/purpose-selections");val before=file.readBytes()
        assertTrue(runCatching{vault.purposeId(ModelPurpose.CHAT)}.isFailure)
        assertArrayEquals(before,file.readBytes());original();create();assertEquals(0,calls.get())
        assertEquals("{\"relationship\":\"old\",\"chat\":\"polish-chat\"}BROKEN",file.readText())
    }
    @Test fun commentTailedSelectionsAreNotMigratedOrAccepted(){
        model();assertTrue(app.creationPolisher.modelReadFailed)
        val file=File(context.noBackupFilesDir,"$namespace/purpose-selections");val before=file.readBytes()
        assertTrue(runCatching{vault.purposeId(ModelPurpose.CHAT)}.isFailure)
        assertArrayEquals(before,file.readBytes());original();create();assertEquals(0,calls.get())
        assertEquals("{\"relationship\":\"old\",\"chat\":\"polish-chat\"}/*damaged*/",file.readText())
    }
    @Test fun guideCannotEditSharedConfigsOrOtherPurposeSelections(){
        model();vault.setPurpose(ModelPurpose.MEMORY,chat.id)
        ui.onNodeWithTag("create-model-config").performScrollTo().performClick()
        ui.waitUntil(10000){configState.purposes[ModelPurpose.MEMORY]==chat.id}
        ui.onNodeWithTag("config-overview").performScrollToNode(hasTestTag("config-edit-${chat.id}"))
        ui.onNodeWithTag("config-edit-${chat.id}").assertIsNotEnabled()
        ui.onNodeWithTag("config-add-embedding").assertDoesNotExist()
        ui.onNodeWithTag("config-tab-purposes").performClick()
        ui.onNodeWithTag("purpose-select-chat").assertExists()
        ui.onNodeWithTag("purpose-select-memory").assertDoesNotExist();ui.onNodeWithTag("purpose-select-aux-vision").assertDoesNotExist()
        ui.runOnIdle{configState.saveChatFromCreation(chat.copy(modelId="must-not-change"),key){error("Protected config must not save")}}
        ui.waitUntil(10000){!configState.saving&&configState.status.contains("其他用途")}
        assertEquals(chat,vault.list().single{it.id==chat.id});assertEquals(chat.id,vault.purposeId(ModelPurpose.MEMORY))
        assertEquals(vision.id,vault.purposeId(ModelPurpose.AUX_VISION));assertEquals(0,calls.get())
    }
    @Test fun aChangedDisplayModelDoesNotReceiveARequestUntilReconfirmed(){
        model();val alternate=config("polish-chat-b","合成新选择（仅测试）")
        vault.save(alternate,key);vault.setPurpose(ModelPurpose.CHAT,alternate.id)
        ui.onNodeWithTag("create-polish").performScrollTo().performClick()
        ui.waitUntil(10000){!app.creationPolisher.running&&app.creationPolisher.message.contains("没有发送")}
        assertEquals(0,calls.get());assertTrue(app.friends.isEmpty());otherPurposes()
        ui.waitUntil(10000){app.creationPolisher.checked&&app.creationPolisher.modelLabel?.contains("合成新选择")==true}
        success();polish();assertEquals(1,calls.get())
    }
    @Test fun recreationKeepsEditedPreviewAndOriginalWithoutASecondRequest(){
        model();success();polish();ui.onNodeWithTag("polish-definition").performTextReplacement("重建前的自由编辑。")
        ui.activityRule.scenario.recreate();ui.onNodeWithTag("polish-definition").assertTextContains("重建前的自由编辑。")
        ui.onNodeWithTag("create-select-original").performScrollTo().performClick();ui.onNodeWithText(source.definition).assertExists()
        ui.onNodeWithTag("create-select-polished").performScrollTo().performClick();ui.onNodeWithTag("polish-definition").assertTextContains("重建前的自由编辑。")
        assertEquals(1,calls.get());assertTrue(app.friends.isEmpty());otherPurposes()
    }
    @Test fun reducedMotionDarkSmallPreviewRemainsEditableAndReachable(){
        var local by mutableStateOf(RoleCreationDraft(step=RoleCreationStep.CONFIRM,name=source.name,definition=source.definition,polished=polished,usePolished=true))
        var submitted:RoleCreationDraft?=null
        ui.runOnUiThread{scale=0f}
        ui.activity.setContent{LianxuTheme(dark=true){
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.6f)){
                Box(Modifier.size(280.dp,380.dp)){RoleCreationGuide(local,{local=it},false,onExit={},onCreate={submitted=it})}
            }
        }}
        ui.onNodeWithTag("polish-definition").performScrollTo().performTextReplacement("长内容😀。".repeat(500))
        ui.onNodeWithTag("create-friend").performScrollTo().assertIsDisplayed();shot("dark-small-preview")
        ui.onNodeWithTag("create-friend").performClick();assertEquals("长内容😀。".repeat(500),submitted!!.definition)
        assertEquals(source.definition,local.definition);assertEquals(0,calls.get())
    }
}
