package app.lianxu.independent

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.*
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@androidx.test.filters.SdkSuppress(minSdkVersion=29)
class ChatVideosUiTest {
    private val calls=AtomicInteger()
    private val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls.incrementAndGet();assertTrue(request.messages.flatMap { it.videos }.isEmpty());assertEquals(23,request.messages.flatMap { it.images }.count { it.videoFrame });return TextChatResult.Success("合成视频已接收")}}
    @get:Rule(order=0)val fixture=MockChatFixture({client},wrapFinalReply=false)
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var vm:AppState;private var uri:Uri?=null
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private fun show(){
        ui.runOnUiThread{vm=ViewModelProvider(ui.activity)[AppState::class.java]};ui.waitUntil(30000){vm.ready};ui.runOnIdle{vm.open("chat","seed-lin")}
        val owner=object:ActivityResultRegistryOwner{override val activityResultRegistry=object:ActivityResultRegistry(){
            override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?){
                assertTrue(contract is ActivityResultContracts.PickVisualMedia);assertEquals(ActivityResultContracts.PickVisualMedia.ImageAndVideo,(input as PickVisualMediaRequest).mediaType)
                val selected=uri;Handler(Looper.getMainLooper()).post{dispatchResult(requestCode,if(selected==null)Activity.RESULT_CANCELED else Activity.RESULT_OK,Intent().setData(selected))}
            }
        }}
        ui.runOnUiThread{ui.activity.setContent{CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner){LianxuTheme(false){Lianxu(vm)}}}}
    }
    private fun source(broken:Boolean=false):Uri=Uri.parse("content://${archiveFixtureAuthority()}/saved/capture-${System.nanoTime()}?media=video").also{u->
        instrument.context.contentResolver.openOutputStream(u)!!.use{out->if(broken)out.write(byteArrayOf(1,2,3))else instrument.context.assets.open("synthetic-video-with-audio.mp4").use{it.copyTo(out)}}
    }
    private fun pick(){uri=source();ui.onNodeWithTag("chat-attach").performClick();ui.waitUntil(15000){vm.chatDrafts["seed-lin"]?.image?.value!=null};ui.waitForIdle()}
    private fun shot(label:String){ui.waitForIdle();val dir=File(instrument.targetContext.getExternalFilesDir(null),"video-evidence").apply{mkdirs()};val b=instrument.uiAutomation.takeScreenshot();File(dir,"$label.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
    private fun player():android.widget.VideoView {
        var result:android.widget.VideoView?=null
        ui.runOnUiThread{
            fun find(v:android.view.View):android.widget.VideoView?{if(v is android.widget.VideoView)return v;if(v is android.view.ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it};return null}
            result=android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull{find(it)}
        };return requireNotNull(result)
    }
    private fun playing(v:android.widget.VideoView):Boolean{var result=false;ui.runOnUiThread{result=v.isPlaying&&v.currentPosition>500};return result}
    private fun tapNativePlay(){
        val rect=android.graphics.Rect()
        ui.runOnUiThread{
            fun find(v:android.view.View):android.view.View?{
                val label=v.contentDescription?.toString().orEmpty()
                if(v is android.widget.ImageButton&&(label.equals("Play",true)||label.contains("播放")))return v
                if(v is android.view.ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it};return null
            }
            val button=requireNotNull(android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull{find(it)}){"native play control missing"}
            assertTrue(button.isShown&&button.width>0&&button.height>0)
            val at=IntArray(2);button.getLocationOnScreen(at);rect.set(at[0],at[1],at[0]+button.width,at[1]+button.height)
        }
        val down=android.os.SystemClock.uptimeMillis()
        for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)){
            val event=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,rect.exactCenterX(),rect.exactCenterY(),0).apply{source=android.view.InputDevice.SOURCE_TOUCHSCREEN}
            try{assertTrue(instrument.uiAutomation.injectInputEvent(event,true))}finally{event.recycle()}
        }
    }
    @Test fun pickerPreviewOriginalPlaybackRemoveAndVideoOnlySend(){
        show();pick();assertEquals(0,calls.get());assertEquals("video/mp4",vm.chatDrafts.getValue("seed-lin").image.value!!.mime)
        ui.onNodeWithTag("pending-picture-collapsed").assertIsDisplayed();ui.onNodeWithTag("message-input").performClick();ui.onNodeWithTag("pending-picture-expanded").assertIsDisplayed();shot("01-pending-video")
        ui.onNodeWithTag("pending-picture").performClick();ui.onNodeWithTag("video-viewer").assertIsDisplayed();ui.onNodeWithTag("video-original-player").assertIsDisplayed();val originalPlayer=player()
        ui.waitUntil(5000){androidx.core.view.ViewCompat.getRootWindowInsets(originalPlayer)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==false}
        Thread.sleep(400);tapNativePlay();ui.waitUntil(7000){playing(originalPlayer)};shot("02-original-player-playing")
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED);ui.runOnUiThread{assertFalse(originalPlayer.isPlaying)};ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        ui.onNodeWithTag("video-viewer-close").performClick();ui.runOnUiThread{assertFalse(originalPlayer.isPlaying)};ui.onNodeWithTag("message-input").performClick();ui.onNodeWithTag("remove-picture").performClick();ui.onNodeWithTag("pending-picture").assertDoesNotExist();assertEquals(0,calls.get())
        pick();ui.onNodeWithTag("send-message").performClick();ui.waitUntil(20000){vm.messages.any{it.friendId=="seed-lin"&&it.mine&&it.text.isEmpty()&&VideoPolicy.isVideo(it.image?.mime.orEmpty())}&&!vm.chatSending}
        assertEquals(TurnState.COMPLETED,vm.turns.last{it.friendId=="seed-lin"}.state);assertEquals(1,calls.get())
        val sent=vm.messages.single{it.mine&&it.friendId=="seed-lin"&&it.image?.mime=="video/mp4"};shot("03-sent-video")
        ui.onNodeWithTag("message-image-${sent.id}",true).performClick();ui.onNodeWithTag("video-viewer").assertIsDisplayed();ui.onNodeWithTag("video-viewer-close").performClick()
        ui.onNodeWithTag("message-image-${sent.id}",true).performTouchInput{longClick()};ui.onNodeWithText("这条消息的使用方式").assertIsDisplayed();ui.onNodeWithText("取消").performClick()
    }
    @Test fun cancelAndInvalidVideoPreserveCaptionAndOldPendingOriginal(){
        show();ui.onNodeWithTag("message-input").performTextInput("保留合成视频附文");uri=null;ui.onNodeWithTag("chat-attach").performClick();ui.onNodeWithTag("message-input").assertTextEquals("保留合成视频附文")
        pick();val previous=vm.chatDrafts.getValue("seed-lin").image.value;uri=source(true);ui.onNodeWithTag("chat-attach").performClick();ui.waitUntil(10000){!vm.chatDrafts.getValue("seed-lin").importing.value}
        assertSame(previous,vm.chatDrafts.getValue("seed-lin").image.value);ui.onNodeWithTag("message-input").assertTextEquals("保留合成视频附文");assertEquals(0,calls.get())
    }
}
