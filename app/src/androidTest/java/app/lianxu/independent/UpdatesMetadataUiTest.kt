package app.lianxu.independent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

class UpdatesMetadataUiTest {
    @get:Rule val ui=createComposeRule()
    @Test fun lookupFailureDoesNotClaimTheBuildHasNoRecords(){
        ui.setContent{LianxuTheme{Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){UpdatesContent(null)}}}
        ui.onNodeWithTag("updates-current-toggle").assertTextContains("版本信息暂时无法读取")
        ui.onNodeWithTag("updates-current-missing").assertTextEquals("版本信息暂时无法读取，无法匹配随包记录。")
        ui.onNodeWithText("当前安装版本没有随包更新记录。").assertDoesNotExist()
        ui.onNodeWithTag("updates-item-experience-1").assertDoesNotExist()
    }
    @Test fun unknownActualBuildDoesNotBorrowAnOlderCurrentRecord(){
        ui.setContent{LianxuTheme{Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){UpdatesContent(InstalledBuild("synthetic","unknown-build",99))}}}
        ui.onNodeWithTag("updates-current-toggle").assertTextContains("unknown-build",substring=true)
        ui.onNodeWithTag("updates-current-missing").assertTextEquals("当前安装版本没有随包更新记录。")
        ui.onNodeWithTag("updates-item-experience-1").assertDoesNotExist()
        ui.onNodeWithTag("updates-published-toggle").assertDoesNotExist()
    }
}
