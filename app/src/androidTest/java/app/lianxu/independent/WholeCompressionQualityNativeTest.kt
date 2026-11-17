package app.lianxu.independent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File

/** Real Choreographer screenshots of the original and the actual single wire copy. */
class WholeCompressionQualityNativeTest {
    @get:Rule val fixture=MockChatFixture()
    @Test fun compressedTextAndColoredEdgesRemainReadableInActualViewer(){
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        val dir=File(context.getExternalFilesDir(null),"compression-quality").apply{mkdirs()}
        val b=Bitmap.createBitmap(1200,5000,Bitmap.Config.ARGB_8888);val c=Canvas(b);c.drawColor(Color.WHITE)
        val p=Paint().apply{isAntiAlias=true;textSize=32f}
        for(y in 0 until 5000 step 100){p.color=Color.rgb(30,80,40);c.drawText("ROW ${y/100} : 0123456789 ABC xyz",24f,y+40f,p);p.color=Color.BLUE;c.drawRect(1000f,y.toFloat(),1120f,y+45f,p);p.color=Color.RED;c.drawLine(0f,y+65f,1200f,y+65f,p)}
        val jpeg=ByteArrayOutputStream().also{b.compress(Bitmap.CompressFormat.JPEG,98,it);b.recycle()}.toByteArray()
        val pending=ChatImages.prepare(OriginalImageFixtures.padJpeg(jpeg,45_000_001).inputStream(),context.cacheDir)
        pending.use{original->
            val ref=ChatImageRef("synthetic",1,"synthetic.jpg",original.mime,original.hash,original.width,original.height,storageKind="original")
            WholeImageDelivery.prepare(context,listOf(ref),{original.source},{ }).use{delivery->
                val copy=delivery.images.values.single();assertTrue(copy.source.size<=20_000_000)
                val a=original.source.decode(BitmapFactory.Options())!!;val z=copy.source.decode(BitmapFactory.Options())!!
                assertEquals(a.width,z.width);assertEquals(a.height,z.height);var error=0.0;var samples=0L
                for(y in 0 until a.height step 3)for(x in 0 until a.width step 3){val u=a.getPixel(x,y);val v=z.getPixel(x,y);for(shift in listOf(0,8,16)){val diff=((u ushr shift)and 255)-((v ushr shift)and 255);error+=diff*diff;samples++}}
                val psnr=if(error==0.0)99.0 else 10*kotlin.math.log10(255.0*255/(error/samples));a.recycle();z.recycle();assertTrue("Synthetic JPEG text PSNR=$psnr",psnr>=30)
                original.source.input().use{input->File(dir,"original.jpg").outputStream().use{input.copyTo(it)}}
                copy.source.input().use{input->File(dir,"single-copy.jpg").outputStream().use{input.copyTo(it)}}
                ActivityScenario.launch(MainActivity::class.java).use{scenario->
                    for((name,source) in listOf("original" to original.source,"single-copy" to copy.source)){
                        scenario.onActivity{it.setContent{LianxuTheme(false){ChatSourceViewer(source){ }}}};SystemClock.sleep(1400)
                        val screen=instrument.uiAutomation.takeScreenshot();File(dir,"$name-viewer.png").outputStream().use{screen.compress(Bitmap.CompressFormat.PNG,100,it)};screen.recycle()
                        val down=SystemClock.uptimeMillis();val props=Array(2){i->MotionEvent.PointerProperties().apply{id=i;toolType=MotionEvent.TOOL_TYPE_FINGER}}
                        fun event(action:Int,distance:Float,count:Int=2){val coords=Array(count){i->MotionEvent.PointerCoords().apply{x=540f;y=900f+if(i==0)-distance else distance;pressure=1f;size=1f}};val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,count,props,coords,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);assertTrue(instrument.uiAutomation.injectInputEvent(e,true));e.recycle()}
                        event(MotionEvent.ACTION_DOWN,40f,1);event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),40f)
                        for(i in 1..30){event(MotionEvent.ACTION_MOVE,40f+180f*i/30);SystemClock.sleep(20)}
                        event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),220f);event(MotionEvent.ACTION_UP,220f,1);SystemClock.sleep(1200)
                        val zoom=instrument.uiAutomation.takeScreenshot();File(dir,"$name-zoom.png").outputStream().use{zoom.compress(Bitmap.CompressFormat.PNG,100,it)};zoom.recycle()
                    }
                }
                File(dir,"quality.json").writeText(JSONObject().put("originalBytes",original.source.size).put("wireBytes",copy.source.size).put("width",1200).put("height",5000).put("psnrDb",psnr).put("wireImages",delivery.images.size).put("fixture","synthetic text and colored edges; not real provider recognition").toString(2))
                assertEquals(original.hash,original.source.hash())
            }
        }
    }
}
