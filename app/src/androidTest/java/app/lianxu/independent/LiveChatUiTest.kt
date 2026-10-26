package app.lianxu.independent

import android.content.Context
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Full UI -> real loopback TLS POST -> durable reply. No service-provider credentials. */
class LiveChatUiTest {
    private val server=TextTlsMock()
    @get:Rule(order=0) val mock=MockChatFixture({server.client()},server.base)
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val vault get()=ModelVault(context,mock.namespace)
    private fun hideIme(){ui.activityRule.scenario.onActivity{a->(a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(a.window.decorView.windowToken,0);a.window.decorView.clearFocus()};ui.waitForIdle()}
    private fun shot(name:String){hideIme();android.os.SystemClock.sleep(600);val dir=File(context.getExternalFilesDir(null),"evidence-live-chat/ui").apply{mkdirs()};val b=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot();File(dir,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}
    @After fun closeServer(){server.close()}
    @Test fun purposesCustomPromptNewRoleAndActualHttpReply(){
        ui.waitUntil(15000){ui.onAllNodesWithTag("tab-2").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("tab-2").performClick();ui.onNodeWithTag("settings-models").performClick()
        for(p in ModelPurpose.entries){
            ui.onNodeWithTag("purpose-select-${p.key}").performScrollTo().performClick()
            ui.onNodeWithTag("purpose-option-${p.key}-fixture").performClick();ui.waitUntil(10000){vault.purposeId(p)=="fixture"}
        }
        assertEquals(0,server.calls.get());assertEquals(4,ModelPurpose.entries.size)
        ui.onNodeWithTag("purpose-select-chat").performScrollTo();shot("01-global-purposes-mock-only")
        ui.onNodeWithTag("config-back").performClick();ui.onNodeWithTag("tab-0").performClick()
        ui.onNodeWithTag("add-menu").performClick();ui.onNodeWithText("添加AI").performClick()
        ui.onNodeWithTag("friend-name").performTextInput("合成·新角色");ui.onNodeWithTag("friend-bio").performTextInput("喜欢安静的小事，仅供本机测试。")
        ui.onNodeWithTag("create-friend").performScrollTo().performClick();ui.waitUntil(10000){ui.onAllNodesWithTag("message-input").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("资料").performClick();ui.onNodeWithTag("role-prompts-open").performScrollTo().performClick()
        ui.onNodeWithTag("role-expression").performTextInput("用自然表达回应 {{character}}，不要机械反问。")
        hideIme();ui.activityRule.scenario.recreate();ui.onNodeWithTag("role-expression").assertTextContains("{{character}}",substring=true)
        ui.onNodeWithTag("config-save").performClick();ui.waitUntil(10000){ui.onAllNodesWithTag("role-prompts-open").fetchSemanticsNodes().isNotEmpty()};ui.onNodeWithTag("back").performClick()
        ui.onNodeWithTag("message-input").performTextInput("你好，这是本机合成测试。")
        ui.onNodeWithTag("send-message").performTouchInput{doubleClick(delayMillis=40)}
        ui.waitUntil(15000){ui.onAllNodesWithText("【Mock HTTP】合成回复").fetchSemanticsNodes().isNotEmpty()}
        assertEquals(1,server.calls.get());assertEquals("",ui.onNodeWithTag("message-input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        val messages=JSONObject(server.body).getJSONArray("messages")
        assertTrue(messages.getJSONObject(0).getString("content").contains("用自然表达回应 合成·新角色"))
        assertEquals("你好，这是本机合成测试。",messages.getJSONObject(messages.length()-2).getString("content"));assertEquals("system",messages.getJSONObject(messages.length()-1).getString("role"))
        assertFalse(server.body.contains("【演示开场】"));assertFalse(server.body.contains("synthetic-key-only"))
        shot("02-new-role-real-http-mock-reply")
        runBlocking{
            val s=Store(context,mock.database);val f=s.friends().single{it.name=="合成·新角色"};val own=s.messages().filter{it.friendId==f.id};assertEquals(2,own.size);assertEquals(1,ChatLedger(s).all().count{it.friendId==f.id&&it.state==TurnState.COMPLETED})
            assertTrue(RolePromptStore.decode(s.preference(RolePromptStore.key(f.id),"")).expression.contains("{{character}}"));assertEquals(RolePrompts(),RolePromptStore.decode(s.preference(RolePromptStore.key("seed-an"),"")));s.close()
        }
    }
}
