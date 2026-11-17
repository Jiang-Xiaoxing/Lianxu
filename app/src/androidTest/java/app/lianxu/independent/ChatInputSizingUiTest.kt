package app.lianxu.independent

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlinx.coroutines.awaitCancellation
import kotlin.math.abs

/** Production chat view and editable field; viewport/font overrides and all records are synthetic. */
class ChatInputSizingUiTest {
    @get:Rule(order=0) val fixture=MockChatFixture({object:TextChatClient{
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
            syntheticCompanionStage(request)?.let{return it}
            awaitCancellation()
        }
    }})
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private val args get()=InstrumentationRegistry.getArguments()
    private val compact get()=args.getString("composerBaseline")!="true"
    private val records=JSONArray()
    private fun folder():File {
        val name=args.getString("composerEvidence")?:"composer-sizing"
        require(name.matches(Regex("[A-Za-z0-9_-]+")))
        return File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),name).apply{mkdirs()}
    }
    private fun measure(label:String,fontScale:Float):Float {
        val density=ui.density.density
        val field=ui.onNodeWithTag("message-input").fetchSemanticsNode().boundsInRoot
        val face=ui.onNodeWithTag("chat-send-face",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val hit=ui.onNodeWithTag("send-message").fetchSemanticsNode().boundsInRoot
        val bar=ui.onNodeWithTag("chat-input-bar").fetchSemanticsNode().boundsInRoot
        val layouts=mutableListOf<TextLayoutResult>()
        ui.onNodeWithTag("message-input").performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
        val scroll=ui.onNodeWithTag("message-input").fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
        records.put(JSONObject().put("case",label).put("fontScale",fontScale)
            .put("fieldHeightDp",field.height/density).put("faceHeightDp",face.height/density)
            .put("sendHitHeightDp",hit.height/density).put("barHeightDp",bar.height/density)
            .put("fieldWidthDp",field.width/density).put("lineCount",layouts.single().lineCount)
            .put("scrollPx",scroll?.value()).put("scrollMaxPx",scroll?.maxValue())
            .put("text",layouts.single().layoutInput.text.text).put("didOverflowWidth",layouts.single().didOverflowWidth))
        File(folder(),"measurements.json").writeText(records.toString(2))
        val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(folder(),"$label.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
        assertTrue("Send click region must retain 48dp",hit.height/density>=47.5f)
        assertFalse("Chinese text must not overflow horizontally",layouts.single().didOverflowWidth)
        return field.height/density
    }
    private fun changedPixels(first:ImageBitmap,last:ImageBitmap):Int {
        assertEquals(first.width,last.width);assertEquals(first.height,last.height)
        val a=first.toPixelMap();val b=last.toPixelMap();var changed=0
        for(y in 0 until a.height)for(x in 0 until a.width){val before=a[x,y];val after=b[x,y]
            if(maxOf(abs(before.red-after.red),abs(before.green-after.green),abs(before.blue-after.blue))>.15f)changed++
        }
        return changed
    }
    private fun exercise(width:Int,fontScale:Float,dark:Boolean,name:String){
        lateinit var app:AppState
        ui.runOnIdle{app=ViewModelProvider(ui.activity)[AppState::class.java]}
        ui.waitUntil(15000){app.ready}
        val density=ui.density.density
        ui.activityRule.scenario.onActivity{it.setContent{
            LianxuTheme(dark){CompositionLocalProvider(LocalDensity provides Density(density,fontScale)){
                Box(Modifier.requiredWidth(width.dp).fillMaxHeight()){Lianxu(app)}
            }}
        }}
        ui.onNodeWithTag("tab-0").performClick()
        ui.waitUntil(15000){ui.onAllNodesWithText("林间").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("林间").performClick()
        ui.onNodeWithTag("send-message").assertIsNotEnabled()
        val empty=measure("$name-empty",fontScale)
        ui.onNodeWithTag("message-input").performTextReplacement("中文")
        val single=measure("$name-single",fontScale)
        assertEquals("Empty and one-line input keep the same height",empty,single,1f)
        if(compact&&fontScale==1f){
            val field=ui.onNodeWithTag("message-input").fetchSemanticsNode().boundsInRoot
            val faceBounds=ui.onNodeWithTag("chat-send-face",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            val face=faceBounds.height/density
            assertEquals("Single-line visual height matches send face",face,single,1f)
            assertEquals("Top edges align",field.top/density,faceBounds.top/density,1f)
            assertEquals("Bottom edges align",field.bottom/density,faceBounds.bottom/density,1f)
            assertTrue("Outer bar is compact",ui.onNodeWithTag("chat-input-bar").fetchSemanticsNode().boundsInRoot.height/density<=58f)
        }
        ui.onNodeWithTag("message-input").performTextReplacement("一行\n二行\n三行\n四行")
        val four=measure("$name-four-lines",fontScale)
        val multi="一行\n二行\n三行\n四行\n五行\n末行"
        ui.onNodeWithTag("message-input").performTextReplacement(multi)
        ui.onNodeWithTag("message-input").assertTextEquals(multi)
        val multiple=measure("$name-multiline",fontScale)
        assertTrue("Multiline input must grow",multiple>single)
        assertEquals("Six lines scroll within the same four-line viewport",four,multiple,1f)
        ui.onNodeWithTag("message-input").performTextInputSelection(TextRange(0))
        ui.onNodeWithTag("message-input").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange,TextRange(0)))
        measure("$name-scroll-first",fontScale)
        val firstViewport=ui.onNodeWithTag("message-input").captureToImage()
        val layouts=mutableListOf<TextLayoutResult>()
        ui.onNodeWithTag("message-input").performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
        // Four 2dp-wide caret areas exceed cursor blink/movement; the remaining change is painted text.
        val cursorOnlyUpperBound=layouts.single().getCursorRect(0).height*density*8
        ui.onNodeWithTag("message-input").performTextInputSelection(TextRange(multi.length))
        ui.onNodeWithTag("message-input").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange,TextRange(multi.length)))
        var changed=0
        ui.waitUntil(5000){changed=changedPixels(firstViewport,ui.onNodeWithTag("message-input").captureToImage());changed>cursorOnlyUpperBound}
        ui.onNodeWithTag("message-input").assertTextEquals(multi)
        records.put(JSONObject().put("case","$name-painted-scroll").put("changedPixels",changed).put("cursorOnlyUpperBound",cursorOnlyUpperBound).put("textUnchanged",true))
        measure("$name-scroll-last",fontScale)
        ui.onNodeWithTag("message-input").performTextInput("中文")
        ui.onNodeWithTag("message-input").assertTextEquals(multi+"中文")
        measure("$name-multiline-end",fontScale)
        ui.onNodeWithTag("message-input").performTextReplacement("")
        assertEquals("Clearing the field restores its compact height",empty,measure("$name-cleared",fontScale),1f)
        ui.onNodeWithTag("chat-attach").performClick()
        cancelNativeImagePicker()
        ui.onNodeWithTag("message-input").assertExists()
        ui.onNodeWithTag("message-input").performTextReplacement("键盘发送")
        ui.onNodeWithTag("message-input").performImeAction()
        ui.waitUntil(15000){app.messages.any{it.mine&&it.text=="键盘发送"}&&ui.onAllNodesWithText("停止").fetchSemanticsNodes().isNotEmpty()}
        val message=app.messages.single{it.mine&&it.text=="键盘发送"}
        ui.onNodeWithTag("message-${message.id}").assertTextEquals("键盘发送")
        val labelLayouts=mutableListOf<TextLayoutResult>()
        ui.onNodeWithText("停止",useUnmergedTree=true).performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(labelLayouts)}
        val face=ui.onNodeWithTag("chat-send-face",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        assertTrue("Large-font Stop label fits its face",labelLayouts.single().size.height<=face.height+1)
        val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(folder(),"$name-large-stop.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
        ui.waitUntil(3000){android.os.SystemClock.uptimeMillis()-app.chatDrafts.getValue("seed-lin").submittedAt.longValue>1000}
        ui.onNodeWithContentDescription("停止回复").performClick()
        ui.waitUntil(15000){!app.chatSending}
    }
    @Test fun normalSingleAndMultiline(){exercise(400,1f,false,"normal")}
    @Test fun smallScreenLargeChineseFont(){exercise(320,2f,false,"small-large")}
    @Test fun smallScreenDarkFont(){exercise(320,1.3f,true,"small-dark")}
}
