package app.lianxu.independent

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.awaitCancellation
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/** Real chat input/runner, isolated synthetic model; theme override is test-only. */
class ChatSendColorsUiTest {
    private val calls=AtomicInteger()
    private val cancelled=AtomicInteger()
    private val painted=JSONArray()
    private lateinit var app:AppState
    @get:Rule(order=0) val mock=MockChatFixture({object:TextChatClient{
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
            check(key=="synthetic-key-only")
            syntheticCompanionStage(request)?.let{return it}
            calls.incrementAndGet()
            try{awaitCancellation()}finally{cancelled.incrementAndGet()}
        }
    }})
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private fun waitFor(test:()->Boolean)=ui.waitUntil(15000,test)
    private fun color(label:String,expected:Color){
        val layouts=mutableListOf<TextLayoutResult>()
        ui.onNodeWithText(label,useUnmergedTree=true).assertIsDisplayed().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult){it(layouts)}
        assertEquals(expected,layouts.single().layoutInput.style.color)
    }
    private fun shot(name:String){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(context.getExternalFilesDir(null),InstrumentationRegistry.getArguments().getString("composerColorEvidence")?:"evidence-send-colors").apply{mkdirs()}
        val bitmap=ui.onRoot().captureToImage().asAndroidBitmap()
        File(dir,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    private fun face(name:String,expected:Color?=null){
        fun pixel():Color{val image=ui.onNodeWithTag("chat-send-face",useUnmergedTree=true).captureToImage().toPixelMap();return image[image.width/2,(image.height*.1f).toInt()]}
        var actual=pixel()
        val first=actual
        // A previous tap's Material ripple briefly overlays the unchanged face color.
        if(expected!=null)ui.waitUntil(3000){actual=pixel();actual.toArgb()==expected.toArgb()}
        painted.put(JSONObject().put("case",name).put("firstFrameArgb",java.lang.Integer.toHexString(first.toArgb())).put("argb",java.lang.Integer.toHexString(actual.toArgb())))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(context.getExternalFilesDir(null),InstrumentationRegistry.getArguments().getString("composerColorEvidence")?:"evidence-send-colors").apply{mkdirs()}
        File(dir,"$name-colors.json").writeText(painted.toString(2))
        if(expected!=null)assertEquals("Painted action background",expected.toArgb(),actual.toArgb())
    }
    private fun exercise(dark:Boolean){
        ui.runOnIdle{app=ViewModelProvider(ui.activity)[AppState::class.java]}
        waitFor{app.ready}
        ui.activityRule.scenario.onActivity{it.setContent{LianxuTheme(dark){Lianxu(app)}}}
        ui.onNodeWithTag("tab-0").performClick()
        waitFor{ui.onAllNodesWithText("林间").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("林间").performClick()
        val name=if(dark)"dark"else"light"
        val idleColor=if(dark)Color(0xFFC1B5AA)else Color(0xFF756B62)
        val green=if(dark)Color(0xFFAFC9B5)else Color(0xFF4E7058)
        val red=if(dark)Color(0xFFE9B6B1)else Color(0xFF915753)
        ui.onNodeWithTag("send-message").assertIsNotEnabled();color("发送",idleColor.copy(alpha=.38f));face("$name-empty");shot("$name-empty")
        ui.onNodeWithTag("message-input").performTextReplacement("   ")
        ui.onNodeWithTag("send-message").assertIsNotEnabled();color("发送",idleColor.copy(alpha=.38f))
        ui.onNodeWithTag("message-input").performTextReplacement("合成第一句")
        ui.onNodeWithTag("send-message").assertIsEnabled();color("发送",green);face("$name-ready",if(dark)Color(0xFF35513F)else Color(0xFFD9F1DB));shot("$name-ready")
        ui.onNodeWithTag("send-message").performClick()
        waitFor{calls.get()==1&&ui.onAllNodesWithText("停止").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithContentDescription("停止回复").assertIsEnabled();color("停止",red)
        face("$name-stop",if(InstrumentationRegistry.getArguments().getString("composerBaseline")=="true")null else if(dark)Color(0xFF573A38)else Color(0xFFF1D6D2));shot("$name-stop")
        ui.onNodeWithTag("message-input").performTextReplacement("生成中再发一句")
        ui.onNodeWithTag("send-message").assertIsEnabled();color("发送",green);face("$name-generating-send",if(dark)Color(0xFF35513F)else Color(0xFFD9F1DB));shot("$name-generating-send")
        ui.onNodeWithTag("send-message").performClick()
        waitFor{calls.get()==2&&cancelled.get()>=1&&ui.onAllNodesWithText("停止").fetchSemanticsNodes().isNotEmpty()}
        color("停止",red)
        // Keep the production double-tap protection; stop only after its real guard has elapsed.
        waitFor{android.os.SystemClock.uptimeMillis()-app.chatDrafts.getValue("seed-lin").submittedAt.longValue>1000}
        ui.onNodeWithContentDescription("停止回复").performClick()
        waitFor{!app.chatSending&&cancelled.get()==2}
        ui.onNodeWithTag("send-message").assertIsNotEnabled();color("发送",idleColor.copy(alpha=.38f));shot("$name-stopped")
        assertEquals(2,calls.get())
    }
    @Test fun lightColorsFollowDisplayedActionAndKeepSendInterruptStop(){exercise(false)}
    @Test fun darkColorsFollowDisplayedActionAndKeepSendInterruptStop(){exercise(true)}
}
