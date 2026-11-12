package app.lianxu.independent

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * 记忆胶囊点开：只有提交成功且真的写进库的那颗能点；点开在胶囊正下方显示库里那条的当前内容，
 * 每颗单独展开、单独收起；改过的显示新内容，记录没了就写「这条记忆已删除」，重建后默认收起。
 */
class MemoryPillExpandUiTest {
    private fun call(id:String,content:String)=ChatToolCall(id,"agent_memory_update",
        """{"op":"add","target":"","category":"PREFERENCE_BOUNDARY","about":"USER","content":"$content","evidence":["S1"]}""")
    private val client=object:TextChatClient{
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
            syntheticCompanionStage(request)?.let{return it}
            return when(request.messages.count{it.role=="tool"}){
                0->TextChatResult.Success("",listOf(call("c1","用户叫小周。")),displayReasoning="第一段思考。")
                // 留一段可见的“暂存”窗口：这时胶囊还不够格点开。
                1->{delay(1200);TextChatResult.Success("",listOf(call("c2","用户喜欢吃意大利面。")),displayReasoning="第二段思考。")}
                else->TextChatResult.Success("两件事都记下了。",displayReasoning="第三段思考。")
            }
        }
    }
    @get:Rule(order=0) val mock=MockChatFixture({client},"https://example.invalid/v1",wrapFinalReply=false)
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext

    private fun waitFor(tag:String,timeout:Long=30000){ui.waitUntil(timeout){ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}}
    private fun openChat(){
        ui.waitUntil(30000){ui.onAllNodesWithTag("tab-0").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("tab-0").performClick()
        ui.waitUntil(30000){ui.onAllNodesWithText("林间").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("林间").performClick()
        waitFor("message-input")
    }
    private fun store()=Store(context,mock.database)
    private fun completedTurn():ChatTurn?=runBlocking{val s=store();try{ChatLedger(s).all().singleOrNull()?.takeIf{it.state==TurnState.COMPLETED}}finally{s.close()}}
    /** 事务提交、回执结算、页面刷新是三段；等收据真正结算并出现在界面上再点。 */
    private fun settled():Boolean=runBlocking{val s=store();try{val t=ChatLedger(s).all().singleOrNull();t!=null&&t.state==TurnState.COMPLETED&&t.toolActivities.filter{it.tool=="agent_memory_update"}.all{it.state=="success"&&it.canOpenMemory}}finally{s.close()}}
    private fun waitToggle(id:String,timeout:Long=20000)=ui.waitUntil(timeout){ui.onAllNodesWithTag("tool-receipt-toggle-$id").fetchSemanticsNodes().isNotEmpty()}
    private fun receipts():List<ChatToolActivity> = runBlocking{val s=store();try{ChatLedger(s).all().flatMap{it.toolActivities}.filter{it.tool=="agent_memory_update"}}finally{s.close()}}
    private fun memories():List<StoredMemory> = runBlocking{val s=store();try{ProcessingRepository(s).memories("seed-lin")}finally{s.close()}}

    @Test fun onlyCommittedPillsOpenAndEachShowsItsOwnStoredText(){
        runBlocking{val s=store();try{ProcessingRepository(s).saveMemoryRules(MemoryRules(immediateWrite=true))}finally{s.close()}}
        openChat()
        ui.onNodeWithTag("message-input").performTextInput("记住两件事：我叫小周，喜欢吃意大利面。")
        ui.onNodeWithTag("send-message").performClick()
        // 暂存：胶囊在，但还不能点，也不显示「已保存」。
        ui.waitUntil(30000){ui.onAllNodesWithText("候选已暂存，尚未正式保存").fetchSemanticsNodes().isNotEmpty()}
        val live=receipts()
        assertEquals("暂存阶段应有回执",1,live.size)
        ui.waitUntil(15000){ui.onAllNodesWithTag("tool-result-${live[0].id}").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("tool-result-${live[0].id}").assertExists()
        ui.onNodeWithTag("tool-receipt-toggle-${live[0].id}").assertDoesNotExist()
        assertTrue("暂存时不能显示已保存",ui.onAllNodesWithText("已保存").fetchSemanticsNodes().isEmpty())
        // 提交完成
        ui.waitUntil(30000){settled()}
        ui.waitForIdle()
        val turn=requireNotNull(completedTurn())
        val saved=turn.toolActivities.filter{it.tool=="agent_memory_update"}
        assertEquals(2,saved.size)
        val rows=memories()
        assertEquals(2,rows.size)
        saved.forEach{r->assertEquals("success",r.state);assertNotNull("提交成功必须指向真实写入的记录",r.memoryId);assertTrue(r.canOpenMemory)}
        assertEquals(saved.map{it.memoryId}.toSet(),rows.map{it.id}.toSet())
        // 两颗都能点，且点开的是各自那条。
        saved.forEach{waitToggle(it.id)}
        val first=requireNotNull(saved.minByOrNull{it.id})
        ui.onNodeWithTag("tool-receipt-toggle-${first.id}").performClick()
        ui.waitUntil(15000){ui.onAllNodesWithTag("memory-peek-${first.id}").fetchSemanticsNodes().isNotEmpty()}
        val firstRow=rows.single{it.id==first.memoryId}
        ui.onNodeWithText("已保存").assertExists()
        ui.onNodeWithText(firstRow.text).assertExists()
        // 只展开了这一颗：另一颗的卡不存在，第二颗的「已保存」不出现。
        assertEquals("一次只展开一颗",1,ui.onAllNodesWithText("已保存").fetchSemanticsNodes().size)
        val other=requireNotNull(saved.first{it.id!=first.id})
        ui.onNodeWithTag("memory-peek-${other.id}").assertDoesNotExist()
        // 再点一下收起。
        ui.onNodeWithTag("tool-receipt-toggle-${first.id}").performClick()
        ui.waitUntil(15000){ui.onAllNodesWithTag("memory-peek-${first.id}").fetchSemanticsNodes().isEmpty()}
        // 第二颗单独展开。
        ui.onNodeWithTag("tool-receipt-toggle-${other.id}").performClick()
        ui.waitUntil(15000){ui.onAllNodesWithTag("memory-peek-${other.id}").fetchSemanticsNodes().isNotEmpty()}
        val otherRow=rows.single{it.id==other.memoryId}
        ui.onNodeWithText(otherRow.text).assertExists()
        // 点开不改库、不重复调用。
        assertEquals(2,receipts().size)
        assertEquals(2,memories().size)
    }

    @Test fun anEditedRowShowsItsCurrentTextAndARemovedRowSaysDeleted(){
        runBlocking{val s=store();try{ProcessingRepository(s).saveMemoryRules(MemoryRules(immediateWrite=true))}finally{s.close()}}
        openChat()
        ui.onNodeWithTag("message-input").performTextInput("记住两件事：我叫小周，喜欢吃意大利面。")
        ui.onNodeWithTag("send-message").performClick()
        ui.waitUntil(30000){settled()}
        ui.waitForIdle()
        val saved=requireNotNull(completedTurn()).toolActivities.filter{it.tool=="agent_memory_update"}
        val rows=memories().associateBy{it.id}
        val first=saved.minByOrNull{it.id}!!.let{r->rows.getValue(requireNotNull(r.memoryId))}
        val receipt=saved.first{it.memoryId==first.id}
        waitToggle(receipt.id)
        // 先展开、收起一遍，证明可反复开关。
        ui.onNodeWithTag("tool-receipt-toggle-${receipt.id}").performClick()
        ui.waitUntil(15000){ui.onAllNodesWithTag("memory-peek-${receipt.id}").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText(first.text).assertExists()
        ui.onNodeWithTag("tool-receipt-toggle-${receipt.id}").performClick()
        ui.waitUntil(15000){ui.onAllNodesWithTag("memory-peek-${receipt.id}").fetchSemanticsNodes().isEmpty()}
        // 同一页里改记录、不重建：再次展开也要读到当前值，不能残留上次的内容。
        runBlocking{val s=store();try{val repo=ProcessingRepository(s);repo.tx{db->repo.saveMemory(db,first.copy(text="用户改名叫小周了。",version=first.version+1));Unit}}finally{s.close()}}
        ui.onNodeWithTag("tool-receipt-toggle-${receipt.id}").performClick()
        ui.waitUntil(15000){ui.onAllNodesWithTag("memory-peek-${receipt.id}").fetchSemanticsNodes().isNotEmpty()}
        ui.waitUntil(15000){ui.onAllNodesWithText("用户改名叫小周了。").fetchSemanticsNodes().isNotEmpty()}
        assertTrue("不能显示旧内容",ui.onAllNodesWithText(first.text).fetchSemanticsNodes().isEmpty())
        ui.onNodeWithTag("tool-receipt-toggle-${receipt.id}").performClick()
        ui.waitUntil(15000){ui.onAllNodesWithTag("memory-peek-${receipt.id}").fetchSemanticsNodes().isEmpty()}
        // 同一页里删记录、不重建：再次展开必须如实说已删除。
        runBlocking{val s=store();try{ProcessingRepository(s).deleteMemory(first.friendId,first.id)}finally{s.close()}}
        ui.onNodeWithTag("tool-receipt-toggle-${receipt.id}").performClick()
        ui.waitUntil(15000){ui.onAllNodesWithTag("memory-peek-${receipt.id}").fetchSemanticsNodes().isNotEmpty()}
        ui.waitUntil(15000){ui.onAllNodesWithText("这条记忆已删除").fetchSemanticsNodes().isNotEmpty()}
        assertTrue("删掉后不能显示旧内容",ui.onAllNodesWithText("用户改名叫小周了。").fetchSemanticsNodes().isEmpty())
        ui.onNodeWithText("这条记忆已删除").assertIsDisplayed()
        assertTrue("删掉的不能再写已保存",ui.onAllNodesWithText("已保存").fetchSemanticsNodes().isEmpty())
        val dir=java.io.File(context.getExternalFilesDir(null),"memory-task-a").apply{mkdirs()}
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let{b->java.io.File(dir,"after_capsule_deleted.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}
        // 重建后默认收起，再点开仍然是删除后的真实状态。
        ui.activityRule.scenario.recreate()
        val again=runCatching{waitFor("message-input",8000);true}.getOrDefault(false)
        if(!again)openChat()
        ui.waitUntil(30000){settled()}
        ui.waitForIdle()
        assertEquals("重建后必须默认收起",0,ui.onAllNodesWithText("已保存").fetchSemanticsNodes().size)
        val afterDelete=requireNotNull(completedTurn()).toolActivities.first{it.memoryId==first.id}
        waitToggle(afterDelete.id)
        ui.onNodeWithTag("tool-receipt-toggle-${afterDelete.id}").performClick()
        ui.waitUntil(15000){ui.onAllNodesWithTag("memory-peek-${afterDelete.id}").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("这条记忆已删除").assertExists()
        assertTrue("重建后删掉的也不能写已保存",ui.onAllNodesWithText("已保存").fetchSemanticsNodes().isEmpty())
        assertTrue("删掉后不能显示旧内容",ui.onAllNodesWithText("用户改名叫小周了。").fetchSemanticsNodes().isEmpty())
    }
}
