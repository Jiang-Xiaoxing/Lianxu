package app.lianxu.independent

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicInteger

/** Submission identity belongs to one edited draft, not to its text or a cooldown window. */
class ChatSubmissionUiTest {
    private val calls=AtomicInteger()
    private var holdFirst=false
    private val client=object:TextChatClient {
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
            if(calls.incrementAndGet()==1&&holdFirst)awaitCancellation()
            return TextChatResult.Success("合成回复")
        }
    }
    @get:Rule(order=0) val fixture=MockChatFixture({client})
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private fun open():AppState {
        lateinit var vm:AppState
        ui.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[AppState::class.java]}
        ui.waitUntil(15000){vm.ready};ui.runOnIdle{vm.open("chat","seed-lin")}
        return vm
    }
    private fun messages()=runBlocking {
        val store=Store(InstrumentationRegistry.getInstrumentation().targetContext,fixture.database)
        try{store.messages().filter{it.friendId=="seed-lin"}}finally{store.close()}
    }
    private fun awaitState(vm:AppState,condition:()->Boolean){
        try{ui.waitUntil(10000,condition)}catch(e:Throwable){
            val dir=java.io.File(ui.activity.getExternalFilesDir(null),"submission-evidence").apply{mkdirs()}
            java.io.File(dir,"failure.png").outputStream().use{InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            throw AssertionError("calls=${calls.get()} sending=${vm.chatSending} turns=${vm.turns} preview=${vm.chatPreview} userMessages=${messages().filter{it.mine}.map{it.text}} error=${vm.error}",e)
        }
    }
    private fun hideKeyboard(){
        ui.activityRule.scenario.onActivity{activity->(activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(activity.window.decorView.windowToken,0)}
        ui.waitForIdle();android.os.SystemClock.sleep(500);ui.waitForIdle()
    }
    private fun delayedPersistenceKeepsNewDraft(replacement:String?,recreate:Boolean=false){
        holdFirst=true;val vm=open();val blocked=CompletableDeferred<Any?>()
        ui.runOnIdle{
            val runner=AppState::class.java.getDeclaredField("runner").apply{isAccessible=true}.get(vm)
            val queue=ConversationRunner::class.java.getDeclaredField("inputs").apply{isAccessible=true}.get(runner)
            @Suppress("UNCHECKED_CAST")
            val tails=ChatInputQueue::class.java.getDeclaredField("tails").apply{isAccessible=true}.get(queue) as MutableMap<String,Any>
            tails["seed-lin"]=blocked
        }
        try{
            ui.onNodeWithTag("message-input").performTextInput("在吗")
            ui.onNodeWithTag("message-input").performImeAction()
            if(recreate){
                ui.activityRule.scenario.recreate()
                ui.onNodeWithTag("message-input").assertTextEquals("在吗")
            }
            ui.onNodeWithTag("send-message").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick){it()} // same draft, other input route
            ui.runOnIdle{assertTrue(vm.chatSending)};assertEquals(0,calls.get());assertTrue(messages().none{it.mine})
            if(replacement!=null){ui.onNodeWithTag("message-input").performTextClearance();ui.onNodeWithTag("message-input").performTextInput(replacement)}
            blocked.complete(null)
            ui.waitUntil(10000){calls.get()==1}
            ui.onNodeWithTag("message-input").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,androidx.compose.ui.text.AnnotatedString(replacement.orEmpty())))
            ui.runOnIdle{assertEquals(1,vm.turns.size)}
            assertEquals(listOf("在吗"),messages().filter{it.mine}.map{it.text})
            ui.runOnIdle{vm.stopReply("seed-lin")}
        }finally{blocked.complete(null)}
    }
    @Test fun oldPersistenceCallbackKeepsDifferentEditedDraft()=delayedPersistenceKeepsNewDraft("补一句")
    @Test fun oldPersistenceCallbackKeepsReenteredIdenticalDraft()=delayedPersistenceKeepsNewDraft("在吗")
    @Test fun recreationKeepsPendingSubmissionAndItsReceipt()=delayedPersistenceKeepsNewDraft(null,true)
    @Test fun recreationKeepsNewDraftWhileOldSubmissionFinishes()=delayedPersistenceKeepsNewDraft("重建后新输入",true)
    @Test fun keyboardAndAccessibilityActivateTheStandardButton(){
        val vm=open()
        ui.onNodeWithTag("message-input").performTextInput("实体键盘发送")
        hideKeyboard()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        ui.onNodeWithTag("send-message").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus){it()}
        ui.onNodeWithTag("send-message").assertIsFocused().performKeyInput{pressKey(androidx.compose.ui.input.key.Key.Enter)}
        awaitState(vm){vm.turns.singleOrNull()?.state==TurnState.COMPLETED}
        ui.onNodeWithTag("message-input").performTextInput("语义操作发送")
        ui.onNodeWithTag("send-message").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick){it()}
        ui.waitUntil(10000){vm.turns.size==2&&vm.turns.last().state==TurnState.COMPLETED}
        assertEquals(listOf("实体键盘发送","语义操作发送"),messages().filter{it.mine}.map{it.text})
        assertEquals(2,calls.get())
    }
    @Test fun doubleTapSubmitsOnceAndReenteredIdenticalTextInterruptsImmediately(){
        holdFirst=true;val vm=open()
        ui.onNodeWithTag("message-input").performTextInput("在吗")
        hideKeyboard()
        ui.onNodeWithTag("send-message").performTouchInput{doubleClick(delayMillis=40)}
        awaitState(vm){calls.get()==1&&vm.turns.size==1}
        ui.onNodeWithTag("message-input").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,androidx.compose.ui.text.AnnotatedString("")))
        assertEquals(listOf("在吗"),messages().filter{it.mine}.map{it.text})
        assertTrue(vm.chatSending)
        ui.onNodeWithTag("message-input").performTextInput("在吗")
        ui.onNodeWithTag("message-input").performImeAction();ui.onNodeWithTag("message-input").performImeAction()
        awaitState(vm){vm.turns.size==2&&vm.turns.last().state==TurnState.COMPLETED}
        assertEquals(2,calls.get());assertEquals(listOf("在吗","在吗"),messages().filter{it.mine}.map{it.text})
        assertEquals(listOf("在吗","在吗"),vm.messages.filter{it.friendId=="seed-lin"&&it.mine}.map{it.text})
        ui.onAllNodesWithText("在吗",useUnmergedTree=true).assertCountEquals(2)
        assertEquals(TurnState.SUPERSEDED,vm.turns.first().state)
        assertEquals(1,messages().count{it.text=="合成回复"})
    }
    @Test fun failedDurableSubmissionKeepsDraftAndCanRetryWithoutEditing(){
        val vm=open();val store=Store(InstrumentationRegistry.getInstrumentation().targetContext,fixture.database)
        val original=messages()
        try{
            store.writableDatabase.execSQL("CREATE TRIGGER synthetic_input_failure BEFORE INSERT ON messages WHEN NEW.mine=1 BEGIN SELECT RAISE(ABORT,'synthetic input failure'); END")
            ui.onNodeWithTag("message-input").performTextInput("原样重试")
            ui.onNodeWithTag("send-message").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick){it()}
            awaitState(vm){vm.error.isNotEmpty()&&!vm.chatSending}
            assertEquals(0,calls.get());assertEquals(original,messages())
            ui.onNodeWithTag("message-input").assertTextEquals("原样重试")
            store.writableDatabase.execSQL("DROP TRIGGER synthetic_input_failure")
            ui.onNodeWithTag("send-message").assertIsEnabled().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick){it()}
            awaitState(vm){vm.turns.singleOrNull()?.state==TurnState.COMPLETED}
            assertEquals(1,calls.get());assertEquals(listOf("原样重试"),messages().filter{it.mine}.map{it.text})
            ui.onNodeWithTag("message-input").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,androidx.compose.ui.text.AnnotatedString("")))
        }finally{store.close()}
    }
}
