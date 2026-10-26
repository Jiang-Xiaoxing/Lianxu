package app.lianxu.independent

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Only synthetic local state and injected responses. This suite never opens a network socket. */
class ModelConfigUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace="ui-test-${System.nanoTime()}"
    private val client=ScriptedDirectory()
    private lateinit var state:ModelConfigState
    private val a=ModelConfig("ui-a","日常陪伴","deepseek","https://api.deepseek.com","synthetic-chat-model")
    private val b=ModelConfig("ui-b","灵感小站","silicon-cn","https://api.siliconflow.cn/v1","synthetic-model-b")
    private val vault get()=ModelVault(context,namespace)

    private class ScriptedDirectory:ModelDirectoryClient {
        val calls=AtomicInteger(0)
        val replies=ConcurrentLinkedQueue<CompletableDeferred<ConfigResult>>()
        fun reply(result:ConfigResult)=CompletableDeferred(result).also {replies.add(it)}
        fun pending()=CompletableDeferred<ConfigResult>().also {replies.add(it)}
        override suspend fun request(config:ModelConfig,key:String,fetchModels:Boolean):ConfigResult {
            assertEquals("synthetic-key-only",key);calls.incrementAndGet()
            // Intentionally ignore cancellation to exercise a late, stale callback.
            val response=checkNotNull(replies.poll()){"No synthetic response queued"}
            return withContext(NonCancellable){response.await()}
        }
    }
    @Before fun setup() {
        vault.save(a,"synthetic-key-only");vault.save(b,"synthetic-key-only")
        ui.activityRule.scenario.onActivity {activity->
            state=ViewModelProvider(activity,object:ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T =
                    ModelConfigState(activity.application as Application,client,namespace) as T
            })[ModelConfigState::class.java]
        }
        ui.waitUntil(10000){state.configs.size==2}
        ui.waitUntil(15000){ui.onAllNodesWithTag("tab-2").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("tab-2").performClick();ui.onNodeWithTag("settings-models").performClick()
    }
    @After fun cleanup(){ui.runOnIdle{state.clearResult()};vault.list().forEach {vault.delete(it.id)}}
    private fun hideKeyboard(){ui.activityRule.scenario.onActivity {activity->
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(activity.window.decorView.windowToken,0)
        activity.window.decorView.clearFocus()
    };ui.waitForIdle()}
    private fun shot(name:String){hideKeyboard();android.os.SystemClock.sleep(350)
        val dir=File(context.getExternalFilesDir(null),"evidence-unified-ui/models").apply{mkdirs()}
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(dir,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    private fun edit(id:String="ui-a"){ui.onNodeWithTag("config-overview").performScrollToNode(hasTestTag("config-edit-$id"));ui.onNodeWithTag("config-edit-$id").performClick()}
    private fun back(){hideKeyboard();ui.onNodeWithTag("config-back").performClick()}
    private fun waitSaved(count:Int){ui.waitUntil(10000){runCatching{vault.list().size==count}.getOrDefault(false)};ui.waitUntil(10000){!state.saving}}

    @Test fun layeredCrudValidationKeyboardAndRecreation(){
        ui.onNodeWithTag("config-overview").performScrollToNode(hasTestTag("config-edit-ui-a"))
        ui.onNodeWithTag("config-probe-ui-a",useUnmergedTree=true).assertTextEquals("未测试")
        shot("01-overview-real-app")
        ui.onNodeWithTag("config-add-top").performClick();shot("02-providers-real-app")
        ui.onNodeWithTag("provider-search").performTextInput("xiaomi")
        ui.onNodeWithTag("provider-mimo").assertIsDisplayed()
        ui.onNodeWithTag("provider-deepseek").assertDoesNotExist()
        ui.onNodeWithTag("provider-search").performTextReplacement("DeepSeek")
        ui.onNodeWithTag("provider-deepseek").performClick()
        ui.onNodeWithTag("config-save").performClick()
        ui.onNodeWithTag("config-status").assertTextContains("API Key",substring=true)
        ui.onNodeWithTag("config-name").performTextReplacement("旅行随记")
        ui.onNodeWithTag("config-key").performTextInput("synthetic-key-only")
        ui.onNodeWithTag("config-model").performScrollTo().performTextInput("synthetic-new-model")
        // IME must leave the focused field and the fixed header save action visible.
        ui.onNodeWithTag("config-model").assertIsDisplayed();ui.onNodeWithTag("config-save").assertIsDisplayed();ui.onNodeWithContentDescription("保存配置").assertHasClickAction()
        hideKeyboard();ui.onNodeWithTag("config-advanced").performScrollTo().performClick()
        ui.onNodeWithTag("config-url").performScrollTo().performTextReplacement("http://example.invalid/v1")
        hideKeyboard();ui.onNodeWithTag("config-save").performClick()
        ui.onNodeWithTag("config-status").performScrollTo().assertTextContains("HTTPS",substring=true)
        ui.onNodeWithTag("config-url").performScrollTo().performTextReplacement("https://example.invalid/v1")
        hideKeyboard();ui.onNodeWithTag("config-advanced").performScrollTo().performClick()
        ui.onNodeWithTag("config-name").performScrollTo();shot("03-editor-masked-real-app")
        ui.onNodeWithTag("config-save").performTouchInput{doubleClick()}
        waitSaved(3);assertEquals(0,client.calls.get())
        val saved=vault.list().single{it.name=="旅行随记"}
        ui.onNodeWithTag("config-overview").performScrollToNode(hasTestTag("config-edit-${saved.id}"))
        ui.onNodeWithTag("config-probe-${saved.id}",useUnmergedTree=true).assertTextEquals("未测试")
        edit(saved.id)
        ui.onNodeWithTag("config-default-${saved.id}").performScrollTo().performClick()
        ui.waitUntil(10000){vault.defaultId()==saved.id&&!state.saving}
        ui.onNodeWithTag("config-name").performScrollTo().performTextReplacement("旅行随记·编辑")
        hideKeyboard();ui.onNodeWithTag("config-save").performClick();waitSaved(3)
        assertEquals("旅行随记·编辑",vault.list().single{it.id==saved.id}.name)
        ui.activityRule.scenario.recreate()
        ui.waitUntil(10000){ui.onAllNodesWithTag("config-overview").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("config-overview").performScrollToNode(hasText("旅行随记·编辑"));ui.onNodeWithText("旅行随记·编辑").assertExists()
        assertEquals("synthetic-key-only",vault.secret(saved.id));assertEquals(saved.id,vault.defaultId())
        edit(saved.id);ui.onNodeWithTag("config-delete-${saved.id}").performScrollTo().performClick()
        ui.onNodeWithText("取消").performClick();assertTrue(vault.hasKey(saved.id))
        ui.onNodeWithTag("config-delete-${saved.id}").performClick();ui.onNodeWithText("确认删除").performClick()
        waitSaved(2);assertFalse(vault.hasKey(saved.id));assertEquals(0,client.calls.get())
    }

    @Test fun activeProbeRetryModelFetchAndLeavingIgnoreLateResults(){
        edit();client.reply(ConfigResult.Failure(ConfigError.UNAUTHORIZED,401))
        ui.onNodeWithTag("config-test").performScrollTo().performClick()
        ui.waitUntil(10000){state.probe.phase==ProbePhase.FAILED}
        ui.onNodeWithTag("config-probe-inline").assertTextContains("401",substring=true)
        assertFalse(state.probe.text.contains("synthetic-key"));shot("04-readable-failure-real-app")
        client.reply(ConfigResult.Success())
        ui.onNodeWithTag("config-test").performClick()
        ui.waitUntil(10000){state.probe.phase==ProbePhase.REACHABLE}
        ui.onNodeWithTag("config-probe-inline").assertTextContains("可达",substring=true)
        client.reply(ConfigResult.Success(listOf("synthetic-selected-model")))
        ui.onNodeWithTag("config-fetch").performScrollTo().performClick()
        ui.waitUntil(10000){state.models.isNotEmpty()}
        ui.onNodeWithText("synthetic-selected-model").performScrollTo().performClick()
        ui.onNodeWithTag("config-model").assertTextContains("synthetic-selected-model",substring=true)
        ui.onNodeWithTag("config-probe-inline").assertTextEquals("未测试")
        val late=client.pending();val before=client.calls.get()
        ui.onNodeWithTag("config-test").performScrollTo().performTouchInput{doubleClick()}
        ui.waitUntil(10000){client.calls.get()==before+1}
        ui.onNodeWithTag("config-test").assertIsNotEnabled();back()
        edit("ui-b");client.reply(ConfigResult.Success())
        ui.onNodeWithTag("config-test").performScrollTo().performClick()
        ui.waitUntil(10000){state.probe.phase==ProbePhase.REACHABLE}
        late.complete(ConfigResult.Failure(ConfigError.NETWORK))
        ui.waitForIdle();assertEquals(ProbePhase.REACHABLE,state.probe.phase);assertFalse(state.testing)
        back();ui.onNodeWithTag("config-overview").performScrollToNode(hasTestTag("config-edit-ui-b"));ui.onNodeWithTag("config-probe-ui-b",useUnmergedTree=true).assertTextContains("可达",substring=true)
        assertEquals("synthetic-chat-model",vault.list().single{it.id==a.id}.modelId)
    }

    @Test fun cancelUnsupportedProviderAndBackgroundClearSecrets(){
        ui.onNodeWithTag("config-add-top").performClick();back()
        ui.onNodeWithTag("config-overview").assertIsDisplayed()
        ui.onNodeWithTag("config-add-top").performClick()
        ui.onNodeWithTag("provider-search").performTextInput("mimo")
        ui.onNodeWithTag("provider-mimo").performClick()
        ui.onNodeWithTag("config-fetch").assertDoesNotExist();ui.onNodeWithTag("config-test").assertDoesNotExist()
        ui.onNodeWithTag("config-key").performTextInput("synthetic-key-only")
        ui.onNodeWithTag("config-model").performScrollTo().performTextInput("synthetic-mimo")
        hideKeyboard();shot("05-manual-provider-real-app")
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        ui.onNodeWithTag("config-key").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,androidx.compose.ui.text.AnnotatedString("")))
        ui.onNodeWithTag("config-save").performClick()
        ui.onNodeWithTag("config-status").performScrollTo().assertTextContains("API Key",substring=true)
        back();assertEquals(2,vault.list().size);assertEquals(0,client.calls.get())
    }
    @Test fun backgroundCancelsActiveProbeAndLateFailureCannotRestoreIt(){
        edit();val late=client.pending()
        ui.onNodeWithTag("config-test").performScrollTo().performClick()
        ui.waitUntil(10000){client.calls.get()==1}
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        late.complete(ConfigResult.Failure(ConfigError.NETWORK))
        ui.waitForIdle();assertEquals(ProbePhase.UNTESTED,state.probe.phase);assertFalse(state.testing)
        assertEquals(0,state.connections.size)
    }
}
