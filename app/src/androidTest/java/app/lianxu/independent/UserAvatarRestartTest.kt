package app.lianxu.independent

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import java.io.ByteArrayOutputStream
import java.io.File

/** Run phase 1, force-stop the synthetic debug app, then run phase 2 with the same unique namespace. */
class UserAvatarRestartTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val args get()=InstrumentationRegistry.getArguments()
    private val name get()=requireNotNull(args.getString("avatarNamespace")).also{require(it.matches(Regex("avatar-restart-[0-9]+")))}
    private val phase get()=args.getString("avatarPhase")
    private lateinit var previousDb:String;private lateinit var previousVault:String
    @get:Rule(order=0)val fixture=object:ExternalResource(){
        override fun before(){
            Assume.assumeTrue(phase in listOf("1","2"))
            previousDb=AppServices.databaseName;previousVault=AppServices.vaultNamespace
            AppServices.databaseName="$name.db";AppServices.vaultNamespace=name
            if(phase=="1"){
                check(!instrument.targetContext.getDatabasePath("$name.db").exists())
                seedLegacyTestRoles(instrument.targetContext,"$name.db")
                runBlocking{val s=Store(instrument.targetContext,"$name.db");try{LocalUpdateRepository(s).acknowledge(InstalledBuild.read(instrument.targetContext).versionCode)}finally{s.close()}}
            }
        }
        override fun after(){
            if(::previousDb.isInitialized){AppServices.databaseName=previousDb;AppServices.vaultNamespace=previousVault}
            if(phase=="2"){instrument.targetContext.deleteDatabase("$name.db");File(instrument.targetContext.noBackupFilesDir,name).deleteRecursively()}
        }
    }
    @get:Rule(order=1)val ui=createAndroidComposeRule<ComponentActivity>()
    @Test fun profilePhotoSurvivesActualProcessRestart(){
        lateinit var vm:AppState
        ui.runOnUiThread{vm=ViewModelProvider(ui.activity,ViewModelProvider.AndroidViewModelFactory.getInstance(ui.activity.application))[AppState::class.java]}
        ui.waitUntil(30000){vm.ready};ui.runOnIdle{vm.tab=3}
        val bitmap=Bitmap.createBitmap(80,80,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.rgb(70,130,110))}
        val original=try{ByteArrayOutputStream().also{bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)}.toByteArray()}finally{bitmap.recycle()}
        val expected=UserAvatarData.prepare(original)
        if(phase=="1"){
            val uri=Uri.parse("content://app.lianxu.independent.test.archivefixture/saved/capture-${System.nanoTime()}")
            instrument.context.contentResolver.openOutputStream(uri)!!.use{it.write(original)}
            ui.runOnIdle{vm.setUserAvatar(uri)}
            ui.waitUntil(15000){!vm.busy&&vm.userAvatar?.contentEquals(UserAvatarData.decode(expected))==true}
        }
        assertEquals(expected,runBlocking{vm.processing.store.preference(UserAvatarData.KEY,"")})
        assertArrayEquals(UserAvatarData.decode(expected),vm.userAvatar)
        ui.setContent{LianxuTheme(false){Lianxu(vm)}}
        ui.onNodeWithTag("profile-avatar-image",true).assertIsDisplayed()
        val dir=File(instrument.targetContext.getExternalFilesDir(null),"evidence-self-avatar").apply{mkdirs()}
        val screen=instrument.uiAutomation.takeScreenshot();File(dir,"restart-phase-$phase.png").outputStream().use{screen.compress(Bitmap.CompressFormat.PNG,100,it)};screen.recycle()
    }
}
