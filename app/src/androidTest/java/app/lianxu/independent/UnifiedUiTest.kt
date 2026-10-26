package app.lianxu.independent

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import java.io.File

class UnifiedUiTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val showcase=Friend("ui-unified-showcase","林间 · 样例","合成角色，喜欢植物、散步与日常的小事。")
    @get:Rule(order=0) val mock=MockChatFixture()
    @get:Rule(order=1) val fixture=object:ExternalResource(){override fun before(){runBlocking{
        val store=Store(context)
        if(store.friends().none{it.id==showcase.id}){
            store.add(listOf(showcase))
            store.exchange(showcase,"回家的路上遇见了日落，整片天空都变得柔和了。","【演示】那一定很适合慢慢走。")
            store.exchange(showcase,"浅橘色吧，是今天的小小奖励。","【演示】辛苦了一天，也有温柔的收尾。")
            store.exchange(showcase,"说着说着，连疲惫都轻了一点。","【演示】愿这份轻松，陪你度过今晚。")
        };store.close()
    }}}
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    private fun app():AppState {lateinit var vm:AppState;ui.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[AppState::class.java]};return vm}
    private fun ready(){ui.waitUntil(15000){app().ready};ui.waitUntil(15000){ui.onAllNodesWithTag("tab-0").fetchSemanticsNodes().isNotEmpty()};ui.onNodeWithTag("tab-0").performClick();ui.waitForIdle()}
    private fun hideKeyboard(){ui.activityRule.scenario.onActivity{activity->(activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(activity.window.decorView.windowToken,0);activity.window.decorView.clearFocus()};ui.waitForIdle()}
    private fun shot(name:String){hideKeyboard();android.os.SystemClock.sleep(350)
        val config=ui.activity.resources.configuration
        val mode=if(config.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES)"dark" else "light"
        val folder="evidence-unified-ui/visual-$mode-${(config.fontScale*100).toInt()}"
        val dir=File(context.getExternalFilesDir(null),folder).apply{mkdirs()}
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(dir,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun visualPages(){
        ready();val vm=app();shot("01-chats")
        ui.onNodeWithTag("tab-1").performClick();shot("02-contacts")
        ui.runOnIdle{vm.open("chat",showcase.id)}
        ui.onNodeWithTag("message-input").performTextInput("好，想再和你聊一会儿。")
        shot("03-chat-detail")
        ui.onNodeWithTag("chat-attach").performClick();ui.onNodeWithText("附件暂未接入").assertIsDisplayed();ui.onNodeWithTag("close-attachment-info").performClick()
        ui.onNodeWithText("资料").performClick();shot("04-friend-detail");ui.onNodeWithTag("back").performClick()
        ui.onNodeWithTag("message-input").assertTextContains("好，想再和你聊一会儿。",substring=true)
        ui.onNodeWithTag("back").performClick();ui.onNodeWithTag("tab-0").performClick()
        ui.onNodeWithTag("add-menu").performClick();ui.onNodeWithText("添加朋友").performClick()
        ui.onNodeWithTag("friend-name").performTextInput("合成新朋友");ui.onNodeWithTag("friend-bio").performTextInput("喜欢分享生活里的小事。")
        shot("05-add-friend");ui.onNodeWithTag("back").performClick()
        ui.onNodeWithTag("add-menu").performClick();ui.onNodeWithText("搜索朋友").performClick();shot("06-radar")
        ui.waitUntil(10000){app().candidates.size==3}
        val ids=app().candidates.map{it.id};ui.onNodeWithTag("candidate-${ids[0]}").performClick();ui.onNodeWithTag("candidate-${ids[1]}").performClick()
        assertEquals(2,app().selected.size);shot("07-candidates");ui.onNodeWithTag("back").performClick()
        ui.onNodeWithTag("tab-2").performClick();shot("08-settings")
        ui.onNodeWithTag("settings-models").performClick();shot("09-models")
        ui.onNodeWithTag("config-add-top").performClick();shot("10-providers")
        ui.onNodeWithTag("provider-deepseek").performScrollTo().performClick()
        ui.onNodeWithTag("config-key").performTextInput("synthetic-key-only")
        ui.onNodeWithTag("config-model").performScrollTo().performTextInput("synthetic-model")
        hideKeyboard();ui.onNodeWithTag("config-name").performScrollTo();shot("11-model-editor")
        ui.onNodeWithTag("config-back").performClick();ui.onNodeWithTag("config-back").performClick()
        ui.onNodeWithTag("settings-display").performClick();shot("12-display");ui.onNodeWithTag("settings-back").performClick()
        ui.onNodeWithTag("settings-info").performClick();shot("13-data-version");ui.onNodeWithTag("settings-back").performClick()
        ui.onNodeWithTag("tab-3").performClick();shot("14-profile")
    }
    @Test fun keyboardRotationSendAndStoredConversation(){
        ready();val vm=app();ui.runOnIdle{vm.open("chat",showcase.id)}
        for(tag in listOf("back","chat-attach","send-message"))ui.onNodeWithTag(tag).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        val text="统一界面合成发送-${System.nanoTime()%10000}"
        ui.onNodeWithTag("message-input").performClick().performTextInput(text)
        ui.waitUntil(10000){ui.activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==true}
        val view=ui.activity.window.decorView;val keyboardTop=view.height-view.rootWindowInsets.getInsets(android.view.WindowInsets.Type.ime()).bottom
        assertTrue(ui.onNodeWithTag("send-message").fetchSemanticsNode().boundsInRoot.bottom<=keyboardTop+1)
        hideKeyboard();ui.activityRule.scenario.onActivity{it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
        ui.waitUntil(15000){ui.activity.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE}
        ui.onNodeWithTag("message-input").assertTextContains(text,substring=true)
        ui.activityRule.scenario.onActivity{it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
        ui.waitUntil(15000){ui.activity.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT}
        ui.onNodeWithTag("send-message").performClick();ui.waitUntil(10000){!vm.busy&&vm.messages.any{it.friendId==showcase.id&&it.mine&&it.text==text}}
        runBlocking{val store=Store(context);assertTrue(store.messages().any{it.friendId==showcase.id&&it.text==text});store.close()}
        ui.activityRule.scenario.recreate();ui.onNodeWithTag("message-input").assertIsDisplayed()
    }
    @Test fun profileSaveAndRestoreExistingPreference(){
        ready();val store=Store(context);val original=runBlocking{store.preference("nickname","__absent__")}
        try{
            ui.onNodeWithTag("tab-3").performClick();ui.onNodeWithTag("nickname").performTextReplacement("合成体验者")
            hideKeyboard();ui.onNodeWithText("保存昵称").performClick();ui.waitUntil(10000){!app().busy&&app().nickname=="合成体验者"}
            ui.activityRule.scenario.recreate();ui.onNodeWithText("你好，合成体验者").assertExists()
            assertEquals("合成体验者",runBlocking{store.preference("nickname","")})
        }finally{if(original=="__absent__")store.writableDatabase.delete("preferences","key=?",arrayOf("nickname")) else runBlocking{store.setPreference("nickname",original)};store.close()}
    }
}
