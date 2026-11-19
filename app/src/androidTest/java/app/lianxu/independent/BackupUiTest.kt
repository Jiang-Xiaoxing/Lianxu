package app.lianxu.independent

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.result.*
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentLinkedQueue

class BackupUiTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private lateinit var vm:AppState;private lateinit var vault:ModelVault
    private lateinit var name:String;private lateinit var oldDb:String;private lateinit var oldVault:String
    private val saves=mutableListOf<Uri>();private var openUri:Uri?=null;private val calls=AtomicInteger()
    private val pickerResults=ConcurrentLinkedQueue<()->Unit>()
    @get:Rule(order=0)val fixture=object:ExternalResource(){
        override fun before(){oldDb=AppServices.databaseName;oldVault=AppServices.vaultNamespace;name="backup-ui-${System.nanoTime()}";AppServices.databaseName="$name.db";AppServices.vaultNamespace=name;seedLegacyTestRoles(instrument.targetContext,"$name.db");Store(instrument.targetContext,"$name.db").use { store -> runBlocking { store.savePreference(EXPORT_DETAIL_PREF,"0") } }}
        override fun after(){if(::vault.isInitialized)vault.list().forEach{vault.delete(it.id)};instrument.targetContext.deleteDatabase("$name.db");File(instrument.targetContext.noBackupFilesDir,name).deleteRecursively();AppServices.databaseName=oldDb;AppServices.vaultNamespace=oldVault}
    }
    @get:Rule(order=1)val ui=createAndroidComposeRule<ComponentActivity>()
    private fun config(endpoint:String="https://example.invalid/v1")=ModelConfig("service","合成服务","custom",endpoint,"synthetic",32000,1024)
    private fun show(dark:Boolean=false,big:Boolean=false,small:Boolean=false){
        ui.runOnUiThread{vm=ViewModelProvider(ui.activity,object:ViewModelProvider.Factory{override fun <T:androidx.lifecycle.ViewModel> create(modelClass:Class<T>):T { @Suppress("UNCHECKED_CAST") return AppState(ui.activity.application,object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls.incrementAndGet();return TextChatResult.Failure(ChatFailure.NETWORK)}},name) as T}})[AppState::class.java]}
        ui.waitUntil(30000){vm.ready};vault=ModelVault(instrument.targetContext,name,vm.processing.store);vault.save(config(),"synthetic-ui-key-only");vault.setPurpose(ModelPurpose.CHAT,"service")
        runBlocking{vm.processing.store.setPreference("nickname","原昵称");RoleAssets(vm.processing).put("seed-lin",RoleAssets.Asset("avatar.png","avatar","image/png",byteArrayOf(1,2,3)))}
        val owner=object:ActivityResultRegistryOwner{override val activityResultRegistry=object:ActivityResultRegistry(){override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?){
            val uri=if(input is String)Uri.parse("content://${archiveFixtureAuthority()}/saved/capture-${System.nanoTime()}").also{saves+=it}else openUri
            // A real user returns from the system picker after the export coroutine has finished.
            // Keep the synthetic result pending until the test reaches that same boundary.
            pickerResults.add{dispatchResult(requestCode,if(uri==null)Activity.RESULT_CANCELED else Activity.RESULT_OK,Intent().setData(uri))}
        }}}
        ui.setContent{val density=LocalDensity.current;CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner,LocalDensity provides Density(density.density,if(big)1.6f else 1f)){LianxuTheme(dark){androidx.compose.material3.Surface(androidx.compose.ui.Modifier.widthIn(max=if(small)320.dp else androidx.compose.ui.unit.Dp.Infinity).fillMaxSize().testTag("backup-test-container"),color=Chrome){androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.safeDrawingPadding()){RoleArchiveScreen(vm)}}}}}
    }
    private fun returnFromPicker(){ui.waitUntil(30000){pickerResults.isNotEmpty()&&ui.onAllNodesWithTag("backup-progress").fetchSemanticsNodes().isEmpty()};ui.runOnIdle{requireNotNull(pickerResults.poll()).invoke()}}
    private fun waitSaved(previous:Int){returnFromPicker();try{ui.waitUntil(30000){saves.size>previous&&ui.onAllNodesWithText("已保存。备份和详细日志可能含私密资料，请妥善保管。").fetchSemanticsNodes().isNotEmpty()}}catch(t:Throwable){println("synthetic_namespace=$name saves=${saves.size} previous=$previous");println(ui.onRoot().printToString());shot("failed-save");throw t}}
    private fun bytes()=instrument.context.contentResolver.openInputStream(saves.last())!!.use{it.readBytes()}
    private fun exportAll(keys:Boolean){val n=saves.size;ui.onNodeWithTag("backup-export-all").performScrollTo().performClick();ui.onNodeWithText("是否包含 API 密钥？").assertExists();assertEquals(n,saves.size);ui.onNodeWithTag(if(keys)"backup-with-keys"else"backup-without-keys").performClick();if(keys){ui.onNodeWithTag("backup-password").performTextInput("synthetic-test-password");ui.onNodeWithTag("backup-password-again").performTextInput("synthetic-test-password");ui.onNodeWithTag("backup-password-ok").performClick()};waitSaved(n)}
    private fun open(mode:String){openUri=saves.last();ui.onNodeWithTag("archive-open").performScrollTo().performClick();returnFromPicker();ui.waitUntil(30000){ui.onAllNodesWithTag("archive-mode-add").fetchSemanticsNodes().isNotEmpty()};ui.onNodeWithTag(if(mode=="ADD")"archive-mode-add"else"archive-mode-replace").performClick();ui.onNodeWithTag("archive-preview").performScrollTo().performClick();ui.waitUntil(30000){ui.onAllNodesWithTag("archive-impact").fetchSemanticsNodes().isNotEmpty()}}
    private fun shot(label:String){ui.mainClock.advanceTimeBy(600);ui.waitForIdle();instrument.waitForIdleSync();SystemClock.sleep(350);val b=if(label=="replace-confirm"||label=="role-picker-in-page")instrument.uiAutomation.takeScreenshot()else ui.onNodeWithTag("backup-test-container").captureToImage().asAndroidBitmap();File(instrument.targetContext.filesDir,"backup-ui-$label.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
    private fun swipeTab(left:Boolean){ui.onNodeWithTag("role-archive-page").performTouchInput{swipe(Offset(width*(if(left).8f else .2f),height*.5f),Offset(width*(if(left).2f else .8f),height*.5f),400)}}
    @Test fun swipeDirectionsBoundariesAndClicksKeepSelectedTabAndPanelTogether(){
        show()
        fun data(){ui.onNodeWithTag("backup-tab-data").assertIsSelected();ui.onNodeWithTag("backup-role-entry").assertExists();ui.onNodeWithTag("backup-export-diagnostics").assertDoesNotExist()}
        fun diagnostic(){ui.onNodeWithTag("backup-tab-diagnostics").assertIsSelected();ui.onNodeWithTag("backup-export-diagnostics").assertExists();ui.onNodeWithTag("backup-role-entry").assertDoesNotExist()}
        ui.capturePagerDrag("backup")
        data();swipeTab(false);data();swipeTab(true);diagnostic();swipeTab(true);diagnostic();shot("swipe-diagnostics")
        repeat(3){swipeTab(false);data();ui.onNodeWithTag("backup-tab-diagnostics").performClick();diagnostic();ui.onNodeWithTag("backup-tab-data").performClick();data();swipeTab(true);diagnostic()}
        ui.onNodeWithTag("backup-tab-data").performClick()
        ui.onNodeWithTag("role-archive-page").performTouchInput{swipe(Offset(width-1f,height*.5f),Offset(width*.3f,height*.5f),400)};data()
        ui.onNodeWithTag("backup-tab-diagnostics").performClick()
        ui.onNodeWithTag("role-archive-page").performTouchInput{swipe(Offset(1f,height*.5f),Offset(width*.7f,height*.5f),400)};diagnostic()
        assertEquals(0,calls.get())
    }
    @Test fun shortDragSnapsByReleaseSpeed(){
        show()
        fun shortDrag(duration:Long){
            ui.onNodeWithTag("role-archive-page").performTouchInput{
                swipe(Offset(width*.70f,height*.55f),Offset(width*.45f,height*.55f),duration)
            }
        }
        shortDrag(900)
        ui.onNodeWithTag("backup-tab-data").assertIsSelected()
        shortDrag(75)
        ui.onNodeWithTag("backup-tab-diagnostics").assertIsSelected()
    }
    @Test fun interruptedTabAnimationsFollowLastAccessibleClick(){
        show()
        ui.mainClock.autoAdvance=false
        try{
            val data=ui.onNodeWithTag("backup-tab-data")
            val logs=ui.onNodeWithTag("backup-tab-diagnostics")
            val width=ui.onNodeWithTag("tab-panel-0",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot.width
            logs.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            ui.mainClock.advanceTimeBy(64)
            val entering=ui.onNodeWithTag("tab-panel-1",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot.width
            assertTrue("页宽=$width 动画中=$entering",entering>0 && entering<width)
            data.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            ui.mainClock.advanceTimeByFrame()
            logs.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        }finally{ui.mainClock.autoAdvance=true}
        ui.waitForIdle()
        ui.onNodeWithTag("backup-tab-diagnostics").assertIsSelected()
        ui.onNodeWithTag("backup-export-diagnostics").assertIsDisplayed()
    }
    @Test fun swipesKeepImportPreviewRoleChoiceAndIndependentVerticalScrolls(){
        show(dark=true,big=true,small=true)
        ui.onNodeWithTag("backup-role-entry").performScrollTo().performClick();ui.onNodeWithTag("archive-export-seed-lin").performClick();ui.onNodeWithText("取消").performClick()
        exportAll(false)
        val saved=ui.onNodeWithTag("archive-status").fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        swipeTab(true);ui.onNodeWithTag("backup-tab-diagnostics").assertIsSelected();ui.onNodeWithTag("archive-status").assertTextEquals(saved);swipeTab(false)
        open("REPLACE")
        ui.onNodeWithTag("archive-impact").performScrollTo()
        val impact=ui.onNodeWithTag("archive-impact").fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        fun scroll()=ui.onNodeWithTag("role-archive-page").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val before=scroll();assertTrue(before>0);shot("swipe-import-preview-before")
        swipeTab(true);ui.onNodeWithTag("backup-tab-diagnostics").assertIsSelected()
        ui.onNodeWithTag("backup-detailed").performScrollTo().performClick();ui.waitUntil(15000){runBlocking{vm.processing.store.preference(EXPORT_DETAIL_PREF,"0")}=="1"}
        ui.onNodeWithTag("role-archive-page").performTouchInput{swipeUp()};ui.onNodeWithTag("backup-tab-diagnostics").assertIsSelected();shot("swipe-diagnostics-dark-large")
        swipeTab(false);ui.onNodeWithTag("backup-tab-data").assertIsSelected();assertEquals(before,scroll(),2f)
        ui.onNodeWithTag("archive-impact").assertTextEquals(impact);ui.onNodeWithTag("backup-choose-mode").assertTextEquals("导入方式：覆盖");shot("swipe-import-preview-returned")
        ui.onNodeWithTag("backup-role-entry").performScrollTo().performClick();ui.onNodeWithTag("archive-export-seed-lin").assertIsSelected();ui.onNodeWithText("取消").performClick()
        assertTrue(vm.diagnosticDetailed);assertEquals(0,calls.get())
    }
    @Test fun diagnosticPreferenceTravelsWithFullBackupAndRestoresRuntimeAndRollbackBase(){
        show();exportAll(false);val old=BackupArchive.read(bytes().inputStream())
        ui.onNodeWithTag("backup-tab-diagnostics").performClick()
        ui.onNodeWithTag("backup-detailed").performClick()
        ui.waitUntil(15000){runBlocking{vm.processing.store.preference(EXPORT_DETAIL_PREF,"0")}=="1"}
        ui.onNodeWithTag("backup-tab-data").performClick();exportAll(false)
        val detailed=BackupArchive.read(bytes().inputStream());assertEquals("1",detailed.root["preferences"].asJsonObject[EXPORT_DETAIL_PREF].asString)
        runBlocking{vm.importBackup(vm.backup.preview(old,"REPLACE"),true,ChatAttemptLease())}
        assertFalse(vm.diagnosticDetailed);assertTrue(Diagnostics.detailed)
        runBlocking{vm.importBackup(vm.backup.preview(detailed,"REPLACE"),true,ChatAttemptLease())}
        assertTrue(vm.diagnosticDetailed);assertTrue(Diagnostics.detailed)
        ui.onNodeWithTag("backup-tab-diagnostics").performClick()
        vm.diagnosticPersist={throw java.io.IOException("synthetic fail after restore")}
        ui.onNodeWithTag("backup-detailed").performClick();ui.waitUntil(15000){vm.diagnosticDetailed}
        assertEquals("1",runBlocking{vm.processing.store.preference(EXPORT_DETAIL_PREF,"0")});vm.diagnosticPersist=null
    }
    @Test fun smallDarkLargeTextKeepsActionsTabsAndHelpReachable(){
        show(dark=true,big=true,small=true)
        assertEquals(ui.onNodeWithTag("backup-tab-data").fetchSemanticsNode().boundsInRoot.height,ui.onNodeWithTag("backup-tab-diagnostics").fetchSemanticsNode().boundsInRoot.height,.1f)
        for(tag in listOf("backup-role-entry","backup-export-all","archive-open"))ui.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        shot("backup-dark-320-large")
        ui.onNodeWithTag("backup-tab-diagnostics").performClick()
        ui.onNodeWithTag("backup-export-diagnostics").performScrollTo().assertIsDisplayed()
        ui.onNodeWithTag("backup-detailed").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        shot("diagnostics-dark-320-large")
        ui.onNodeWithTag("backup-page-help").performClick()
        ui.onNodeWithTag("backup-help-close").assertIsDisplayed().performClick()
        ui.onNodeWithTag("backup-tab-data").performClick()
        ui.onNodeWithTag("backup-role-entry").performScrollTo().performClick()
        ui.onNodeWithTag("archive-export-seed-lin").performScrollTo().assertIsDisplayed().performClick()
        ui.onNodeWithTag("archive-export").assertIsEnabled();ui.onNodeWithText("取消").performClick()
    }
    @Test fun tabsHelpAndRoleCancelKeepPanelsExclusive(){
        show()
        ui.onNodeWithTag("backup-export-diagnostics").assertDoesNotExist()
        ui.onNodeWithTag("backup-role-entry").performClick()
        ui.onNodeWithTag("archive-export-seed-lin").performScrollTo().performClick()
        ui.onNodeWithTag("archive-export-seed-lin").assertIsSelected()
        shot("role-picker-in-page")
        ui.onNodeWithText("取消").performClick()
        assertTrue(saves.isEmpty())
        shot("backup-light")
        ui.onNodeWithTag("backup-tab-diagnostics").performClick()
        ui.onNodeWithTag("backup-role-entry").assertDoesNotExist()
        ui.onNodeWithTag("backup-export-diagnostics").assertIsDisplayed()
        ui.onNodeWithTag("backup-page-help").performClick()
        ui.onNodeWithText("诊断日志说明").assertIsDisplayed()
        ui.onNodeWithTag("backup-help-content").assertExists()
        ui.onNodeWithTag("backup-help-close").performClick()
        shot("diagnostics-light")
        ui.onNodeWithTag("backup-tab-data").performClick()
        ui.onNodeWithTag("backup-role-entry").performClick()
        ui.onNodeWithTag("archive-export").assertIsEnabled()
        ui.onNodeWithText("取消").performClick()
        assertEquals(0,calls.get())
    }
    @Test fun pickerCancelInvalidFileAndBusyCancelPreserveSavedData(){
        show();val before=vm.friends.map{it.id}
        openUri=null;ui.onNodeWithTag("archive-open").performClick();returnFromPicker();ui.waitForIdle()
        assertEquals(before,vm.friends.map{it.id})
        openUri=Uri.parse("content://${archiveFixtureAuthority()}/invalid")
        ui.onNodeWithTag("archive-open").performClick()
        returnFromPicker()
        ui.waitUntil(15000){ui.onAllNodesWithText("处理未完成，请检查文件格式、文件权限与可用空间后重试。").fetchSemanticsNodes().isNotEmpty()}
        assertEquals(before,vm.friends.map{it.id})
        exportAll(false)
        openUri=saves.last().buildUpon().appendQueryParameter("slow","1").build()
        ui.onNodeWithTag("archive-open").performScrollTo().performClick()
        returnFromPicker()
        ui.waitUntil(10000){ui.onAllNodesWithTag("backup-cancel").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("backup-progress").assertIsDisplayed()
        ui.onNodeWithTag("backup-tab-diagnostics").assertIsNotEnabled()
        swipeTab(true);ui.onNodeWithTag("backup-tab-data").assertIsSelected();ui.onNodeWithTag("backup-export-diagnostics").assertDoesNotExist()
        ui.onNodeWithTag("backup-cancel").assertIsDisplayed().performClick()
        ui.waitUntil(15000){ui.onAllNodesWithText("已取消，已保存资料保留。").fetchSemanticsNodes().isNotEmpty()}
        assertEquals(before,vm.friends.map{it.id});assertEquals(0,calls.get())
        shot("cancel-preserved")
    }
    @Test fun allExportRequiresKeyDecisionAndExcludesSecretByDefault(){show(dark=true,big=true);exportAll(false);val p=BackupArchive.read(bytes().inputStream());assertTrue(p.all);assertFalse(p.includesKeys);assertFalse(p.root.toString().contains("synthetic-ui-key-only"));assertEquals(0,calls.get());shot("all-export-dark-large")}
    @Test fun explicitIncludeKeysProducesPortableSensitiveBackup(){show();exportAll(true);val p=BackupArchive.read(bytes().inputStream());assertTrue(p.includesKeys);assertTrue(BackupKeySeal.sealed(p.root));assertFalse(p.root.toString().contains("synthetic-ui-key-only"));assertTrue(BackupKeySeal.open(p.root,"synthetic-test-password".toCharArray()).toString().contains("synthetic-ui-key-only"));assertEquals(0,calls.get())}
    @Test fun fullAddPreservesCurrentModelsAndOnlyAddsRoles(){show();exportAll(true);val before=vm.friends.size;vault.save(config("https://keep.invalid/v1"),"synthetic-current-key");open("ADD");ui.onNodeWithTag("archive-import").performScrollTo().performClick();ui.waitUntil(30000){vm.friends.size==before*2};assertEquals("https://keep.invalid/v1",vault.list().single().baseUrl);assertEquals("synthetic-current-key",vault.secret("service"));assertEquals(0,calls.get())}
    @Test fun fullReplaceCancelIsReadOnlyAndConfirmRestoresSettings(){show();exportAll(false);runBlocking{vm.processing.store.setPreference("nickname","新昵称")};open("REPLACE");ui.onNodeWithTag("archive-import").performScrollTo().performClick();ui.onNodeWithText("取消").performClick();assertEquals("新昵称",runBlocking{vm.processing.store.preference("nickname","")});ui.onNodeWithTag("archive-import").performScrollTo().performClick();shot("replace-confirm");ui.onNodeWithTag("archive-confirm-overwrite").performClick();ui.waitUntil(30000){vm.nickname=="原昵称"};assertEquals("synthetic-ui-key-only",vault.secret("service"));assertEquals(0,calls.get())}
    @Test fun singleRoleCompleteExportAddsAssetsWithoutScopeFragments(){show();ui.onNodeWithTag("backup-role-entry").performClick();ui.onNodeWithTag("archive-export").assertIsNotEnabled();ui.onNodeWithTag("archive-export-seed-lin").performScrollTo().performClick();val n=saves.size;ui.onNodeWithTag("archive-export").performClick();waitSaved(n);val p=BackupArchive.read(bytes().inputStream());assertFalse(p.all);assertEquals(1,p.roles.size());assertEquals(1,p.files.size);val before=vm.friends.size;open("ADD");ui.onNodeWithTag("archive-import").performScrollTo().performClick();ui.waitUntil(30000){vm.friends.size==before+1};assertEquals(0,calls.get())}
    /** 保存必须串行：两次写入不能同时进行，否则旧值可能盖掉新值。 */
    @Test fun detailedSavesNeverOverlap(){
        show()
        ui.onNodeWithTag("backup-tab-diagnostics").performClick()
        var inFlight=0;var overlap=false
        vm.diagnosticPersist={inFlight++;if(inFlight>1)overlap=true;Thread.sleep(120);inFlight--}
        fun toggle()=ui.onNodeWithTag("backup-detailed").performScrollTo().performClick()
        toggle();toggle();toggle()
        instrument.waitForIdleSync();Thread.sleep(1200)
        vm.diagnosticPersist=null
        assertFalse("两次保存不能重叠",overlap)
    }

    /** 保存失败时界面要退回落盘值，不能显示一个没存上的开关。 */
    @Test fun failedDetailedSaveRollsBackToStoredValue(){
        show()
        ui.onNodeWithTag("backup-tab-diagnostics").performClick()
        assertFalse(vm.diagnosticDetailed)
        vm.diagnosticPersist={if(it=="1")throw java.io.IOException("synthetic save failure")}
        ui.onNodeWithTag("backup-detailed").performScrollTo().performClick()
        ui.waitUntil(15000){!vm.diagnosticDetailed}
        vm.diagnosticPersist=null
        assertEquals("没有存上就不能留成开","0",runBlocking{vm.processing.store.preference(EXPORT_DETAIL_PREF,"0")})
    }

    @Test fun simpleExportCannotUpgradeWhileItsWorkerStarts() {
        show()
        ui.onNodeWithTag("backup-tab-diagnostics").performClick()
        ui.runOnIdle {
            vm.setDiagnosticDetailed(true)
            Diagnostics.event("synthetic.sample",mapOf("text" to Diagnostics.raw("只许在确认后的详细文件出现")))
            vm.setDiagnosticDetailed(false)
        }
        val click=ui.onNodeWithTag("backup-export-diagnostics").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        val before=saves.size
        ui.runOnIdle { click(); vm.setDiagnosticDetailed(true) }
        waitSaved(before)
        val file=bytes().decodeToString()
        assertFalse(com.google.gson.JsonParser.parseString(file.lineSequence().first()).asJsonObject["detailed"].asBoolean)
        assertFalse(file.contains("只许在确认后的详细文件出现"))
        assertEquals(before+1,saves.size)
    }

    @Test fun oneDetailedSwitchControlsExportAndSecretsNeverAppear(){
        show();ui.onNodeWithTag("backup-tab-diagnostics").performClick()
        // Exercise the production request/response logging entry, with a local client and no network.
        val logged=LoggedTextChatClient(object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest)=TextChatResult.Success("回复："+request.messages.last().content)})
        fun record(marker:String)=runBlocking{logged.complete(config(),"synthetic-ui-key-only",TextChatRequest(listOf(WireMessage("user",marker+" synthetic-ui-key-only"))))}
        record("关闭期间合成诊断正文")
        var n=saves.size;ui.onNodeWithTag("backup-export-diagnostics").performClick();waitSaved(n)
        assertFalse(bytes().toString(Charsets.UTF_8).contains("关闭期间合成诊断正文"))
        ui.onNodeWithTag("backup-detailed").performClick();ui.onAllNodesWithTag("backup-detailed").assertCountEquals(1)
        record("开启期间合成诊断正文")
        n=saves.size
        fun detailedExports(): Int {
            runBlocking { Diagnostics.export(false) } // Drain the writer, then inspect only this synthetic namespace.
            val dir=File(instrument.targetContext.filesDir,"diagnostics/$name")
            return dir.listFiles().orEmpty().filter { it.extension=="jsonl" }.sumOf { file ->
                file.readLines().count { line ->
                    val row=com.google.gson.JsonParser.parseString(line).asJsonObject
                    row["event"]?.asString=="diagnostic.export" && row.getAsJsonObject("data")["detailed"]?.asBoolean==true
                }
            }
        }
        val beforeConfirm=detailedExports()
        val export=ui.onNodeWithTag("backup-export-diagnostics")
        export.performClick()
        ui.onNodeWithText("取消").performClick()
        assertEquals(n,saves.size)
        export.performClick()
        ui.onNodeWithText("导出详细诊断").assertIsDisplayed()
        ui.waitUntil(5000) { !ui.activity.hasWindowFocus() }
        instrument.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.onNodeWithText("导出详细诊断").assertDoesNotExist()
        assertEquals(n,saves.size)
        export.performClick()
        ui.onNodeWithText("继续导出").assertIsDisplayed()
        ui.waitUntil(5000) { !ui.activity.hasWindowFocus() }
        instrument.uiAutomation.executeShellCommand("input tap 2 80").close()
        ui.waitUntil(5000) { ui.onAllNodesWithText("继续导出").fetchSemanticsNodes().isEmpty() }
        assertEquals(n,saves.size)
        export.performClick()
        ui.runOnIdle { vm.setDiagnosticDetailed(false); vm.setDiagnosticDetailed(true) }
        ui.onNodeWithText("继续导出").performClick()
        assertEquals(n,saves.size)
        export.performClick()
        ui.onNodeWithText("密钥和登录凭据不会写入日志。").assertIsDisplayed()
        ui.waitForIdle()
        SystemClock.sleep(500) // Let the native dialog window finish fading in before capturing it.
        instrument.uiAutomation.takeScreenshot().let { bitmap ->
            File(instrument.targetContext.filesDir,"detailed-export.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
        }
        assertEquals(beforeConfirm,detailedExports())
        val action=ui.onNodeWithTag("confirm-detailed-export").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        ui.runOnIdle { action(); action() }
        waitSaved(n)
        assertEquals(n+1,saves.size)
        assertEquals(beforeConfirm+1,detailedExports())
        val detailed=bytes().toString(Charsets.UTF_8)
        assertTrue(detailed.contains("开启期间合成诊断正文"));assertTrue(detailed.contains("关闭期间合成诊断正文"));assertFalse(detailed.contains("synthetic-ui-key-only"))
        assertEquals(0,calls.get());shot("diagnostic-switch")
    }
    /** 快速开关不能让较早的一次保存盖掉最后一次选择：落盘的必须等于最后的状态。 */
    @Test fun detailedSwitchStoresTheLastChoiceEvenWhenToggledRapidly(){
        show()
        ui.onNodeWithTag("backup-tab-diagnostics").performClick()
        fun toggle()=ui.onNodeWithTag("backup-detailed").performScrollTo().performClick()
        fun stored():String{instrument.waitForIdleSync();Thread.sleep(600);return runBlocking{vm.processing.store.preference(EXPORT_DETAIL_PREF,"0")}}
        toggle();toggle();toggle()   // 关 → 开 → 关 → 开
        assertEquals("最后选择是开，落盘也必须是开","1",stored())
        assertTrue(vm.diagnosticDetailed)
        toggle()                    // 开 → 关
        assertEquals("最后选择是关，落盘也必须是关","0",stored())
        assertFalse("界面状态要等于最后落盘的选择",vm.diagnosticDetailed)
        toggle()                    // 关 → 开
        assertEquals("1",stored());assertTrue(vm.diagnosticDetailed)
    }
}
