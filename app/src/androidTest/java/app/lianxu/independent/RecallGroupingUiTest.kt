package app.lianxu.independent

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import com.google.gson.Gson

/** Production receipt components; synthetic journals only, no API or account. */
class RecallGroupingUiTest {
    @get:Rule val ui=createComposeRule()
    private fun row(id:String,state:String="success",group:String?="one",detail:String="",tool:String="agent_memory_read")=
        ChatToolActivity(id,1,"让我想一想",state,detail=detail,kind=ChatToolActivity.READ,tool=tool,reasoningAt=2,reasoningKnown=true,recallGroup=group)
    private fun render(rows:List<ChatToolActivity>,dark:Boolean=false,narrow:Boolean=false,thinking:Boolean=false){
        val loader:suspend(List<ChatToolActivity>)->List<RecallContentPart> = {items->items.map{item->
            if(item.recallOutcome()==RecallDisplayOutcome.FOUND)RecallContentPart.Text(if(item.tool=="agent_history_search")"合成聊天原话"else"合成回想${item.id}")
            else RecallContentPart.Status(item.recallOutcome().label,if(item.recallOutcome()==RecallDisplayOutcome.ERROR)item.detail else "")
        }.distinct()}
        ui.setContent{
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,if(narrow)2f else 1f)){
                LianxuTheme(dark){Box(Modifier.fillMaxSize().background(ChatBackground).padding(20.dp)){
                    Column(Modifier.width(if(narrow)200.dp else 320.dp)){
                        if(thinking)ChatThinkingStream("test","之前之后",false,rows.map{ChatThinkingBreak(2,it.id)},rows,loadRecall=loader)
                        else ChatToolActivities(rows,loadRecall=loader)
                    }
                }}
            }
        }
    }
    private fun shot(name:String){
        ui.waitForIdle();val i=InstrumentationRegistry.getInstrumentation()
        val dir=File(i.targetContext.getExternalFilesDir(null),"evidence-recall-grouping").apply{mkdirs()}
        // PixelCopy waits for this actual Compose root to render; automation screenshots can
        // otherwise race the first frame and capture the empty test activity.
        val bitmap=ui.onRoot().captureToImage().asAndroidBitmap()
        File(dir,name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun successfulSameResponseCallsUseOneCapsuleAndExpandBothActualOutcomes(){
        render(listOf(row("a"),row("b")))
        ui.onNodeWithText("想起来了").assertIsDisplayed()
        ui.onNodeWithTag("tool-result-a").assertIsDisplayed();ui.onNodeWithTag("tool-result-b").assertDoesNotExist()
        ui.onNodeWithTag("recall-toggle-a").performClick()
        ui.onNodeWithText("合成回想a").assertIsDisplayed();ui.onNodeWithText("合成回想b").assertIsDisplayed()
        shot("recall-success-expanded.png")
        ui.onNodeWithTag("recall-toggle-a").performClick();ui.onNodeWithTag("recall-results-a").assertDoesNotExist()
    }
    @Test fun mixedCollapsedStateClearlyWarnsAndExpandedRowsDoNotHideMissOrError(){
        render(listOf(row("a"),row("b","failed"),row("c","failed",detail="操作未完成，角色会根据结果继续回应")))
        ui.onNodeWithText("想起来了 · 部分失败").assertIsDisplayed();shot("recall-mixed-collapsed.png")
        ui.onNodeWithTag("recall-toggle-a").performClick()
        ui.onNodeWithText("合成回想a").assertIsDisplayed();ui.onNodeWithText("没找到").assertIsDisplayed();ui.onNodeWithText("回想出错").assertIsDisplayed()
        ui.onNodeWithText("操作未完成，角色会根据结果继续回应").assertIsDisplayed();shot("recall-mixed-expanded.png")
    }
    @Test fun aRealMissAndAnExecutionFailureUseDifferentCollapsedWording(){
        render(listOf(row("miss","failed","miss-group"),row("error","failed","error-group","操作未完成")))
        ui.onNodeWithText("没找到").assertIsDisplayed();ui.onNodeWithText("回想出错").assertIsDisplayed()
        shot("recall-miss-error.png")
    }
    @Test fun separateResponsesAndLegacyRowsStaySeparateAlongsideOtherTools(){
        render(listOf(row("a",group="first"),row("b",group="second"),row("old1","failed",null,"没找到符合的内容","agent_history_search"),row("old2",group=null),row("write",tool="agent_memory_update").copy(title="嗯…我记住了",kind=ChatToolActivity.WRITE)))
        listOf("a","b","old1","old2","write").forEach{ui.onNodeWithTag("tool-result-$it").assertIsDisplayed()}
        assertEquals(3,ui.onAllNodesWithText("想起来了").fetchSemanticsNodes().size)
        ui.onNodeWithText("没找到").assertIsDisplayed();ui.onNodeWithText("回想出错").assertDoesNotExist()
    }
    @Test fun interleavedThinkingKeepsItsTextAndOnlyOneSameResponseCapsule(){
        render(listOf(row("a"),row("b")),thinking=true)
        ui.onNodeWithText("之前").assertIsDisplayed();ui.onNodeWithText("之后").assertIsDisplayed()
        ui.onNodeWithTag("tool-result-a").assertIsDisplayed();ui.onNodeWithTag("tool-result-b").assertDoesNotExist()
        ui.onNodeWithTag("recall-toggle-a").performClick();ui.onNodeWithText("合成回想b").assertIsDisplayed()
    }
    @Test fun darkNarrowDoubleFontKeepsTheMixedWarningAndEveryLineInsideItsTextBox(){
        render(listOf(row("a"),row("b","failed")),dark=true,narrow=true)
        val results=mutableListOf<TextLayoutResult>()
        ui.onNodeWithText("想起来了 · 部分失败").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        val layout=results.single();assertEquals(2f,layout.layoutInput.density.fontScale)
        assertFalse(layout.didOverflowHeight)
        for(line in 0 until layout.lineCount)assertTrue(layout.getLineRight(line)-layout.getLineLeft(line)<=layout.size.width+1f)
        ui.onNodeWithTag("recall-toggle-a").performClick();ui.onNodeWithText("没找到").assertIsDisplayed()
        shot("recall-dark-narrow-2x.png")
    }
    @Test fun eightResultsCanScrollToTheLastActualOutcome(){
        render((1..8).map{row("m$it")})
        ui.onNodeWithTag("recall-toggle-m1").performClick()
        ui.onNodeWithText("合成回想m8").performScrollTo().assertIsDisplayed()
        shot("recall-eight-last.png")
    }
    @Suppress("UNCHECKED_CAST")
    private fun incidentHistory():ChatToolActivity {
        val raw=Gson().fromJson("""{"success":true,"status":"found","visible_duplicates":8,"results":[]}""",Map::class.java) as Map<String,Any>
        return ChatToolJournal.result(ChatToolJournal.start(ChatToolCall("history","agent_history_search","""{"query":"合成查询"}"""),1,"","incident-response"),raw)
    }
    @Test fun incidentHistoryDuplicatesAndMemoryReadShowOneSuccessfulGreenCapsule(){
        val history=incidentHistory();val memory=row("incident-memory",group="incident-response")
        render(listOf(history,memory))
        ui.onNodeWithText("想起来了").assertIsDisplayed()
        ui.onNodeWithTag("tool-result-${history.id}").assertIsDisplayed();ui.onNodeWithTag("tool-result-${memory.id}").assertDoesNotExist()
        ui.onNodeWithText("没找到").assertDoesNotExist();ui.onNodeWithText("回想出错").assertDoesNotExist()
        shot("incident-history-memory-collapsed.png")
        ui.onNodeWithTag("recall-toggle-${history.id}").performClick()
        ui.onNodeWithText("合成聊天原话").assertIsDisplayed();ui.onNodeWithText("合成回想incident-memory").assertIsDisplayed()
        shot("incident-history-memory-expanded.png")
    }
    @Test fun incidentHistoryHitAndMemoryErrorStillWarnWhileCollapsed(){
        val history=incidentHistory();val memory=row("incident-error","failed","incident-response","操作未完成")
        render(listOf(history,memory))
        ui.onNodeWithText("想起来了 · 部分失败").assertIsDisplayed()
        ui.onNodeWithTag("recall-toggle-${history.id}").performClick()
        ui.onNodeWithText("合成聊天原话").assertIsDisplayed();ui.onNodeWithText("回想出错").assertIsDisplayed()
        shot("incident-history-memory-mixed.png")
    }
}
