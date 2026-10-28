package app.lianxu.independent
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File

class NavigationIconTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun fourTabStatesAndAccessibleLabels() {
        ui.waitUntil(15000){ui.onAllNodesWithTag("tab-0").fetchSemanticsNodes().isNotEmpty()}
        val labels=listOf("聊天","通讯录","设置","我的")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val dir=File(instrumentation.targetContext.getExternalFilesDir(null),"evidence-unified-ui/navigation").apply{mkdirs()}
        labels.forEachIndexed { selected,_ ->
            ui.onNodeWithTag("tab-$selected").performClick()
            if(selected==0){ui.onNodeWithTag("home-title").assertTextEquals("恋叙");ui.onNodeWithContentDescription("创建角色").assertHasClickAction()}
            labels.forEachIndexed {i,label->
                val node=ui.onNodeWithTag("tab-$i");node.assertIsDisplayed().assertHasClickAction().assert(hasText(label))
                if(i==selected)node.assertIsSelected() else node.assertIsNotSelected()
            }
            ui.waitForIdle();android.os.SystemClock.sleep(500)
            val bitmap=instrumentation.uiAutomation.takeScreenshot()
            File(dir,"tab-$selected-real-app.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
    }
}
