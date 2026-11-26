package app.lianxu.independent

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Only independent synthetic database/configuration; every directory request fails this test. */
class ModelConfigTabsUiTest {
    @get:Rule(order=0) val isolated=MockChatFixture()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace="model-tabs-${System.nanoTime()}"
    private val vault get()=ModelVault(context,namespace)
    private lateinit var state:ModelConfigState
    private lateinit var app:AppState
    private val requests=AtomicInteger()
    private val chat=ModelConfig("tabs-chat","合成聊天配置","custom","https://example.invalid/v1","synthetic-chat")
    private val embedding=ModelConfig("tabs-embedding","合成向量配置","silicon-cn","https://api.siliconflow.cn/v1","BAAI/bge-m3")
    private var dark by mutableStateOf(false)
    private var width by mutableIntStateOf(411)
    private var scale by mutableFloatStateOf(1f)
    private val evidence get()=File(context.getExternalFilesDir(null),"evidence-model-tabs").apply{mkdirs()}

    @Before fun setup(){
        ui.activityRule.scenario.onActivity{activity->
            state=ViewModelProvider(activity,object:ViewModelProvider.Factory{
                @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T=
                    ModelConfigState(activity.application,object:ModelDirectoryClient{
                        override suspend fun request(config:ModelConfig,key:String,fetchModels:Boolean):ConfigResult {requests.incrementAndGet();error("No network in model tabs tests")}
                    },namespace) as T
            })[ModelConfigState::class.java]
            app=ViewModelProvider(activity)[AppState::class.java]
        }
        ui.waitUntil(15000){app.ready}
        ui.onNodeWithTag("tab-2").performClick();ui.onNodeWithTag("settings-models").performClick()
        ui.onNodeWithTag("config-tab-chat").assertIsSelected()
    }
    @After fun close(){
        ui.runOnIdle{state.clearResult()}
        ui.activityRule.scenario.onActivity{it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
        vault.list().forEach{vault.delete(it.id)}
        assertEquals(0,requests.get())
    }
    private fun seed(){
        vault.save(chat,"synthetic-only");vault.save(embedding,"synthetic-only")
        ModelPurpose.entries.forEach{vault.setPurpose(it,if(it==ModelPurpose.EMBEDDING)embedding.id else chat.id)}
        ui.runOnIdle{state.refresh()};ui.waitUntil(15000){state.configs.size==2&&state.purposes.size==ModelPurpose.entries.size&&!state.busy}
    }
    private fun back(){InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);ui.waitForIdle()}
    private fun services(){
        if(ui.onAllNodesWithTag("config-tab-chat").fetchSemanticsNodes().isEmpty()){
            ui.waitUntil(5000){ui.activity.hasWindowFocus()}
            ui.onNodeWithTag("config-back").performClick()
            ui.onNodeWithTag("settings-models").performScrollTo().performClick()
            ui.waitUntil(5000){ui.onAllNodesWithTag("config-tab-chat").fetchSemanticsNodes().isNotEmpty()}
        }
        ui.onNodeWithTag("config-tab-chat").performClick().assertIsSelected()
    }
    private fun purposes(){
        ui.onNodeWithTag("config-open-purposes").assertDoesNotExist()
        ui.onNodeWithTag("config-tab-indicator").assertExists()
        ui.onNodeWithTag("config-back").performClick()
        ui.onNodeWithTag("settings-purposes").performScrollTo().performClick()
    }
    private fun swipeTab(left:Boolean){ui.onNodeWithTag("config-overview").performTouchInput{swipe(Offset(width*(if(left).8f else .2f),height*.55f),Offset(width*(if(left).2f else .8f),height*.55f),400)}}
    @Test fun swipesClicksEdgesAndVerticalListsKeepPurposeAndScrollState(){
        seed()
        repeat(10){vault.save(chat.copy(id="scroll-$it",name="滚动合成配置$it"),"synthetic-only")}
        ui.runOnIdle{state.refresh()};ui.waitUntil(15000){state.configs.size==12}
        val list=ui.onNodeWithTag("config-overview")
        list.performScrollToNode(hasTestTag("config-add-chat"))
        val top=ui.onNodeWithTag("config-add-chat").fetchSemanticsNode().boundsInRoot.top
        for(tag in listOf("embedding","voice","image")){
            swipeTab(true)
            ui.onNodeWithTag("config-tab-$tag").assertIsSelected()
        }
        swipeTab(true);ui.onNodeWithTag("config-tab-image").assertIsSelected()
        services()
        ui.onNodeWithTag("config-add-chat").assertIsDisplayed()
        assertEquals(top,ui.onNodeWithTag("config-add-chat").fetchSemanticsNode().boundsInRoot.top,2f)
        purposes();ui.onNodeWithTag("purpose-select-memory").performScrollTo().performClick()
        ui.onNodeWithTag("purpose-clear-memory").performClick()
        ui.waitUntil(10000){!state.busy&&state.purposes[ModelPurpose.MEMORY].orEmpty().isEmpty()}
        services();swipeTab(false);ui.onNodeWithTag("config-tab-chat").assertIsSelected()
    }
    @Test fun swipeOutsideOverviewDoesNotDiscardEditorDraft(){
        seed();ui.onNodeWithTag("config-edit-${chat.id}").performClick()
        ui.onNodeWithTag("config-name").performTextReplacement("切页不应丢失的编辑草稿")
        ui.onRoot().performTouchInput{swipeLeft()}
        ui.onNodeWithTag("config-name").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,androidx.compose.ui.text.AnnotatedString("切页不应丢失的编辑草稿")))
        ui.onNodeWithTag("config-open-purposes").assertDoesNotExist()
        ui.onNodeWithTag("config-back").performClick()
        ui.waitUntil(5000){ui.onAllNodesWithTag("config-tab-chat").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("config-tab-chat").assertIsSelected();assertEquals(chat,vault.list().single{it.id==chat.id})
        ui.onNodeWithTag("config-edit-${chat.id}").performClick()
        ui.onNodeWithTag("config-editor").assertIsDisplayed()
        back()
        ui.waitUntil(5000){ui.onAllNodesWithTag("config-tab-chat").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("config-tab-chat").assertIsSelected()
    }
    @Test fun emptyTabsKeepBothTextAddEntrancesAndNativeTabSemantics(){
        ui.onNodeWithTag("config-services-empty").assertIsDisplayed()
        ui.onNodeWithTag("config-add-top").assertDoesNotExist()
        ui.onNodeWithTag("purpose-select-chat").assertDoesNotExist()
        for(tag in listOf("config-tab-chat","config-tab-embedding","config-tab-voice","config-tab-image")){
            ui.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Tab)).assertHeightIsAtLeast(48.dp)
        }
        ui.onNodeWithTag("config-add-chat").performScrollTo().assertTextEquals("+ 添加聊天服务商").assertHeightIsAtLeast(48.dp).performClick()
        ui.onNodeWithTag("provider-search").assertIsDisplayed();back()
        ui.onNodeWithTag("config-tab-chat").assertIsSelected()
        ui.onNodeWithTag("config-tab-embedding").performClick();ui.onNodeWithTag("config-add-embedding").performScrollTo().assertTextEquals("+ 添加向量服务商").performClick()
        ui.onNodeWithText("选择向量服务商").assertIsDisplayed();back()
        purposes();ui.onNodeWithTag("config-add-chat").assertDoesNotExist();ui.onNodeWithTag("config-services-empty").assertDoesNotExist()
        ui.onNodeWithTag("purpose-select-chat").assertExists()
        ui.onNodeWithTag("purpose-help-embedding").performClick()
        ui.activityRule.scenario.onActivity{it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
        ui.waitUntil(15000){ui.activity.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE}
        ui.onNodeWithTag("purpose-select-review").assertExists()
        // Rotation recreates the transient dialog; its entry remains accessible on the same tab.
        ui.onNodeWithTag("purpose-help-embedding").performScrollTo().performClick()
        ui.onNodeWithText("所有角色共用用途选择，不设角色单独覆盖。先在“服务商”页添加并保存配置，再选择对应模型。保存配置和选择用途只保存设置，不发出请求。").performScrollTo().assertIsDisplayed()
        ui.onNodeWithTag("purpose-help-close").assertIsDisplayed().performClick()
        ui.activityRule.scenario.onActivity{it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
        ui.waitUntil(15000){ui.activity.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT}
        ui.onNodeWithTag("purpose-help-chat").performScrollTo().assertContentDescriptionEquals("聊天说明").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp).performClick()
        ui.onNodeWithText("聊天时使用这个模型；未单独选择回顾模型时，心情与学习回顾也用它。").assertIsDisplayed()
        ui.onNodeWithTag("purpose-clear-chat").assertDoesNotExist()
        ui.onNodeWithTag("purpose-help-close").performClick()
        ui.onNodeWithTag("purpose-help-memory").performScrollTo().performClick()
        ui.onNodeWithText("主动总结记忆使用所选总结记忆模型；未选择时沿用聊天模型。").assertIsDisplayed()
        ui.onNodeWithTag("purpose-help-close").performClick()
        ui.onNodeWithTag("auxiliary-vision-switch",useUnmergedTree=true).assertDoesNotExist()
        ui.waitUntil(5000){ui.activity.hasWindowFocus()}
        back()
        ui.waitUntil(5000){ui.onAllNodesWithTag("settings-toolbox").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("settings-toolbox").performScrollTo().performClick();ui.onNodeWithTag("tool-card-image").performClick()
        ui.onNodeWithTag("purpose-help-aux-vision").performScrollTo().performClick()
        ui.onNodeWithText("辅助看图开启时，所选辅助模型接收图片并提供观察，聊天模型接收观察与文字；关闭时图片直接发给所选聊天模型。不检测模型能力，不自动换模型或回退。图片只在发送时上传。").assertIsDisplayed()
        back()
        ui.waitUntil(5000){ui.activity.hasWindowFocus() && ui.onAllNodesWithTag("purpose-help-close").fetchSemanticsNodes().isEmpty()}
        ui.onNodeWithTag("toolbox-image-page").assertIsDisplayed()
        back()
        ui.waitUntil(5000){ui.onAllNodesWithTag("toolbox-image-page").fetchSemanticsNodes().isEmpty()}
        ui.onNodeWithTag("toolbox-page").assertIsDisplayed()
        back()
        ui.waitUntil(5000){ui.onAllNodesWithTag("toolbox-page").fetchSemanticsNodes().isEmpty()}
        ui.onNodeWithTag("settings-home").assertIsDisplayed()
    }
    @Test fun savedModelsPurposesAndTabSurviveRotationAndNavigation(){
        seed()
        ui.onNodeWithTag("config-edit-${chat.id}").assertIsDisplayed()
        ui.onNodeWithTag("config-add-chat").performScrollTo().performClick();ui.onNodeWithTag("provider-search").assertIsDisplayed();back()
        purposes()
        ui.onNodeWithTag("config-edit-${chat.id}").assertDoesNotExist()
        ui.onNodeWithTag("purpose-select-chat").assertTextContains(chat.modelId,substring=true)
        ui.activityRule.scenario.onActivity{it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
        ui.waitUntil(15000){ui.activity.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE}
        ui.onNodeWithTag("purpose-select-review").assertExists()
        ui.onNodeWithTag("purpose-select-chat").assertExists()
        ui.activityRule.scenario.onActivity{it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
        ui.waitUntil(15000){ui.activity.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT}
        ui.onNodeWithTag("purpose-select-review").assertExists()
        services();purposes()
        ModelPurpose.entries.filter{it!=ModelPurpose.AUX_VISION}.forEach{purpose->
            ui.onNodeWithTag("purpose-select-${purpose.key}").performScrollTo().assertTextContains(if(purpose==ModelPurpose.EMBEDDING)embedding.modelId else chat.modelId,substring=true)
        }
        assertEquals(listOf(chat,embedding).toSet(),vault.list().toSet())
        ui.onNodeWithTag("purpose-select-aux-vision").assertDoesNotExist()
        back();ui.onNodeWithTag("settings-home").assertIsDisplayed()
        ui.onNodeWithTag("settings-toolbox").performScrollTo().performClick();ui.onNodeWithTag("tool-card-image").performClick()
        ui.onNodeWithTag("purpose-select-aux-vision").assertTextContains(chat.modelId,substring=true);back();back()
        ui.onNodeWithTag("settings-models").performClick();ui.onNodeWithTag("config-tab-chat").assertIsSelected()
        assertEquals(chat.id,vault.purposeId(ModelPurpose.CHAT));assertEquals(embedding.id,vault.purposeId(ModelPurpose.EMBEDDING))
    }
    @Test fun purposeSelectionStillPersistsAndClearsWithoutNetwork(){
        seed();purposes()
        ui.onNodeWithTag("purpose-select-memory").performScrollTo().performClick()
        ui.onNodeWithTag("purpose-clear-memory").performClick()
        ui.waitUntil(10000){!state.busy&&state.purposes[ModelPurpose.MEMORY].orEmpty().isEmpty()}
        assertEquals("",vault.purposeId(ModelPurpose.MEMORY))
        ui.onNodeWithTag("purpose-select-memory").performClick()
        ui.onNodeWithTag("purpose-option-memory-${chat.id}").performClick()
        ui.waitUntil(10000){!state.busy&&state.purposes[ModelPurpose.MEMORY]==chat.id}
        services();purposes()
        ui.onNodeWithTag("purpose-select-memory").performScrollTo().assertTextContains(chat.modelId,substring=true)
        assertEquals(chat.id,vault.purposeId(ModelPurpose.MEMORY))
    }
    @Test fun nativeScreensAcrossEmptySavedLightDarkAndSmallLargeText(){
        ui.activityRule.scenario.onActivity{activity->activity.setContent{LianxuTheme(dark){
            val density=LocalDensity.current
            val c=MaterialTheme.colorScheme
            SideEffect{
                val a=c.primary.luminance();val b=c.surfaceContainer.luminance()
                assertTrue("Unselected tab contrast",(maxOf(a,b)+.05f)/(minOf(a,b)+.05f)>=4.5f)
            }
            CompositionLocalProvider(LocalDensity provides Density(density.density,scale)){
                Box(Modifier.fillMaxSize()){Box(Modifier.width(width.dp).fillMaxHeight().testTag("model-tabs-viewport")){Lianxu(app)}}
            }
        }}}
        for(saved in listOf(false,true)){
            if(saved)seed()
            for(night in listOf(false,true))for(dp in listOf(320,411)){
                ui.runOnIdle{dark=night;width=dp;scale=if(dp==320)1.3f else 1f}
                services();ui.onNodeWithTag("model-tabs-viewport").assertWidthIsEqualTo(dp.dp)
                val indicator = ui.onNodeWithTag("config-tab-indicator").fetchSemanticsNode().boundsInRoot
                for (tab in listOf("chat", "embedding", "voice", "image")) {
                    val bounds = ui.onNodeWithTag("config-tab-$tab").fetchSemanticsNode().boundsInRoot
                    assertEquals(indicator.top, bounds.top, .1f)
                    assertEquals(indicator.bottom, bounds.bottom, .1f)
                    assertEquals(indicator.width, bounds.width, 1f)
                }
                if(saved)ui.onNodeWithTag("config-edit-${chat.id}").performScrollTo().assertIsDisplayed() else ui.onNodeWithTag("config-services-empty").assertIsDisplayed()
                shot("services",saved)
                ui.onNodeWithTag("config-overview").performScrollToNode(hasTestTag("config-add-chat"))
                ui.onNodeWithTag("config-add-chat").assertIsDisplayed()
                ui.onNodeWithTag("config-tab-embedding").performClick();ui.onNodeWithTag("config-overview").performScrollToNode(hasTestTag("config-add-embedding"))
                ui.onNodeWithTag("config-add-embedding").assertIsDisplayed()
                purposes();ui.onNodeWithTag("purpose-select-embedding").performScrollTo().assertIsDisplayed();shot("purposes",saved)
                ui.onNodeWithTag("auxiliary-vision-switch",useUnmergedTree=true).assertDoesNotExist()
            }
        }
    }
    private fun shot(page:String,saved:Boolean){
        ui.mainClock.advanceTimeBy(500);ui.waitForIdle();android.os.SystemClock.sleep(250)
        val name="$page-${if(saved)"saved" else "empty"}-${if(dark)"dark" else "light"}-$width-font$scale.png"
        val viewport=if(ui.onAllNodesWithTag("model-tabs-viewport").fetchSemanticsNodes().isEmpty())ui.onRoot() else ui.onNodeWithTag("model-tabs-viewport")
        val bitmap=viewport.captureToImage().asAndroidBitmap()
        File(evidence,name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
}
