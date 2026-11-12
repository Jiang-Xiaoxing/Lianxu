package app.lianxu.independent

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import java.io.File

class RelationshipRemovalUiTest {
    @get:Rule(order=0) val fixture=MockChatFixture()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var vm:AppState
    private fun shot(name:String){
        ui.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val dir=File(instrumentation.targetContext.getExternalFilesDir(null),"evidence-six-removal").apply{mkdirs()}
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        File(dir,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun profileMemorySettingsAndModelPurposesHaveNoSixDimensionalEntry(){
        ui.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[AppState::class.java]}
        ui.waitUntil(15000){vm.ready}
        ui.runOnIdle{vm.open("detail","seed-lin")}
        ui.onNodeWithTag("role-processing-open").performScrollTo().assertIsDisplayed()
        // The later current-feeling page is intentional. Only the retired six-dimensional model
        // and its separate model purpose must stay absent.
        ui.onNodeWithTag("role-relationship-open").assertExists();ui.onNodeWithText("六维",substring=true).assertDoesNotExist();shot("profile")
        ui.onNodeWithTag("role-processing-open").performClick()
        ui.onNodeWithText("我写的").performClick();ui.onNodeWithTag("memory-add").assertIsDisplayed();ui.onNodeWithTag("processing-relationship").assertDoesNotExist();shot("memory")
        ui.onNodeWithTag("back").performClick()
        ui.runOnIdle{vm.home();vm.tab=2}
        ui.onNodeWithTag("settings-processing").assertIsDisplayed();ui.onNodeWithText("关系").assertDoesNotExist();shot("settings")
        ui.onNodeWithTag("settings-models").performClick()
        ui.onNodeWithText("关系评估").assertDoesNotExist();ui.onNodeWithText("六维",substring=true).assertDoesNotExist();shot("models")
    }
}
