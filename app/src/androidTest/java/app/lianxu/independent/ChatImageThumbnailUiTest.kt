package app.lianxu.independent

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TestName
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Real persisted, alternating chat rows; pixel checks detect drawing beyond measured placeholders. */
class ChatImageThumbnailUiTest {
    @get:Rule(order=0) val fixture=MockChatFixture()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=2) val testName=TestName()
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrument.targetContext
    private lateinit var vm:AppState
    private data class Picture(val label:String,val id:Long,val reply:Long,val ref:ChatImageRef,val color:Int)
    private val rows=mutableListOf<Picture>()
    private val metrics=JSONArray()
    private fun folder()=File(context.getExternalFilesDir(null),"thumbnail-evidence").apply{mkdirs()}
    private fun shot(name:String){ui.waitForIdle();SystemClock.sleep(180);val b=instrument.uiAutomation.takeScreenshot();File(folder(),"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle();File(folder(),"metrics-${testName.methodName}.json").writeText(metrics.toString(2))}
    private fun seed(){
        ui.runOnIdle{vm=ViewModelProvider(ui.activity)[AppState::class.java]};ui.waitUntil(15000){vm.ready}
        runBlocking{val store=Store(context,fixture.database);try{val ledger=ChatLedger(store)
            for((index,spec) in listOf(Triple(300,3000,Color.MAGENTA),Triple(3000,300,Color.CYAN),Triple(600,450,Color.YELLOW),Triple(300,240,Color.BLUE)).withIndex()){
                val b=Bitmap.createBitmap(spec.first,spec.second,Bitmap.Config.ARGB_8888);b.eraseColor(spec.third);val raw=ByteArrayOutputStream().also{b.compress(Bitmap.CompressFormat.PNG,100,it);b.recycle()}.toByteArray()
                val p=ChatImages.prepare(raw.inputStream(),context.cacheDir);val t=ledger.begin("seed-lin","","fixture",image=p);p.close();assertTrue(ledger.finishWithMemory(t.id,1,"图片${index+1}下面的对话，位置应当正确。",onCompleted={},displayReasoning="合成思考：检查图片之后，继续下一条对话。"))
                val reply=ledger.all().single{it.id==t.id}.assistantMessageId!!;val db=store.writableDatabase;val at=System.currentTimeMillis()-1_000_000+index*240_000
                db.execSQL("UPDATE messages SET created=? WHERE id=?",arrayOf(at,t.userMessageId));db.execSQL("UPDATE messages SET created=? WHERE id=?",arrayOf(at+120_000,reply))
                rows+=Picture(listOf("vertical","wide","normal","failed")[index],t.userMessageId,reply,ChatImages.ref(db,t.userMessageId)!!,spec.third)
            }
            val bad=rows.last();store.writableDatabase.execSQL("UPDATE role_assets SET data=? WHERE friend_id=? AND path=?",arrayOf(byteArrayOf(1,2,3),bad.ref.friendId,bad.ref.path));ImageDisk.clearReadCache()
        }finally{store.close()}}
        ui.runOnIdle{vm.foreground()};ui.waitUntil(15000){vm.messages.any{it.id==rows.last().reply}}
    }
    private fun show(font:Float=1f,width:Int=393,dark:Boolean=false){
        val density=ui.density.density
        ui.activityRule.scenario.onActivity{it.setContent{LianxuTheme(dark){CompositionLocalProvider(LocalDensity provides Density(density,font)){Box(Modifier.requiredWidth(width.dp).fillMaxHeight()){Lianxu(vm)}}}}}
        ui.runOnIdle{vm.open("chat","seed-lin")};ui.waitForIdle()
    }
    private fun scroll(p:Picture){ui.onNodeWithTag("chat-history").performScrollToKey("group-${p.id}");ui.waitForIdle()}
    private fun bounds(tag:String)=ui.onNodeWithTag(tag,true).fetchSemanticsNode().boundsInRoot
    private fun paintedBounds(color:Int):Rect?{
        val b=ui.onRoot().captureToImage().asAndroidBitmap();var x0=b.width;var y0=b.height;var x1=-1;var y1=-1
        for(y in 0 until b.height)for(x in 0 until b.width){val v=b.getPixel(x,y);if(kotlin.math.abs(Color.red(v)-Color.red(color))<5&&kotlin.math.abs(Color.green(v)-Color.green(color))<5&&kotlin.math.abs(Color.blue(v)-Color.blue(color))<5){x0=minOf(x0,x);x1=maxOf(x1,x);y0=minOf(y0,y);y1=maxOf(y1,y)}}
        b.recycle();return if(x1<0)null else Rect(x0.toFloat(),y0.toFloat(),(x1+1).toFloat(),(y1+1).toFloat())
    }
    private fun check(p:Picture,name:String){
        ui.waitUntil(10000){ui.onAllNodesWithTag("message-image-${p.id}",true).fetchSemanticsNodes().size==1}
        try{ui.waitUntil(10000){paintedBounds(p.color)!=null}}catch(e:Throwable){shot("$name-missing-paint");throw e}
        val message=bounds("message-${p.id}");val image=bounds("message-image-${p.id}");val painted=paintedBounds(p.color)?:error("Synthetic image not visible")
        val timeMatcher=SemanticsMatcher("timestamp"){it.config.getOrNull(SemanticsProperties.Text)?.joinToString(""){t->t.text}?.matches(Regex("\\d{2}:\\d{2}"))==true}
        val times=ui.onAllNodes(timeMatcher,true).fetchSemanticsNodes().map{it.boundsInRoot}.filter{it.top>=message.bottom-1}
        metrics.put(JSONObject().put("case",name).put("message",message.toString()).put("image",image.toString()).put("painted",painted.toString()).put("laterTimestampCount",times.size));shot(name)
        assertTrue("Image painted above/below its message: $painted vs $message",painted.top>=message.top-1&&painted.bottom<=message.bottom+1&&painted.left>=message.left-1&&painted.right<=message.right+1)
        assertTrue("Thumbnail exceeds 180dp: $image",image.width/ui.density.density<=181&&image.height/ui.density.density<=181)
        assertTrue("Timestamp after image is missing",times.isNotEmpty())
        val reply=bounds("message-${p.reply}");assertTrue("Next reply overlaps picture",reply.top>=message.bottom)
        val thinking=bounds("thinking-card-message-${p.reply}");assertTrue("Next thinking card overlaps picture or timestamp",thinking.top>=message.bottom&&times.any{it.bottom<=thinking.top+1})
    }
    @Test fun longWideAndNormalDoNotPaintAcrossFollowingMessagesAndStillOpenOriginal(){
        seed();show()
        for(p in rows.take(3)){scroll(p);check(p,"list-${p.label}");ui.onNodeWithTag("message-image-${p.id}",true).performClick();ui.onNodeWithTag("image-viewer").assertExists();if(p.label=="vertical")shot("viewer-vertical-original");ui.onNodeWithTag("image-viewer-close").performClick();scroll(p);check(p,"returned-${p.label}")}
    }
    @Test fun loadingSuccessFailureLargeFontKeyboardAndScrollKeepStableFrames(){
        seed();val p=rows.first();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val worker=thread{ synchronized(ImagePixels.thumbnailGate){entered.countDown();release.await(30,TimeUnit.SECONDS)} };assertTrue(entered.await(3,TimeUnit.SECONDS))
        lateinit var loading:Rect;lateinit var failedLoading:Rect
        try{show(1.5f,320,true);scroll(p);ui.onNodeWithTag("message-image-loading-${p.id}",true).assertExists();loading=bounds("message-image-frame-${p.id}");metrics.put(JSONObject().put("case","loading").put("frame",loading.toString()));shot("small-dark-large-font-loading")
            val bad=rows.last();scroll(bad);ui.onNodeWithTag("message-image-loading-${bad.id}",true).assertExists();failedLoading=bounds("message-image-frame-${bad.id}");metrics.put(JSONObject().put("case","failed-loading").put("frame",failedLoading.toString()));shot("failed-image-loading");scroll(p)
        }
        finally{release.countDown();worker.join(3000)}
        ui.waitUntil(10000){ui.onAllNodesWithTag("message-image-${p.id}",true).fetchSemanticsNodes().size==1};val loaded=bounds("message-image-frame-${p.id}");assertEquals(loading.width,loaded.width,1f);assertEquals(loading.height,loaded.height,1f);check(p,"small-dark-large-font-loaded")
        ui.onNodeWithTag("message-input").performClick();ui.runOnUiThread{ui.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(ui.activity.window.decorView,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)}
        ui.waitUntil(5000){ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==true};SystemClock.sleep(400)
        scroll(rows[2]);ui.waitUntil(10000){paintedBounds(rows[2].color)!=null};val keyboardMessage=bounds("message-${rows[2].id}");val keyboardPaint=requireNotNull(paintedBounds(rows[2].color));assertTrue(keyboardPaint.top>=keyboardMessage.top-1&&keyboardPaint.bottom<=keyboardMessage.bottom+1);shot("keyboard-normal-row");ui.onNodeWithTag("chat-history").performTouchInput{swipeUp()};shot("keyboard-scrolled")
        ui.runOnUiThread{ui.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(ui.activity.window.decorView.windowToken,0)};SystemClock.sleep(400)
        val bad=rows.last();scroll(bad);ui.waitUntil(10000){ui.onAllNodesWithTag("message-image-failed-${bad.id}",true).fetchSemanticsNodes().size==1};val failed=bounds("message-image-frame-${bad.id}");assertEquals(failedLoading.width,failed.width,1f);assertEquals(failedLoading.height,failed.height,1f);assertTrue(failed.width/ui.density.density<=181&&failed.height/ui.density.density<=181);metrics.put(JSONObject().put("case","failed").put("frame",failed.toString()));shot("small-dark-large-font-failed")
        assertNull(paintedBounds(p.color));scroll(p);check(p,"scrolled-back-vertical")
    }
}
