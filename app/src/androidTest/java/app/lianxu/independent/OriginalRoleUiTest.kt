package app.lianxu.independent

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File

class OriginalRoleUiTest {
    @Volatile private var mode="success"
    @Volatile private var calls=0
    private var responseGate=kotlinx.coroutines.CompletableDeferred<TextChatResult>()
    private val payload=Gson().toJson(mapOf("roles" to (1..3).map{mapOf("name" to "合成角色$it","summary" to "喜欢植物与夜晚散步的原创角色$it","definition" to "合成设定：在小城经营花店，温柔且有自己的生活。$it","expression" to "自然简短，愿意认真倾听。","opening" to "今晚要一起看看新开的花吗？") }))
    @get:Rule(order=0) val fixture=MockChatFixture(clientFactory={object:TextChatClient{
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
            check(key=="synthetic-key-only");calls++
            check(request.messages.size==2&&request.tools.isEmpty())
            if(mode=="controlled")return responseGate.await()
            if(mode=="wait")delay(60000)
            return if(mode=="failure")TextChatResult.Failure(ChatFailure.NETWORK) else TextChatResult.Success(payload)
        }
    }})
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private fun vm():AppState {lateinit var value:AppState;ui.activityRule.scenario.onActivity{value=ViewModelProvider(it)[AppState::class.java]};return value}
    private fun ready(){ui.waitUntil(15000){vm().ready};ui.runOnIdle{vm().startSearch()}}
    private fun hideKeyboard(){ui.activityRule.scenario.onActivity{(it.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.window.decorView.windowToken,0);it.window.decorView.clearFocus()};ui.waitUntil(10000){ui.activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==false};android.os.SystemClock.sleep(350);ui.waitForIdle()}
    private fun shot(name:String){
        hideKeyboard();val context=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(context.getExternalFilesDir(null),"evidence-original-role").apply{mkdirs()}
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(dir,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun generateInspectAddAndFailedRefreshPreservesBatch(){
        ready();assertEquals(0,calls)
        ui.onNodeWithTag("role-requirements").performTextInput("温柔的园艺师，有自己的日常生活")
        shot("01-requirements-courtyard")
        ui.onNodeWithTag("generate-roles").assertIsEnabled().performClick();ui.waitUntil(15000){vm().candidates.size==3||vm().error.isNotBlank()};assertEquals("calls=$calls, error=${vm().error}",3,vm().candidates.size)
        val batch=vm().candidates;val before=vm().friends.size
        shot("02-generated-synthetic-candidates")
        ui.onNodeWithTag("candidate-${batch[0].id}").performClick();shot("03-generated-role-detail")
        ui.onNodeWithTag("add-candidate").performClick();ui.waitUntil(15000){vm().friends.size==before+1&&!vm().busy}
        ui.onNodeWithTag("add-candidate").assertIsNotEnabled()
        runBlocking{val context=InstrumentationRegistry.getInstrumentation().targetContext;val store=Store(context,fixture.database);try{
            val saved=RolePromptStore.decode(store.preference(RolePromptStore.key(batch[0].id),""));assertEquals("合成设定：在小城经营花店，温柔且有自己的生活。1",saved.characterDefinition)
            assertEquals("自然简短，愿意认真倾听。",saved.expression)
            assertEquals(1,store.messages().count{it.friendId==batch[0].id})
        }finally{store.close()}}
        ui.onNodeWithTag("back").performClick();mode="failure"
        ui.onNodeWithTag("role-requirements").performTextReplacement("更外向一点的园艺师");hideKeyboard()
        ui.onNodeWithTag("generate-roles").performClick();ui.waitUntil(15000){!vm().searching&&vm().error.isNotBlank()}
        assertEquals(batch,vm().candidates);shot("04-failed-refresh-keeps-candidates")
        mode="wait";ui.onNodeWithTag("generate-roles").performClick();ui.waitUntil(15000){vm().searching}
        ui.onNodeWithTag("search-cancel").performClick();assertFalse(vm().searching);assertEquals(batch,vm().candidates)
        ui.runOnIdle{vm().home();vm().tab=1};shot("05-contacts-title")
        ui.onNodeWithTag("tab-2").performClick();shot("06-settings-title")
        ui.onNodeWithTag("settings-models").performClick();shot("07-models-title")
    }
    @Test fun waitingTracksRequestWithoutDuplicateCalls(){
        ready();mode="controlled"
        ui.onNodeWithTag("role-requirements").performTextInput("合成测试：温柔的园艺师");hideKeyboard()
        ui.onNodeWithTag("generate-roles").performClick();ui.waitUntil(10000){calls==1}
        ui.onNodeWithTag("role-generation-waiting").assertExists()
        ui.onNodeWithTag("role-requirements").assertIsNotEnabled();ui.onNodeWithTag("generate-roles").assertDoesNotExist()
        ui.runOnIdle{repeat(4){vm().findAgain()}};assertEquals(1,calls)
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        android.os.SystemClock.sleep(250)
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        assertTrue(vm().searching);assertEquals(1,calls)
        responseGate.complete(TextChatResult.Success(payload));ui.waitUntil(10000){!vm().searching}
        ui.onNodeWithTag("role-generation-waiting").assertDoesNotExist();val batch=vm().candidates;assertEquals(3,batch.size)
        mode="wait";ui.onNodeWithTag("generate-roles").performClick();ui.waitUntil(10000){calls==2}
        ui.onNodeWithTag("role-generation-waiting").assertExists();ui.onNodeWithTag("search-cancel").performClick()
        ui.onNodeWithTag("role-generation-waiting").assertDoesNotExist();assertEquals(batch,vm().candidates)
        mode="controlled";responseGate=kotlinx.coroutines.CompletableDeferred()
        ui.onNodeWithTag("generate-roles").performClick();ui.waitUntil(10000){calls==3}
        responseGate.complete(TextChatResult.Failure(ChatFailure.NETWORK));ui.waitUntil(10000){!vm().searching&&vm().error.isNotBlank()}
        ui.onNodeWithTag("role-generation-waiting").assertDoesNotExist();assertEquals(batch,vm().candidates)
        mode="wait";ui.onNodeWithTag("generate-roles").performClick();ui.waitUntil(10000){calls==4}
        ui.onNodeWithTag("back").performClick();assertFalse(vm().searching)
        ui.onNodeWithTag("role-generation-waiting").assertDoesNotExist()
        ui.runOnIdle{vm().startSearch()};assertFalse(vm().searching);assertEquals(4,calls);assertEquals(batch,vm().candidates)
        ui.onNodeWithTag("generate-roles").performClick();ui.waitUntil(10000){calls==5}
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        assertTrue(vm().searching);assertEquals(5,calls);assertEquals(batch,vm().candidates)
        ui.onNodeWithTag("role-generation-waiting").assertIsDisplayed()
        ui.onNodeWithTag("search-cancel").performClick();assertFalse(vm().searching)
        ui.onNodeWithTag("role-generation-waiting").assertDoesNotExist()
    }
    @Test fun stoppedActivityReturnsToSamePendingRequestAndSurvivesRecreation(){
        ready();mode="controlled"
        ui.onNodeWithTag("role-requirements").performTextInput("合成后台切换要求");hideKeyboard()
        ui.onNodeWithTag("generate-roles").performClick();ui.waitUntil(10000){calls==1}
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        shot("background-return-pending")
        ui.onNodeWithTag("role-generation-waiting").assertIsDisplayed()
        ui.onNodeWithTag("search-cancel").assertIsEnabled();assertTrue(vm().searching);assertEquals(1,calls)
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("role-generation-waiting").assertIsDisplayed();assertTrue(vm().searching);assertEquals(1,calls)
        responseGate.complete(TextChatResult.Success(payload));ui.waitUntil(10000){!vm().searching}
        assertEquals(3,vm().candidates.size);assertEquals(1,calls)
        ui.onNodeWithTag("role-generation-waiting").assertDoesNotExist();shot("background-return-completed")
    }
    @Test fun completionWhileStoppedIsVisibleOnReturnWithoutAnotherRequest(){
        ready();mode="controlled"
        ui.onNodeWithTag("role-requirements").performTextInput("合成后台完成要求");hideKeyboard()
        ui.onNodeWithTag("generate-roles").performClick();ui.waitUntil(10000){calls==1};val state=vm()
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        responseGate.complete(TextChatResult.Success(payload))
        runBlocking{kotlinx.coroutines.withTimeout(10000){while(state.searching)delay(20)}}
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        assertEquals(3,vm().candidates.size);assertEquals(1,calls)
        ui.onNodeWithTag("role-generation-waiting").assertDoesNotExist()
        ui.onNodeWithTag("candidate-${vm().candidates.first().id}").assertIsDisplayed()
        shot("background-completed-before-return")
    }
    @Test fun failureWhileStoppedShowsErrorAndKeepsPreviousCandidates(){
        ready();ui.onNodeWithTag("role-requirements").performTextInput("合成原候选要求");hideKeyboard()
        ui.onNodeWithTag("generate-roles").performClick();ui.waitUntil(10000){vm().candidates.size==3};val batch=vm().candidates
        mode="controlled";ui.onNodeWithTag("generate-roles").performClick();ui.waitUntil(10000){calls==2};val state=vm()
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        responseGate.complete(TextChatResult.Failure(ChatFailure.NETWORK))
        runBlocking{kotlinx.coroutines.withTimeout(10000){while(state.searching)delay(20)}}
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        assertEquals(batch,vm().candidates);assertEquals(2,calls)
        ui.onNodeWithText(ChatFailure.NETWORK.display).assertIsDisplayed()
        ui.onNodeWithTag("role-generation-waiting").assertDoesNotExist();ui.onNodeWithTag("generate-roles").assertIsEnabled()
        shot("background-failed-before-return")
    }
    @Test fun recordedSyntheticWaitingThenResults(){
        ready();mode="controlled"
        ui.onNodeWithTag("role-requirements").performTextInput("合成演示：温柔的园艺师");hideKeyboard();shot("10-generation-input")
        android.os.SystemClock.sleep(1000)
        ui.onNodeWithTag("generate-roles").performClick();ui.waitUntil(10000){calls==1}
        ui.onNodeWithTag("role-generation-waiting").assertExists();shot("11-generation-waiting")
        // Real wall-clock recording: production animation runs without test clock advancement.
        android.os.SystemClock.sleep(12000)
        responseGate.complete(TextChatResult.Success(payload));ui.waitUntil(10000){vm().candidates.size==3&&!vm().searching}
        ui.onNodeWithTag("role-generation-waiting").assertDoesNotExist();shot("12-generation-complete")
        android.os.SystemClock.sleep(1800);assertEquals(1,calls)
    }
    @Test fun courtyardVisualPreviewWithoutModelCall(){
        ready();ui.onNodeWithTag("modern-search-background").assertExists()
        shot("08-modern-empty-native-input")
        android.os.SystemClock.sleep(12000)
        ui.onNodeWithTag("role-requirements").performClick().performTextInput("温柔的园艺师")
        ui.waitUntil(10000){ui.activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==true}
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(context.getExternalFilesDir(null),"evidence-original-role").apply{mkdirs()}
        ui.onNodeWithTag("role-requirements").assertTextContains("温柔的园艺师",substring=true);ui.waitForIdle();android.os.SystemClock.sleep(350)
        File(dir,"09-modern-keyboard.png").outputStream().use{InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}
        android.os.SystemClock.sleep(4000);hideKeyboard();assertEquals(0,calls)
    }
    @Test fun keyboardRotationAndBackKeepDraftWithoutCallingModel(){
        ready();ui.onNodeWithTag("role-requirements").performClick().performTextInput("合成键盘旋转要求")
        ui.waitUntil(10000){ui.activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==true}
        val view=ui.activity.window.decorView
        val keyboardTop=view.height-view.rootWindowInsets.getInsets(android.view.WindowInsets.Type.ime()).bottom
        assertTrue(ui.onNodeWithTag("generate-roles").fetchSemanticsNode().boundsInRoot.bottom<=keyboardTop+1)
        hideKeyboard();ui.activityRule.scenario.onActivity{it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
        ui.waitUntil(15000){ui.activity.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE}
        ui.onNodeWithTag("role-requirements").assertTextContains("合成键盘旋转要求",substring=true)
        ui.activityRule.scenario.onActivity{it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
        ui.waitUntil(15000){ui.activity.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT}
        ui.onNodeWithTag("back").performClick();ui.runOnIdle{vm().startSearch()}
        ui.onNodeWithTag("role-requirements").assertTextContains("合成键盘旋转要求",substring=true);assertEquals(0,calls)
    }
    @Test fun integratedComposerPreviewAndStop(){
        ready();mode="controlled"
        ui.onNodeWithTag("generate-roles").assertIsNotEnabled();assertEquals(0,calls)
        shot("composer-empty")
        ui.onNodeWithTag("role-requirements").performTextInput("合成预览：温柔的园艺师，有自己的日常生活")
        shot("composer-input")
        ui.onNodeWithTag("generate-roles").assertIsEnabled()
        ui.onNodeWithTag("role-requirements").performClick().performTextReplacement("合成预览：温柔的园艺师\n有自己的日常生活\n喜欢植物，也愿意认真倾听")
        ui.waitUntil(10000){ui.activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==true}
        val keyboardTop=ui.activity.window.decorView.height-ui.activity.window.decorView.rootWindowInsets.getInsets(android.view.WindowInsets.Type.ime()).bottom
        val button=ui.onNodeWithTag("generate-roles").fetchSemanticsNode().boundsInRoot
        val composer=ui.onNodeWithTag("role-composer").fetchSemanticsNode().boundsInRoot
        assertTrue(button.bottom<=keyboardTop+1);assertTrue(composer.contains(button.center))
        val density=ui.activity.resources.displayMetrics.density
        assertTrue(button.width/density>=48f);assertTrue(button.height/density>=48f)
        val dir=File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"evidence-original-role").apply{mkdirs()}
        ui.waitForIdle();android.os.SystemClock.sleep(350)
        File(dir,"composer-keyboard.png").outputStream().use{InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}
        hideKeyboard();ui.onNodeWithTag("generate-roles").performClick();ui.waitUntil(10000){calls==1}
        ui.onNodeWithTag("role-generation-waiting").assertExists();ui.onNodeWithTag("generate-roles").assertDoesNotExist()
        ui.onNodeWithTag("search-cancel").assertContentDescriptionEquals("停止生成角色").assertIsEnabled()
        ui.onNodeWithTag("role-requirements").assertIsNotEnabled()
        ui.runOnIdle{repeat(3){vm().findAgain()}};assertEquals(1,calls)
        shot("composer-pending")
        ui.onNodeWithTag("search-cancel").performClick()
        ui.onNodeWithTag("role-generation-waiting").assertDoesNotExist();ui.onNodeWithTag("generate-roles").assertIsEnabled()
        ui.onNodeWithTag("role-requirements").assertTextContains("有自己的日常生活",substring=true)
        assertFalse(vm().searching);assertEquals(1,calls)
    }
}
