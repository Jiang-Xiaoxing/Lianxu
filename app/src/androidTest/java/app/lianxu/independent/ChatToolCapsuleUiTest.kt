package app.lianxu.independent

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import java.io.File

class ChatToolCapsuleUiTest {
    @get:Rule val ui=createComposeRule()
    private fun settled(name:String,args:String,before:String,result:Map<String,Any>)=
        ChatToolJournal.result(ChatToolJournal.start(ChatToolCall("id-$before",name,args),1,before),result)
    private fun assertTextColor(text:String,color:Color){
        val layouts=mutableListOf<TextLayoutResult>()
        ui.onNodeWithText(text,useUnmergedTree=true).performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
        assertEquals(color,layouts.single().layoutInput.style.color)
    }
    @Test fun anUncommittedWriteWithoutAMotionEventStaysNeutralUntilCommitted(){
        val staged=settled("agent_memory_update","""{"op":"add"}""","",mapOf("success" to true,"status" to "staged_until_reply_commit"))
        assertEquals("staged",staged.state)
        val item=mutableStateOf(staged)
        var neutralColor=Color.Unspecified;var savedColor=Color.Unspecified
        ui.setContent{LianxuTheme(false){neutralColor=Muted;savedColor=GreenText;ChatToolActivities(listOf(item.value))}}
        val neutralDisplayed=runCatching{ui.onNodeWithText("正在记住").assertIsDisplayed();true}.getOrDefault(false)
        val successDisplayed=runCatching{ui.onNodeWithText("我记住了").assertIsDisplayed();true}.getOrDefault(false)
        val instrument=InstrumentationRegistry.getInstrumentation()
        val evidence=File(instrument.targetContext.getExternalFilesDir(null),"evidence-no-event-staged/${System.currentTimeMillis()}-${System.nanoTime()}").apply{mkdirs()}
        File(evidence,"state.json").writeText(com.google.gson.Gson().toJson(mapOf("fixtureState" to staged.state,"motionEventCount" to 0,"neutralDisplayed" to neutralDisplayed,"successDisplayed" to successDisplayed,"realApiRequests" to 0,"actualMemoryWrites" to 0)))
        instrument.waitForIdleSync();android.os.SystemClock.sleep(100)
        val bitmap=instrument.uiAutomation.takeScreenshot()
        try{File(evidence,"screen.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
        ui.onNodeWithText("正在记住").assertIsDisplayed()
        assertTextColor("正在记住",neutralColor)
        ui.onNodeWithText("我记住了").assertDoesNotExist()
        ui.mainClock.autoAdvance=false
        ui.mainClock.advanceTimeBy(2500)
        ui.onNodeWithText("正在记住").assertIsDisplayed()
        ui.onNodeWithText("我记住了").assertDoesNotExist()
        ui.runOnIdle{item.value=staged.copy(state="success",detail="",memoryId="synthetic-committed-row")}
        ui.mainClock.advanceTimeByFrame()
        ui.onNodeWithText("我记住了").assertIsDisplayed()
        assertTextColor("我记住了",savedColor)
        ui.onNodeWithText("正在记住").assertDoesNotExist()
    }
    @Test fun anUncommittedWriteThatFailsNeverClaimsItWasSaved(){
        val staged=settled("agent_memory_update","{}","",mapOf("success" to true,"status" to "staged_until_reply_commit"))
        val item=mutableStateOf(staged)
        var errorColor=Color.Unspecified
        ui.setContent{LianxuTheme(false){errorColor=androidx.compose.material3.MaterialTheme.colorScheme.error;ChatToolActivities(listOf(item.value))}}
        ui.onNodeWithText("正在记住").assertIsDisplayed()
        ui.onNodeWithText("我记住了").assertDoesNotExist()
        ui.runOnIdle{item.value=staged.copy(state="failed",detail="合成失败，本条未保存")}
        ui.onNodeWithText("没记住").assertIsDisplayed()
        assertTextColor("没记住",errorColor)
        ui.onNodeWithText("我记住了").assertDoesNotExist()
        ui.onNodeWithText("正在记住").assertDoesNotExist()
    }
    @Test fun actualCapsulesShowOrderedResultsInLightAndLargeDarkTheme(){
        val dark=mutableStateOf(false)
        val items=listOf(
            settled("agent_recall_search","""{"query":"生日"}""","刚才好像没想起来。",mapOf("status" to "error")),
            settled("agent_memory_update","""{"op":"add"}""","这次记下了。",mapOf("success" to true)).copy(memoryId="synthetic-committed-row"))
        ui.setContent{
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,if(dark.value)1.3f else 1f)){
                LianxuTheme(dark.value){Column(Modifier.fillMaxSize().background(ChatBackground).verticalScroll(rememberScrollState()).padding(24.dp)){
                    Text("合成聊天 · 工具结果",color=Ink)
                    Spacer(Modifier.height(24.dp))
                    ChatToolActivities(items,Modifier.widthIn(max=320.dp))
                    Spacer(Modifier.height(12.dp));Text("这次处理好了。",color=Ink)
                }}
            }
        }
        ui.onNodeWithTag("tool-before-${items[0].id}").assertTextEquals("刚才好像没想起来。")
        // Failure replaces the phrase in the same pill; it is never shown as 让我想一想，没想起来.
        ui.onNodeWithText("回想出错").assertIsDisplayed()
        ui.onNodeWithText("让我想一想，没想起来").assertDoesNotExist()
        ui.onNodeWithText("我记住了").assertIsDisplayed()
        fun shot(name:String){ui.waitForIdle();val i=InstrumentationRegistry.getInstrumentation();val dir=File(i.targetContext.getExternalFilesDir(null),"evidence-live-tools").apply{mkdirs()};val bitmap=i.uiAutomation.takeScreenshot();File(dir,name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
        shot("capsules-light.png")
        ui.runOnIdle{dark.value=true}
        ui.onNodeWithText("我记住了").performScrollTo().assertIsDisplayed()
        shot("capsules-dark-large.png")
    }
}
