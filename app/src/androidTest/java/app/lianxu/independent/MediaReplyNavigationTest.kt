package app.lianxu.independent

import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicInteger

/** Exercise a cancellable media request through the real navigation and persistence paths. */
class MediaReplyNavigationTest {
    private val reply=CompletableDeferred<TextChatResult>()
    private val calls=AtomicInteger()
    private val cancellations=AtomicInteger()
    private val returned=CompletableDeferred<Unit>()
    private var historyMessageId=0L
    private val client=object:TextChatClient {
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
            syntheticCompanionStage(request)?.let{return it}
            assertTrue(request.messages.any{it.videos.isNotEmpty()})
            calls.incrementAndGet()
            return try{reply.await().also{returned.complete(Unit)}}catch(e:CancellationException){cancellations.incrementAndGet();throw e}
        }
    }
    @get:Rule(order=0) val fixture=MockChatFixture({client})
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var vm:AppState
    private val body="只属于原会话的视频回复"
    @After fun release(){reply.complete(TextChatResult.Success(body))}

    private fun sendAndSwitch(withHistory:Boolean=false):ChatTurn {
        ui.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[AppState::class.java]}
        ui.waitUntil(20000){vm.ready}
        val inst=InstrumentationRegistry.getInstrumentation()
        ChatVideos.prepare(inst.context.assets.open("synthetic-video-with-audio.mp4"),inst.targetContext.cacheDir).use{video->
            if(withHistory)runBlocking{
                val ledger=ChatLedger(vm.processing.store)
                val previous=ledger.begin("seed-lin","旧视频说明","fixture",image=video)
                historyMessageId=previous.userMessageId
                check(ledger.finish(previous.id,previous.attempt,"已看过合成视频"))
            }
            ui.runOnIdle{vm.open("chat","seed-lin");assertTrue(vm.sendImage("合成视频提问",video))}
            ui.waitUntil(30000){calls.get()==1 && vm.turns.any{it.state==TurnState.PENDING}}
        }
        val turn=vm.turns.single{it.state==TurnState.PENDING}
        ui.runOnIdle{vm.open("chat","seed-an")}
        assertFalse(vm.chatSending)
        return turn
    }

    @Test fun switchingRolesKeepsTheReplyWithItsOriginalTurn(){
        val turn=sendAndSwitch()
        reply.complete(TextChatResult.Success(body))
        ui.waitUntil(15000){returned.isCompleted}
        ui.runOnIdle{assertNull(vm.chatPreview);assertEquals("seed-an",vm.activeId)}
        ui.runOnIdle{vm.open("chat","seed-lin")}
        ui.waitUntil(15000){vm.turns.any{it.id==turn.id && it.state==TurnState.COMPLETED}}
        assertEquals("seed-lin",vm.messages.single{it.text==body}.friendId)
        assertEquals(0,cancellations.get());assertEquals(1,calls.get())
    }

    @Test fun providerFailureBelongsToOriginalTurnAndDoesNotResend(){
        val turn=sendAndSwitch()
        reply.complete(TextChatResult.Failure(ChatFailure.REJECTED,400,"HTTP 400 synthetic rejection"))
        ui.waitUntil(15000){vm.turns.any{it.id==turn.id && it.state==TurnState.FAILED}}
        val failed=vm.turns.single{it.id==turn.id}
        assertEquals("seed-lin",failed.friendId);assertEquals(ChatFailure.REJECTED,failed.failure)
        assertTrue(vm.turns.none{it.friendId=="seed-an"})
        ui.runOnIdle{vm.open("chat","seed-lin")};ui.waitForIdle()
        assertEquals(0,cancellations.get());assertEquals(1,calls.get())
    }

    @Test fun editingPriorVideoSourceRejectsTheLateReply(){
        val turn=sendAndSwitch(withHistory=true)
        val changes=SourceChanges(vm.processing)
        runBlocking{changes.confirm(changes.preview("seed-lin",historyMessageId,"user","已修改视频说明",false))}
        reply.complete(TextChatResult.Success(body))
        ui.waitUntil(15000){vm.turns.any{it.id==turn.id && it.state==TurnState.FAILED}}
        assertEquals(ChatFailure.SOURCE_CHANGED,vm.turns.single{it.id==turn.id}.failure)
        assertTrue(runBlocking{vm.processing.store.messages()}.none{it.text==body})
        assertEquals(1,calls.get())
    }

    @Test fun deletingOriginalRoleStillCancelsPendingVideo(){
        val turn=sendAndSwitch()
        runBlocking{vm.deleteRole(vm.previewRoleDeletion(turn.friendId),true)}
        ui.waitUntil(15000){cancellations.get()==1}
        reply.complete(TextChatResult.Success(body))
        assertTrue(runBlocking{ChatLedger(vm.processing.store).all()}.none{it.id==turn.id})
        assertTrue(runBlocking{vm.processing.store.messages()}.none{it.text==body})
        assertEquals(1,calls.get())
    }
}
