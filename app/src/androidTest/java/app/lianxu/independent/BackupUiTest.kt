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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class BackupUiTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private lateinit var vm:AppState;private lateinit var vault:ModelVault
    private lateinit var name:String;private lateinit var oldDb:String;private lateinit var oldVault:String
    private val saves=mutableListOf<Uri>();private var openUri:Uri?=null;private val calls=AtomicInteger()
    @get:Rule(order=0)val fixture=object:ExternalResource(){
        override fun before(){oldDb=AppServices.databaseName;oldVault=AppServices.vaultNamespace;name="backup-ui-${System.nanoTime()}";AppServices.databaseName="$name.db";AppServices.vaultNamespace=name;seedLegacyTestRoles(instrument.targetContext,"$name.db")}
        override fun after(){if(::vault.isInitialized)vault.list().forEach{vault.delete(it.id)};instrument.targetContext.deleteDatabase("$name.db");File(instrument.targetContext.noBackupFilesDir,name).deleteRecursively();AppServices.databaseName=oldDb;AppServices.vaultNamespace=oldVault}
    }
    @get:Rule(order=1)val ui=createAndroidComposeRule<ComponentActivity>()
    private fun config(endpoint:String="https://example.invalid/v1")=ModelConfig("service","合成服务","custom",endpoint,"synthetic",32000,1024)
    private fun show(dark:Boolean=false,big:Boolean=false){
        ui.runOnUiThread{vm=ViewModelProvider(ui.activity,object:ViewModelProvider.Factory{override fun <T:androidx.lifecycle.ViewModel> create(modelClass:Class<T>):T { @Suppress("UNCHECKED_CAST") return AppState(ui.activity.application,object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls.incrementAndGet();return TextChatResult.Failure(ChatFailure.NETWORK)}},name) as T}})[AppState::class.java]}
        ui.waitUntil(30000){vm.ready};vault=ModelVault(instrument.targetContext,name,vm.processing.store);vault.save(config(),"synthetic-ui-key-only");vault.setPurpose(ModelPurpose.CHAT,"service")
        runBlocking{vm.processing.store.setPreference("nickname","原昵称");RoleAssets(vm.processing).put("seed-lin",RoleAssets.Asset("avatar.png","avatar","image/png",byteArrayOf(1,2,3)))}
        val owner=object:ActivityResultRegistryOwner{override val activityResultRegistry=object:ActivityResultRegistry(){override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?){
            val uri=if(input is String)Uri.parse("content://app.lianxu.independent.test.archivefixture/saved/capture-${System.nanoTime()}").also{saves+=it}else openUri
            Handler(Looper.getMainLooper()).post{dispatchResult(requestCode,if(uri==null)Activity.RESULT_CANCELED else Activity.RESULT_OK,Intent().setData(uri))}
        }}}
        ui.setContent{val density=LocalDensity.current;CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner,LocalDensity provides Density(density.density,if(big)1.6f else 1f)){LianxuTheme(dark){androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize(),color=Paper){RoleArchiveScreen(vm)}}}}
    }
    private fun waitSaved(previous:Int){ui.waitUntil(30000){saves.size>previous&&ui.onAllNodesWithText("已保存。备份和详细日志可能含私密资料，请妥善保管。").fetchSemanticsNodes().isNotEmpty()}}
    private fun bytes()=instrument.context.contentResolver.openInputStream(saves.last())!!.use{it.readBytes()}
    private fun exportAll(keys:Boolean){val n=saves.size;ui.onNodeWithTag("backup-export-all").performScrollTo().performClick();ui.onNodeWithText("消耗额度",substring=true).assertExists();assertEquals(n,saves.size);ui.onNodeWithTag(if(keys)"backup-with-keys"else"backup-without-keys").performClick();waitSaved(n)}
    private fun open(mode:String){openUri=saves.last();ui.onNodeWithTag("archive-open").performScrollTo().performClick();ui.waitUntil(30000){ui.onAllNodesWithTag("archive-mode-add").fetchSemanticsNodes().isNotEmpty()};ui.onNodeWithTag(if(mode=="ADD")"archive-mode-add"else"archive-mode-replace").performClick();ui.onNodeWithTag("archive-preview").performScrollTo().performClick();ui.waitUntil(30000){ui.onAllNodesWithTag("archive-impact").fetchSemanticsNodes().isNotEmpty()}}
    private fun shot(label:String){ui.waitForIdle();instrument.uiAutomation.takeScreenshot().let{b->File(instrument.targetContext.filesDir,"backup-ui-$label.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
    @Test fun allExportRequiresKeyDecisionAndExcludesSecretByDefault(){show(dark=true,big=true);exportAll(false);val p=BackupArchive.read(bytes().inputStream());assertTrue(p.all);assertFalse(p.includesKeys);assertFalse(p.root.toString().contains("synthetic-ui-key-only"));assertEquals(0,calls.get());shot("all-export-dark-large")}
    @Test fun explicitIncludeKeysProducesPortableSensitiveBackup(){show();exportAll(true);val p=BackupArchive.read(bytes().inputStream());assertTrue(p.includesKeys);assertTrue(p.root.toString().contains("synthetic-ui-key-only"));assertEquals(0,calls.get())}
    @Test fun fullAddPreservesCurrentModelsAndOnlyAddsRoles(){show();exportAll(true);val before=vm.friends.size;vault.save(config("https://keep.invalid/v1"),"synthetic-current-key");open("ADD");ui.onNodeWithTag("archive-import").performScrollTo().performClick();ui.waitUntil(30000){vm.friends.size==before*2};assertEquals("https://keep.invalid/v1",vault.list().single().baseUrl);assertEquals("synthetic-current-key",vault.secret("service"));assertEquals(0,calls.get())}
    @Test fun fullReplaceCancelIsReadOnlyAndConfirmRestoresSettings(){show();exportAll(false);runBlocking{vm.processing.store.setPreference("nickname","新昵称")};open("REPLACE");ui.onNodeWithTag("archive-import").performScrollTo().performClick();ui.onNodeWithText("取消").performClick();assertEquals("新昵称",runBlocking{vm.processing.store.preference("nickname","")});ui.onNodeWithTag("archive-import").performScrollTo().performClick();shot("replace-confirm");ui.onNodeWithTag("archive-confirm-overwrite").performClick();ui.waitUntil(30000){vm.nickname=="原昵称"};assertEquals("synthetic-ui-key-only",vault.secret("service"));assertEquals(0,calls.get())}
    @Test fun singleRoleCompleteExportAddsAssetsWithoutScopeFragments(){show();ui.onNodeWithTag("archive-export").assertIsNotEnabled();ui.onNodeWithTag("archive-export-seed-lin").performScrollTo().performClick();val n=saves.size;ui.onNodeWithTag("archive-export").performScrollTo().performClick();waitSaved(n);val p=BackupArchive.read(bytes().inputStream());assertFalse(p.all);assertEquals(1,p.roles.size());assertEquals(1,p.files.size);val before=vm.friends.size;open("ADD");ui.onNodeWithTag("archive-import").performScrollTo().performClick();ui.waitUntil(30000){vm.friends.size==before+1};assertEquals(0,calls.get())}
    @Test fun oneDetailedSwitchControlsExportAndSecretsNeverAppear(){show();Diagnostics.event("synthetic.ui",mapOf("message" to "合成诊断正文","api_key" to "synthetic-ui-key-only"));var n=saves.size;ui.onNodeWithTag("backup-export-diagnostics").performScrollTo().performClick();waitSaved(n);val simple=bytes().toString(Charsets.UTF_8);assertFalse(simple.contains("合成诊断正文"));ui.onNodeWithTag("backup-detailed").performScrollTo().performClick();ui.onAllNodesWithTag("backup-detailed").assertCountEquals(1);n=saves.size;ui.onNodeWithTag("backup-export-diagnostics").performScrollTo().performClick();waitSaved(n);val detailed=bytes().toString(Charsets.UTF_8);assertTrue(detailed.contains("合成诊断正文"));assertFalse(detailed.contains("synthetic-ui-key-only"));shot("diagnostic-switch")}
}
