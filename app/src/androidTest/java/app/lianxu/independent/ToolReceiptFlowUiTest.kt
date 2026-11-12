package app.lianxu.independent

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.assertTrue
import org.junit.Rule

/**
 * 真实聊天界面：思考A → 工具胶囊 → 思考B → 正文，提交后显示已保存；
 * 退出重进与 Activity 重建后顺序和状态不变，不再出现「候选已暂存」。
 */
class ToolReceiptFlowUiTest {
    private val toolFirst=java.util.concurrent.atomic.AtomicInteger()
    private val client=object:TextChatClient{
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
            syntheticCompanionStage(request)?.let{return it}
            val tookTool=request.messages.any{it.role=="tool"}
            if(!tookTool&&toolFirst.getAndIncrement()==0){
                return TextChatResult.Success("",listOf(ChatToolCall("stage","agent_memory_update",
                    """{"op":"add","target":"","category":"PREFERENCE_BOUNDARY","about":"USER","content":"喜欢吃意大利面","evidence":["S1"]}""")),
                    displayReasoning="先想一想。")
            }
            return TextChatResult.Success("记下了，意面我也爱吃。",displayReasoning="然后再说。")
        }
    }
    @get:Rule(order=0) val mock=MockChatFixture({client}, "https://example.invalid/v1")
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext

    private fun waitFor(tag:String,timeout:Long=15000){ui.waitUntil(timeout){ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}}
    private fun hideIme(){
        ui.activityRule.scenario.onActivity{a->(a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(a.window.decorView.windowToken,0);a.window.decorView.clearFocus()}
        ui.waitForIdle()
    }
    private fun shot(name:String){
        hideIme();android.os.SystemClock.sleep(400)
        val dir=java.io.File(context.getExternalFilesDir(null),"evidence-tool-receipt").apply{mkdirs()}
        val b=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(dir,"$name.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()
    }
    private fun openChat(){
        ui.waitUntil(20000){ui.onAllNodesWithTag("tab-0").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("tab-0").performClick()
        ui.waitUntil(20000){ui.onAllNodesWithText("林间").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("林间").performClick()
        waitFor("message-input")
    }
    private fun assertOrderAndState(){
        ui.waitUntil(20000){ui.onAllNodesWithText("记下了，意面我也爱吃。").fetchSemanticsNodes().isNotEmpty()}
        // 回执先按「暂存」出现，提交结算后同一页自动变成已保存——不用手动刷新。
        ui.waitUntil(20000){ui.onAllNodesWithText("嗯…我记住了").fetchSemanticsNodes().isNotEmpty()}
        ui.waitUntil(20000){ui.onAllNodesWithText("候选已暂存，尚未正式保存").fetchSemanticsNodes().isEmpty()}
        val staged=ui.onAllNodesWithText("候选已暂存，尚未正式保存").fetchSemanticsNodes()
        assertTrue("提交后不能再显示暂存文案",staged.isEmpty())
        val unsaved=ui.onAllNodesWithText("回话没完成，这条没存下").fetchSemanticsNodes()
        assertTrue("提交成功后不能显示未保存",unsaved.isEmpty())
        val capsule=ui.onAllNodesWithText("嗯…我记住了").onFirst().getUnclippedBoundsInRoot().top.value
        val first=ui.onNodeWithText("先想一想。").getUnclippedBoundsInRoot().top.value
        val second=ui.onNodeWithText("然后再说。").getUnclippedBoundsInRoot().top.value
        val body=ui.onNodeWithText("记下了，意面我也爱吃。").getUnclippedBoundsInRoot().top.value
        assertTrue("思考A要在胶囊上面：$first / $capsule",first<capsule)
        assertTrue("胶囊要在思考B上面：$capsule / $second",capsule<second)
        assertTrue("思考B要在正文上面：$second / $body",second<body)
    }

    @Test fun thinkingReceiptAndReplyKeepTheirOrderThroughReentryAndRecreation(){
        runBlocking{ProcessingRepository(Store(context,mock.database)).saveMemoryRules(MemoryRules(immediateWrite=true))}
        openChat()
        ui.onNodeWithTag("message-input").performTextInput("那你记一下，我喜欢吃意大利面")
        ui.onNodeWithTag("send-message").performClick()
        // 生成中（还没提交）就要按断点排：思考A 在胶囊上面，暂存文案如实出现。
        ui.waitUntil(20000){ui.onAllNodesWithText("候选已暂存，尚未正式保存").fetchSemanticsNodes().isNotEmpty()}
        val liveThinking=ui.onNodeWithText("先想一想。").getUnclippedBoundsInRoot().top.value
        val liveCapsule=ui.onAllNodesWithText("嗯…我记住了").onFirst().getUnclippedBoundsInRoot().top.value
        assertTrue("生成中也是思考A在胶囊上面：$liveThinking / $liveCapsule",liveThinking<liveCapsule)
        assertOrderAndState()
        shot("01-completed");android.os.SystemClock.sleep(4000)
        // 退出再进来
        ui.onNodeWithTag("back").performClick()
        openChat()
        assertOrderAndState()
        shot("02-reentered")
        // Activity 重建（相当于切到后台被回收后回来）
        ui.activityRule.scenario.recreate()
        val reopened=runCatching{waitFor("message-input",8000);true}.getOrDefault(false)
        if(!reopened)openChat()
        assertOrderAndState()
        shot("03-recreated")
        // 数据库里也只有一颗成功回执
        runBlocking{
            val store=Store(context,mock.database)
            try{
                val turn=ChatLedger(store).all().single()
                val receipt=turn.toolActivities.single{it.tool=="agent_memory_update"}
                Assert.assertEquals("success",receipt.state)
                Assert.assertTrue(receipt.detail.isBlank())
                Assert.assertEquals("先想一想。".length,receipt.reasoningAt)
                Assert.assertEquals(1,ProcessingRepository(store).memories(turn.friendId).size)
            }finally{store.close()}
        }
    }
}
