package app.lianxu.independent

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import java.io.File

class BubbleSplitScreenshotTest {
    @get:Rule(order = 0) val gate = object : ExternalResource() {
        override fun before() { assumeTrue(InstrumentationRegistry.getArguments().getString("bubbleShot") == "true") }
    }
    private val reply = "哟，就一个“hi”，这才想起我？\n\n这会儿快四点了，手上的活还在吗？"
    private val client = object : TextChatClient {
        override suspend fun complete(config: ModelConfig, key: String, request: TextChatRequest): TextChatResult =
            syntheticCompanionStage(request) ?: TextChatResult.Success(reply, displayReasoning = "他就发了个hi，我先逗他一句，再问问他手上的活。")
    }
    @get:Rule(order = 1) val mock = MockChatFixture({ client })
    @get:Rule(order = 2) val ui = createAndroidComposeRule<MainActivity>()
    @Test fun savedReplyHasTwoBubbles() {
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
        val id = requireNotNull(completed()?.assistantMessageId)
        ui.waitUntil(15000) { ui.onAllNodesWithTag("message-$id").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("message-$id").assertTextEquals(reply)
        ui.onNodeWithTag("bubble-$id-0", useUnmergedTree = true).assertIsDisplayed()
        ui.onNodeWithTag("bubble-$id-1", useUnmergedTree = true).assertIsDisplayed()
        ui.onNodeWithTag("bubble-$id-2", useUnmergedTree = true).assertDoesNotExist()
        ui.onNodeWithTag("thinking-card-message-$id").assertIsDisplayed()
        ui.onNodeWithText("思考内容不作为聊天原话或记忆。").assertDoesNotExist()
        ui.waitForIdle()
        val dir = requireNotNull(instrument.targetContext.getExternalFilesDir("bubbles")).apply { mkdirs() }
        val bitmap = instrument.uiAutomation.takeScreenshot()
        File(dir, "bubbles.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
