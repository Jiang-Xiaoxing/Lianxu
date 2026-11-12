package app.lianxu.independent

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** 她每组回复最上面、头像右侧显示角色名（类似微信）；用户自己的消息不显示名字。合成数据。 */
class ChatSenderNameUiTest {
    private val reply = "哟，就一个“hi”，这才想起我？\n\n这会儿快四点了，手上的活还在吗？"
    private val client = object : TextChatClient {
        override suspend fun complete(config: ModelConfig, key: String, request: TextChatRequest): TextChatResult =
            syntheticCompanionStage(request) ?: TextChatResult.Success(reply, displayReasoning = "他就发了个hi，我先逗他一句，再问问他手上的活。")
    }
    @get:Rule(order = 1) val mock = MockChatFixture({ client })
    @get:Rule(order = 2) val ui = createAndroidComposeRule<MainActivity>()
    @Test fun roleGroupShowsNameAboveEverythingAndUserGroupDoesNot() {
        ui.waitUntil(30000) { ui.onAllNodesWithTag("tab-0").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("tab-0").performClick()
        ui.waitUntil(30000) { ui.onAllNodesWithText("林间").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("林间").performClick()
        ui.onNodeWithTag("message-input").performTextInput("hi")
        ui.onNodeWithTag("send-message").performClick()
        val instrument = InstrumentationRegistry.getInstrumentation()
        fun completed() = runBlocking {
            val store = Store(instrument.targetContext, mock.database)
            try { ChatLedger(store).all().singleOrNull()?.takeIf { it.state == TurnState.COMPLETED } } finally { store.close() }
        }
        ui.waitUntil(30000) { completed() != null }
        val turn = requireNotNull(completed())
        val id = requireNotNull(turn.assistantMessageId)
        ui.waitUntil(15000) { ui.onAllNodesWithTag("message-$id").fetchSemanticsNodes().isNotEmpty() }
        ui.waitForIdle()
        val names = ui.onAllNodes(SemanticsMatcher("sender-name") { n ->
            n.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }.startsWith("sender-name-")
        }, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("只有她的那一组显示名字，用户的 hi 不显示", 1, names.size)
        val name = ui.onNodeWithTag("sender-name-$id", useUnmergedTree = true)
        name.assertTextEquals("林间")
        val nameBox = name.getUnclippedBoundsInRoot()
        val bodyTop = ui.onNodeWithTag("message-$id").getUnclippedBoundsInRoot().top
        assertTrue("名字必须在正文气泡上面", nameBox.bottom <= bodyTop)
        val pills = ui.onAllNodesWithText("嗯…我记住了")
        repeat(pills.fetchSemanticsNodes().size) { i ->
            assertTrue("名字必须在胶囊上面", nameBox.bottom <= pills[i].getUnclippedBoundsInRoot().top)
        }
        val think = ui.onAllNodesWithTag("thinking-card-message-$id", useUnmergedTree = true)
        val thinkCount = think.fetchSemanticsNodes().size
        assertTrue("应有思考框", thinkCount > 0)
        repeat(thinkCount) { i -> assertTrue("名字必须在思考框上面", nameBox.bottom <= think[i].getUnclippedBoundsInRoot().top) }
        val dir = requireNotNull(instrument.targetContext.getExternalFilesDir("names")).apply { mkdirs() }
        val bitmap = instrument.uiAutomation.takeScreenshot()
        File(dir, "name_added.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
