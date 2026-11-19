package app.lianxu.independent

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.*
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class ChatImagesUiTest {
    private val calls=AtomicInteger()
    private val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls.incrementAndGet();return TextChatResult.Success("这张合成图片已经收到了。")}}
    @get:Rule(order=0)val fixture=MockChatFixture({client},wrapFinalReply=false)
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var vm:AppState;private var uri:Uri?=null;private var launches=0
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private fun show(){
        ui.runOnUiThread{vm=ViewModelProvider(ui.activity,ViewModelProvider.AndroidViewModelFactory.getInstance(ui.activity.application))[AppState::class.java]}
        ui.waitUntil(30000){vm.ready};ui.runOnIdle{vm.open("chat","seed-lin")}
        val owner=object:ActivityResultRegistryOwner{override val activityResultRegistry=object:ActivityResultRegistry(){
            override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?){
                assertTrue(contract is ActivityResultContracts.PickVisualMedia);launches++;val selected=uri
                Handler(Looper.getMainLooper()).post{dispatchResult(requestCode,if(selected==null)Activity.RESULT_CANCELED else Activity.RESULT_OK,Intent().setData(selected))}
            }
        }}
        ui.runOnUiThread{ui.activity.setContent{CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner){LianxuTheme(false){Lianxu(vm)}}}}
    }
    private fun source():Uri{
        val b=Bitmap.createBitmap(320,260,Bitmap.Config.ARGB_8888);b.eraseColor(Color.rgb(199,218,173));val c=android.graphics.Canvas(b);val p=android.graphics.Paint().apply{color=Color.YELLOW;isAntiAlias=true};c.drawCircle(150f,130f,65f,p);p.color=Color.rgb(110,80,40);c.drawCircle(150f,130f,26f,p)
        val bytes=ByteArrayOutputStream().also{b.compress(Bitmap.CompressFormat.PNG,100,it);b.recycle()}.toByteArray()
        return Uri.parse("content://${archiveFixtureAuthority()}/saved/capture-${System.nanoTime()}").also{instrument.context.contentResolver.openOutputStream(it)!!.use{out->out.write(bytes)}}
    }
    private fun pick(){uri=source();ui.onNodeWithTag("chat-attach").performClick();ui.waitUntil(10000){vm.chatDrafts["seed-lin"]?.image?.value!=null};ui.waitForIdle()}
    private fun shot(name:String){ui.waitForIdle();val bitmap=instrument.uiAutomation.takeScreenshot();val dir=File(instrument.targetContext.getExternalFilesDir(null),"chat-image-evidence").apply{mkdirs()};File(dir,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
    @Test fun approvedPendingCardRemoveImageOnlyCaptionSendAndReview(){
        show();pick();assertEquals(0,calls.get());ui.onNodeWithTag("pending-picture-collapsed").assertIsDisplayed();shot("01-collapsed")
        ui.onNodeWithTag("message-input").performClick();ui.onNodeWithTag("pending-picture-expanded").assertIsDisplayed()
        ui.runOnUiThread{ui.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(ui.activity.window.decorView,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)}
        ui.waitUntil(5000){androidx.core.view.ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true};android.os.SystemClock.sleep(700);shot("02-expanded-keyboard")
        ui.onNodeWithTag("pending-picture").performClick();ui.onNodeWithTag("image-viewer").assertIsDisplayed();shot("03-preview")
        ui.onNodeWithTag("image-viewer-close").performClick();ui.onNodeWithTag("message-input").performClick();ui.onNodeWithTag("pending-picture-expanded").assertIsDisplayed();ui.onNodeWithTag("remove-picture").performClick();ui.onNodeWithTag("pending-picture").assertDoesNotExist();assertEquals(0,calls.get())
        pick();ui.onNodeWithTag("send-message").performClick();ui.waitUntil(15000){vm.messages.any{it.friendId=="seed-lin"&&it.mine&&it.text.isEmpty()&&it.image!=null}&&!vm.chatSending}
        assertEquals(TurnState.COMPLETED,vm.turns.last{it.friendId=="seed-lin"}.state)
        val sent=vm.messages.single{it.friendId=="seed-lin"&&it.mine&&it.text.isEmpty()&&it.image!=null}
        ui.onNodeWithTag("pending-picture").assertDoesNotExist();ui.onNodeWithTag("message-image-${sent.id}",true).performClick();ui.onNodeWithTag("image-viewer").assertIsDisplayed();ui.onNodeWithTag("image-viewer-close").performClick()
        ui.onNodeWithTag("message-image-${sent.id}",true).performTouchInput{longClick()};ui.onNodeWithText("这条消息的使用方式").assertIsDisplayed();ui.onNodeWithText("取消").performClick()
        pick();ui.onNodeWithTag("message-input").performTextInput("今天看到的花")
        ui.onNodeWithTag("send-message").performClick();ui.waitUntil(15000){vm.messages.any{it.text=="今天看到的花"&&it.image!=null}&&!vm.chatSending}
        assertEquals(TurnState.COMPLETED,vm.turns.last{it.friendId=="seed-lin"}.state)
        ui.waitUntil(15000){ui.onAllNodesWithTag("send-message").fetchSemanticsNodes().size==1}
        ui.activityRule.scenario.onActivity{it.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(it.window.decorView.windowToken,0)}
        shot("04-message-history");assertTrue(calls.get()>=2)
        ui.runOnIdle{vm.back();vm.open("chat","seed-lin")};ui.onNodeWithTag("message-image-${sent.id}",true).assertExists()
    }
    @Test fun cancelOrBadImageKeepsDraftAndDoesNotCallModel(){
        show();ui.onNodeWithTag("message-input").performTextInput("保留附文");uri=null;ui.onNodeWithTag("chat-attach").performClick();ui.onNodeWithTag("message-input").assertTextEquals("保留附文");assertEquals(0,calls.get())
        pick();val chosen=vm.chatDrafts.getValue("seed-lin").image.value
        uri=null;ui.onNodeWithTag("chat-attach").performClick();ui.waitForIdle();assertSame(chosen,vm.chatDrafts.getValue("seed-lin").image.value)
        uri=Uri.parse("content://${archiveFixtureAuthority()}/saved/capture-${System.nanoTime()}").also{instrument.context.contentResolver.openOutputStream(it)!!.use{out->out.write(byteArrayOf(1,2,3))}}
        ui.onNodeWithTag("chat-attach").performClick();ui.waitUntil(10000){vm.chatDrafts.getValue("seed-lin").importing.value.not()};ui.waitForIdle()
        assertSame(chosen,vm.chatDrafts.getValue("seed-lin").image.value);ui.onNodeWithTag("message-input").assertTextEquals("保留附文")
        assertEquals(0,calls.get());assertTrue(launches>=3)
    }
    @Test fun auxiliarySwitchIsExplicitAndPersistsWithoutNetwork(){
        show();ui.runOnIdle{vm.back();vm.open("toolbox-image")}
        ui.onNodeWithTag("auxiliary-vision-switch").performScrollTo().assertIsOff().performClick()
        ui.waitUntil(10000){runBlocking{vm.processing.read{ChatImages.enabled(it)}}}
        ui.onNodeWithTag("auxiliary-vision-switch").assertIsOn();assertEquals(0,calls.get());shot("05-auxiliary-setting")
        ui.onNodeWithTag("auxiliary-vision-switch").performClick()
        ui.waitUntil(10000){runBlocking{vm.processing.read{!ChatImages.enabled(it)}}};assertEquals(0,calls.get())
    }
}
