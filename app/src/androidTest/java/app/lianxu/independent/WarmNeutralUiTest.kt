package app.lianxu.independent

import android.content.ContentValues
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** Real production composables in an isolated database. Theme/width/font overrides are test-only. */
class WarmNeutralUiTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val database="warm-neutral-${System.nanoTime()}.db"
    private val namespace="warm-neutral-${System.nanoTime()}"
    private val calls=AtomicInteger()
    private lateinit var previousDatabase:String
    private lateinit var previousNamespace:String
    private lateinit var previousClient:()->TextChatClient
    private lateinit var app:AppState
    private lateinit var palette:ColorScheme
    private var dark by mutableStateOf(false)
    private var width by mutableStateOf(411)
    private var fontScale by mutableStateOf(1f)
    private val evidence get()=File(context.getExternalFilesDir(null),"evidence-warm-neutral").apply{mkdirs()}

    @get:Rule(order=0) val fixture=object:ExternalResource(){
        override fun before(){
            previousDatabase=AppServices.databaseName;previousNamespace=AppServices.vaultNamespace;previousClient=AppServices.createTextClient
            AppServices.databaseName=database;AppServices.vaultNamespace=namespace
            AppServices.createTextClient={object:TextChatClient{
                override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
                    calls.incrementAndGet();error("No network in visual tests")
                }
            }}
            runBlocking{val store=Store(context,database);try{LocalUpdateRepository(store).acknowledge(InstalledBuild.read(context).versionCode)}finally{store.close()}}
        }
        override fun after(){
            AppServices.databaseName=previousDatabase;AppServices.vaultNamespace=previousNamespace;AppServices.createTextClient=previousClient
            context.deleteDatabase(database)
            assertEquals(0,calls.get())
        }
    }
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()

    @Before fun show(){
        ui.runOnIdle{app=ViewModelProvider(ui.activity)[AppState::class.java]}
        ui.waitUntil(15000){app.ready}
        ui.activityRule.scenario.onActivity{activity->activity.setContent{
            LianxuTheme(dark){
                val density=LocalDensity.current
                val scheme=MaterialTheme.colorScheme
                SideEffect{palette=scheme}
                CompositionLocalProvider(LocalDensity provides Density(density.density,fontScale)){
                    Box(Modifier.fillMaxSize()){
                        Box(Modifier.width(width.dp).fillMaxHeight().testTag("warm-viewport")){Lianxu(app)}
                    }
                }
            }
        }}
        ui.waitForIdle()
    }
    private fun variants(check:()->Unit){
        listOf(false,true).forEach{night->listOf(320,360,411).forEach{dp->
            ui.runOnIdle{dark=night;width=dp;fontScale=if(dp==320)1.3f else 1f;app.back();app.tab=0}
            ui.onNodeWithTag("warm-viewport").assertWidthIsEqualTo(dp.dp)
            checkContrast()
            check()
        }}
    }
    private fun shot(surface:String){
        val name="$surface-${if(dark)"dark" else "light"}-$width-font$fontScale"
        val bitmap=ui.onNodeWithTag("warm-viewport").captureToImage().asAndroidBitmap()
        File(evidence,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    private fun checkContrast(){
        val c=palette
        val pairs=listOf(
            Triple("正文",c.onSurface,c.surface),Triple("辅助文字",c.onSurfaceVariant,c.surface),
            Triple("目录说明与底栏",c.onSurfaceVariant,c.surfaceContainerLow),Triple("强调文字",c.primary,c.surface),
            Triple("圆角按钮",c.onSecondaryContainer,c.secondaryContainer),Triple("已选导航",c.primary,c.secondaryContainer),
            Triple("用户气泡",c.onPrimaryContainer,c.primaryContainer),Triple("角色气泡与输入",c.onSurface,c.surface),
            Triple("发送按钮",c.primary,c.primary.copy(alpha=.18f).compositeOver(c.surfaceContainerLow))
        )
        val results=pairs.map{(label,fg,bg)->
            val a=fg.luminance();val b=bg.luminance();val contrast=(maxOf(a,b)+.05f)/(minOf(a,b)+.05f)
            assertTrue("$label dark=$dark contrast=$contrast",contrast>=4.5f)
            "$label=$contrast"
        }
        File(evidence,"contrast-${if(dark)"dark" else "light"}.txt").writeText(results.joinToString("\n"))
    }
    private fun checkPlus(){
        val plus=ui.onNodeWithTag("add-menu").assertContentDescriptionEquals("添加角色").assertHasClickAction()
        plus.assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        val image=plus.captureToImage();val pixel=image.toPixelMap()[(image.width*.17f).toInt(),image.height/2]
        val bg=palette.surfaceContainerLow
        assertTrue("加号不应有圆底",abs(pixel.red-bg.red)<.02f&&abs(pixel.green-bg.green)<.02f&&abs(pixel.blue-bg.blue)<.02f)
    }
    @Test fun emptyHomeAndSettingsAcrossThemesWidthsAndLargeText(){
        variants{
            ui.onNodeWithTag("roles-empty-chat").assertIsDisplayed()
            checkPlus();shot("empty-home")
            ui.onNodeWithText("创建角色").assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
            ui.onNodeWithTag("friend-name").assertIsDisplayed()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            ui.onNodeWithTag("roles-empty-chat").assertIsDisplayed()
            ui.onNodeWithTag("add-menu").performClick()
            ui.onNodeWithText("寻找角色").assertIsDisplayed()
            ui.onAllNodesWithText("创建角色").onLast().performClick()
            ui.onNodeWithTag("friend-name").assertIsDisplayed()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            ui.onNodeWithTag("tab-2").performClick()
            ui.onNodeWithTag("settings-home").assertIsDisplayed()
            ui.onNodeWithTag("settings-models").assertIsDisplayed()
            ui.onNodeWithTag("settings-display").assertDoesNotExist()
            shot("settings")
            ui.onNodeWithTag("settings-info").performScrollTo().performClick()
            ui.onNodeWithTag("settings-info-page").assertIsDisplayed()
            ui.onNodeWithTag("settings-back").performClick()
            ui.onNodeWithTag("tab-0").performClick()
        }
    }
    @Test fun populatedHomeAndChatAcrossThemesWidthsAndLargeText(){
        val store=Store(context,database)
        var userMessage=0L
        try{
            runBlocking{store.add(listOf(Friend("warm-role","合成同伴","只供界面验证的合成角色")),false)}
            listOf(true,false).forEachIndexed{i,mine->val id=store.writableDatabase.insertOrThrow("messages",null,ContentValues().apply{
                put("friend_id","warm-role");put("body",if(mine)"这是合成消息，用来检查气泡和文字。" else "你好，今天过得怎么样？")
                put("mine",if(mine)1 else 0);put("created",System.currentTimeMillis()+i)
            });if(mine)userMessage=id}
        }finally{store.close()}
        ui.runOnIdle{app.foreground()};ui.waitUntil(15000){app.friends.size==1&&app.messages.size==2}
        variants{
            ui.onNodeWithText("合成同伴").assertIsDisplayed()
            checkPlus();shot("populated-home")
            ui.onNodeWithText("合成同伴").performClick()
            ui.onNodeWithTag("chat-title").assertTextEquals("合成同伴")
            ui.onNodeWithTag("message-input").assertIsDisplayed()
            ui.onNodeWithTag("send-message").assertHeightIsEqualTo(48.dp)
            ui.onNodeWithTag("bubble-$userMessage-0",useUnmergedTree=true).assertIsDisplayed()
            shot("chat")
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            ui.onNodeWithTag("chat-list").assertIsDisplayed()
        }
    }
}
