package app.lianxu.independent

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asAndroidBitmap
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
    private val streamingJob=CompletableDeferred<kotlinx.coroutines.Job>()
    private val calls=AtomicInteger()
    private var emitReasoning=true
    private var failReply=false
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
            streamingJob.complete(requireNotNull(kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]))
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
                // Keep this response live after one complete paragraph has reached the page.
                onEvent(TextStreamEvent.Content(correction+if(toolReasoning)""else"\n\n"))
                if(!toolReasoning)finish.await()
                return TextChatResult.Success(correction,displayReasoning=if(toolReasoning)"先去翻翻旧记录。翻完了，没找到。" else "")
            }
            if(emitReasoning)onEvent(TextStreamEvent.Reasoning(reasoning))
            allowBody.await()
            // Plain-text answer: no JSON envelope to decode.
            if (failReply) return TextChatResult.Failure(ChatFailure.NETWORK)
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
    // Sample the label only once the 220ms page slide has settled.
    private val pageSettleMs = 320L

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
    private fun returnToList() {
        ui.onNodeWithTag("back").performClick()
        ui.onNodeWithTag("friend-row-seed-lin").assert(hasAnyDescendant(hasText("对方正在输入")) or hasText("对方正在输入"))
        ui.onAllNodesWithTag("replying-label",useUnmergedTree=true).assertCountEquals(1)
    }

    @Test fun listTypingStaysInPlaceAndCompletesAfterReentry() {
        start()
        // Infinite animations started under autoAdvance are cancelled by Compose's test policy.
        ui.mainClock.autoAdvance=false
        val label=ui.onNodeWithTag("replying-label",useUnmergedTree=true)
        try {
            ui.runOnUiThread { vm.back() }
            ui.mainClock.advanceTimeBy(pageSettleMs)
            val bounds=label.getUnclippedBoundsInRoot()
            val glyphSamples=mutableSetOf<Int>()
            for (i in 0..16) {
                ui.mainClock.advanceTimeBy(160)
                assertEquals(bounds,label.getUnclippedBoundsInRoot())
                screenshot("typing-frame-"+i.toString().padStart(2,'0'))
                val region=label.fetchSemanticsNode().boundsInWindow
                val file=File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
                    "evidence-chat-stream/ui/typing-frame-"+i.toString().padStart(2,'0')+".png")
                val bitmap=android.graphics.BitmapFactory.decodeFile(file.path)
                val width=region.width.toInt();val height=region.height.toInt()
                val pixels=IntArray(width*height)
                bitmap.getPixels(pixels,0,width,region.left.toInt(),region.top.toInt(),width,height)
                bitmap.recycle()
                glyphSamples += pixels.contentHashCode()
            }
            assertTrue("字形内需要多个真实中间帧",glyphSamples.size>=6)
        } finally { ui.mainClock.autoAdvance=true }
        ui.onNodeWithTag("friend-row-seed-lin").performClick()
        ui.onNodeWithTag("chat-title").assertTextContains("对方正在输入",substring=true)
        allowBody.complete(Unit);finish.complete(Unit)
        ui.waitUntil(20000){!vm.chatSending}
        ui.onNodeWithTag("back").performClick()
        label.assertDoesNotExist()
        ui.onNodeWithTag("friend-row-seed-lin").assertTextContains(vm.friends.first{it.id=="seed-lin"}.name)
    }

    @Test fun listTypingRestoresNameOnFailure() {
        failReply=true; start(); returnToList()
        ui.onNodeWithTag("friend-row-seed-lin").performClick()
        ui.onNodeWithTag("chat-title").assertTextEquals("对方正在输入…")
        allowBody.complete(Unit)
        ui.waitUntil(20000){!vm.isReplying("seed-lin")}
        assertRoleTitle("seed-lin")
        ui.onNodeWithTag("back").performClick()
        ui.onNodeWithTag("replying-label",useUnmergedTree=true).assertDoesNotExist()
    }

    private fun verifyTextSize(node: SemanticsNodeInteraction, size: Int) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(size.sp, layout.layoutInput.style.fontSize)
        assertEquals(1, layout.lineCount)
        assertFalse("文字不应出现省略裁切", layout.isLineEllipsized(0))
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(0, visibleEnd = true))
        val bounds = node.fetchSemanticsNode().boundsInWindow
        assertTrue("文字右侧超出可见区域", layout.getLineRight(0) <= bounds.width + 1)
        assertTrue("文字基线应在可见区域", layout.firstBaseline in 0f..bounds.height)
    }

    private fun assertRoleTitle(role: String) {
        val title = ui.onNodeWithTag("chat-title").assertTextEquals(vm.friends.first { it.id == role }.name)
        verifyTextSize(title, 22)
    }

    private fun verifySweep(node: SemanticsNodeInteraction, surface: String) {
        val bounds = node.fetchSemanticsNode().boundsInWindow
        val width = bounds.width.toInt()
        val height = bounds.height.toInt()
        val times = listOf(320, 800, 1440, 1920, 2256, 2432, 2560)
        val samples = mutableListOf<IntArray>()
        val instrument = InstrumentationRegistry.getInstrumentation()
        val dir = File(instrument.targetContext.getExternalFilesDir(null), "evidence-header-typing/$surface").apply { mkdirs() }
        var previous = pageSettleMs.toInt()
        // 2672 ms is the nearest 16 ms display frame to the 2670 ms cycle.
        for (time in times + times.map { it + 2672 }) {
            ui.mainClock.advanceTimeBy((time - previous).toLong())
            previous = time
            ui.waitForIdle()
            assertEquals(bounds, node.fetchSemanticsNode().boundsInWindow)
            // Capture the rendered Compose node at the paused frame, including its actual bounds.
            if (time == 800) {
                val screen = ui.onRoot().captureToImage().asAndroidBitmap()
                File(dir, "screen.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }
                screen.recycle()
            }
            val crop = node.captureToImage().asAndroidBitmap()
            File(dir, "glyph-$time.png").outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val pixels = IntArray(width * height)
            crop.getPixels(pixels, 0, width, 0, 0, width, height)
            samples += pixels
            crop.recycle()
        }
        fun distance(a: IntArray, b: IntArray) = a.indices.sumOf { kotlin.math.abs((a[it] and 255) - (b[it] and 255)) }
        val motion = distance(samples[1], samples[2])
        assertTrue("字形必须出现可测量的流光", motion > 100)
        assertEquals("停留期字形应静止", 0, distance(samples[4], samples[6]))
        for (i in times.indices) {
            assertTrue("下一周期同相位应该重合，采样时间 ${times[i]}", distance(samples[i], samples[i + times.size]) < motion / 8)
        }
    }

    @Test fun chatTitleAndListRepeatTheSameSweepWithoutClipping() {
        start()
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnUiThread { vm.back() }
            ui.mainClock.advanceTimeBy(pageSettleMs)
            val listLabel = ui.onNodeWithTag("replying-label", useUnmergedTree = true)
            verifyTextSize(listLabel, 16)
            verifySweep(listLabel, "list")
            ui.runOnUiThread { vm.open("chat", "seed-lin") }
            ui.mainClock.advanceTimeBy(pageSettleMs)
            val title = ui.onNodeWithTag("chat-title").assertTextEquals("对方正在输入…")
            verifyTextSize(title, 18)
            verifySweep(title, "header")
        } finally { ui.mainClock.autoAdvance = true }
        allowBody.complete(Unit)
        finish.complete(Unit)
        ui.waitUntil(20000) { !vm.isReplying("seed-lin") }
        assertRoleTitle("seed-lin")
    }

    @Test fun concurrentConversationsOwnTheirTypingLabel() {
        start()
        val other=vm.friends.first{it.id!="seed-lin"}.id
        ui.runOnIdle { vm.open("chat",other) }
        assertRoleTitle(other)
        ui.runOnIdle { assertTrue(vm.send("第二段合成对话")) }
        ui.onNodeWithTag("chat-title").assertTextEquals("对方正在输入…")
        ui.onNodeWithTag("back").performClick()
        ui.onAllNodesWithTag("replying-label",useUnmergedTree=true).assertCountEquals(2)
        ui.runOnIdle { vm.stopReply("seed-lin") }
        ui.onNodeWithTag("friend-row-$other").assertTextContains("对方正在输入")
        ui.onAllNodesWithTag("replying-label",useUnmergedTree=true).assertCountEquals(1)
        ui.onNodeWithTag("friend-row-seed-lin").performClick()
        assertRoleTitle("seed-lin")
        ui.onNodeWithTag("back").performClick()
        ui.onNodeWithTag("friend-row-$other").performClick()
        ui.onNodeWithTag("chat-title").assertTextEquals("对方正在输入…")
        ui.runOnIdle { vm.stopReply(other) }
        assertRoleTitle(other)
        ui.onNodeWithTag("back").performClick()
        ui.onAllNodesWithTag("replying-label",useUnmergedTree=true).assertCountEquals(0)
    }

    @Test fun listTypingRestoresNameOnStop() {
        start(); returnToList()
        ui.onNodeWithTag("friend-row-seed-lin").performClick()
        ui.runOnIdle { vm.stopReply("seed-lin") }
        assertRoleTitle("seed-lin")
        ui.onNodeWithTag("back").performClick()
        ui.onNodeWithTag("replying-label",useUnmergedTree=true).assertDoesNotExist()
    }

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
        val owningJob = kotlinx.coroutines.runBlocking { streamingJob.await() }
        ui.onNodeWithTag("cancel-turn-${turn.id}").performClick()
        ui.waitUntil(10000){!vm.chatSending}
        finish.complete(Unit)
        try{ui.waitUntil(10000){owningJob.isCompleted&&vm.turns.single{it.id==turn.id}.state==TurnState.SUPERSEDED}}
        catch(e:Throwable){
            val folder=File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"cancel-diagnostic").apply{mkdirs()}
            val state=vm.turns.single{it.id==turn.id};File(folder,"state.json").writeText(org.json.JSONObject().put("state",state.state.name).put("failure",state.failure?.name).put("jobCompleted",owningJob.isCompleted).put("jobCancelled",owningJob.isCancelled).put("sending",vm.chatSending).put("messages",vm.messages.filter{it.friendId=="seed-lin"}.map{it.text}).toString(2));throw e
        }
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
        val turn=vm.turns.single{it.friendId=="seed-lin"}
        val owner="${turn.id}-${turn.attempt}"
        ui.waitUntil(10000){vm.chatPreview?.text=="删好了。"}
        assertTrue(vm.chatPreview!!.tools.isEmpty())
        allowBody.complete(Unit)
        ui.waitUntil(10000){vm.chatPreview?.tools?.singleOrNull()?.state=="failed"&&vm.chatPreview?.text=="刚才没删掉，还没有找到那条消息。\n\n"&&vm.chatPreview!!.visible>=1}
        val liveLabel=hasText("遗忘") and hasAnyAncestor(hasTestTag("reply-preview-$owner"))
        ui.waitUntil(5000){ui.onAllNodes(liveLabel).fetchSemanticsNodes().isNotEmpty()}
        ui.onNode(liveLabel).performScrollTo().assertIsDisplayed()
        assertEquals(TurnState.PENDING,vm.turns.single{it.id==turn.id}.state)
        ui.onNodeWithText("删好了。").assertExists()
        ui.onNodeWithText("刚才没删掉，还没有找到那条消息。").assertExists()
        screenshot("05-real-chat-tool-failure-continuation")
        finish.complete(Unit)
        ui.waitUntil(15000){vm.turns.single{it.friendId=="seed-lin"}.state==TurnState.COMPLETED}
        ui.onNodeWithTag("reply-preview-$owner").assertDoesNotExist()
        val receipt=vm.turns.single{it.id==turn.id}.toolActivities.single()
        val savedLabel=hasText("遗忘") and hasAnyAncestor(hasTestTag("tool-result-${receipt.id}"))
        ui.waitUntil(5000){ui.onAllNodes(savedLabel).fetchSemanticsNodes().isNotEmpty()}
        ui.onNode(savedLabel).performScrollTo().assertIsDisplayed()
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
        val liveLabel=hasText("遗忘") and hasAnyAncestor(hasTestTag("reply-preview-$owner"))
        ui.waitUntil(5000){ui.onAllNodes(liveLabel).fetchSemanticsNodes().isNotEmpty()}
        ui.onNode(liveLabel).assertExists()
        assertEquals(TurnState.PENDING,vm.turns.single{it.id==turn.id}.state)
        screenshot("07-thinking-continues-after-tool")
        finish.complete(Unit)
        ui.waitUntil(15000){vm.turns.single{it.id==turn.id}.state==TurnState.COMPLETED}
        val saved=vm.messages.single{it.friendId=="seed-lin"&&!it.mine&&it.text=="刚才没删掉，还没有找到那条消息。"}
        assertEquals("先去翻翻旧记录。翻完了，没找到。",saved.displayReasoning)
    }
}
