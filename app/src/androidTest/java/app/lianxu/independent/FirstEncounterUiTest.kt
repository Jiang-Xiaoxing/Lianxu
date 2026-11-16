package app.lianxu.independent

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.*
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Real application routing with isolated synthetic data; no live model or user database. */
class FirstEncounterUiTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrument.targetContext
    private lateinit var oldDb:String
    private lateinit var oldVault:String
    private lateinit var oldClient:()->TextChatClient
    private lateinit var namespace:String
    private lateinit var app:AppState
    private val calls=AtomicInteger()
    private var durationScale by mutableFloatStateOf(1f)
    @get:Rule(order=0) val isolated=object:TestWatcher(){
        override fun starting(description:Description){
            namespace="door-entry-${System.nanoTime()}"
            oldDb=AppServices.databaseName;oldVault=AppServices.vaultNamespace;oldClient=AppServices.createTextClient
            AppServices.databaseName="$namespace.db";AppServices.vaultNamespace=namespace
            AppServices.createTextClient={object:TextChatClient{
                override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
                    calls.incrementAndGet();return TextChatResult.Failure(ChatFailure.NETWORK)
                }
            }}
            runBlocking{Store(context,"$namespace.db").use{store->
                if(description.methodName=="existingRolesBypassAtLaunch")store.add(listOf(Friend("door-existing","已有合成角色","保留原资料")),false)
                LocalUpdateRepository(store).acknowledge(InstalledBuild.read(context).versionCode)
            }}
        }
        override fun finished(description:Description){
            try{
                if(::app.isInitialized)app.processing.store.close()
                context.deleteDatabase("$namespace.db");context.deleteDatabase("$namespace-source.db")
                File(context.noBackupFilesDir,namespace).deleteRecursively()
                File(context.noBackupFilesDir,"$namespace-source").deleteRecursively()
            }finally{AppServices.databaseName=oldDb;AppServices.vaultNamespace=oldVault;AppServices.createTextClient=oldClient}
            assertEquals("No model request is part of onboarding",0,calls.get())
        }
    }
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>(effectContext=object:MotionDurationScale{override val scaleFactor get()=durationScale})
    @Before fun ready(){
        ui.runOnIdle{app=ViewModelProvider(ui.activity)[AppState::class.java]}
        ui.waitUntil(15000){app.ready}
        ui.waitForIdle() // Readiness can precede the first composed frame; settle before freezing time.
    }
    private fun enter(){ui.onNodeWithTag("first-encounter").performClick();ui.onNodeWithTag("first-encounter-create").assertIsDisplayed()}
    private fun back(){instrument.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);ui.waitForIdle()}
    private fun shot(name:String){
        ui.waitForIdle()
        instrument.waitForIdleSync();SystemClock.sleep(200)
        val dir=File(context.getExternalFilesDir(null),"evidence-first-encounter").apply{mkdirs()}
        val image=instrument.uiAutomation.takeScreenshot()
        File(dir,"$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }
    @Test fun freshHomeShowsOnlyThePaintingUntilTapped(){
        ui.onNodeWithTag("first-encounter").assertIsDisplayed()
        ui.onNodeWithTag("first-encounter-create").assertDoesNotExist()
        ui.onNodeWithTag("first-encounter-restore").assertDoesNotExist()
        ui.onNodeWithTag("tab-0").assertDoesNotExist()
        assertTrue(app.friends.isEmpty());shot("closed")
    }
    @Test fun openingThenAdvanceKeepsActionsHiddenAndIgnoresRepeatedTaps(){
        ui.mainClock.autoAdvance=false
        ui.onNodeWithTag("first-encounter").performClick();ui.mainClock.advanceTimeBy(750)
        repeat(6){ui.onNodeWithTag("first-encounter").performTouchInput{click(center)}}
        assertEquals("home",app.page);ui.onNodeWithTag("first-encounter-actions").assertDoesNotExist();shot("opening")
        ui.mainClock.advanceTimeBy(1100);ui.onNodeWithTag("first-encounter-actions").assertDoesNotExist();shot("open-before-advance")
        ui.mainClock.advanceTimeBy(600);ui.onNodeWithTag("first-encounter-actions").assertDoesNotExist();shot("advancing")
        ui.mainClock.advanceTimeBy(1600);ui.onNodeWithTag("first-encounter-create").assertIsDisplayed()
        val area=ui.onNodeWithTag("first-encounter").getUnclippedBoundsInRoot()
        val actions=ui.onNodeWithTag("first-encounter-actions").getUnclippedBoundsInRoot()
        assertEquals((area.top.value+area.bottom.value)/2f,(actions.top.value+actions.bottom.value)/2f,2f);shot("settled")
        ui.mainClock.autoAdvance=true
    }
    @Test fun createCancelAndRecreationReturnToTheSettledChoice(){
        enter();ui.onNodeWithTag("first-encounter-create").performClick()
        ui.onNodeWithTag("friend-name").assertIsDisplayed();shot("create-existing-page")
        ui.activityRule.scenario.recreate();ui.onNodeWithTag("create-back").assertIsEnabled();back()
        ui.onNodeWithTag("first-encounter-create").assertIsDisplayed()
        ui.onNodeWithText("轻触，开始相遇").assertDoesNotExist();assertTrue(app.friends.isEmpty());shot("returned-from-create")
    }
    @Test fun tapsAtTheCameraToButtonsBoundaryCannotChooseAnInvisibleAction(){
        ui.mainClock.autoAdvance=false
        ui.onNodeWithTag("first-encounter").performClick()
        var frames=0
        while(ui.onAllNodesWithTag("first-encounter-create").fetchSemanticsNodes().isEmpty()&&frames<260){ui.mainClock.advanceTimeByFrame();frames++}
        ui.onNodeWithTag("first-encounter-create").assertIsNotEnabled()
        ui.mainClock.advanceTimeBy(160);shot("actions-fading")
        repeat(5){ui.onNodeWithTag("first-encounter-create").performTouchInput{click(center)}}
        assertEquals("home",app.page);assertTrue(app.friends.isEmpty())
        ui.mainClock.advanceTimeBy(500)
        ui.onNodeWithTag("first-encounter-create").assertIsEnabled().performClick()
        assertEquals("create",app.page)
        ui.mainClock.autoAdvance=true
        ui.onNodeWithTag("friend-name").assertIsDisplayed()
    }
    @Test fun creatingTheFirstRoleLeavesTheDoorAndDoesNotRequestAModel(){
        enter();ui.onNodeWithTag("first-encounter-create").performClick()
        ui.onNodeWithTag("friend-name").performTextInput("门内合成角色")
        ui.onNodeWithTag("create-next").performScrollTo().performClick()
        ui.onNodeWithTag("friend-bio").performTextInput("仅用于首次引导测试，不是真实用户资料。")
        ui.onNodeWithTag("create-next").performScrollTo().performClick()
        ui.onNodeWithTag("create-skip-relationship").performScrollTo().performClick()
        ui.onNodeWithTag("create-next").performScrollTo().performClick()
        ui.onNodeWithTag("create-use-original").performScrollTo().performClick()
        ui.activityRule.scenario.onActivity{activity->
            val input=activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            input.hideSoftInputFromWindow(activity.window.decorView.windowToken,0)
        }
        ui.waitForIdle();shot("before-create-save")
        ui.onNodeWithTag("create-friend").performScrollTo().performClick()
        try{ui.waitUntil(15000){app.friends.size==1&&app.page=="chat"}}catch(error:Throwable){
            println("synthetic create page=${app.page} friends=${app.friends.size} error=${app.error}")
            println(ui.onRoot().printToString());shot("create-save-failed");throw error
        }
        ui.onNodeWithTag("first-encounter").assertDoesNotExist()
        ui.runOnIdle{app.home()};ui.onNodeWithTag("tab-0").assertIsDisplayed();shot("created-original-home")
    }
    @Test fun restoreSystemPickerCancelReturnsWithoutChangingData(){
        enter();ui.onNodeWithTag("first-encounter-restore").performClick()
        ui.onNodeWithTag("archive-open").performScrollTo().performClick()
        ui.waitUntil(10000){instrument.uiAutomation.rootInActiveWindow?.packageName?.toString()?.let{it!=context.packageName}==true}
        instrument.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        ui.waitUntil(10000){instrument.uiAutomation.rootInActiveWindow?.packageName?.toString()==context.packageName}
        ui.onNodeWithTag("role-archive-page").assertIsDisplayed();back()
        ui.onNodeWithTag("first-encounter-create").assertIsDisplayed();assertTrue(app.friends.isEmpty());shot("restore-cancelled")
    }
    @Test fun restoreUsesTheExistingPreviewAndCommitBeforeBypassingTheDoor(){
        val uri=Uri.parse("content://app.lianxu.independent.test.archivefixture/saved/capture-${System.nanoTime()}")
        val bytes=runBlocking{Store(context,"$namespace-source.db").use{source->
            source.add(listOf(Friend("door-restored","恢复的合成角色","只用于引导测试")),false)
            val exported=BackupRepository(source,ModelVault(context,"$namespace-source",source)).export(null,false)
            ByteArrayOutputStream().also{BackupArchive.write(exported.root,exported.files,it)}.toByteArray()
        }}
        instrument.context.contentResolver.openOutputStream(uri)!!.use{it.write(bytes)}
        val owner=object:ActivityResultRegistryOwner{override val activityResultRegistry=object:ActivityResultRegistry(){
            override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?){dispatchResult(requestCode,Activity.RESULT_OK,Intent().setData(uri))}
        }}
        ui.activityRule.scenario.onActivity{activity->activity.setContent{CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner){LianxuTheme{Lianxu(app)}}}}
        enter();ui.onNodeWithTag("first-encounter-restore").performClick()
        ui.onNodeWithTag("archive-open").performScrollTo().performClick()
        ui.waitUntil(15000){ui.onAllNodesWithTag("archive-mode-add").fetchSemanticsNodes().isNotEmpty()}
        assertTrue(app.friends.isEmpty());ui.onNodeWithTag("archive-mode-add").performClick()
        ui.onNodeWithTag("archive-preview").performScrollTo().performClick()
        ui.waitUntil(15000){ui.onAllNodesWithTag("archive-impact").fetchSemanticsNodes().isNotEmpty()}
        assertTrue(app.friends.isEmpty());shot("restore-preview")
        ui.onNodeWithTag("archive-import").performScrollTo().performClick()
        ui.waitUntil(15000){app.friends.singleOrNull()?.name=="恢复的合成角色"}
        ui.waitUntil(15000){ui.onAllNodesWithTag("backup-progress").fetchSemanticsNodes().isEmpty()}
        back();ui.onNodeWithTag("tab-0").assertIsDisplayed();ui.onNodeWithTag("first-encounter").assertDoesNotExist();shot("restored-original-home")
    }
    @Test fun existingRolesBypassAtLaunch(){
        ui.onNodeWithTag("first-encounter").assertDoesNotExist();ui.onNodeWithTag("tab-0").assertIsDisplayed()
        assertEquals("door-existing",app.friends.single().id);shot("existing-user")
    }
    @Test fun reducedMotionShowsUsableFinalChoicesWithoutATap(){
        ui.runOnIdle{durationScale=0f}
        ui.onNodeWithTag("first-encounter-create").assertIsDisplayed().assertIsEnabled();ui.onNodeWithTag("first-encounter-restore").assertIsDisplayed().assertIsEnabled()
        shot("reduced-motion")
    }
    @Test fun reducedMotionDuringOpeningSettlesWithoutRestarting(){
        ui.mainClock.autoAdvance=false
        ui.onNodeWithTag("first-encounter").performClick();ui.mainClock.advanceTimeBy(700)
        ui.onNodeWithTag("first-encounter-actions").assertDoesNotExist()
        ui.runOnIdle{durationScale=0f}
        // Recomposition, the phase effect and placement need separate frames; no animation wait.
        ui.mainClock.advanceTimeBy(64)
        ui.onNodeWithTag("first-encounter-create").assertIsDisplayed();shot("reduced-during-opening")
        ui.mainClock.autoAdvance=true
    }
    @Test fun rebuildingDuringOpeningShowsSettledChoices(){
        ui.mainClock.autoAdvance=false
        ui.onNodeWithTag("first-encounter").performClick();ui.mainClock.advanceTimeBy(700)
        ui.activityRule.scenario.recreate();ui.mainClock.advanceTimeByFrame()
        ui.onNodeWithTag("first-encounter-create").assertIsDisplayed();shot("recreated-during-opening")
        ui.mainClock.autoAdvance=true
    }
    @Test fun landscapeKeepsTheWholeOriginalDoorInView(){
        ui.activityRule.scenario.onActivity{it.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
        ui.waitUntil(15000){context.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE}
        ui.waitUntil(15000){ui.onAllNodesWithTag("first-encounter").fetchSemanticsNodes().singleOrNull()?.boundsInRoot?.let{it.width>it.height}==true}
        ui.onNodeWithTag("first-encounter").assertIsDisplayed();shot("landscape-closed")
    }
    @Test fun recordedMotionUsesTheProductionDoorAndEndsAtTheCentralChoices(){
        ui.mainClock.autoAdvance=false;shot("recorded-closed");SystemClock.sleep(500)
        ui.onNodeWithTag("first-encounter").performClick()
        repeat(255){ui.mainClock.advanceTimeBy(16);SystemClock.sleep(16)}
        ui.onNodeWithTag("first-encounter-create").assertIsDisplayed();shot("recorded-settled");SystemClock.sleep(600)
        ui.mainClock.autoAdvance=true
    }
}
