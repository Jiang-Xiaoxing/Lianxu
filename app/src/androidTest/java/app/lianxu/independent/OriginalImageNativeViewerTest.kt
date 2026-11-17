package app.lianxu.independent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File

/** No Compose test clock: the original viewer runs on native Choreographer timing. */
class OriginalImageNativeViewerTest {
    @get:Rule val fixture=MockChatFixture()
    @Test fun nativeClockLongOriginalActuallyLoadsSharpRegionAfterPinch(){
        val source=Bitmap.createBitmap(600,10000,Bitmap.Config.ARGB_8888);val canvas=Canvas(source);canvas.drawColor(Color.WHITE)
        val paint=Paint().apply{color=Color.rgb(60,90,50);textSize=26f;isAntiAlias=true}
        for(y in 0 until 10000 step 100){canvas.drawText("ROW ${y/100} : 0123456789 ABC",14f,y+35f,paint);canvas.drawLine(0f,y+60f,600f,y+60f,paint)}
        val raw=ByteArrayOutputStream().also{source.compress(Bitmap.CompressFormat.PNG,100,it);source.recycle()}.toByteArray()
        val instrument=InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            scenario.onActivity{it.setContent{LianxuTheme(false){ChatPictureViewer(raw){ }}}};SystemClock.sleep(900)
            val down=SystemClock.uptimeMillis()
            val props=Array(2){i->MotionEvent.PointerProperties().apply{id=i;toolType=MotionEvent.TOOL_TYPE_FINGER}}
            fun event(action:Int,distance:Float,count:Int=2){val coords=Array(count){i->MotionEvent.PointerCoords().apply{x=540f;y=900f+if(i==0)-distance else distance;pressure=1f;size=1f}}
                val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,count,props,coords,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
                assertTrue(instrument.uiAutomation.injectInputEvent(e,true));e.recycle()
            }
            event(MotionEvent.ACTION_DOWN,40f,1);event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),40f)
            for(i in 1..30){event(MotionEvent.ACTION_MOVE,40f+220f*i/30);SystemClock.sleep(20)}
            event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),260f);event(MotionEvent.ACTION_UP,260f,1)
            SystemClock.sleep(1200)
            val bitmap=instrument.uiAutomation.takeScreenshot();val dir=File(instrument.targetContext.getExternalFilesDir(null),"chat-image-evidence").apply{mkdirs()}
            File(dir,"12-native-original-viewer-zoom.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            var peak=0
            for(y in bitmap.height/5 until bitmap.height*4/5)for(x in bitmap.width/4 until bitmap.width*3/4){
                val a=Color.red(bitmap.getPixel(x,y));val b=Color.red(bitmap.getPixel(x,y+1));peak=maxOf(peak,kotlin.math.abs(a-b))
            }
            File(dir,"native-viewer-sharpness.txt").writeText("verticalNeighborContrast=$peak; native clock; synthetic row text")
            bitmap.recycle();assertTrue("Only a blurry preview was visible: contrast=$peak",peak>=100)
        }
    }
}
