package app.lianxu.independent

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Native synthetic chunks through the real runner/UI. No network or provider credentials.
 * The reply streams directly; private accounting is a separate background request. */
class ChatReplyStreamingUiTest {
    private val allowBody=CompletableDeferred<Unit>()
    private val finish=CompletableDeferred<Unit>()
    private val calls=AtomicInteger()
    private var emitReasoning=true
    private var toolMode=false
    private var toolReasoning=false
    private val reply="那今晚先休息一下\n\n我陪你聊点轻松的。"
    private val firstPart="那今晚先休息一下"
    private val reasoning=(1..18).joinToString("\n"){"接口原生合成片段 $it：这里只验证原文显示和滚动，不是实际模型效果。"}
    private val client=object:TextChatClient {
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult =
            syntheticCompanionStage(request)?:syntheticFinalReply(request,TextChatResult.Success(reply))
        override suspend fun streamEvents(config:ModelConfig,key:String,request:TextChatRequest,onEvent:suspend(TextStreamEvent)->Unit):TextChatResult {
            syntheticCompanionStage(request)?.let{return it}
            val call=calls.incrementAndGet()
            if(toolMode){
                if(call==1){
                    if(toolReasoning)onEvent(TextStreamEvent.Reasoning("先去翻翻旧记录。"))
                    onEvent(TextStreamEvent.Content("删好了。"));allowBody.await()
                    return TextChatResult.Success("删好了。",listOf(ChatToolCall("synthetic-failed",JointControl.NAME,"""{"action":"forget","quote":"忘记之前那件事","targets":[999999],"authorization":"current_direct_request"}""")))
                }
                val correction="刚才没删掉，还没有找到那条消息。"
                if(toolReasoning)onEvent(TextStreamEvent.Reasoning("翻完了，没找到。"))
                allowBody.await()
                if(toolReasoning)finish.await()
                onEvent(TextStreamEvent.Content(correction))
                return TextChatResult.Success(correction,displayReasoning=if(toolReasoning)"先去翻翻旧记录。翻完了，没找到。" else "")
            }
            if(emitReasoning)onEvent(TextStreamEvent.Reasoning(reasoning))
            allowBody.await()
            // Plain-text answer: no JSON envelope to decode.
            onEvent(TextStreamEvent.Content(firstPart+"\n\n"))
            // A cancelled provider can finish late; the attempt guard must still reject it.
            withContext(NonCancellable){finish.await()}
            onEvent(TextStreamEvent.Content(reply.substring(firstPart.length+2)))
            return TextChatResult.Success(reply,displayReasoning=if(emitReasoning)reasoning else "")
        }
    }
    @get:Rule(order=0) val fixture=MockChatFixture({client})
    @get:Rule(order=1) val nativeStream=object:ExternalResource(){
        private lateinit var previous:()->TextChatClient
        override fun before(){
            previous=AppServices.createTextClient;AppServices.createTextClient={client}
            ModelVault(InstrumentationRegistry.getInstrumentation().targetContext,fixture.namespace).let{v->
                val model=v.list().single();v.save(model.copy(contextTokens=131072,outputTokens=16384),"synthetic-key-only")
            }
        }
        override fun after(){AppServices.createTextClient=previous}
    }
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var vm:AppState
    private fun start(input:String="今天有点累，想聊点轻松的。"){
        ui.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[AppState::class.java]}
        ui.waitUntil(15000){vm.ready}
        ui.runOnIdle{vm.open("chat","seed-lin");assertTrue(vm.send(input))}
        ui.waitUntil(15000){calls.get()==1&&vm.turns.any{it.friendId=="seed-lin"}}
    }
    private fun screenshot(name:String){
        ui.waitForIdle()
        val instrument=InstrumentationRegistry.getInstrumentation()
        val dir=File(instrument.targetContext.getExternalFilesDir(null),"evidence-chat-stream/ui").apply{mkdirs()}
        val bitmap=instrument.uiAutomation.takeScreenshot()
        File(dir,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    @After fun release(){allowBody.complete(Unit);finish.complete(Unit)}
    @Test fun streamingNavigationRecreationAndSavedReasoningKeepTheirOwner(){
        start()
        ui.waitUntil(10000){vm.chatPreview?.reasoning==reasoning}
        val turn=vm.turns.single{it.friendId=="seed-lin"}
        val owner="${turn.id}-${turn.attempt}"
        ui.onNodeWithTag("thinking-text-$owner").assertExists()
        ui.onNodeWithTag("cancel-turn-${turn.id}").assertIsEnabled()
        screenshot("01-receiving-native-reasoning")
        ui.onNodeWithTag("chat-role-detail").performClick()
        ui.runOnIdle{vm.back()}
        ui.activityRule.scenario.recreate()
        ui.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[AppState::class.java]}
        ui.waitUntil(10000){vm.chatPreview?.turnId==turn.id}
        ui.onNodeWithTag("thinking-text-$owner").assertExists()
        assertEquals(1,calls.get())
        allowBody.complete(Unit)
        ui.waitUntil(10000){vm.chatPreview?.text==firstPart+"\n\n"&&vm.chatPreview!!.visible>=1}
        ui.onNodeWithTag("reply-draft-$owner").assertTextEquals(firstPart)
        // Thinking is kept across the tool round: the live panel folds once the body streams,
        // but the same thought (not an empty one) is still there and stays in the saved message.
        assertEquals(reasoning,vm.chatPreview?.reasoning)
        ui.onNodeWithTag("thinking-toggle-$owner").assertExists()
        assertTrue(vm.messages.none{it.text==reply});assertTrue(kotlinx.coroutines.runBlocking{vm.processing.store.messages().any{it.text==firstPart}})
        screenshot("02-streaming-reply-draft")
        finish.complete(Unit)
        ui.waitUntil(15000){vm.turns.single{it.id==turn.id}.state==TurnState.COMPLETED}
        val saved=vm.messages.single{it.friendId=="seed-lin"&&!it.mine&&it.text==reply}
        assertEquals(reply,saved.text);assertEquals(reasoning,saved.displayReasoning)
        ui.onNodeWithTag("reply-preview-$owner").assertDoesNotExist()
        ui.onNodeWithTag("thinking-text-message-${saved.id}").assertDoesNotExist()
        ui.onNodeWithTag("thinking-toggle-message-${saved.id}").performScrollTo().performClick()
        ui.onNodeWithTag("thinking-text-message-${saved.id}").assert(hasScrollAction())
        screenshot("03-saved-reasoning-expanded")
        assertEquals(1,calls.get())
    }
    @Test fun noReasoningCreatesNoEmptyPanelAndStopRejectsTheLateDraft(){
        emitReasoning=false
        start();allowBody.complete(Unit)
        ui.waitUntil(10000){vm.chatPreview?.text==firstPart+"\n\n"&&vm.chatPreview!!.visible>=1}
        val turn=vm.turns.single{it.friendId=="seed-lin"}
        val owner="${turn.id}-${turn.attempt}"
        ui.onNodeWithTag("thinking-card-$owner").assertDoesNotExist()
        ui.onNodeWithTag("reply-draft-$owner").assertTextEquals(firstPart)
        lateinit var owningJob:kotlinx.coroutines.Job
        ui.runOnIdle{
            val runner=AppState::class.java.getDeclaredField("runner").apply{isAccessible=true}.get(vm)
            val jobs=ConversationRunner::class.java.getDeclaredField("jobs").apply{isAccessible=true}.get(runner) as Map<*,*>
            owningJob=requireNotNull(jobs["seed-lin"] as? kotlinx.coroutines.Job)
        }
        ui.onNodeWithTag("cancel-turn-${turn.id}").performClick()
        ui.waitUntil(10000){!vm.chatSending}
        finish.complete(Unit)
        ui.waitUntil(10000){owningJob.isCompleted&&vm.turns.single{it.id==turn.id}.state==TurnState.SUPERSEDED}
        ui.runOnIdle{vm.open("detail","seed-lin");vm.back()}
        ui.onNodeWithTag("reply-preview-$owner").assertDoesNotExist()
        ui.onNodeWithTag("retry-turn-${turn.id}").assertDoesNotExist()
        ui.onNodeWithTag("message-input").assertIsEnabled().performTextInput("接着说下一件事")
        ui.onNodeWithTag("send-message").assertIsEnabled()
        assertTrue(vm.messages.none{it.text==reply});assertTrue(kotlinx.coroutines.runBlocking{vm.processing.store.messages().any{it.text==firstPart}})
        screenshot("04-cancelled-draft-not-a-message")
    }
    @Test fun failedToolCapsuleSeparatesLivePreambleFromModelCorrection(){
        toolMode=true;start("忘记之前那件事")
        ui.waitUntil(10000){vm.chatPreview?.text=="删好了。"}
        assertTrue(vm.chatPreview!!.tools.isEmpty())
        allowBody.complete(Unit)
        ui.waitUntil(10000){vm.chatPreview?.tools?.singleOrNull()?.state=="failed"&&vm.chatPreview?.text=="刚才没删掉，还没有找到那条消息。"}
        ui.onNodeWithText("遗忘").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("删好了。").assertExists()
        ui.onNodeWithText("刚才没删掉，还没有找到那条消息。").assertExists()
        screenshot("05-real-chat-tool-failure-continuation")
        finish.complete(Unit)
        ui.waitUntil(15000){vm.turns.single{it.friendId=="seed-lin"}.state==TurnState.COMPLETED}
        ui.onNodeWithText("遗忘").performScrollTo().assertIsDisplayed()
        assertEquals(2,calls.get());assertTrue(vm.messages.none{it.text=="删好了。"})
        screenshot("06-real-chat-persisted-tool-result")
    }
    @Test fun toolCallTruncatesThinkingThenContinuesTheSameStream(){
        toolMode=true;toolReasoning=true
        start("忘记之前那件事")
        ui.waitUntil(10000){vm.chatPreview?.reasoning=="先去翻翻旧记录。"}
        val turn=vm.turns.single{it.friendId=="seed-lin"}
        val owner="${turn.id}-${turn.attempt}"
        // The preamble has already started, so the live panel is folded; the thinking itself must be intact.
        assertEquals("先去翻翻旧记录。",vm.chatPreview?.reasoning)
        ui.onNodeWithTag("thinking-toggle-$owner").assertExists()
        allowBody.complete(Unit)
        ui.waitUntil(10000){vm.chatPreview?.reasoning=="先去翻翻旧记录。翻完了，没找到。"}
        // Thinking continues after the tool, so the same panel re-opens with the receipt inside it.
        ui.onNodeWithTag("thinking-text-$owner-0").assertExists()
        ui.onNodeWithTag("thinking-text-$owner-1").assertExists()
        val preview=requireNotNull(vm.chatPreview)
        assertEquals(1,preview.thinkingBreaks.size)
        assertEquals(preview.tools.single().id,preview.thinkingBreaks.single().toolId)
        ui.onNodeWithText("先去翻翻旧记录。").assertExists()
        ui.onNodeWithText("翻完了，没找到。").assertExists()
        ui.onNodeWithText("遗忘").assertExists()
        screenshot("07-thinking-continues-after-tool")
        finish.complete(Unit)
        ui.waitUntil(15000){vm.turns.single{it.id==turn.id}.state==TurnState.COMPLETED}
        val saved=vm.messages.single{it.friendId=="seed-lin"&&!it.mine&&it.text=="刚才没删掉，还没有找到那条消息。"}
        assertEquals("先去翻翻旧记录。翻完了，没找到。",saved.displayReasoning)
    }
}
