package app.lianxu.independent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*

class WaitingMotionIntegrationTest {
 @Volatile private var phase=0f
 @Volatile private var active=false
 private val ins get()=InstrumentationRegistry.getInstrumentation()
 private fun shell(s:String){ins.uiAutomation.executeShellCommand(s).use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}}
 private fun await(check:()->Boolean){val end=System.currentTimeMillis()+8000;while(!check()&&System.currentTimeMillis()<end)Thread.sleep(40);assertTrue(check())}
 @Test fun pausesInBackgroundReducedMotionAndRemoval(){
  val resolver=ins.targetContext.contentResolver
  val original=Settings.Global.getString(resolver,Settings.Global.ANIMATOR_DURATION_SCALE)
  var visible by mutableStateOf(true)
  try {
   shell("settings put global animator_duration_scale 1")
   ActivityScenario.launch(ComponentActivity::class.java).use{scenario->
    scenario.onActivity{it.setContent{LianxuTheme{if(visible)RoleGenerationWaiting{p,a->phase=p;active=a}}}}
    await{active&&phase>.05f};val p=phase;Thread.sleep(350);assertNotEquals(p,phase)
    scenario.moveToState(Lifecycle.State.CREATED);Thread.sleep(500)
    val paused=phase;Thread.sleep(350);assertEquals(paused,phase,0f)
    scenario.moveToState(Lifecycle.State.RESUMED);await{active};val resumed=phase;await{phase!=resumed}
    shell("settings put global animator_duration_scale 0");await{!active}
    val reduced=phase;Thread.sleep(350);assertEquals(reduced,phase,0f)
    shell("settings put global animator_duration_scale 1");await{active}
    scenario.onActivity{visible=false};Thread.sleep(250)
    val removed=phase;Thread.sleep(350);assertEquals(removed,phase,0f)
   }
  }finally{
   shell(if(original==null)"settings delete global animator_duration_scale" else "settings put global animator_duration_scale $original")
   assertEquals(original,Settings.Global.getString(resolver,Settings.Global.ANIMATOR_DURATION_SCALE))
  }
 }
}
