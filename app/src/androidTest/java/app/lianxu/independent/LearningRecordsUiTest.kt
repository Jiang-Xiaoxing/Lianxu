package app.lianxu.independent

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic records through the real entry/query/detail; no provider or production data. */
class LearningRecordsUiTest {
    private val calls=AtomicInteger()
    @get:Rule(order=0) val fixture=MockChatFixture({object:TextChatClient{
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
            calls.incrementAndGet();return TextChatResult.Failure(ChatFailure.NETWORK)
        }
    }})
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var vm:AppState
    private fun start(){
        ui.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[AppState::class.java]}
        ui.waitUntil(15000){vm.ready}
    }
    private fun awaitTag(tag:String){ui.waitUntil(10000){ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}}
    private fun shot(name:String){
        ui.waitForIdle();val instrument=InstrumentationRegistry.getInstrumentation()
        val dir=File(instrument.targetContext.getExternalFilesDir(null),"evidence-learning-view").apply{mkdirs()}
        val bitmap=instrument.uiAutomation.takeScreenshot()
        File(dir,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    @Test fun allFourChipsAlsoSwipeAndKeepSearchAndIndependentListPositions(){
        start()
        runBlocking{val seed=LearningRecordsFixture(vm.processing.store)
            for(kind in listOf("viewpoint","interest","habit"))repeat(7){index->
                seed.event("seed-lin","合成${kind}来源$index"){wire,c->wire.put("learning",seed.learning("","propose",org.json.JSONArray().put(seed.evidence(c.current.turnId)),"合成${kind}记录$index").put("kind",kind))}
            };vm.refreshProcessing()}
        val before=runBlocking{vm.companion.learningRecords("seed-lin")};assertEquals(21,before.size)
        ui.runOnIdle{vm.open("role-learning","seed-lin")};awaitTag("learning-list")
        ui.onNodeWithTag("learning-search").performTextInput("合成")
        ui.runOnIdle{WindowInsetsControllerCompat(ui.activity.window,ui.activity.window.decorView).hide(WindowInsetsCompat.Type.ime())}
        fun swipe(left:Boolean){ui.onNodeWithTag("learning-list").performTouchInput{swipe(Offset(width*(if(left).8f else .2f),height*.7f),Offset(width*(if(left).2f else .8f),height*.7f),400)}}
        fun scroll()=ui.onNodeWithTag("learning-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        ui.capturePagerDrag("learning")
        ui.onNodeWithTag("learning-filter-all").assertIsSelected();swipe(false);ui.onNodeWithTag("learning-filter-all").assertIsSelected();shot("swipe-all-chips")
        ui.onNodeWithTag("learning-list").performScrollToIndex(7);val allPosition=scroll();assertTrue(allPosition>0)
        swipe(true);ui.onNodeWithTag("learning-filter-viewpoint").assertIsSelected();ui.onNodeWithTag("learning-list").performScrollToIndex(6);val viewPosition=scroll()
        swipe(true);ui.onNodeWithTag("learning-filter-interest").assertIsSelected();swipe(true);ui.onNodeWithTag("learning-filter-habit").assertIsSelected();swipe(true);ui.onNodeWithTag("learning-filter-habit").assertIsSelected();shot("swipe-habit")
        swipe(false);swipe(false);assertEquals(viewPosition,scroll(),2f);swipe(false);assertEquals(allPosition,scroll(),2f)
        ui.onNodeWithTag("learning-list").performScrollToIndex(0);ui.onNodeWithTag("learning-search").assertTextContains("合成")
        repeat(2){ui.onNodeWithTag("learning-filter-habit").performScrollTo().performClick();swipe(false);ui.onNodeWithTag("learning-filter-interest").assertIsSelected();ui.onNodeWithTag("learning-filter-all").performClick();ui.onNodeWithTag("learning-filter-all").assertIsSelected()}
        ui.onNodeWithTag("learning-list").performTouchInput{swipeUp()};ui.onNodeWithTag("learning-list").performScrollToIndex(0);ui.onNodeWithTag("learning-filter-all").assertIsSelected()
        ui.onNodeWithTag("learning-list").performTouchInput{swipe(Offset(width-1f,height*.7f),Offset(width*.3f,height*.7f),400)};ui.onNodeWithTag("learning-filter-all").assertIsSelected()
        assertEquals(before,runBlocking{vm.companion.learningRecords("seed-lin")});assertEquals(0,calls.get())
    }
    @Test fun roleEntrySearchDetailAndHistoryAreReadOnly(){
        start()
        val records=runBlocking{
            val seed=LearningRecordsFixture(vm.processing.store)
            val first=seed.proposeHabit("seed-lin")
            seed.reviseHabit(first,"seed-lin")
            vm.refreshProcessing()
            vm.companion.learningRecords("seed-lin")
        }
        val record=records.single()
        val before=runBlocking{vm.processing.store.messages()}
        ui.runOnIdle{vm.open("detail","seed-lin")}
        ui.onNodeWithTag("role-learning-open").performScrollTo().performClick()
        awaitTag("learning-item-${record.state.id}")
        ui.onNodeWithTag("learning-filter-interest").performClick()
        ui.onNodeWithText("没有找到匹配的记录").assertExists()
        ui.onNodeWithTag("learning-filter-all").performClick()
        ui.onNodeWithTag("learning-search").performTextInput("不会匹配的合成词")
        ui.onNodeWithText("没有找到匹配的记录").assertExists()
        ui.onNodeWithTag("learning-search").performTextClearance()
        ui.onNodeWithTag("learning-search").performTextInput(record.state.scope)
        ui.runOnIdle{WindowInsetsControllerCompat(ui.activity.window,ui.activity.window.decorView).hide(WindowInsetsCompat.Type.ime())}
        shot("01-learning-list")
        ui.onNodeWithTag("learning-item-${record.state.id}").performScrollTo().performClick()
        ui.onNodeWithTag("learning-detail").assertExists()
        ui.onNodeWithText("当前状态：可使用").assertExists()
        shot("02-learning-detail")
        ui.onNodeWithTag("learning-detail").performScrollToNode(hasText(record.evidence.first().text))
        ui.onNodeWithText(record.evidence.first().text).assertIsDisplayed()
        ui.onNodeWithTag("learning-detail").performScrollToNode(hasTestTag("learning-history"))
        ui.onNodeWithTag("learning-history").assertIsDisplayed()
        ui.onNodeWithTag("learning-detail").performScrollToNode(hasTestTag("learning-change-evidence-1"))
        ui.onNodeWithTag("learning-change-evidence-1").performClick()
        shot("03-learning-history")
        ui.onNodeWithTag("detail-back").performClick()
        ui.onNodeWithTag("learning-search").assertTextContains(record.state.scope)
        ui.onNodeWithTag("learning-back").performClick()
        ui.onNodeWithTag("role-learning-open").assertExists()
        assertEquals(0,calls.get())
        assertEquals(records,runBlocking{vm.companion.learningRecords("seed-lin")})
        assertEquals(before,runBlocking{vm.processing.store.messages()})
    }
    @Test fun roleSwitchAndWithdrawnSourceDoNotLeaveStaleLearning(){
        start()
        val first=runBlocking{
            val seed=LearningRecordsFixture(vm.processing.store)
            seed.proposeHabit("seed-lin").also{vm.refreshProcessing()}
        }
        val id=runBlocking{vm.companion.learningRecords("seed-lin").single().state.id}
        ui.runOnIdle{vm.open("role-learning","seed-lin")}
        awaitTag("learning-item-$id")
        ui.onNodeWithTag("learning-item-$id").performClick()
        runBlocking{vm.companion.forget("seed-lin",first.userMessageId);vm.refreshProcessing()}
        awaitTag("learning-detail-missing")
        ui.onNodeWithTag("learning-evidence").assertDoesNotExist()
        ui.runOnIdle{vm.open("role-learning","seed-an")}
        awaitTag("learning-empty")
        ui.onNodeWithText("时安 · 学习记录").assertExists()
        ui.onNodeWithText("还没有学习记录").assertExists()
        ui.onNodeWithTag("learning-item-$id").assertDoesNotExist()
        shot("04-learning-empty-other-role")
        assertEquals(0,calls.get())
    }
}
