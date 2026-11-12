package app.lianxu.independent

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class LearningRecordsStatesUiTest {
    @get:Rule val ui=createComposeRule()
    @Test fun loadingFailureRetryAndEmptyAreDistinct(){
        val records=mutableStateOf<List<LearningRecord>?>(null)
        val failed=mutableStateOf(false)
        var retries=0
        ui.setContent{LianxuTheme{LearningRecordsContent(Friend("test","合成角色",""),records.value,failed.value,{retries++;failed.value=false},{})}}
        ui.onNodeWithTag("learning-loading").assertExists()
        ui.onNodeWithTag("learning-empty").assertDoesNotExist()
        ui.runOnIdle{failed.value=true}
        ui.onNodeWithTag("learning-error").assertExists()
        ui.onNodeWithTag("learning-empty").assertDoesNotExist()
        ui.onNodeWithTag("learning-retry").performClick()
        assertEquals(1,retries)
        ui.onNodeWithTag("learning-loading").assertExists()
        ui.runOnIdle{records.value=emptyList()}
        ui.onNodeWithTag("learning-empty").assertExists()
        ui.onNodeWithTag("learning-error").assertDoesNotExist()
    }
}
