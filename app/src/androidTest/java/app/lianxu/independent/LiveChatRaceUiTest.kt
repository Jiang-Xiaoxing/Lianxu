package app.lianxu.independent

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

@org.junit.runner.RunWith(org.junit.runners.Parameterized::class)
class LiveChatRaceUiTest(private val repetition:Int) {
    // Keep the warm-process path: the old test passed cold but clicked inside the guard when warm.
    companion object { @JvmStatic @org.junit.runners.Parameterized.Parameters(name="pressure-{0}") fun repetitions()=(1..8).map{arrayOf(it)} }
    private class Replies:TextChatClient {
        val pending=ConcurrentLinkedQueue<CompletableDeferred<TextChatResult>>();val calls=AtomicInteger()
        val all=ConcurrentLinkedQueue<CompletableDeferred<TextChatResult>>()
        fun queue()=CompletableDeferred<TextChatResult>().also{pending.add(it);all.add(it)}
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
            syntheticCompanionStage(request)?.let{return it}
            calls.incrementAndGet();return withContext(NonCancellable){checkNotNull(pending.poll()).await()}
        }
    }
    private val client=Replies()
    @get:Rule(order=0) val mock=MockChatFixture({client})
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private fun app():AppState {lateinit var value:AppState;ui.activityRule.scenario.onActivity{value=ViewModelProvider(it)[AppState::class.java]};return value}
    @After fun releaseResponses(){client.all.forEach{it.cancel()}}
    @Test fun doubleSendThenExplicitCancelRetryFailureAndLateCallback(){
        ui.waitUntil(15000){app().ready}
        ui.onNodeWithTag("add-menu").performClick();ui.onNodeWithText("创建角色").performClick()
        ui.onNodeWithTag("friend-name").performTextInput("合成·取消测试");ui.onNodeWithTag("friend-bio").performTextInput("仅供自动测试")
        ui.onNodeWithTag("create-friend").performScrollTo().performClick();ui.waitUntil(10000){app().page=="chat"}
        val late=client.queue();ui.onNodeWithTag("message-input").performTextInput("唯一用户消息");ui.onNodeWithTag("send-message").performTouchInput{doubleClick(delayMillis=40)}
        try{ui.waitUntil(10000){client.calls.get()==1&&app().turns.isNotEmpty()}}catch(error:Throwable){
            println("RACE_UI_FAILURE repetition=$repetition calls=${client.calls.get()} original=${error.javaClass.simpleName}")
            runCatching{
                val instrument=InstrumentationRegistry.getInstrumentation()
                val dir=java.io.File(instrument.targetContext.getExternalFilesDir(null),"failure-chat-race/${System.currentTimeMillis()}-$repetition-${System.nanoTime()}").apply{mkdirs()}
                val state=linkedMapOf<String,Any?>("calls" to client.calls.get(),"originalFailure" to error.toString())
                java.io.File(dir,"state.json").writeText(com.google.gson.Gson().toJson(state))
                runCatching{val vm=app();state["page"]=vm.page;state["sending"]=vm.chatSending;state["turns"]=vm.turns;state["preview"]=vm.chatPreview}
                    .onFailure{state["viewModelError"]=it.toString()}
                state["imeVisible"]=runCatching{androidx.core.view.ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())}.fold({it},{"captureError:${it.javaClass.simpleName}"})
                state["sendBounds"]=runCatching{ui.onNodeWithTag("send-message").fetchSemanticsNode().boundsInRoot.toString()}.getOrElse{"captureError:${it.javaClass.simpleName}"}
                java.io.File(dir,"state.json").writeText(com.google.gson.Gson().toJson(state))
                runCatching{val bitmap=instrument.uiAutomation.takeScreenshot();try{java.io.File(dir,"screen.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}}
                    .onFailure{java.io.File(dir,"screenshot-error.txt").writeText(it.toString())}
            }.onFailure{println("RACE_UI_CAPTURE_ERROR ${it.javaClass.simpleName}")}
            throw error
        };val turn=app().turns.last()
        lateinit var oldJob:Job
        ui.runOnIdle{
            val runner=AppState::class.java.getDeclaredField("runner").apply{isAccessible=true}.get(app())
            val jobs=ConversationRunner::class.java.getDeclaredField("jobs").apply{isAccessible=true}.get(runner) as Map<*,*>
            oldJob=requireNotNull(jobs[turn.friendId] as? Job)
        }
        val submittedAt=app().chatDrafts.getValue(turn.friendId).submittedAt.longValue
        assertTrue("第 $repetition 次双击不能把发送变成停止",app().chatSending)
        // The UI intentionally ignores the second Send tap while the button becomes Stop.
        // Wait for that existing gesture boundary, not for the deliberately held transport.
        ui.waitUntil(10000){android.os.SystemClock.uptimeMillis()-submittedAt>android.view.ViewConfiguration.getDoubleTapTimeout()}
        ui.onNodeWithTag("cancel-turn-${turn.id}").performClick()
        ui.waitUntil(10000){!app().chatSending}
        // Stop ends this answer. A new user message can start before the old transport returns;
        // only a genuine failed new attempt is retried.
        val failed=client.queue()
        ui.onNodeWithTag("message-input").performTextInput("继续这次合成测试")
        ui.onNodeWithTag("send-message").performClick()
        ui.waitUntil(10000){client.calls.get()==2}
        failed.complete(TextChatResult.Failure(ChatFailure.RATE_LIMIT))
        ui.waitUntil(10000){app().turns.last().state==TurnState.FAILED&&!app().chatSending}
        val retry=app().turns.last();assertNotEquals(turn.id,retry.id)
        late.complete(TextChatResult.Success("STALE-MUST-NOT-BE-SAVED"))
        ui.waitUntil(10000){oldJob.isCompleted&&app().turns.first{it.id==turn.id}.state==TurnState.SUPERSEDED}
        ui.onNodeWithText("STALE-MUST-NOT-BE-SAVED").assertDoesNotExist()
        val accepted=client.queue();ui.onNodeWithTag("retry-turn-${retry.id}").performScrollTo().performClick()
        ui.waitUntil(10000){client.calls.get()==3}
        accepted.complete(TextChatResult.Success("【Mock】当前有效回复"));ui.waitUntil(10000){app().turns.last().state==TurnState.COMPLETED}
        ui.waitForIdle();ui.onNodeWithText("STALE-MUST-NOT-BE-SAVED").assertDoesNotExist()
        runBlocking{
            val store=Store(InstrumentationRegistry.getInstrumentation().targetContext,mock.database)
            try{
                val own=store.messages().filter{it.friendId==turn.friendId}
                assertEquals(3,own.size);assertEquals(listOf("唯一用户消息","继续这次合成测试"),own.filter{it.mine}.map{it.text})
                val turns=ChatLedger(store).all();assertEquals(2,turns.size)
                assertEquals(1,turns.first{it.id==turn.id}.attempt);assertEquals(2,turns.first{it.id==retry.id}.attempt)
                assertEquals("【Mock】当前有效回复",own.single{!it.mine}.text)
            }finally{store.close()}
        }
    }
}
