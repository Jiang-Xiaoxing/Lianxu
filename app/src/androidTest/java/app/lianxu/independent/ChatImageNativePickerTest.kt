package app.lianxu.independent

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** The actual APK launches Android's picker, without storage permission or a custom gallery. */
class ChatImageNativePickerTest {
    @get:Rule(order=0)val fixture=MockChatFixture()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    @Test fun opensRealSystemPickerAndCancelPreservesText(){
        lateinit var vm:AppState;ui.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[AppState::class.java]}
        ui.waitUntil(15000){vm.ready};ui.runOnIdle{vm.open("chat","seed-lin")}
        ui.onNodeWithTag("message-input").performTextInput("取消选图保留的合成文字")
        ui.onNodeWithTag("chat-attach").performClick()
        val instrument=InstrumentationRegistry.getInstrumentation();val deadline=android.os.SystemClock.uptimeMillis()+5000
        var pickerPackage=""
        while(android.os.SystemClock.uptimeMillis()<deadline){pickerPackage=instrument.uiAutomation.rootInActiveWindow?.packageName?.toString().orEmpty();if(pickerPackage.isNotEmpty()&&pickerPackage!=instrument.targetContext.packageName)break;android.os.SystemClock.sleep(50)}
        assertTrue("picker package=$pickerPackage",pickerPackage.isNotEmpty()&&pickerPackage!=instrument.targetContext.packageName)
        assertTrue(pickerPackage.contains("providers.media")||pickerPackage.contains("photopicker")||pickerPackage.contains("documentsui"))
        android.os.SystemClock.sleep(800)
        val bitmap=instrument.uiAutomation.takeScreenshot();val dir=File(instrument.targetContext.getExternalFilesDir(null),"chat-image-evidence").apply{mkdirs()}
        File(dir,"06-native-system-picker.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        File(dir,"native-picker-package.txt").writeText(pickerPackage)
        cancelNativeImagePicker();ui.onNodeWithTag("message-input").assertTextEquals("取消选图保留的合成文字")
        assertNull(vm.chatDrafts.getValue("seed-lin").image.value)
    }
}
