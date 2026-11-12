package app.lianxu.independent

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicInteger

/** Slow synthetic final replies; exercises real navigation, lifecycle and durable per-role state. */
class ChatNavigationContinuityTest {
    private val pending=CompletableDeferred<TextChatResult>()
    private val calls=AtomicInteger()
    private val client=object:TextChatClient {
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
            syntheticCompanionStage(request)?.let{return it}
            calls.incrementAndGet()
            return withContext(NonCancellable){pending.await()}
        }
    }
    @get:Rule(order=0) val fixture=MockChatFixture({client})
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var vm:AppState
    private fun start(){
        ui.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[AppState::class.java]}
        ui.waitUntil(15000){vm.ready}
        ui.runOnIdle{vm.open("chat","seed-lin");assertTrue(vm.send("合成慢回复测试"))}
        ui.waitUntil(15000){calls.get()==1&&vm.turns.any{it.friendId=="seed-lin"}}
    }
    @After fun release(){pending.complete(TextChatResult.Success("清理合成请求"))}
    @Test fun viewingProfileMemoryAndAnotherRoleDoesNotCancelOrMisattributeReply(){
        start()
        ui.onNodeWithTag("chat-role-detail").performClick()
        ui.runOnIdle{vm.open("processing-role","seed-lin");vm.back();vm.open("chat","seed-lin");assertTrue(vm.chatSending);vm.back();vm.home();vm.open("chat","seed-an")}
        assertFalse(vm.chatSending)
        pending.complete(TextChatResult.Success("只属于角色甲的合成回复"))
        assertEquals("seed-an",vm.activeId)
        assertTrue(vm.messages.none{it.friendId=="seed-an"&&it.text=="只属于角色甲的合成回复"})
        assertEquals(1,calls.get())
        // Delivery waits until this role's bubble is actually laid out. Returning to its page
        // resumes the same request, without attributing an unseen answer to the other role.
        ui.runOnIdle{vm.open("chat","seed-lin")}
        ui.waitUntil(15000){vm.turns.single{it.friendId=="seed-lin"}.state==TurnState.COMPLETED}
        assertEquals("seed-lin",vm.messages.single{it.text=="只属于角色甲的合成回复"}.friendId)
        assertEquals(1,calls.get())
        ui.onNodeWithText("只属于角色甲的合成回复").assertExists()
    }
    @Test fun stoppedActivityAndRecreationKeepTheSameRequest(){
        start()
        ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(vm.chatSending)
        pending.complete(TextChatResult.Success("后台完成的合成回复"))
        ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        ui.activityRule.scenario.recreate()
        ui.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[AppState::class.java]}
        ui.waitUntil(15000){vm.turns.single{it.friendId=="seed-lin"}.state==TurnState.COMPLETED}
        assertEquals(1,calls.get())
        ui.onNodeWithText("后台完成的合成回复").assertExists()
    }
    @Test fun explicitStopStillRejectsLateReply(){
        start()
        val turn=vm.turns.single{it.friendId=="seed-lin"}
        ui.onNodeWithTag("cancel-turn-${turn.id}").performClick()
        ui.waitUntil(10000){vm.turns.single{it.id==turn.id}.state==TurnState.CANCELLED}
        pending.complete(TextChatResult.Success("停止后不应保存"))
        ui.runOnIdle{vm.open("detail","seed-lin");vm.back()}
        ui.waitForIdle()
        assertTrue(vm.messages.none{it.text=="停止后不应保存"})
        assertFalse(vm.chatSending)
    }
}
