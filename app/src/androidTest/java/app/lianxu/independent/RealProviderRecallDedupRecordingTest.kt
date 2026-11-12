package app.lianxu.independent

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import java.io.File

/**
 * 真实服务商验收：第一轮存一条记忆，第二轮在本轮参考资料已经带上它的前提下再回查。
 * 要求工具结果不是空页、胶囊显示“想起来了”、角色答得出这条记忆。
 */
class RealProviderRecallDedupRecordingTest {
    @get:Rule(order = 0) val fixture = RealProviderChatFixture()
    @get:Rule(order = 1) val ui = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun turns(): List<ChatTurn> = runBlocking {
        val store = Store(context, fixture.database)
        try { ChatLedger(store).all() } finally { store.close() }
    }
    private fun settled(count: Int): Boolean = runBlocking {
        val store = Store(context, fixture.database)
        try {
            val all = ChatLedger(store).all()
            all.size == count && all.all { it.state != TurnState.PENDING }
        } finally { store.close() }
    }
    private fun replyOf(turn: ChatTurn): String = runBlocking {
        val id = requireNotNull(turn.assistantMessageId)
        val store = Store(context, fixture.database)
        try {
            store.readableDatabase.rawQuery("SELECT body FROM messages WHERE id=?", arrayOf(id.toString())).use {
                if (it.moveToFirst()) it.getString(0) else ""
            }
        } finally { store.close() }
    }
    private fun memoryCount(): Int = runBlocking {
        val store = Store(context, fixture.database)
        try { ProcessingRepository(store).memories("seed-lin").size } finally { store.close() }
    }
    private fun firstMemoryId(): String = runBlocking {
        val store = Store(context, fixture.database)
        try { ProcessingRepository(store).memories("seed-lin").single().id } finally { store.close() }
    }
    private fun waitFor(tag: String, timeout: Long = 30000) {
        ui.waitUntil(timeout) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun hideIme() {
        ui.activityRule.scenario.onActivity { a ->
            (a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(a.window.decorView.windowToken, 0)
            a.window.decorView.clearFocus()
        }
        ui.waitForIdle()
    }
    private fun shot(name: String) {
        hideIme(); android.os.SystemClock.sleep(400)
        val dir = File(context.getExternalFilesDir(null), "evidence-real-recall").apply { mkdirs() }
        val b = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(dir, "$name.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        b.recycle()
    }
    private fun openChat() {
        ui.waitUntil(30000) { ui.onAllNodesWithTag("tab-0").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("tab-0").performClick()
        ui.waitUntil(30000) { ui.onAllNodesWithText("林间").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("林间").performClick()
        waitFor("message-input")
    }
    private fun send(text: String) {
        ui.onNodeWithTag("message-input").performTextInput(text)
        ui.onNodeWithTag("send-message").performClick()
    }

    @Test fun aRecallThatWasAlreadyInTheReferenceSaysRemembered() {
        assumeTrue("没有 sf-key.txt，跳过真实服务商验收", fixture.available)
        openChat()
        send("记住一件事，直接存不用解释：我最喜欢吃意大利面。")
        ui.waitUntil(300000) { settled(1) }
        ui.waitForIdle()
        val firstTurn = turns().single()
        assertTrue("第一轮应正常完成，实际 ${firstTurn.state}/${firstTurn.failure}",
            firstTurn.state == TurnState.COMPLETED)
        assertEquals("第一轮必须真的写入一条记忆", 1, memoryCount())
        val storedId = firstMemoryId()
        shot("00-saved"); android.os.SystemClock.sleep(1200)
        // 第二轮：这条记忆已经在本轮参考资料里，再让角色回查一次（明确要求先调用工具）。
        send("你还记得我最喜欢吃什么吗？请先用记忆回查工具 agent_recall_search 查一次“意大利面”，再回答。")
        ui.waitUntil(300000) { settled(2) }
        ui.waitForIdle()
        val all = turns()
        assertEquals(2, all.size)
        val second = all.last()
        assertEquals(TurnState.COMPLETED, second.state)
        val receipt = second.toolActivities.firstOrNull { it.tool in ChatToolActivity.RECALL_TOOLS }
        assertNotNull("第二轮必须真的调用一次记忆回查，实际 ${second.toolActivities.map { it.tool to it.state }}", receipt)
        // 证明确实走了“记忆已在本轮参考资料里”的去重分支，而不是普通检索成功。
        assertTrue("第二轮请求必须把这条记忆作为参考资料给出", fixture.requestMemoryIds.any { storedId in it })
        assertTrue("工具结果必须带“已在上方参考资料中”的去重摘要：${fixture.toolOutputs}",
            fixture.toolOutputs.any { it.contains("already_in_context") && it.contains("已在上方参考资料中") })
        assertEquals("回查已结算成功", "success", receipt!!.state)
        assertEquals("回想出结果说“想起来了”", "想起来了", receipt.label())
        assertTrue("想起来了不能挂“没找到”小字", receipt.detail.isBlank())
        val reply = replyOf(second)
        assertTrue("角色要答得出这条记忆，实际：$reply", reply.contains("意大利面"))
        assertEquals("界面不能出现“没想起来”", 0, ui.onAllNodesWithText("没想起来").fetchSemanticsNodes().size)
        assertTrue("界面要有“想起来了”胶囊", ui.onAllNodesWithText("想起来了").fetchSemanticsNodes().isNotEmpty())
        shot("01-remembered")
        android.util.Log.e("REALRECALL", "turn=${second.id} tool=${receipt.tool} state=${receipt.state} label=${receipt.label()} detail=${receipt.detail} reply=$reply")
    }
}
