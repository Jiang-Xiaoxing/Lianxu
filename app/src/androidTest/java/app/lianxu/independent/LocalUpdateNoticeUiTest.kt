package app.lianxu.independent

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import java.io.File

/** Only isolated synthetic preferences and a mock client; never touches the production database. */
class LocalUpdateNoticeUiTest {
    @get:Rule(order=0) val fixture=MockChatFixture()
    @get:Rule(order=1) val unread=object:ExternalResource(){
        override fun before(){runBlocking{Store(context,fixture.database).use{it.savePreference(LocalUpdateReceipt.KEY,"")}}}
    }
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val note get()=LocalReleaseNotes.forVersion(InstalledBuild.read(context).versionCode)!!
    private lateinit var app:AppState

    @Before fun open(){
        ui.runOnIdle{app=ViewModelProvider(ui.activity)[AppState::class.java]}
        waitForNotice()
    }
    private fun waitForNotice(){ui.waitUntil(15000){ui.onAllNodesWithTag("update-notice").fetchSemanticsNodes().isNotEmpty()}}
    private fun receipt()=runBlocking{Store(context,fixture.database).use{it.preference(LocalUpdateReceipt.KEY,"")}}
    private fun assertClosedAndRead(){
        ui.waitUntil(15000){ui.onAllNodesWithTag("update-notice").fetchSemanticsNodes().isEmpty()}
        assertEquals(note.code,LocalUpdateReceipt.decode(receipt()).lastAcknowledged)
    }
    private fun recreate(){
        ui.activityRule.scenario.recreate()
        ui.runOnIdle{app=ViewModelProvider(ui.activity)[AppState::class.java]}
        ui.waitUntil(15000){app.ready}
        ui.waitForIdle()
    }
    @Test fun startupShowsSpacedNumberedHighlightsAndBothActions(){
        assertEquals("",receipt())
        ui.onNodeWithText("${note.title} 更新了").assertIsDisplayed()
        UpdatesPresentation.noticeSummary(note).forEachIndexed{i,text->
            ui.onNodeWithTag("updates-item-notice-${i+1}").assertIsDisplayed().assertTextContains("${i+1}、").assertTextContains(text)
            if(i>0){
                val previous=ui.onNodeWithTag("updates-item-notice-$i").getUnclippedBoundsInRoot()
                val current=ui.onNodeWithTag("updates-item-notice-${i+1}").getUnclippedBoundsInRoot()
                assertTrue("Numbered rows must have visible spacing",current.top.value-previous.bottom.value>=11f)
            }
        }
        note.changes.forEach{ui.onNodeWithText(it).assertDoesNotExist()}
        note.limits.forEach{ui.onNodeWithText(it).assertDoesNotExist()}
        ui.onNodeWithText("查看完整更新").assertIsDisplayed()
        ui.onNodeWithText("知道了").assertIsDisplayed()
        shot("startup-light")
    }
    @Test fun fullUpdatesOpensExistingPageAndKeepsEveryOriginalDetail(){
        ui.onNodeWithText("查看完整更新").performClick()
        assertClosedAndRead()
        ui.onNodeWithTag("updates-page").assertIsDisplayed()
        ui.onNodeWithTag("updates-details-current").performScrollTo().performClick()
        note.changes.forEachIndexed{i,text->ui.onNodeWithTag("updates-item-original-current-${i+1}").performScrollTo().assertTextContains(text)}
        note.limits.forEach{ui.onNodeWithText(it).performScrollTo().assertIsDisplayed()}
        shot("full-record")
        recreate()
        ui.onNodeWithTag("update-notice").assertDoesNotExist()
    }
    @Test fun gotItPersistsReadReceiptAcrossRecreation(){
        ui.onNodeWithText("知道了").performClick()
        assertClosedAndRead()
        recreate()
        ui.onNodeWithTag("update-notice").assertDoesNotExist()
    }
    @Test fun backDismissalStillPersistsReadReceipt(){
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        assertClosedAndRead()
        recreate()
        ui.onNodeWithTag("update-notice").assertDoesNotExist()
    }
    @Test fun failedReceiptSaveCanCloseWithoutMarkingReadAndRemindsAgain(){
        val db=app.processing.store.writableDatabase
        db.execSQL("CREATE TEMP TRIGGER fail_update_receipt BEFORE INSERT ON preferences WHEN NEW.key='${LocalUpdateReceipt.KEY}' BEGIN SELECT RAISE(ABORT,'synthetic receipt failure'); END")
        try{
            ui.onNodeWithText("知道了").performClick()
            val error="阅读记录未保存。再次关闭可继续使用，下次启动仍会提醒。"
            ui.waitUntil(15000){ui.onAllNodesWithText(error).fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithText(error).performScrollTo().assertIsDisplayed()
            assertEquals("",receipt())
            ui.onNodeWithText("知道了").performClick()
            ui.onNodeWithTag("update-notice").assertDoesNotExist()
            assertEquals("",receipt())
        }finally{db.execSQL("DROP TRIGGER IF EXISTS fail_update_receipt")}
        recreate()
        waitForNotice()
        ui.onNodeWithText("知道了").performClick()
        assertClosedAndRead()
    }
    @Test fun bothThemesKeepActionsVisibleAndHighlightsScrollable(){
        for(dark in listOf(false,true)){
            ui.activityRule.scenario.onActivity{activity->activity.setContent{LianxuTheme(dark){Lianxu(app)}}}
            waitForNotice()
            UpdatesPresentation.noticeSummary(note).indices.forEach{i->ui.onNodeWithTag("updates-item-notice-${i+1}").performScrollTo().assertIsDisplayed()}
            ui.onNodeWithText("查看完整更新").assertIsDisplayed()
            ui.onNodeWithText("知道了").assertIsDisplayed()
            ui.onNodeWithTag("updates-item-notice-1").performScrollTo()
            shot("startup-${if(dark)"dark" else "light"}-font${context.resources.configuration.fontScale}")
            assertEquals("",receipt())
        }
    }
    private fun shot(name:String){
        ui.waitForIdle()
        val dir=File(context.getExternalFilesDir(null),"evidence-update-notice").apply{mkdirs()}
        val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(dir,"$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
        image.recycle()
    }
}
