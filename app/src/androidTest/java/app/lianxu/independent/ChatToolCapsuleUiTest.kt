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
import java.io.File

class ChatToolCapsuleUiTest {
    @get:Rule val ui=createComposeRule()
    private fun settled(name:String,args:String,before:String,result:Map<String,Any>)=
        ChatToolJournal.result(ChatToolJournal.start(ChatToolCall("id-$before",name,args),1,before),result)
    @Test fun actualCapsulesShowOrderedResultsInLightAndLargeDarkTheme(){
        val dark=mutableStateOf(false)
        val items=listOf(
            settled("agent_recall_search","""{"query":"生日"}""","刚才好像没想起来。",mapOf("status" to "error")),
            settled("agent_memory_update","""{"op":"add"}""","这次记下了。",mapOf("success" to true,"status" to "staged_until_reply_commit")))
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
        ui.onNodeWithText("没想起来").assertIsDisplayed()
        ui.onNodeWithText("让我想一想，没想起来").assertDoesNotExist()
        ui.onNodeWithText("嗯…我记住了").assertIsDisplayed()
        fun shot(name:String){ui.waitForIdle();val i=InstrumentationRegistry.getInstrumentation();val dir=File(i.targetContext.getExternalFilesDir(null),"evidence-live-tools").apply{mkdirs()};val bitmap=i.uiAutomation.takeScreenshot();File(dir,name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
        shot("capsules-light.png")
        ui.runOnIdle{dark.value=true}
        ui.onNodeWithText("嗯…我记住了").performScrollTo().assertIsDisplayed()
        shot("capsules-dark-large.png")
    }
}
