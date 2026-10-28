package app.lianxu.independent

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Uses only a dedicated synthetic vault; restores the emulator's original compact preference. */
class SettingsHierarchyTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace="directory-test-${System.nanoTime()}"
    private val vault get()=ModelVault(context,namespace)
    private val a=ModelConfig("directory-a","常用合成服务","deepseek","https://api.deepseek.com","synthetic-daily-model")
    private val b=ModelConfig("directory-b","备用合成服务","silicon-cn","https://api.siliconflow.cn/v1","synthetic-backup-model")
    private lateinit var models:ModelConfigState
    private lateinit var app:AppState
    private var originalCompact:String?=null
    private val calls=AtomicInteger()
    @Before fun setup(){
        val store=Store(context);originalCompact=runBlocking{store.preference("compact","__absent__")};store.close()
        vault.save(a,"synthetic-key-only");vault.save(b,"synthetic-key-only");vault.setDefault(a.id)
        ui.activityRule.scenario.onActivity {activity->
            models=ViewModelProvider(activity,object:ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T = ModelConfigState(activity.application,object:ModelDirectoryClient {
                    override suspend fun request(config:ModelConfig,key:String,fetchModels:Boolean):ConfigResult {calls.incrementAndGet();error("Unexpected network request in settings navigation")}
                },namespace) as T
            })[ModelConfigState::class.java]
            app=ViewModelProvider(activity)[AppState::class.java]
        }
        ui.waitUntil(15000){models.configs.size==2&&app.ready}
        ui.onNodeWithTag("tab-2").performClick();ui.onNodeWithTag("settings-home").assertIsDisplayed()
    }
    @After fun cleanup(){
        ui.runOnIdle{if(::models.isInitialized)models.clearResult()}
        ui.activityRule.scenario.onActivity{it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
        if(::app.isInitialized)ui.waitUntil(10000){!app.busy}
        originalCompact?.let{original->
            val store=Store(context)
            if(original=="__absent__")store.writableDatabase.delete("preferences","key=?",arrayOf("compact"))
            else runBlocking{store.setPreference("compact",original)}
            store.close()
        }
        vault.list().forEach{vault.delete(it.id)};assertEquals(0,calls.get())
    }
    private fun hideKeyboard(){ui.activityRule.scenario.onActivity{activity->(activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(activity.window.decorView.windowToken,0);activity.window.decorView.clearFocus()};ui.waitForIdle()}
    private fun shot(name:String){hideKeyboard();android.os.SystemClock.sleep(350)
        val dir=File(context.getExternalFilesDir(null),"evidence-unified-ui/settings").apply{mkdirs()}
        val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(dir,"$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun directoryHomeAndSecondaryRowsWithRealRotation(){
        ui.onNodeWithTag("settings-model-summary",useUnmergedTree=true).assertTextContains(a.modelId,substring=true)
        ui.onNodeWithTag("config-name").assertDoesNotExist();ui.onNodeWithTag("compact-switch").assertDoesNotExist()
        ui.onNodeWithText("紧凑列表").assertDoesNotExist();shot("01-settings-home-real-app")
        ui.onNodeWithTag("settings-display").performClick();ui.onNodeWithTag("settings-display-page").assertIsDisplayed()
        val old=app.compact;ui.onNodeWithTag("compact-switch").performClick()
        ui.waitUntil(10000){app.compact!=old&&!app.busy}
        ui.activityRule.scenario.onActivity{it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
        ui.waitUntil(15000){ui.activity.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE}
        ui.onNodeWithTag("settings-display-page").assertIsDisplayed();assertEquals(!old,app.compact)
        ui.activityRule.scenario.onActivity{it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
        ui.waitUntil(15000){ui.activity.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT}
        shot("03-display-secondary-real-app")
        ui.onNodeWithContentDescription("返回设置").performClick();ui.onNodeWithTag("settings-home").assertIsDisplayed()
        ui.onNodeWithTag("settings-info").performClick();ui.onNodeWithTag("settings-info-page").assertIsDisplayed()
        ui.onNodeWithTag("settings-version").assertTextContains(InstalledBuild.read(context).versionName,substring=true)
        ui.onNodeWithTag("compact-switch").assertDoesNotExist();shot("04-data-version-secondary-real-app")
        ui.onNodeWithTag("settings-back").performClick();ui.onNodeWithTag("settings-home").assertIsDisplayed()
        listOf(0,3,1,2).forEach{i->ui.onNodeWithTag("tab-$i").performClick();ui.onNodeWithTag("tab-$i").assertIsSelected()}
        ui.onNodeWithTag("settings-home").assertIsDisplayed()
    }
    @Test fun modelDirectoryDefaultSummaryAndEditCancel(){
        ui.onNodeWithTag("settings-models").performTouchInput{doubleClick()}
        ui.onNodeWithTag("config-overview").assertIsDisplayed();ui.onNodeWithTag("config-editor").assertDoesNotExist()
        shot("02-model-secondary-real-app")
        ui.onNodeWithTag("config-edit-${b.id}").performClick()
        ui.onNodeWithTag("config-default-${b.id}").performScrollTo().performClick()
        ui.waitUntil(10000){models.defaultId==b.id&&!models.saving}
        ui.onNodeWithTag("config-back").performClick();ui.onNodeWithTag("config-back").performClick()
        ui.onNodeWithTag("settings-model-summary",useUnmergedTree=true).assertTextContains(b.modelId,substring=true)
        ui.onNodeWithTag("settings-models").performClick();ui.onNodeWithTag("config-edit-${b.id}").performClick()
        ui.onNodeWithTag("config-name").performTextReplacement("未保存合成草稿")
        hideKeyboard();ui.onNodeWithTag("config-back").performClick();ui.onNodeWithTag("config-back").performClick()
        assertEquals(b.name,vault.list().single{it.id==b.id}.name)
        ui.onNodeWithTag("settings-model-summary",useUnmergedTree=true).assertTextContains(b.name,substring=true)
        ui.activityRule.scenario.recreate();ui.onNodeWithTag("settings-home").assertIsDisplayed()
        ui.onNodeWithTag("settings-model-summary",useUnmergedTree=true).assertTextContains(b.modelId,substring=true)
    }
    @Test fun repeatedDirectoryClickAndSystemBackReturnOnce(){
        ui.onNodeWithTag("settings-display").performTouchInput{doubleClick()}
        ui.onNodeWithTag("settings-display-page").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        ui.waitForIdle();ui.onNodeWithTag("settings-home").assertIsDisplayed()
        ui.onNodeWithTag("settings-info").performTouchInput{doubleClick()}
        ui.onNodeWithTag("settings-info-page").assertIsDisplayed()
        ui.onNodeWithTag("settings-back").performClick();ui.onNodeWithTag("settings-home").assertIsDisplayed()
    }
}
