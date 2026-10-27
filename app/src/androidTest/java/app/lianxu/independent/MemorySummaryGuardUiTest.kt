package app.lianxu.independent

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.junit.*

class MemorySummaryGuardUiTest {
    @get:Rule(order=0)val mock=MockChatFixture()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private fun app():AppState{lateinit var v:AppState;ui.activityRule.scenario.onActivity{v=ViewModelProvider(it)[AppState::class.java]};return v}
    @Test fun summaryDisablesSavingPreservesDraftAndRestoresEditing(){
        ui.waitUntil(15000){app().ready};val v=app();val repo=v.processing
        runBlocking{repo.store.add(listOf(Friend("guard-ui","合成角色","测试")),false);v.refreshProcessing()}
        ui.runOnIdle{v.open("processing-role","guard-ui")};ui.onNodeWithTag("memory-add").performClick()
        ui.onNodeWithTag("memory-text").performTextInput("尚未保存的合成草稿")
        val ticket=repo.store.memorySummary.begin("guard-ui",ChatAttemptLease(),10000){}!!
        try{ui.waitForIdle();ui.onNodeWithTag("memory-save").assertIsNotEnabled()}
        finally{repo.store.memorySummary.finish(ticket)}
        ui.waitForIdle();ui.onNodeWithTag("memory-text").assertTextContains("尚未保存的合成草稿");ui.onNodeWithTag("memory-save").assertIsEnabled().performClick()
        ui.waitUntil(10000){runBlocking{repo.memories("guard-ui").size==1}}
        val again=repo.store.memorySummary.begin("guard-ui",ChatAttemptLease(),10000){}!!
        try{ui.waitForIdle();ui.onNodeWithTag("memory-add").assertIsNotEnabled();ui.onNodeWithTag("memory-summary-guard").assertExists()}
        finally{repo.store.memorySummary.finish(again)}
        ui.waitForIdle();ui.onNodeWithTag("memory-add").assertIsEnabled()
    }
}
