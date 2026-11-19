package app.lianxu.independent

import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.*
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Actual Profile/AppState/chat/backup paths; test-only URI picker and synthetic images. */
class UserAvatarUiTest {
    @get:Rule(order=0)val fixture=MockChatFixture()
    @get:Rule(order=1)val ui=createAndroidComposeRule<ComponentActivity>()
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private lateinit var vm:AppState
    private var nextUri:Uri?=null
    private var selections=0
    private fun show(narrow:Boolean=false){
        ui.runOnUiThread{vm=ViewModelProvider(ui.activity,ViewModelProvider.AndroidViewModelFactory.getInstance(ui.activity.application))[AppState::class.java]}
        ui.waitUntil(30000){vm.ready}
        ui.runOnIdle{vm.tab=3}
        val owner=object:ActivityResultRegistryOwner{override val activityResultRegistry=object:ActivityResultRegistry(){
            override fun <I,O> onLaunch(code:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?){
                assertArrayEquals(arrayOf("image/*"),input as Array<*>)
                selections++;val selected=nextUri
                Handler(Looper.getMainLooper()).post{dispatchResult(code,if(selected==null)Activity.RESULT_CANCELED else Activity.RESULT_OK,Intent().setData(selected))}
            }
        }}
        ui.setContent{CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner){LianxuTheme(false){
            if(narrow) androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(320.dp)) { Lianxu(vm) } else Lianxu(vm)
        }}}
    }
    private fun jpeg(color:Int,width:Int=100,height:Int=80):ByteArray{
        val b=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);b.eraseColor(color)
        return try{ByteArrayOutputStream().also{b.compress(Bitmap.CompressFormat.JPEG,95,it)}.toByteArray()}finally{b.recycle()}
    }
    private fun source(bytes:ByteArray):Uri=Uri.parse("content://${archiveFixtureAuthority()}/saved/capture-${System.nanoTime()}").also{uri->instrument.context.contentResolver.openOutputStream(uri)!!.use{it.write(bytes)}}
    private fun select(bytes:ByteArray){val expected=UserAvatarData.decode(UserAvatarData.prepare(bytes));nextUri=source(bytes);ui.onNodeWithTag("profile-avatar").performClick();ui.waitUntil(15000){!vm.busy&&vm.userAvatar?.contentEquals(expected)==true}}
    private fun shot(name:String){ui.waitForIdle();val b=instrument.uiAutomation.takeScreenshot();val dir=File(instrument.targetContext.getExternalFilesDir(null),"evidence-self-avatar").apply{mkdirs()};File(dir,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
    private fun pack(export:BackupRepository.Export):BackupArchive.Package{
        val out=ByteArrayOutputStream();BackupArchive.write(export.root,export.files,out);return BackupArchive.read(out.toByteArray().inputStream())
    }
    private fun restore(p:BackupArchive.Package,mode:String,target:String?=null)=runBlocking{vm.importBackup(vm.backup.preview(p,mode,target),true,ChatAttemptLease())}
    @Test fun narrowProfileCanEditScrollAndSave() {
        show(narrow=true)
        ui.onNodeWithTag("persona-introduction").performScrollTo().performTextReplacement("窄屏合成介绍")
        ui.onNodeWithText("保存资料").performScrollTo().assertIsDisplayed().performClick()
        ui.waitUntil(15000){vm.persona.introduction=="窄屏合成介绍"}
        shot("profile-320dp")
    }
    @Test fun selectCancelInvalidSourceAndChatUsePersistedOwnImage(){
        show();ui.onNodeWithTag("profile-avatar-default",true).assertExists()
        select(jpeg(Color.rgb(60,130,110)))
        val saved=runBlocking{vm.processing.store.preference(UserAvatarData.KEY,"")}
        assertArrayEquals(UserAvatarData.decode(saved),vm.userAvatar)
        ui.onNodeWithTag("profile-avatar-image",true).assertIsDisplayed();shot("profile-selected")
        nextUri=null;ui.onNodeWithTag("profile-avatar").performClick();ui.waitForIdle()
        assertEquals(saved,runBlocking{vm.processing.store.preference(UserAvatarData.KEY,"")})
        nextUri=source(byteArrayOf(1,2,3));ui.onNodeWithTag("profile-avatar").performClick()
        ui.waitUntil(15000){vm.error.isNotEmpty()&&!vm.busy}
        assertEquals(saved,runBlocking{vm.processing.store.preference(UserAvatarData.KEY,"")});assertEquals(3,selections)
        ui.runOnIdle{vm.open("chat","seed-lin")}
        ui.onNodeWithTag("message-input").performTextInput("这是一条合成头像测试消息")
        ui.onNodeWithTag("send-message").performClick()
        ui.runOnIdle{ui.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(ui.activity.window.decorView.windowToken,0)}
        try{ui.waitUntil(30000){vm.messages.any{it.mine}&&vm.turns.any{it.state==TurnState.COMPLETED}}}catch(t:Throwable){println("synthetic_namespace=${fixture.namespace} states=${vm.turns.map{it.state to it.failure}} busy=${vm.busy} error=${vm.error}");println(ui.onRoot().printToString());shot("failed-chat");throw t}
        val mine=vm.messages.first{it.mine}.id
        ui.onNodeWithTag("self-avatar-$mine-image",true).assertIsDisplayed()
        ui.onNodeWithTag("chat-title").assertTextEquals("林间")
        assertTrue(ui.onAllNodesWithTag("role-avatar-seed-lin").fetchSemanticsNodes().isNotEmpty())
        ui.onAllNodes(SemanticsMatcher("no repeated sender"){it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag){""}.startsWith("sender-name-")},true).assertCountEquals(0)
        ui.runOnIdle{ui.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(ui.activity.window.decorView.windowToken,0)}
        shot("chat-own-avatar")
    }
    @Test fun fullRestoreIncludesPhotoRoleRestoreAndAddPreserveItAndMissingCorruptFallBack(){
        show();select(jpeg(Color.GREEN));val first=runBlocking{vm.processing.store.preference(UserAvatarData.KEY,"")}
        val all=pack(runBlocking{vm.exportBackup(null,false)})
        val role=pack(runBlocking{vm.exportBackup("seed-lin",false)})
        assertEquals(first,all.root["preferences"].asJsonObject[UserAvatarData.KEY].asString)
        assertFalse(role.root.toString().contains(UserAvatarData.KEY));assertFalse(role.root.toString().contains(first))
        select(jpeg(Color.BLUE));val second=runBlocking{vm.processing.store.preference(UserAvatarData.KEY,"")};assertNotEquals(first,second)
        restore(all,"ADD");assertEquals(second,runBlocking{vm.processing.store.preference(UserAvatarData.KEY,"")})
        restore(role,"REPLACE","seed-lin");assertEquals(second,runBlocking{vm.processing.store.preference(UserAvatarData.KEY,"")})
        restore(all,"REPLACE");assertArrayEquals(UserAvatarData.decode(first),vm.userAvatar)
        val missing=runBlocking{vm.exportBackup(null,false)};missing.root["preferences"].asJsonObject.remove(UserAvatarData.KEY)
        restore(pack(missing),"REPLACE");assertNull(vm.userAvatar);ui.onNodeWithTag("profile-avatar-default",true).assertExists()
        val corrupt=runBlocking{vm.exportBackup(null,false)};corrupt.root["preferences"].asJsonObject.addProperty(UserAvatarData.KEY,"v1:AQID")
        restore(pack(corrupt),"REPLACE");assertNull(vm.userAvatar);assertEquals(2,vm.friends.size)
        assertFalse(runBlocking{vm.exportBackup(null,false)}.root["preferences"].asJsonObject.has(UserAvatarData.KEY))
        ui.onNodeWithTag("profile-avatar-default",true).assertExists();shot("missing-photo-default")
        val taggedFile=File(instrument.targetContext.cacheDir,"restore-exif-${System.nanoTime()}.jpg")
        try{
            taggedFile.writeBytes(jpeg(Color.RED));ExifInterface(taggedFile.absolutePath).apply{setAttribute(ExifInterface.TAG_GPS_LATITUDE,"1/1,2/1,3/1");saveAttributes()}
            val tagged=runBlocking{vm.exportBackup(null,false)};tagged.root["preferences"].asJsonObject.addProperty(UserAvatarData.KEY,UserAvatarData.encode(taggedFile.readBytes()))
            restore(pack(tagged),"REPLACE")
            val reexported=runBlocking{vm.exportBackup(null,false)}.root["preferences"].asJsonObject[UserAvatarData.KEY].asString
            val clean=requireNotNull(UserAvatarData.checked(reexported));assertNull(ExifInterface(clean.inputStream()).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            ui.onNodeWithTag("profile-avatar-image",true).assertIsDisplayed()
            val main=jpeg(Color.RED);val oldThumb=jpeg(Color.BLUE,16,16)
            val thumbPayload="JFXX\u0000".toByteArray(Charsets.US_ASCII)+byteArrayOf(16)+oldThumb;val length=thumbPayload.size+2
            val withOldThumb=main.copyOfRange(0,2)+byteArrayOf(255.toByte(),224.toByte(),(length shr 8).toByte(),length.toByte())+thumbPayload+main.copyOfRange(2,main.size)
            val oldPicture=runBlocking{vm.exportBackup(null,false)};oldPicture.root["preferences"].asJsonObject.addProperty(UserAvatarData.KEY,UserAvatarData.encode(withOldThumb))
            restore(pack(oldPicture),"REPLACE")
            val finalPhoto=runBlocking{vm.exportBackup(null,false)}.root["preferences"].asJsonObject[UserAvatarData.KEY].asString
            assertArrayEquals("主图不变，隐藏的旧蓝图不能再次导出",main,UserAvatarData.decode(finalPhoto))
        }finally{taggedFile.delete()}
    }
    @Test fun thumbnailBoundsOrientationMetadataAndCorruptInputAreHandled(){
        val original=jpeg(Color.RED,1200,600)
        val file=File(instrument.targetContext.cacheDir,"synthetic-exif-${System.nanoTime()}.jpg")
        try{
            file.writeBytes(original);ExifInterface(file.absolutePath).apply{setAttribute(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_ROTATE_90.toString());setAttribute(ExifInterface.TAG_GPS_LATITUDE,"1/1,2/1,3/1");saveAttributes()}
            val encoded=UserAvatarData.prepare(file.readBytes());val bytes=requireNotNull(UserAvatarData.checked(encoded))
            val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
            assertEquals(256,bitmap.width);assertEquals(512,bitmap.height);bitmap.recycle()
            val exif=ExifInterface(bytes.inputStream());assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            assertNull(UserAvatarData.checked("v1:AQID"));assertNull(UserAvatarData.checked(""))
            try{UserAvatarData.prepare(byteArrayOf(1,2,3));fail("坏图必须拒绝")}catch(_:IllegalArgumentException){}
        }finally{file.delete()}
    }
    @Test fun texturedPhotoCanBeRestoredRepeatedlyWithoutLossyReencoding(){
        val b=Bitmap.createBitmap(128,96,Bitmap.Config.ARGB_8888)
        for(y in 0 until b.height)for(x in 0 until b.width)b.setPixel(x,y,Color.rgb((x*17+y*5)%256,(x*7+y*19)%256,(x*11+y*13)%256))
        val input=try{ByteArrayOutputStream().also{b.compress(Bitmap.CompressFormat.JPEG,90,it)}.toByteArray()}finally{b.recycle()}
        val canonical=UserAvatarData.prepare(input);var restored=canonical
        repeat(5){restored=requireNotNull(UserAvatarData.forRestore(restored));assertEquals("压缩图像数据不得再次有损编码",canonical,restored)}
    }
    @Test fun largeImportedRoleAvatarIsSampledAndBrokenPhotoUsesDefault(){
        val b=Bitmap.createBitmap(1024,8192,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.rgb(60,120,90))}
        val texture=IntArray(1024*1024){i->val x=i%1024;val y=i/1024;Color.rgb((x/4%2)*255,(y/4%2)*255,(x+y)%256)}
        b.setPixels(texture,0,1024,0,3584,1024,1024)
        val bytes=try{ByteArrayOutputStream().also{b.compress(Bitmap.CompressFormat.JPEG,90,it)}.toByteArray()}finally{b.recycle()}
        assertTrue(bytes.size<BackupArchive.MAX_FILE)
        val decoded=requireNotNull(decodeAvatar(bytes,128));assertEquals(256,decoded.width);assertEquals(256,decoded.height);assertTrue(decoded.allocationByteCount<1024*1024)
        val full=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=4})
        val expected=Bitmap.createBitmap(full,0,(full.height-full.width)/2,full.width,full.width)
        assertTrue("区域解码应与按相同采样率取得的中央清晰区域一致",decoded.sameAs(expected))
        if(expected!==full)expected.recycle();full.recycle();decoded.recycle()
        assertNull(decodeAvatar(byteArrayOf(1,2,3),128))
        ui.setContent{LianxuTheme(false){CompositionLocalProvider(LocalRoleAvatars provides mapOf("bad-avatar" to byteArrayOf(1,2,3))){Avatar(Friend("bad-avatar","损坏图片角色","合成"),64)}}}
        ui.onNodeWithTag("role-avatar-bad-avatar").assertIsDisplayed()
        ui.onNodeWithContentDescription("损坏图片角色").assertDoesNotExist()
    }
}
