package app.lianxu.independent

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalTestApi::class)
class RoleExamplesUiTest {
    private val calls=AtomicInteger()
    @Volatile private var responseMode="success"
    @get:Rule(order=0) val fixture=MockChatFixture({object:TextChatClient {
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
            calls.incrementAndGet()
            if(responseMode=="wait")awaitCancellation()
            if(responseMode=="invalid")return TextChatResult.Success("invalid selection")
            val c=JSONObject(request.messages.last().content).getJSONArray("candidates")
            return TextChatResult.Success("{\"ids\":[\"${c.getJSONObject(0).getString("id")}\",\"${c.getJSONObject(1).getString("id")}\"]}")
        }
    }})
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private fun app():AppState {lateinit var s:AppState;ui.activityRule.scenario.onActivity{s=ViewModelProvider(it)[AppState::class.java]};return s}
    private fun prepare():AppState {
        ui.waitUntil(30_000){app().ready};val state=app()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        ModelVault(context,fixture.namespace).setPurpose(ModelPurpose.CHAT,"fixture")
        runBlocking {
            val store=state.processing.store
            store.add(listOf(Friend("examples-ui","合成示例角色","仅合成数据")),false)
            repeat(20){val ledger=ChatLedger(store);val t=ledger.begin("examples-ui","今天想和你聊聊第 $it 件事","fixture");assertTrue(ledger.finish(t.id,1,"我愿意听你慢慢说，再一起去散步。"))}
            state.refreshProcessing()
        }
        ui.runOnIdle{state.open("chat","examples-ui")}
        ui.waitUntil(30_000){ui.onAllNodesWithTag("examples-invitation").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("examples-invitation").performClick()
        ui.waitUntil(30_000){ui.onAllNodesWithTag("examples-prepare").fetchSemanticsNodes().isNotEmpty()}
        return state
    }
    private fun confirm(){ui.onNodeWithTag("examples-prepare").performScrollTo().performClick();ui.waitUntil(30_000){ui.onAllNodesWithTag("examples-confirm").fetchSemanticsNodes().isNotEmpty()};ui.onNodeWithTag("examples-confirm").performClick()}
    private fun shot(name:String){val i=InstrumentationRegistry.getInstrumentation();val dir=File(i.targetContext.getExternalFilesDir(null),"evidence-examples").apply{mkdirs()};File(dir,"$name.png").outputStream().use{i.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}}
    @Test fun consentDismissSuccessPersistenceAndDisable(){
        val state=prepare();assertEquals(0,calls.get());shot("invitation")
        ui.onNodeWithTag("examples-prepare").performClick();ui.waitUntil(30_000){ui.onAllNodesWithTag("examples-confirm").fetchSemanticsNodes().isNotEmpty()};shot("consent")
        ui.onNodeWithTag("examples-dismiss").performClick();assertEquals(0,calls.get())
        confirm();ui.waitUntil(30_000){runBlocking{state.examples.get("examples-ui")?.state==ExamplesState.ACTIVE}}
        ui.waitUntil(30_000){ui.onAllNodesWithTag("examples-disable").fetchSemanticsNodes().isNotEmpty()};assertEquals(1,calls.get());shot("active")
        ui.activityRule.scenario.recreate();ui.waitUntil(30_000){app().ready};ui.runOnIdle{app().open("role-prompts","examples-ui")}
        ui.waitUntil(30_000){ui.onAllNodesWithTag("examples-disable").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("examples-disable").performClick();ui.waitUntil(30_000){runBlocking{app().examples.get("examples-ui")?.state==ExamplesState.DISABLED}}
        ui.waitUntil(30_000){ui.onAllNodesWithText("你已停用示例，原记录保留。").fetchSemanticsNodes().isNotEmpty()}
        assertEquals(2,runBlocking{app().rolePrompts("examples-ui")}.examples.size);assertEquals(1,calls.get());shot("disabled")
    }
    @Test fun failureRetryAndCancellationAreExplicit(){
        val state=prepare();responseMode="invalid";confirm()
        ui.waitUntil(30_000){runBlocking{state.examples.get("examples-ui")?.state==ExamplesState.FAILED}};assertEquals(1,calls.get());ui.waitUntil(30_000){ui.onAllNodesWithText("挑选未完成：",substring=true).fetchSemanticsNodes().isNotEmpty()};shot("failed")
        responseMode="wait";ui.waitUntil(30_000){ui.onAllNodesWithTag("examples-prepare").fetchSemanticsNodes().isNotEmpty()};confirm()
        ui.waitUntil(30_000){ui.onAllNodesWithTag("examples-cancel").fetchSemanticsNodes().isNotEmpty()};shot("selecting");ui.onNodeWithTag("examples-cancel").performClick()
        ui.waitUntil(30_000){runBlocking{state.examples.get("examples-ui")?.state==ExamplesState.FAILED}};assertEquals(2,calls.get());assertFalse(runBlocking{state.rolePrompts("examples-ui")}.examplesEnabled)
    }
}
