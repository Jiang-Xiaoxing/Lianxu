package app.lianxu.independent

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import android.graphics.Bitmap
import java.io.File

@RunWith(AndroidJUnit4::class)
class FlowTest {
    @get:Rule(order=0) val mock=MockChatFixture()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private fun ready() {ui.waitUntil(15000) {ui.onAllNodesWithTag("add-menu").fetchSemanticsNodes().isNotEmpty()}}
    private fun screenshot(name: String) {
        ui.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        android.os.SystemClock.sleep(400)
        val dir=File(instrumentation.targetContext.getExternalFilesDir(null),"evidence-unified-ui/flow").apply {mkdirs()}
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        File(dir,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun visualEvidence() {
        ready();screenshot("01-chat-list-real-app")
        ui.onNodeWithText("林间",useUnmergedTree=true).performClick()
        ui.onNodeWithTag("message-input").performTextInput("今天想和你分享一件开心的小事。")
        ui.onNodeWithTag("send-message").performClick()
        ui.waitUntil(10000) {ui.onAllNodesWithText("今天想和你分享一件开心的小事。").fetchSemanticsNodes().isNotEmpty()}
        ui.activityRule.scenario.onActivity { activity -> (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(activity.window.decorView.windowToken,0); activity.window.decorView.clearFocus() }
        screenshot("02-conversation-real-app")
        ui.onNodeWithTag("back").performClick();ui.onNodeWithTag("tab-1").performClick();screenshot("03-contacts-real-app")
        ui.onNodeWithText("林间",useUnmergedTree=true).performClick();screenshot("04-friend-detail-real-app");ui.onNodeWithTag("back").performClick();ui.onNodeWithTag("tab-1").assertExists()
        ui.onNodeWithTag("tab-2").performClick();screenshot("05-settings-real-app");ui.onNodeWithTag("tab-3").performClick();screenshot("06-profile-real-app")
        ui.onNodeWithTag("tab-0").performClick();ui.onNodeWithTag("add-menu").performClick();ui.onNodeWithText("搜索朋友").performClick();screenshot("07-radar-real-app")
        ui.waitUntil(10000) {ui.onAllNodesWithText("全选 3 位").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("全选 3 位").performClick();screenshot("08-candidates-real-app")
    }
    @Test fun createValidateSendAndNavigate() {
        val testName="合成测试友-${System.nanoTime()%10000}"
        ready();ui.onNodeWithTag("add-menu").performClick();ui.onNodeWithText("添加朋友").performClick()
        ui.onNodeWithText("创建朋友").performScrollTo().performClick();ui.onNodeWithText("请填写名字和角色介绍。").assertIsDisplayed()
        ui.onNodeWithTag("friend-name").performScrollTo().performTextInput(testName)
        ui.onNodeWithTag("friend-bio").performTextInput("仅供自动测试")
        ui.onNodeWithText("创建朋友").performScrollTo().performClick()
        ui.waitUntil(10000) {ui.onAllNodesWithTag("message-input").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("message-input").performTextInput("synthetic-message")
        ui.onNodeWithTag("send-message").performClick()
        ui.waitUntil(10000) {ui.onAllNodesWithText("synthetic-message").fetchSemanticsNodes().isNotEmpty()}
        ui.activityRule.scenario.onActivity { activity -> (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(activity.window.decorView.windowToken,0) }
        screenshot("10-sent-message-real-app")
        ui.onNodeWithText("资料").performClick();ui.onNodeWithText("朋友详情").assertIsDisplayed();ui.onNodeWithTag("back").performClick();ui.onNodeWithTag("message-input").assertExists();ui.onNodeWithTag("back").performClick();ui.onNodeWithTag("tab-1").performClick();ui.onNode(hasScrollAction()).performScrollToNode(hasText(testName));ui.onNodeWithText(testName).assertIsDisplayed()
    }
    @Test fun cancelAndSelectSearch() {
        ready();ui.onNodeWithTag("add-menu").performClick();ui.onNodeWithText("搜索朋友").performClick();ui.onNodeWithText("取消").performClick();ui.onNodeWithTag("tab-0").assertExists()
        ui.onNodeWithTag("add-menu").performClick();ui.onNodeWithText("搜索朋友").performClick()
        ui.waitUntil(10000) {ui.onAllNodesWithText("全选 3 位").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("添加已选（0）").assertIsNotEnabled();ui.onNodeWithText("全选 3 位").performClick();ui.onNodeWithText("添加已选（3）").assertIsEnabled();ui.onNodeWithText("不选").performClick();ui.onNodeWithText("添加已选（0）").assertIsNotEnabled()
        ui.onNodeWithText("重新寻找").performClick();ui.waitUntil(10000) {ui.onAllNodesWithText("全选 3 位").fetchSemanticsNodes().isNotEmpty()};ui.onNodeWithText("全选 3 位").performClick();ui.onNodeWithText("添加已选（3）").performTouchInput { doubleClick(delayMillis=40) };ui.waitUntil(10000) {ui.onAllNodesWithTag("tab-1").fetchSemanticsNodes().isNotEmpty()}
    }
    @Test fun databaseIdempotencyIsolationAndReopen(): Unit = runBlocking {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val dbName="acceptance-${System.nanoTime()}.db"
        val a=Friend("a","甲","合成");val b=Friend("b","乙","合成")
        var store=Store(ctx,dbName)
        store.add(listOf(a,b));store.add(listOf(a,a,b));assertEquals(1,store.friends().count {it.id=="a"})
        store.exchange(a,"only-a","demo-a");store.exchange(b,"only-b","demo-b");store.close()
        store=Store(ctx,dbName)
        assertEquals(1,store.friends().count{it.id=="b"});assertTrue(store.messages().filter{it.friendId=="a"}.none {it.text.contains("only-b")});assertTrue(store.messages().any{it.friendId=="a"&&it.text=="only-a"});store.close();ctx.deleteDatabase(dbName)
    }
}
