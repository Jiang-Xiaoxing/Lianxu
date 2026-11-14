package app.lianxu.independent

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Production page with isolated synthetic preferences; viewing never acknowledges another update. */
class UpdatesPageUiTest {
    @get:Rule(order=0) val fixture=MockChatFixture()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var app:AppState
    private var dark by mutableStateOf(false)
    private var width by mutableIntStateOf(411)
    private var scale by mutableFloatStateOf(1f)
    @Before fun open(){
        ui.runOnIdle{app=ViewModelProvider(ui.activity)[AppState::class.java]}
        ui.waitUntil(15000){app.ready}
        ui.onNodeWithTag("tab-2").performClick()
        ui.onNodeWithTag("settings-updates").performScrollTo().performClick()
        ui.onNodeWithTag("updates-page").assertIsDisplayed()
    }
    @After fun restoreOrientation(){ui.activityRule.scenario.onActivity{it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}}
    private fun receipt()=runBlocking{Store(context,fixture.database).use{it.preference(LocalUpdateReceipt.KEY,"")}}
    @Test fun currentStartsOpenHistoryStartsClosedAndChangesUseNumbers(){
        ui.onNodeWithTag("updates-current-toggle").assertTextContains(InstalledBuild.read(context).versionName,substring=true)
        ui.onNodeWithTag("updates-current-toggle").assertHeightIsAtLeast(48.dp).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"已展开"))
        ui.onNodeWithTag("updates-current-content").assertIsDisplayed()
        ui.onNodeWithText("本地体验改动 · 尚未正式发布").assertDoesNotExist()
        ui.onNodeWithText("以下体验改动不属于已发布的1.0.1记录。").assertDoesNotExist()
        for(i in 1..3)ui.onNodeWithTag("updates-item-experience-$i").assertTextContains("$i、")
        ui.onNodeWithTag("updates-published-content").assertDoesNotExist()
        LocalReleaseNotes.forVersion(60)!!.limits.forEach{ui.onNodeWithText(it).assertDoesNotExist()}
        ui.onNodeWithText("后台联系可能延迟；真实模型、真机及长期后台尚未验收。").assertDoesNotExist()
        ui.onNodeWithTag("updates-history-content-59").assertDoesNotExist()
        ui.onNodeWithTag("updates-current-toggle").performClick().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"已收起"))
        ui.onNodeWithTag("updates-current-content").assertDoesNotExist()
        ui.onNodeWithTag("updates-current-toggle").performClick()
        ui.onNodeWithTag("updates-current-content").assertIsDisplayed()
    }
    @Test fun historicalFullFactsSurviveRotationWithoutChangingReceipt(){
        val before=receipt()
        ui.onNodeWithTag("updates-history-59").performScrollTo().performClick()
        ui.onNodeWithTag("updates-item-history-59-1").assertTextContains("1、")
        ui.onNodeWithTag("updates-details-history-59").performScrollTo().performClick()
        ui.onNodeWithTag("updates-item-original-history-59-5").performScrollTo().assertTextContains(LocalReleaseNotes.forVersion(59)!!.changes[4])
        ui.activityRule.scenario.onActivity{it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
        ui.waitUntil(15000){ui.activity.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE}
        ui.onNodeWithTag("updates-history-59").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"已展开"))
        ui.onNodeWithTag("updates-original-history-59").assertExists()
        ui.activityRule.scenario.onActivity{it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
        ui.waitUntil(15000){ui.activity.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT}
        assertEquals(before,receipt())
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        ui.onNodeWithTag("settings-home").assertIsDisplayed()
        ui.onNodeWithTag("settings-updates").performScrollTo().performClick()
        ui.onNodeWithTag("updates-history-content-59").assertDoesNotExist()
        ui.onNodeWithTag("updates-current-content").assertIsDisplayed()
    }
    @Test fun publishedAndLegacyRecordsRemainAccessibleAndSeparate(){
        ui.onNodeWithTag("updates-published-toggle").performScrollTo().performClick()
        ui.onNodeWithTag("updates-item-published-1").assertTextContains("主动联系",substring=true)
        ui.onNodeWithTag("updates-details-published").performScrollTo().performClick()
        LocalReleaseNotes.forVersion(60)!!.changes.forEachIndexed{i,text->
            ui.onNodeWithTag("updates-item-original-published-${i+1}").performScrollTo().assertTextContains(text)
        }
        LocalReleaseNotes.forVersion(60)!!.limits.forEach{ui.onNodeWithText(it).performScrollTo().assertIsDisplayed()}
        ui.onNodeWithTag("updates-history-58").performScrollTo().performClick()
        ui.onNodeWithTag("updates-item-history-58-4").assertDoesNotExist()
        ui.onNodeWithTag("updates-item-original-history-58-17").assertDoesNotExist()
        ui.onNodeWithTag("updates-details-history-58").performScrollTo().performClick()
        ui.onNodeWithTag("updates-item-original-history-58-17").performScrollTo().assertTextContains(LocalReleaseNotes.forVersion(58)!!.changes.last())
        ui.onNodeWithTag("updates-history-16").performScrollTo().performClick()
        ui.onNodeWithTag("updates-details-history-16").performScrollTo().performClick()
        ui.onNodeWithText(LocalReleaseNotes.forVersion(16)!!.limits.single()).performScrollTo().assertIsDisplayed()
        assertTrue(LocalReleaseNotes.forVersion(60)!!.changes.none{"暖灰棕" in it||"分页" in it})
    }
    @Test fun nativeCurrentAndHistoryScreensAcrossThemesSmallWidthAndLargeText(){
        ui.activityRule.scenario.onActivity{activity->activity.setContent{LianxuTheme(dark){
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,scale)){
                Box(Modifier.fillMaxSize()){Box(Modifier.width(width.dp).fillMaxHeight().testTag("updates-viewport")){Lianxu(app)}}
            }
        }}}
        for(night in listOf(false,true))for(dp in listOf(320,411)){
            ui.runOnIdle{dark=night;width=dp;scale=if(dp==320)1.3f else 1f}
            ui.onNodeWithTag("updates-viewport").assertWidthIsEqualTo(dp.dp)
            ui.onNodeWithTag("updates-current-toggle").performScrollTo().assertHeightIsAtLeast(48.dp)
            ui.onNodeWithTag("updates-item-experience-1").assertIsDisplayed();shot("current")
            ui.onNodeWithTag("updates-history-59").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            ui.onNodeWithTag("updates-details-history-59").performScrollTo().assertIsDisplayed();shot("history")
            ui.onNodeWithTag("updates-history-59").performScrollTo().performClick()
        }
    }
    private fun shot(page:String){
        ui.mainClock.advanceTimeBy(500);ui.waitForIdle();android.os.SystemClock.sleep(400)
        val dir=File(context.getExternalFilesDir(null),"evidence-updates-ui").apply{mkdirs()}
        val image=ui.onNodeWithTag("updates-viewport").captureToImage().asAndroidBitmap()
        File(dir,"$page-${if(dark)"dark" else "light"}-$width-font$scale.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
}
