package app.lianxu.independent

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.junit.*
import java.io.File

/** Design 1 (2026-11-02): a batch that failed three times is shown on the memory page with its rounds and reason; synthetic data only. */
class MemoryBatchFailureUiTest {
    @get:Rule(order=0)val mock=MockChatFixture()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private fun app():AppState{lateinit var v:AppState;ui.activityRule.scenario.onActivity{v=ViewModelProvider(it)[AppState::class.java]};return v}
    private fun shot(name:String){ui.waitForIdle();val dir=File(ui.activity.getExternalFilesDir(null),"xinghe-ui").apply{mkdirs()}
        File(dir,"$name.png").outputStream().use{ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}}
    @Test fun failedBatchIsVisibleWithRoundsReasonAndManualRetryThenClears(){
        ui.waitUntil(15000){app().ready};val v=app();val repo=v.processing;val id="failure-ui"
        runBlocking{
            repo.store.add(listOf(Friend(id,"合成角色","测试")),false)
            val l=ChatLedger(repo.store);repeat(2){val t=l.begin(id,"合成原话$it","synthetic");Assert.assertTrue(l.finish(t.id,t.attempt,"合成回复$it"))}
            val b=MemoryBatchRepository(repo);val w=b.prepare(id,repo.sources(id),"failure-ui")
            b.recordFailure(w,"UNKNOWN_TARGET",MemoryRepairFeedback.encode(listOf(MemoryRepairFeedback.Issue("op1","UNKNOWN_TARGET","target「M9」不是提供的已有记忆编号"))),3)
            b.fail(w,"UNKNOWN_TARGET") // Match a terminal failure; PREPARED work is recovered on foreground.
            v.refreshProcessing()
        }
        ui.runOnIdle{v.open("processing-role",id)}
        ui.waitUntil(10000){ui.onAllNodesWithTag("memory-batch-failure").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("memory-batch-failure").assertTextContains("有一段聊天没总结成功",substring=true)
        ui.onNodeWithText("更多",substring=true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick){it()};ui.onNodeWithTag("memory-maintenance").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick){it()}
        val node=ui.onNodeWithTag("memory-batch-failure")
        listOf("第 1—2 轮","总结失败","模型引用了不存在的记忆编号","已尝试 3 次","本批没有保存","原话仍留在最近窗口","手动重试","M9").forEach{node.assertTextContains(it,substring=true)}
        Assert.assertEquals(2,runBlocking{repo.recentSources(id).size});Assert.assertTrue(runBlocking{repo.memories(id).isEmpty()})
        shot("08-memory-batch-failure")
        runBlocking{MemoryBatchRepository(repo).clearFailure(id);v.refreshProcessing()}
        ui.waitUntil(10000){ui.onAllNodesWithTag("memory-batch-failure").fetchSemanticsNodes().isEmpty()}
    }
}
