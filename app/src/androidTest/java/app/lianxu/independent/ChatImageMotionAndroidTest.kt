package app.lianxu.independent

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Native Choreographer timing, without Compose test-clock acceleration. */
class ChatImageMotionAndroidTest {
    @get:Rule val fixture=MockChatFixture()
    @Test fun nativeClockFocusExpandDismissCollapseAndActivityRecreation(){
        val instrument=InstrumentationRegistry.getInstrumentation();val b=Bitmap.createBitmap(320,260,Bitmap.Config.ARGB_8888);b.eraseColor(Color.rgb(197,216,177))
        val canvas=android.graphics.Canvas(b);val p=android.graphics.Paint().apply{color=Color.rgb(234,199,111);isAntiAlias=true};canvas.drawCircle(160f,125f,70f,p);p.color=Color.rgb(150,116,73);canvas.drawCircle(160f,125f,30f,p)
        val bytes=ByteArrayOutputStream().also{b.compress(Bitmap.CompressFormat.PNG,100,it);b.recycle()}.toByteArray();val image=ChatImages.prepare(bytes.inputStream())
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            lateinit var vm:AppState;scenario.onActivity{vm=ViewModelProvider(it)[AppState::class.java]}
            val deadline=SystemClock.uptimeMillis()+15000;while(!vm.ready&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(25);assertTrue(vm.ready)
            scenario.onActivity{vm.open("chat","seed-lin");vm.chatDrafts.getOrPut("seed-lin"){ChatDraftState()}.image.value=image}
            SystemClock.sleep(1000)
            val dir=File(instrument.targetContext.getExternalFilesDir(null),"chat-image-evidence").apply{mkdirs()}
            fun shot(name:String){val bitmap=instrument.uiAutomation.takeScreenshot();File(dir,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            fun touch(x:Float,y:Float){val time=SystemClock.uptimeMillis();for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)){val e=MotionEvent.obtain(time,SystemClock.uptimeMillis(),action,x,y,0);e.source=InputDevice.SOURCE_TOUCHSCREEN;assertTrue(instrument.uiAutomation.injectInputEvent(e,true));e.recycle()}}
            shot("07-native-collapsed")
            fun editable(node:android.view.accessibility.AccessibilityNodeInfo):android.view.accessibility.AccessibilityNodeInfo?{
                if(node.isEditable)return node
                for(i in 0 until node.childCount)node.getChild(i)?.let{editable(it)?.let{return it}}
                return null
            }
            val node=editable(instrument.uiAutomation.rootInActiveWindow)?:error("找不到实际可编辑输入框")
            val bounds=Rect();node.getBoundsInScreen(bounds);touch(bounds.exactCenterX(),bounds.exactCenterY());SystemClock.sleep(1100);shot("08-native-expanded-ime")
            scenario.onActivity{assertTrue(androidx.core.view.ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true)}
            val display=instrument.targetContext.resources.displayMetrics;touch(display.widthPixels*.95f,display.heightPixels*.22f);SystemClock.sleep(1100);shot("09-native-collapsed-again")
            scenario.onActivity{assertFalse(androidx.core.view.ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true)}
            scenario.recreate();SystemClock.sleep(900);scenario.onActivity{val restored=ViewModelProvider(it)[AppState::class.java];assertSame(vm,restored);assertEquals(image.hash,restored.chatDrafts.getValue("seed-lin").image.value?.hash)}
            shot("10-native-recreated-draft")
            assertTrue(runBlocking{Store(instrument.targetContext,fixture.database).use{it.messages().none{m->m.mine&&m.image!=null}}})
        }
    }
}
