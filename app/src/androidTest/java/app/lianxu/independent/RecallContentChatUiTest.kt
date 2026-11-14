package app.lianxu.independent

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Actual Chat/MainActivity and repository; all texts and credentials are synthetic. */
class RecallContentChatUiTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val calls=AtomicInteger()
    private lateinit var memoryId:String;private lateinit var turnId:String;private lateinit var hit:ChatToolActivity
    private val original="你喜欢雨天的安静，也记得那次一起在窗边听雨。"
    @get:Rule(order=0) val mock=MockChatFixture({object:TextChatClient{
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls.incrementAndGet();error("No API in recall-content UI tests")}
    }},wrapFinalReply=false)
    @get:Rule(order=1) val seed=object:ExternalResource(){
        override fun before()=runBlocking{
            val store=Store(context,mock.database)
            try{
                val repo=ProcessingRepository(store)
                val memory=repo.manualSave("seed-lin",null,original,MemoryCategory.STATE_FACT,false,false,MemoryAbout.USER);memoryId=memory.id
                val capture=RecallContentCapture(repo,"seed-lin")
                val rows=listOf(mapOf<String,Any>("id" to memory.id,"source_type" to "companion_memory","content" to repo.describe(memory).take(600)))
                val refs=capture.capture(rows,mapOf("status" to "found","results" to rows),listOf(RecallContentOrigin("memory",memory.id,memory.version,"full")));check(!refs.isNullOrEmpty())
                val ledger=ChatLedger(store);val turn=ledger.begin("seed-lin","你还记得我们那次听雨吗？","synthetic");turnId=turn.id
                check(ledger.finish(turn.id,turn.attempt,"记得，点开回想胶囊就能看到当时想起的内容。"))
                hit=ChatToolActivity("recall-content-hit",turn.attempt,"让我想一想","success",kind=ChatToolActivity.READ,tool="agent_memory_read",recallGroup="content-response",recallRefs=refs)
                repo.tx{ChatToolJournal.put(it,turn.id,hit)}
            }finally{store.close()}
        }
        override fun after(){assertEquals(0,calls.get())}
    }
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    private fun waitFor(tag:String)=ui.waitUntil(20000){ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}
    private fun shot(name:String){
        ui.waitForIdle();val image=ui.onRoot().captureToImage().asAndroidBitmap()
        val dir=File(context.getExternalFilesDir(null),"evidence-recall-content").apply{mkdirs()}
        File(dir,name).outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }
    @Test fun actualChatShowsContentWithoutRepeatedStatusesAndRefreshesForMixedEditAndDelete(){
        waitFor("tab-0");ui.onNodeWithTag("tab-0").performClick()
        ui.waitUntil(20000){ui.onAllNodesWithText("林间").fetchSemanticsNodes().isNotEmpty()};ui.onNodeWithText("林间").performClick()
        waitFor("recall-toggle-${hit.id}");ui.onNodeWithTag("recall-toggle-${hit.id}").performClick()
        ui.waitUntil(10000){ui.onAllNodesWithText(original).fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("1. 想起来了").assertDoesNotExist();ui.onNodeWithText("agent_memory_read").assertDoesNotExist()
        shot("chat-recalled-content.png")
        val app=ViewModelProvider(ui.activity)[AppState::class.java]
        runBlocking{
            app.processing.tx{ChatToolJournal.put(it,turnId,hit.copy(id="recall-error",state="failed",detail="合成读取失败",recallRefs=null))}
            app.refreshProcessing()
        }
        ui.waitUntil(10000){ui.onAllNodesWithText("想起来了 · 部分失败").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText(original).assertIsDisplayed();ui.onNodeWithText("回想出错").assertIsDisplayed();shot("chat-recalled-mixed.png")
        runBlocking{app.processing.manualSave("seed-lin",memoryId,"后来修改成的新内容",MemoryCategory.STATE_FACT,false,false,MemoryAbout.USER);app.refreshProcessing()}
        ui.waitUntil(10000){ui.onAllNodesWithText(original).fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("后来修改成的新内容").assertDoesNotExist()
        runBlocking{app.processing.deleteMemory("seed-lin",memoryId);app.refreshProcessing()}
        ui.waitUntil(10000){ui.onAllNodesWithText("内容已删除").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText(original).assertDoesNotExist();shot("chat-recalled-deleted.png")
    }
}
