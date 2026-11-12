package app.lianxu.independent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CompletableDeferred
import com.google.gson.Gson
import android.graphics.Bitmap
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** No Compose test rule or virtual clock: records the actual application's animation clock. */
class RoleCardsRealtimeTest {
 private val gate=CompletableDeferred<TextChatResult>()
 @Volatile private var calls=0
 @get:Rule val fixture=MockChatFixture(clientFactory={object:TextChatClient{
  override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls++;return gate.await()}
 }})
 private fun shot(name:String){
  val ins=InstrumentationRegistry.getInstrumentation()
  val dir=File(ins.targetContext.getExternalFilesDir(null),"cards-realtime").apply{mkdirs()}
  File(dir,"$name.png").outputStream().use{ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}
 }
 @Test fun normalClockPendingThenSuccess(){
  ActivityScenario.launch(MainActivity::class.java).use{scenario->
   lateinit var vm:AppState
   scenario.onActivity{vm=ViewModelProvider(it)[AppState::class.java]}
   val deadline=System.currentTimeMillis()+15000
   while(!vm.ready&&System.currentTimeMillis()<deadline)Thread.sleep(50)
   assertTrue(vm.ready)
   scenario.onActivity{vm.startSearch();vm.updateSearchRequirements("合成演示：温柔的园艺师")}
   Thread.sleep(1500);shot("input")
   scenario.onActivity{vm.findAgain()}
   Thread.sleep(2500);assertTrue(vm.searching);assertEquals(1,calls);shot("pending-a")
   Thread.sleep(4000);shot("pending-b")
   Thread.sleep(5500)
   val payload=Gson().toJson(mapOf("roles" to (1..3).map{mapOf("name" to "合成角色$it","summary" to "合成演示候选","definition" to "虚构园艺师，在小镇生活。$it","expression" to "温柔简洁","opening" to "一起看看新开的花吧。")}))
   gate.complete(TextChatResult.Success(payload))
   val end=System.currentTimeMillis()+10000
   while(vm.searching&&System.currentTimeMillis()<end)Thread.sleep(50)
   assertFalse(vm.searching);assertEquals(3,vm.candidates.size);assertEquals(1,calls)
   Thread.sleep(1800);shot("complete")
  }
 }
}
