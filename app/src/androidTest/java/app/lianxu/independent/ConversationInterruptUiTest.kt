package app.lianxu.independent

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Actual input/button interaction using synthetic transport; the old request deliberately returns late. */
class ConversationInterruptUiTest {
    private val release=CompletableDeferred<Unit>()
    private val entered=CompletableDeferred<Unit>()
    private val returned=CompletableDeferred<Unit>()
    private val calls=AtomicInteger()
    private val client=object:TextChatClient {
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
            calls.incrementAndGet()
            return if(request.latestUserText()=="第一句")withContext(NonCancellable){
                entered.complete(Unit);release.await();returned.complete(Unit);TextChatResult.Success("不应出现的旧尾巴")
            }else TextChatResult.Success("接着你补的话说")
        }
    }
    @get:Rule(order=0) val fixture=MockChatFixture({client})
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    @After fun finish(){release.complete(Unit)}
    @Test fun typingDuringReplyKeepsSendAvailableAndInterruptsTheOldAttempt(){
        lateinit var vm:AppState
        ui.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[AppState::class.java]}
        ui.waitUntil(15000){vm.ready}
        ui.runOnIdle{vm.open("chat","seed-lin")}
        ui.onNodeWithTag("message-input").performTextInput("第一句")
        ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        ui.waitUntil(10000){entered.isCompleted&&vm.turns.any{it.friendId=="seed-lin"}}
        val first=vm.turns.single{it.friendId=="seed-lin"}
        // Test-only observation of the owning job: a client-return flag does not mean its
        // NonCancellable runner cleanup has finished. Do not add a production testing API.
        lateinit var firstJob:Job
        ui.runOnIdle{
            val runner=AppState::class.java.getDeclaredField("runner").apply{isAccessible=true}.get(vm)
            val jobs=ConversationRunner::class.java.getDeclaredField("jobs").apply{isAccessible=true}.get(runner) as Map<*,*>
            firstJob=requireNotNull(jobs["seed-lin"] as? Job)
        }
        ui.onNodeWithTag("cancel-turn-${first.id}").assertIsEnabled()
        ui.onNodeWithTag("message-input").assertIsEnabled().performTextInput("补一句")
        ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        ui.waitUntil(15000){vm.turns.count{it.friendId=="seed-lin"}==2&&vm.turns.any{it.id!=first.id&&it.state==TurnState.COMPLETED}}
        assertFalse("The next reply must finish before the old transport returns",returned.isCompleted)
        assertEquals(listOf("第一句","补一句"),vm.messages.filter{it.friendId=="seed-lin"&&it.mine}.map{it.text})
        ui.onNodeWithText("接着你补的话说").assertIsDisplayed()
        release.complete(Unit)
        ui.waitUntil(10000){returned.isCompleted&&firstJob.isCompleted}
        ui.runOnIdle{assertTrue(vm.messages.none{it.text=="不应出现的旧尾巴"});assertEquals(2,calls.get())}
        val instrument=InstrumentationRegistry.getInstrumentation()
        runBlocking{
            val store=Store(instrument.targetContext,fixture.database)
            try{assertTrue(store.messages().none{it.text=="不应出现的旧尾巴"})}finally{store.close()}
        }
        val bitmap=instrument.uiAutomation.takeScreenshot()
        File(instrument.targetContext.filesDir,"human-review-interrupt-ui.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
}
