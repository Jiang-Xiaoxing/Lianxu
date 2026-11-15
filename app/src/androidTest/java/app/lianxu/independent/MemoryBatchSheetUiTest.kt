package app.lianxu.independent

import android.content.ContentValues
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

private fun batchSheetShot(name:String){
    val i=InstrumentationRegistry.getInstrumentation()
    val dir=File(i.targetContext.getExternalFilesDir(null),"evidence-memory-batch-sheet").apply{mkdirs()}
    val bitmap=i.uiAutomation.takeScreenshot()
    File(dir,name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    bitmap.recycle()
}

/** Production sheet, synthetic display data; no provider, account or system settings. */
class MemoryBatchSheetUiTest {
    @get:Rule val ui=createComposeRule()
    private var shown by mutableStateOf(true)
    private var details by mutableStateOf<MemoryPillDetails?>(null)
    private fun render(value:MemoryPillDetails,dark:Boolean=false,small:Boolean=false){
        details=value
        ui.setContent{
            val density=LocalDensity.current
            val config=Configuration(LocalConfiguration.current).apply{if(small){screenWidthDp=360;screenHeightDp=640}}
            CompositionLocalProvider(LocalDensity provides Density(if(small)3f else density.density,if(small)2f else 1f),LocalConfiguration provides config){
                LianxuTheme(dark){Box(Modifier.fillMaxSize().background(ChatBackground)){
                    if(shown)MemoryPillDetailsSheet(details){shown=false}
                }}
            }
        }
        ui.onNodeWithText("这次整理的记忆").assertIsDisplayed()
    }
    @Test fun numberedHistoricalItemsAndUpdateBadgeUseTheApprovedSheet(){
        render(MemoryPillDetails.Available((1..5).map{BatchMemoryDetail("m$it",if(it==2)2 else 1,"这是第${it}条整理时保存的记忆。",it==2)}))
        (1..5).forEach{ui.onNodeWithText("$it.").assertIsDisplayed()}
        ui.onNodeWithTag("memory-batch-updated-m2").assertIsDisplayed()
        assertEquals(1,ui.onAllNodesWithText("更新").fetchSemanticsNodes().size)
        batchSheetShot("sheet-light-five.png")
    }
    @Test fun darkSmallScreenAtDoubleFontScrollsToTheLastItem(){
        render(MemoryPillDetails.Available((1..30).map{BatchMemoryDetail("m$it",1,"第${it}条合成记忆，内容较长时自然换行，保留完整文字。",it%3==0)}),dark=true,small=true)
        batchSheetShot("sheet-dark-small-2x-top.png")
        ui.onNodeWithTag("memory-batch-detail-list").performScrollToNode(hasTestTag("memory-batch-item-m30"))
        ui.onNodeWithText("30.").assertIsDisplayed()
        ui.onNodeWithText("第30条合成记忆，内容较长时自然换行，保留完整文字。").assertIsDisplayed()
        batchSheetShot("sheet-dark-small-2x-last.png")
    }
    @Test fun emptyUnavailableAndDeletedStatesContainNoHistoricalBody(){
        render(MemoryPillDetails.Available(emptyList()))
        ui.onNodeWithTag("memory-batch-empty").assertIsDisplayed()
        ui.runOnIdle{details=MemoryPillDetails.Unavailable}
        ui.onNodeWithText("详情不可用").assertIsDisplayed()
        ui.runOnIdle{details=MemoryPillDetails.Available(listOf(BatchMemoryDetail("gone",1,null,false)))}
        ui.onNodeWithText("已删除").assertIsDisplayed()
        batchSheetShot("sheet-deleted.png")
    }
    @Test fun tappingTheDimmedAreaDismissesTheSheet(){
        render(MemoryPillDetails.Available(listOf(BatchMemoryDetail("m",1,"合成记忆",false))))
        val i=InstrumentationRegistry.getInstrumentation()
        val time=android.os.SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP).forEach{action->
            val event=MotionEvent.obtain(time,time+if(action==MotionEvent.ACTION_UP)80 else 0,action,20f,120f,0)
            try{i.sendPointerSync(event)}finally{event.recycle()}
        }
        ui.waitUntil(5000){!shown}
        ui.onNodeWithTag("memory-batch-sheet").assertDoesNotExist()
    }
    @Test fun draggingDownFromTheHandleDismissesTheSheet(){
        render(MemoryPillDetails.Available(listOf(BatchMemoryDetail("m",1,"合成记忆",false))))
        ui.onNodeWithTag("memory-batch-sheet").performTouchInput{
            swipe(Offset(width/2f,12f),Offset(width/2f,height-4f),500)
        }
        ui.waitUntil(5000){!shown}
        ui.onNodeWithTag("memory-batch-sheet").assertDoesNotExist()
    }
}

/** Actual chat capsule -> repository -> historical sheet, in a disposable seeded database. */
class MemoryBatchChatUiTest {
    private val calls=AtomicInteger()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule(order=0) val mock=MockChatFixture({object:TextChatClient{
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
            calls.incrementAndGet();error("Opening a memory detail must not call a model")
        }
    }},wrapFinalReply=false)
    @get:Rule(order=1) val seed=object:ExternalResource(){
        override fun before(){
            val store=Store(context,mock.database)
            try{
                val db=store.writableDatabase
                val repo=ProcessingRepository(store)
                runBlocking{repo.tx{repo.saveMemory(it,StoredMemory("sheet-memory","seed-lin",MemoryCategory.STATE_FACT,"批次当时保存的原文",about=MemoryAbout.USER))}}
                db.insertOrThrow("memory_batches",null,ContentValues().apply{put("id","sheet-batch");put("friend_id","seed-lin");put("json","{}");put("state","COMMITTED")})
                db.insertOrThrow("memory_operations",null,ContentValues().apply{put("batch_id","sheet-batch");put("op_key","add");put("json",JSONObject().put("op","ADD").toString());put("digest","synthetic");put("result_id","sheet-memory");put("result_version",1);put("saved",1)})
                repeat(2){ChatMemoryPills.record(db,"seed-lin","sheet-batch",1)}
            }finally{store.close()}
        }
        override fun after(){assertEquals(0,calls.get())}
    }
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    private fun waitFor(tag:String){ui.waitUntil(20000){ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}}
    @Test fun originalCapsuleOpensHistoryAndRefreshesAnOpenSheetAfterDeletion(){
        waitFor("tab-0");ui.onNodeWithTag("tab-0").performClick()
        ui.waitUntil(20000){ui.onAllNodesWithText("林间").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("林间").performClick();waitFor("memory-pill-box-sheet-batch")
        ui.onNodeWithText("已整理 1 条记忆").performClick()
        ui.waitUntil(10000){ui.onAllNodesWithText("批次当时保存的原文").fetchSemanticsNodes().isNotEmpty()}
        batchSheetShot("chat-open-history.png")
        val app=ViewModelProvider(ui.activity)[AppState::class.java]
        runBlocking{app.processing.manualSave("seed-lin","sheet-memory","后来人工修改的内容",MemoryCategory.STATE_FACT,false,false,MemoryAbout.USER);app.refreshProcessing()}
        ui.waitUntil(10000){ui.onAllNodesWithText("批次当时保存的原文").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("后来人工修改的内容").assertDoesNotExist()
        runBlocking{app.processing.deleteMemory("seed-lin","sheet-memory");app.refreshProcessing()}
        ui.waitUntil(10000){ui.onAllNodesWithText("已删除").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("批次当时保存的原文").assertDoesNotExist()
        batchSheetShot("chat-open-deleted.png")
        assertEquals(1,app.memoryPills.count{it.batchId=="sheet-batch"})
    }
}
