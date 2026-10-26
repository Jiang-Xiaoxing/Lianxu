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

class LiveChatRaceUiTest {
    private class Replies:TextChatClient {
        val pending=ConcurrentLinkedQueue<CompletableDeferred<TextChatResult>>();val calls=AtomicInteger()
        fun queue()=CompletableDeferred<TextChatResult>().also{pending.add(it)}
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls.incrementAndGet();return withContext(NonCancellable){checkNotNull(pending.poll()).await()}}
    }
    private val client=Replies()
    @get:Rule(order=0) val mock=MockChatFixture({client})
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private fun app():AppState {lateinit var value:AppState;ui.activityRule.scenario.onActivity{value=ViewModelProvider(it)[AppState::class.java]};return value}
    @Test fun doubleSendImmediateCancelRetryFailureAndLateCallback(){
        ui.waitUntil(15000){app().ready}
        ui.onNodeWithTag("add-menu").performClick();ui.onNodeWithText("添加朋友").performClick()
        ui.onNodeWithTag("friend-name").performTextInput("合成·取消测试");ui.onNodeWithTag("friend-bio").performTextInput("仅供自动测试")
        ui.onNodeWithTag("create-friend").performScrollTo().performClick();ui.waitUntil(10000){app().page=="chat"}
        val late=client.queue();ui.onNodeWithTag("message-input").performTextInput("唯一用户消息");ui.onNodeWithTag("send-message").performTouchInput{doubleClick(delayMillis=40)}
        ui.waitUntil(10000){client.calls.get()==1&&app().turns.isNotEmpty()};val turn=app().turns.last()
        ui.onNodeWithTag("cancel-turn-${turn.id}").performScrollTo().performClick()
        ui.waitUntil(10000){!app().chatSending&&app().turns.last().state==TurnState.CANCELLED}
        val failed=client.queue();ui.onNodeWithTag("retry-turn-${turn.id}").performScrollTo().performClick();ui.waitUntil(10000){client.calls.get()==2}
        failed.complete(TextChatResult.Failure(ChatFailure.RATE_LIMIT));ui.waitUntil(10000){app().turns.last().state==TurnState.FAILED&&!app().chatSending}
        val accepted=client.queue();ui.onNodeWithTag("retry-turn-${turn.id}").performScrollTo().performClick();ui.waitUntil(10000){client.calls.get()==3}
        accepted.complete(TextChatResult.Success("【Mock】当前有效回复"));ui.waitUntil(10000){app().turns.last().state==TurnState.COMPLETED}
        late.complete(TextChatResult.Success("STALE-MUST-NOT-BE-SAVED"));ui.waitForIdle();android.os.SystemClock.sleep(300)
        ui.onNodeWithText("STALE-MUST-NOT-BE-SAVED").assertDoesNotExist()
        runBlocking{val store=Store(InstrumentationRegistry.getInstrumentation().targetContext,mock.database);val own=store.messages().filter{it.friendId==turn.friendId};assertEquals(2,own.size);assertEquals(1,own.count{it.mine});assertEquals(3,ChatLedger(store).all().single().attempt);store.close()}
    }
}
