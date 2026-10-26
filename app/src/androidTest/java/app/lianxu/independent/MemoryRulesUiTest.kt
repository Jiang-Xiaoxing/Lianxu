package app.lianxu.independent
import android.content.Context
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File

class MemoryRulesUiTest{
    @get:Rule(order=0)val mock=MockChatFixture()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private fun app():AppState{lateinit var v:AppState;ui.activityRule.scenario.onActivity{v=ViewModelProvider(it)[AppState::class.java]};return v}
    private fun hideIme(){ui.activityRule.scenario.onActivity{a->(a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(a.window.decorView.windowToken,0);a.window.decorView.clearFocus()};ui.waitForIdle()}
    @Test fun settingsDefaultsValidationSaveCancelAndRecreation(){
        ui.waitUntil(15000){app().ready};val v=app();ui.runOnIdle{v.tab=2};ui.onNodeWithTag("settings-processing").performClick();ui.onNodeWithTag("settings-memory-rules").performClick()
        ui.onNodeWithTag("rules-recent").assertTextContains("40");ui.onNodeWithTag("rules-summary").assertTextContains("20");ui.onNodeWithTag("rules-related").assertTextContains("15")
        val c=InstrumentationRegistry.getInstrumentation().targetContext;val dir=File(c.getExternalFilesDir(null),"evidence-memory-014").apply{mkdirs()};val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot();File(dir,"01-memory-rules-settings-real-app.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        ui.onNodeWithTag("rules-recent").performTextReplacement("0");hideIme();ui.onNodeWithTag("memory-rules-save").performClick();ui.onNodeWithTag("memory-rules-status").performScrollTo().assertTextContains("正整数",substring=true);assertEquals(MemoryRules(),runBlocking{v.processing.memoryRules().first})
        ui.onNodeWithTag("rules-recent").performScrollTo().performTextReplacement("10");ui.onNodeWithTag("rules-summary").performTextReplacement("20");hideIme();ui.onNodeWithTag("memory-rules-save").performClick();assertEquals(MemoryRules(),runBlocking{v.processing.memoryRules().first})
        ui.onNodeWithTag("rules-recent").performScrollTo().performTextReplacement("46");ui.onNodeWithTag("rules-summary").performTextReplacement("17");ui.onNodeWithTag("rules-related").performScrollTo().performTextReplacement("9");hideIme();ui.onNodeWithTag("memory-rules-save").assertIsDisplayed().performClick();ui.waitUntil(10000){runBlocking{v.processing.memoryRules().first==MemoryRules(46,17,9)}}
        ui.onNodeWithTag("memory-rules-save").performClick();ui.activityRule.scenario.recreate();ui.onNodeWithTag("rules-recent").assertTextContains("46");ui.onNodeWithTag("rules-summary").assertTextContains("17");ui.onNodeWithTag("rules-related").performScrollTo().assertTextContains("9")
        ui.onNodeWithTag("rules-related").performTextReplacement("3");hideIme();ui.onNodeWithTag("back").performClick();ui.onNodeWithTag("settings-memory-rules").performClick();ui.onNodeWithTag("rules-related").performScrollTo().assertTextContains("9");assertEquals(MemoryRules(46,17,9),runBlocking{app().processing.memoryRules().first})
    }
}
