package app.lianxu.independent

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ProfileLayoutAndroidTest {
    @get:Rule(order=0) val fixture=MockChatFixture()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()

    private fun shot(label:String) {
        ui.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        val output=File(instrumentation.targetContext.getExternalFilesDir(null),"profile-$label.png")
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }

    @Test fun realKeyboardRotationAndSaveKeepTheEditedProfile() {
        lateinit var vm:AppState
        ui.activityRule.scenario.onActivity { vm=ViewModelProvider(it)[AppState::class.java] }
        ui.waitUntil(15000){vm.ready}
        ui.onNodeWithTag("tab-3").performClick()
        ui.onNodeWithText("点击更换头像").assertIsDisplayed()
        ui.onNodeWithText("资料保存在本机 · 无需注册").assertDoesNotExist()
        ui.onNodeWithText("保存资料").assertIsDisplayed()
        shot("portrait")
        ui.onNodeWithTag("nickname").performTextReplacement("合成名字")
        ui.onNodeWithTag("persona-address").performTextReplacement("合成称呼")
        val intro=ui.onNodeWithTag("persona-introduction")
        intro.performScrollTo().performClick()
        ui.waitUntil(6000){ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==true}
        intro.performTextReplacement("合成介绍")
        shot("keyboard")
        ui.activityRule.scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        try {
            ui.waitUntil(10000){ui.activity.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE}
            intro.assertTextContains("合成介绍")
            ui.onNodeWithText("保存资料").performScrollTo().assertIsDisplayed().performClick()
            val expected=UserPersona("合成名字","合成称呼","合成介绍")
            ui.waitUntil(15000){vm.persona==expected}
            Store(ui.activity,fixture.database).use { store -> assertEquals(expected,runBlocking{store.persona()}) }
            shot("landscape")
        } finally {
            ui.activityRule.scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        }
    }
}
