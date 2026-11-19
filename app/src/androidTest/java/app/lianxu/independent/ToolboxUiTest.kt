package app.lianxu.independent

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class ToolboxUiTest {
    private val calls=AtomicInteger()
    @get:Rule(order=0) val fixture=MockChatFixture({object:TextChatClient{
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {calls.incrementAndGet();error("No network in toolbox tests")}
    }})
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var vm:AppState
    @Before fun ready(){ui.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[AppState::class.java]};ui.waitUntil(15000){vm.ready}}
    @After fun noNetwork(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        listOf("seed-lin","seed-an").forEach{androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag("companion:$it").result.get(5,java.util.concurrent.TimeUnit.SECONDS)}
        assertEquals(0,calls.get())
    }
    private fun back(){InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);ui.waitForIdle()}
    private fun home(){ui.runOnIdle{vm.tab=2;vm.open("home")};ui.onNodeWithTag("settings-toolbox").performScrollTo().performClick();ui.waitUntil(10000){runCatching{ui.onNodeWithTag("tool-card-image").assertIsEnabled();true}.getOrDefault(false)};ui.waitForIdle()}
    private fun shot(name:String,wait:Boolean=true){
        if(wait)ui.waitForIdle()
        Thread.sleep(180)
        val i=InstrumentationRegistry.getInstrumentation();val dir=File(i.targetContext.getExternalFilesDir(null),"toolbox-shots").apply{mkdirs()}
        i.uiAutomation.takeScreenshot().let{b->File(dir,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
    }
    @Test fun allFourCardsNavigateAndOldControlsAreReallyMoved(){
        val old=MemoryRules(52,19,17,proactiveRecall=false,immediateWrite=true,recallRounds=8,recallTokens=6000,recallPerCall=2000,adaptiveContext=true,reviewRounds=7)
        runBlocking{vm.processing.saveMemoryRules(old);vm.processing.read{ChatImages.setEnabled(it,true)}}
        home();ui.onNodeWithTag("toolbox-subtitle").assertTextEquals("为陪伴增添更多可能");shot("01-toolbox-compact")
        ui.onNodeWithTag("tool-card-image").performClick();ui.onNodeWithTag("auxiliary-vision-switch").assertIsOn();ui.onNodeWithTag("purpose-select-aux-vision").assertIsDisplayed();shot("02-image-settings")
        ui.onNodeWithTag("auxiliary-vision-switch").performClick();ui.waitUntil(10000){runBlocking{vm.processing.read{!ChatImages.enabled(it)}}};back()
        ui.onNodeWithTag("tool-card-memory").performClick();ui.onNodeWithTag("rules-recall-enabled").assertIsOff();ui.onNodeWithTag("rules-write-enabled").assertIsOn()
        ui.onNodeWithTag("rules-recall-info").performClick();ui.onNodeWithText("聊天时，她可以主动查找相关记忆和过往聊天。").assertIsDisplayed();ui.onNodeWithTag("rules-help-close").performClick()
        ui.onNodeWithTag("rules-recall-enabled").performClick();ui.onNodeWithTag("rules-write-enabled").performClick()
        assertEquals(old,runBlocking{vm.processing.memoryRules().first})
        ui.onNodeWithTag("memory-tools-save").performClick();ui.waitUntil(10000){runBlocking{vm.processing.memoryRules().first==old.copy(proactiveRecall=true,immediateWrite=false)}};shot("03-memory-tools");back()
        ui.onNodeWithTag("tool-card-video").performClick()
        ui.waitUntil(10000){runCatching{ui.onNodeWithTag("video-mode-frames").assertIsSelected();true}.getOrDefault(false)}
        ui.onNodeWithTag("video-mode-original").performClick()
        ui.waitUntil(10000){runBlocking{ToolboxPreferences.videoMode(vm.processing.store)==ToolboxPreferences.VideoMode.ORIGINAL}};shot("04-video-settings");back()
        ui.onNodeWithTag("tool-card-video").performClick()
        ui.waitUntil(10000){runCatching{ui.onNodeWithTag("video-mode-original").assertIsSelected();true}.getOrDefault(false)}
        back()
        ui.onNodeWithTag("tool-card-contact").performClick();ui.onNodeWithTag("toolbox-contact-page").assertIsDisplayed();ui.onNodeWithTag("companion-notification").assertExists();shot("05-contact-roles");back();back()
        ui.onNodeWithTag("settings-models").performScrollTo().performClick();ui.onNodeWithTag("config-tab-purposes").performClick();ui.onNodeWithTag("auxiliary-vision-switch").assertDoesNotExist();ui.onNodeWithTag("purpose-select-aux-vision").assertDoesNotExist();back()
        ui.onNodeWithTag("settings-processing").performScrollTo().performClick();ui.onNodeWithTag("settings-memory-rules").performClick();ui.onNodeWithTag("rules-recall-enabled").assertDoesNotExist();ui.onNodeWithTag("rules-write-enabled").assertDoesNotExist();back();back()
        ui.runOnIdle{vm.open("detail","seed-lin")};ui.onNodeWithTag("role-companion-open").assertDoesNotExist()
        assertEquals(old.copy(proactiveRecall=true,immediateWrite=false),runBlocking{vm.processing.memoryRules().first})
    }
    @Test fun contactSelectionKeepsEachRolePermissionAndGlobalNotificationSeparate(){
        runBlocking{vm.companion.setPolicy("seed-lin",true,CompanionRuntime.owner);vm.companion.setPolicy("seed-an",false,CompanionRuntime.owner)}
        home();ui.onNodeWithTag("tool-card-contact").performClick()
        ui.onNodeWithTag("toolbox-contact-seed-lin").assertTextContains("已开启",substring=true)
        ui.onNodeWithTag("toolbox-contact-seed-an").assertTextContains("已暂停",substring=true)
        ui.onNodeWithTag("toolbox-contact-seed-lin").performClick();ui.waitUntil(10000){runCatching{ui.onNodeWithTag("companion-enabled").assertIsEnabled();true}.getOrDefault(false)}
        ui.onNodeWithTag("companion-enabled").assertIsOn();ui.onNodeWithTag("companion-notification").assertDoesNotExist();ui.onNodeWithTag("companion-role-name").assertTextEquals("林间")
        ui.onNodeWithTag("companion-enabled").performClick();ui.waitUntil(10000){runBlocking{vm.companion.contactPolicies()["seed-lin"]=="paused_by_user"}};back()
        ui.onNodeWithTag("toolbox-contact-seed-an").performClick();ui.waitUntil(10000){runCatching{ui.onNodeWithTag("companion-enabled").assertIsEnabled();true}.getOrDefault(false)};ui.onNodeWithTag("companion-enabled").assertIsOff()
        ui.onNodeWithTag("companion-enabled").performClick();ui.waitUntil(10000){ui.onAllNodesWithTag("companion-background-close").fetchSemanticsNodes().isNotEmpty()};ui.onNodeWithTag("companion-background-close").performClick()
        assertEquals("paused_by_user",runBlocking{vm.companion.contactPolicies()["seed-lin"]});assertEquals("enabled",runBlocking{vm.companion.contactPolicies()["seed-an"]});shot("09-role-contact")
        back();ui.onNodeWithTag("companion-notification").assertExists();ui.onNodeWithTag("toolbox-contact-seed-an").assertTextContains("已开启",substring=true)
    }
    @Test fun savingPermissionContinuesAfterOpeningAnotherRole(){
        runBlocking{vm.companion.setPolicy("seed-lin",false,CompanionRuntime.owner);vm.companion.setPolicy("seed-an",false,CompanionRuntime.owner)}
        home();ui.onNodeWithTag("tool-card-contact").performClick();ui.onNodeWithTag("toolbox-contact-seed-lin").performClick()
        ui.waitUntil(10000){runCatching{ui.onNodeWithTag("companion-enabled").assertIsEnabled();true}.getOrDefault(false)}
        ui.onNodeWithTag("companion-enabled").performClick();ui.runOnIdle{vm.open("role-companion","seed-an")}
        ui.waitUntil(10000){runBlocking{vm.companion.contactPolicies()["seed-lin"]=="enabled"}}
        ui.waitUntil(10000){runCatching{ui.onNodeWithTag("companion-enabled").assertIsEnabled();true}.getOrDefault(false)}
        ui.onNodeWithTag("companion-enabled").assertIsOff();ui.onNodeWithTag("companion-role-name").assertTextEquals("时安")
        assertEquals("paused_by_user",runBlocking{vm.companion.contactPolicies()["seed-an"]})
        ui.onNodeWithTag("companion-background-close").assertDoesNotExist()
    }
    @Test fun failedContactRetryRemainsUsableWithoutNotificationPermission(){
        val opportunity="toolbox-retry-synthetic"
        runBlocking{
            vm.companion.setPolicy("seed-lin",true,CompanionRuntime.owner)
            val ledger=ChatLedger(vm.processing.store);val turn=ledger.begin("seed-lin","【合成】回访来源原话","fixture");check(ledger.finish(turn.id,1,"【合成】来源回复"))
            vm.processing.tx{db->
                val version=db.rawQuery("SELECT policy_version FROM companion_state WHERE role='seed-lin'",null).use{it.moveToFirst();it.getLong(0)}
                val revision=db.rawQuery("SELECT source_revision FROM chat_turns WHERE id=?",arrayOf(turn.id)).use{it.moveToFirst();it.getLong(0)}
                val now=System.currentTimeMillis()
                db.insertOrThrow("companion_opportunities",null,android.content.ContentValues().apply{
                    put("id",opportunity);put("role","seed-lin");put("source_id",turn.userMessageId);put("revision",revision);put("epoch",0);put("policy_version",version)
                    put("not_before",now);put("expires_at",now+86_400_000);put("reason","【合成】失败后由用户重试");put("state","failed");put("fail_kind",ContactFailureKind.MODEL_RESPONSE.name);put("fail_at",now)
                })
            }
            assertEquals(1,vm.companion.retryable("seed-lin"))
        }
        home();ui.onNodeWithTag("tool-card-contact").performClick();ui.onNodeWithTag("toolbox-contact-seed-lin").performClick()
        ui.waitUntil(10000){ui.onAllNodesWithTag("companion-retry").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("companion-last-failure").assertExists();ui.onNodeWithTag("companion-retry").performScrollTo().performClick()
        ui.waitUntil(10000){runBlocking{vm.companion.queued().any{it.id==opportunity}}}
        ui.waitUntil(10000){ui.onAllNodesWithTag("companion-retry").fetchSemanticsNodes().isEmpty()}
        assertEquals(0,runBlocking{vm.companion.retryable("seed-lin")});assertEquals("disabled",runBlocking{vm.companion.contactPolicies()["seed-an"]})
        ui.onNodeWithTag("companion-notification").assertDoesNotExist();shot("11-contact-retry-kept")
    }
}
