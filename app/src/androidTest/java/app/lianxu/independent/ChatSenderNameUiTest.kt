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

/** 角色名只留在聊天顶部；消息、思考和工具保留，分组不重复显示姓名。合成数据。 */
class ChatSenderNameUiTest {
    private val reply = "哟，就一个“hi”，这才想起我？\n\n这会儿快四点了，手上的活还在吗？"
    private val client = object : TextChatClient {
        override suspend fun complete(config: ModelConfig, key: String, request: TextChatRequest): TextChatResult {
            syntheticCompanionStage(request)?.let{return it}
            if(request.tools.any{toolNameOf(it)=="agent_recall_search"}&&request.messages.none{it.role=="tool"&&it.name=="agent_recall_search"})return TextChatResult.Success("",listOf(ChatToolCall("name-ui-recall","agent_recall_search","""{"query":"合成记忆"}""")))
            return TextChatResult.Success(reply, displayReasoning = "他就发了个hi，我先逗他一句，再问问他手上的活。")
        }
    }
    @get:Rule(order = 1) val mock = MockChatFixture({ client })
    @get:Rule(order = 2) val ui = createAndroidComposeRule<MainActivity>()
    @Test fun roleNameStaysInHeaderWithoutRepeatedGroupNames() {
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
        try{ui.waitUntil(30000) { completed() != null }}catch(error:Throwable){
            println("NAME_UI_FAILURE original=${error.javaClass.simpleName} database=${mock.database}")
            runCatching{
                val dir=File(instrument.targetContext.getExternalFilesDir(null),"failure-chat-name/${System.currentTimeMillis()}-${System.nanoTime()}").apply{mkdirs()}
                val state=linkedMapOf<String,Any?>("database" to mock.database,"originalFailure" to error.toString())
                File(dir,"state.json").writeText(com.google.gson.Gson().toJson(state))
                runCatching{
                    val vm=androidx.lifecycle.ViewModelProvider(ui.activity)[AppState::class.java]
                    state["preview"]=vm.chatPreview;state["chatSending"]=vm.chatSending
                }.onFailure{state["viewModelError"]=it.toString()}
                runCatching{val store=Store(instrument.targetContext,mock.database);try{state["turns"]=runBlocking{ChatLedger(store).all()}}finally{store.close()}}
                    .onFailure{state["ledgerError"]=it.toString()}
                File(dir,"state.json").writeText(com.google.gson.Gson().toJson(state))
                runCatching{val bitmap=instrument.uiAutomation.takeScreenshot();try{File(dir,"screen.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}}
                    .onFailure{File(dir,"screenshot-error.txt").writeText(it.toString())}
            }.onFailure{println("NAME_UI_CAPTURE_ERROR ${it.javaClass.simpleName}")}
            throw error
        }
        val turn = requireNotNull(completed())
        val id = requireNotNull(turn.assistantMessageId)
        ui.waitUntil(15000) { ui.onAllNodesWithTag("message-$id").fetchSemanticsNodes().isNotEmpty() }
        ui.runOnIdle{ui.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(ui.activity.window.decorView.windowToken,0)}
        ui.waitForIdle()
        val names = ui.onAllNodes(SemanticsMatcher("sender-name") { n ->
            n.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }.startsWith("sender-name-")
        }, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("消息上方不再重复角色名，也不新增用户姓名", 0, names.size)
        ui.onNodeWithTag("chat-title").assertTextEquals("林间")
        ui.onNodeWithTag("chat-role-detail").assertHasClickAction()
        ui.onNodeWithTag("back").assertHasClickAction()
        ui.onNodeWithTag("message-$id").assertTextEquals(reply)
        ui.onNodeWithTag("bubble-$id-0",useUnmergedTree=true).assertIsDisplayed()
        ui.onNodeWithTag("bubble-$id-1",useUnmergedTree=true).assertIsDisplayed()
        assertTrue("角色头像仍显示",ui.onAllNodesWithTag("role-avatar-seed-lin").fetchSemanticsNodes().isNotEmpty())
        val think = ui.onAllNodes(SemanticsMatcher("thinking card") {it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag){""}.startsWith("thinking-card-message-$id")}, useUnmergedTree = true)
        val thinkCount = think.fetchSemanticsNodes().size
        assertTrue("应有思考框", thinkCount > 0)
        val recall=turn.toolActivities.first{it.tool=="agent_recall_search"}
        ui.onNodeWithTag("tool-result-${recall.id}",true).assertIsDisplayed()
        val dir = requireNotNull(instrument.targetContext.getExternalFilesDir("names")).apply { mkdirs() }
        val bitmap = instrument.uiAutomation.takeScreenshot()
        File(dir, "name_removed.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
