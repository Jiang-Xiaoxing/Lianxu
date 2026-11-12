package app.lianxu.independent

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 修正版排版：两次真实工具调用产生两颗框外胶囊，思考按断点拆成三张独立卡；
 * 胶囊在聊天流里、两张卡之间，不被任何思考卡包住。后段没有文字时不补卡。
 */
class ToolReceiptCardBreakUiTest {
    private fun call(id: String, content: String) = ChatToolCall(id, "agent_memory_update",
        """{"op":"add","target":"","category":"PREFERENCE_BOUNDARY","about":"USER","content":"$content","evidence":["S1"]}""")
    private val client = object : TextChatClient {
        override suspend fun complete(config: ModelConfig, key: String, request: TextChatRequest): TextChatResult {
            syntheticCompanionStage(request)?.let { return it }
            return when (request.messages.count { it.role == "tool" }) {
                0 -> TextChatResult.Success("", listOf(call("c1", "我叫小周")), displayReasoning = "第一段思考。")
                1 -> TextChatResult.Success("", listOf(call("c2", "我喜欢吃意大利面")), displayReasoning = "第二段思考。")
                else -> TextChatResult.Success("两件事都记下了。", displayReasoning = "第三段思考。")
            }
        }
    }
    @get:Rule(order = 0) val mock = MockChatFixture({ client }, "https://example.invalid/v1", wrapFinalReply = false)
    @get:Rule(order = 1) val ui = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun waitFor(tag: String, timeout: Long = 30000) {
        ui.waitUntil(timeout) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun openChat() {
        ui.waitUntil(30000) { ui.onAllNodesWithTag("tab-0").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("tab-0").performClick()
        ui.waitUntil(30000) { ui.onAllNodesWithText("林间").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("林间").performClick()
        waitFor("message-input")
    }
    private fun completedTurn() = runBlocking {
        val store = Store(context, mock.database)
        try { ChatLedger(store).all().singleOrNull()?.takeIf { it.state == TurnState.COMPLETED } } finally { store.close() }
    }

    @Test fun twoCallsSitBetweenThreeIndependentThinkingCards() {
        val store = Store(context, mock.database)
        try { runBlocking { ProcessingRepository(store).saveMemoryRules(MemoryRules(immediateWrite = true)) } } finally { store.close() }
        openChat()
        ui.onNodeWithTag("message-input").performTextInput("记住两件事：我叫小周，喜欢吃意大利面。")
        ui.onNodeWithTag("send-message").performClick()
        // 持久条件：以数据库里的 COMPLETED 为准，不赌短暂的“停止回复”按钮会出现。
        ui.waitUntil(30000) { completedTurn() != null }
        ui.waitForIdle()
        val turn = requireNotNull(completedTurn())
        assertEquals("两次合成调用必须各有一颗回执", 2, turn.toolActivities.count { it.tool == "agent_memory_update" })
        val id = requireNotNull(turn.assistantMessageId)
        // DB commit precedes AppState reload/Compose publication. Await that actual UI boundary;
        // the following bounds assertions still verify every card and capsule's placement.
        waitFor("thinking-card-message-$id-2")
        assertEquals("界面必须显示两颗真实回执", 2, ui.onAllNodesWithText("嗯…我记住了").fetchSemanticsNodes().size)
        val cards = (0..2).map { ui.onNodeWithTag("thinking-card-message-$id-$it").getUnclippedBoundsInRoot() }
        val capsules = (0..1).map { ui.onAllNodesWithText("嗯…我记住了")[it].getUnclippedBoundsInRoot() }
        val body = ui.onNodeWithTag("message-$id").getUnclippedBoundsInRoot()
        assertTrue("第一颗胶囊必须在第一张卡下面：${cards[0].bottom} / ${capsules[0].top}", capsules[0].top >= cards[0].bottom)
        assertTrue("第一颗胶囊必须在第二张卡上面：${capsules[0].bottom} / ${cards[1].top}", capsules[0].bottom <= cards[1].top)
        assertTrue("第二颗胶囊必须在第二张卡下面：${cards[1].bottom} / ${capsules[1].top}", capsules[1].top >= cards[1].bottom)
        assertTrue("第二颗胶囊必须在第三张卡上面：${capsules[1].bottom} / ${cards[2].top}", capsules[1].bottom <= cards[2].top)
        assertTrue("正文必须在最后一张思考卡下面：${cards[2].bottom} / ${body.top}", body.top >= cards[2].bottom)
        // 胶囊不能落在任何思考卡边框里面。
        capsules.forEachIndexed { i, capsule ->
            cards.forEachIndexed { j, card ->
                val inside = capsule.top >= card.top && capsule.bottom <= card.bottom
                assertTrue("第 ${i + 1} 颗胶囊被第 ${j + 1} 张思考卡包住了", !inside)
            }
        }
    }
}
