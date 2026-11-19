package app.lianxu.independent

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Synthetic UI -> production prompt/runner/store; fake transport only. */
class ChatTimeReferenceUiTest {
    private val inputs=listOf("今天就安静喝茶吧。","现在几点？请自然告诉我时分。","请只回复 [17:29]，保留方括号。")
    private val replies=CopyOnWriteArrayList(listOf("好呀，我刚泡好茶。","待模型输入中的程序时间","[17:29]"))
    private val histories=CopyOnWriteArrayList<Int>()
    private val afterRecall=java.util.concurrent.atomic.AtomicInteger()
    private val client:TextChatClient=object:TextChatClient{
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
            syntheticCompanionStage(request)?.let{return it}
            assertEquals("synthetic-key-only",key)
            val history=request.messages.filter{it.sourceMessageId.isNotBlank()}
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            val store=Store(context,mock.database)
            try{
                val originals=store.messagesFor("seed-lin").associateBy{it.id.toString()}
                history.forEach{m->assertEquals(originals.getValue(m.sourceMessageId).text,m.content);assertEquals(0,m.sourcePrefix)}
            }finally{store.close()}
            val reference=request.messages.single{it.turnContext};assertEquals("system",reference.role);assertEquals(reference,request.messages[request.messages.size-2])
            assertTrue(reference.content.contains("不要自行给正文加时间标签"))
            assertTrue(request.messages.last().thinkingContext)
            assertFalse(request.messages.last{it.role=="user"}.content.endsWith("\n\n"+CompanionPrompt.THINKING_REMINDER))
            assertEquals(1,request.messages.sumOf{m->Regex.fromLiteral("[思考语言]").findAll(m.content).count()})
            assertFalse(request.messages.first().content.contains("[思考语言]"))
            assertEquals(CompanionPrompt.thinkingLanguage("林间"),request.messages.last().content)
            val index=inputs.indexOf(request.latestUserText());assertTrue(index>=0)
            assertEquals(index*2,history.size);histories+=history.size
            if(index==0&&request.tools.any{toolNameOf(it)=="agent_recall_search"}){
                if(request.messages.none{it.role=="tool"&&it.name=="agent_recall_search"})return TextChatResult.Success("",listOf(ChatToolCall("time-recall","agent_recall_search","""{"query":"合成陶杯"}""")))
                afterRecall.incrementAndGet()
            }
            if(index==1){val time=requireNotNull(Regex("当前设备时间：[^\\n]*?([0-9]{2}:[0-9]{2})").find(reference.content)).groupValues[1];replies[1]="现在是 $time。"}
            return TextChatResult.Success(replies[index])
        }
    }
    @get:Rule(order=0) val mock:MockChatFixture=MockChatFixture({client})
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    @Test fun threeUiTurnsKeepRawHistoryAndRequestedTimeWhileNormalReplyHasNoInjectedLabel(){
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        val baseline=runBlocking{val store=Store(context,mock.database);try{store.messagesFor("seed-lin").map{it.text}}finally{store.close()}}
        ui.waitUntil(30000){ui.onAllNodesWithTag("tab-0").fetchSemanticsNodes().isNotEmpty()};ui.onNodeWithTag("tab-0").performClick()
        ui.waitUntil(30000){ui.onAllNodesWithText("林间").fetchSemanticsNodes().isNotEmpty()};ui.onNodeWithText("林间").performClick()
        fun turns()=runBlocking{val store=Store(context,mock.database);try{ChatLedger(store).all()}finally{store.close()}}
        inputs.forEachIndexed{i,input->
            ui.waitUntil(30000){ui.onAllNodes(hasTestTag("message-input") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("message-input").performTextInput(input)
            ui.waitUntil(30000){ui.onAllNodes(hasTestTag("send-message") and isEnabled()).fetchSemanticsNodes().isNotEmpty()};ui.onNodeWithTag("send-message").performClick()
            ui.waitUntil(30000){turns().count{it.state==TurnState.COMPLETED}==i+1}
            val id=requireNotNull(turns().last().assistantMessageId)
            ui.waitUntil(15000){ui.onAllNodesWithTag("message-$id").fetchSemanticsNodes().isNotEmpty()};ui.onNodeWithTag("message-$id").assertTextEquals(replies[i])
        }
        runBlocking{val store=Store(context,mock.database);try{assertEquals(baseline+inputs.zip(replies).flatMap{listOf(it.first,it.second)},store.messagesFor("seed-lin").map{it.text})}finally{store.close()}}
        assertTrue(histories.containsAll(listOf(0,2,4)))
        assertTrue("至少一次查记忆结果后的请求保留了末尾独立中文思考提示且用户原话不变",afterRecall.get()>0)
        ui.activityRule.scenario.onActivity{a->a.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(a.window.decorView.windowToken,0)}
        ui.waitForIdle();val folder=File(context.getExternalFilesDir(null),"chat-time-reference/ui").apply{mkdirs()};val bitmap=instrument.uiAutomation.takeScreenshot()
        File(folder,"three-synthetic-turns.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
}
