package app.lianxu.independent

import android.app.Activity
import android.content.*
import android.database.Cursor
import android.net.Uri
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.result.*
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import java.io.File

class RoleArchiveUiTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private lateinit var name:String
    private lateinit var oldName:String
    private lateinit var vm:AppState
    @get:Rule(order=0)val fixture=object:ExternalResource(){override fun before(){oldName=AppServices.databaseName;name="archive-ui-${System.nanoTime()}.db";AppServices.databaseName=name};override fun after(){AppServices.databaseName=oldName;instrument.targetContext.deleteDatabase(name)}}
    @get:Rule(order=1)val ui=createAndroidComposeRule<ComponentActivity>()
    private fun show(dark:Boolean=false,big:Boolean=false,invalid:Boolean=false){
        ui.runOnUiThread{vm=ViewModelProvider(ui.activity)[AppState::class.java]}
        ui.waitUntil(30000){vm.ready}
        val owner=object:ActivityResultRegistryOwner{
            override val activityResultRegistry=object:ActivityResultRegistry(){
                override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?){
                    Handler(Looper.getMainLooper()).post{dispatchResult(requestCode,Activity.RESULT_OK,Intent().setData(Uri.parse("content://app.lianxu.independent.test.archivefixture/"+if(invalid)"invalid" else "linche")))}
                }
            }
        }
        ui.setContent{val density=LocalDensity.current;CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner,LocalDensity provides Density(density.density,if(big)1.6f else 1f)){LianxuTheme(dark){RoleArchiveScreen(vm)}}}
    }
    private fun open(){ui.onNodeWithTag("archive-open").performScrollTo().performClick();ui.waitUntil(30000){ui.onAllNodesWithTag("archive-preview").fetchSemanticsNodes().isNotEmpty()}}
    private fun screenshot(label:String){ui.waitForIdle();val f=File(instrument.targetContext.filesDir,"archive-ui-$label.png");instrument.uiAutomation.takeScreenshot().let{b->f.outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
    @Test fun addRequiresPreviewAndExplicitConfirmation(){show();val count=vm.friends.size;open();assertEquals(count,vm.friends.size);ui.onNodeWithTag("archive-preview").performScrollTo().performClick();ui.waitUntil(30000){ui.onAllNodesWithTag("archive-impact").fetchSemanticsNodes().isNotEmpty()};screenshot("new-preview");ui.onNodeWithTag("archive-import").performScrollTo().performClick();ui.waitUntil(30000){vm.friends.size==count+1};assertEquals(1,vm.friends.count{it.name=="林澈"})}
    @Test fun overwriteCancelKeepsOriginalAndConfirmChangesOnlyTarget(){show(dark=true,big=true);val before=vm.friends;open();ui.onNodeWithTag("archive-target-seed-lin").performScrollTo().performClick();ui.onNodeWithTag("archive-preview").performScrollTo().performClick();ui.waitUntil(30000){ui.onAllNodesWithTag("archive-import").fetchSemanticsNodes().isNotEmpty()};ui.onNodeWithTag("archive-import").performScrollTo().performClick();ui.onNodeWithText("取消").performClick();assertEquals(before,vm.friends);ui.onNodeWithTag("archive-import").performScrollTo().performClick();screenshot("overwrite-dark-large");ui.onNodeWithTag("archive-confirm-overwrite").performClick();ui.waitUntil(30000){vm.friends.any{it.id=="seed-lin"&&it.name=="林澈"}};assertEquals(before.first{it.id=="seed-an"},vm.friends.first{it.id=="seed-an"})}
    @Test fun invalidPackageShowsErrorWithoutChangingRoles(){show(invalid=true);val before=vm.friends;ui.onNodeWithTag("archive-open").performScrollTo().performClick();ui.waitUntil(30000){ui.onAllNodesWithTag("archive-error").fetchSemanticsNodes().isNotEmpty()};assertEquals(before,vm.friends);screenshot("invalid")}
}
