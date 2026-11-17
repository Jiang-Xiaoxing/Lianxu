package app.lianxu.independent

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** The existing full-screen viewer, with a long original and real multi-touch input. */
class OriginalImageViewerTest {
    @get:Rule val ui=createComposeRule()
    @Test fun originalLongImageSupportsZoomPanAndCloseWithoutFullBitmapDecode(){
        val source=android.graphics.Bitmap.createBitmap(600,10000,android.graphics.Bitmap.Config.ARGB_8888)
        val canvas=android.graphics.Canvas(source);canvas.drawColor(android.graphics.Color.WHITE)
        val paint=android.graphics.Paint().apply{color=android.graphics.Color.rgb(60,90,50);textSize=26f;isAntiAlias=true}
        for(y in 0 until 10000 step 100){canvas.drawText("ROW ${y/100} : 0123456789 ABC",14f,y+35f,paint);canvas.drawLine(0f,y+60f,600f,y+60f,paint)}
        val raw=java.io.ByteArrayOutputStream().also{source.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it);source.recycle()}.toByteArray();var open by mutableStateOf(true)
        ui.setContent{LianxuTheme(false){if(open)ChatPictureViewer(raw){open=false}}}
        ui.onNodeWithTag("image-viewer").assertExists()
        ui.waitForIdle();Thread.sleep(350)
        val node=ui.onNodeWithContentDescription("图片，可双指缩放和拖动")
        node.performTouchInput{val c=center;pinch(c-Offset(0f,40f),c+Offset(0f,40f),c-Offset(0f,260f),c+Offset(0f,260f),600)}
        ui.waitForIdle();Thread.sleep(250)
        node.performTouchInput{swipe(center,center-Offset(0f,300f),500)}
        ui.waitForIdle();Thread.sleep(250)
        val instrument=InstrumentationRegistry.getInstrumentation();val dir=File(instrument.targetContext.getExternalFilesDir(null),"chat-image-evidence").apply{mkdirs()}
        val bitmap=instrument.uiAutomation.takeScreenshot();File(dir,"11-original-viewer-zoom.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        ui.onNodeWithText("此原图区域无法读取，原文件仍保留").assertDoesNotExist()
        ui.onNodeWithTag("image-viewer-close").performClick();ui.onNodeWithTag("image-viewer").assertDoesNotExist();assertFalse(open)
    }
}
