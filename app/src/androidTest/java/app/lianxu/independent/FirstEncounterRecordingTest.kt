package app.lianxu.independent

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Native Android frame clock and input, for real-time screen recording; no Compose test clock. */
class FirstEncounterRecordingTest {
    @Test fun captureRealTimeOpeningAndAdvanceWithoutATestClock(){
        val instrument=InstrumentationRegistry.getInstrumentation()
        val context=instrument.targetContext
        val namespace="door-recording-${System.nanoTime()}"
        val oldDb=AppServices.databaseName;val oldVault=AppServices.vaultNamespace;val oldClient=AppServices.createTextClient
        var calls=0
        AppServices.databaseName="$namespace.db";AppServices.vaultNamespace=namespace
        AppServices.createTextClient={object:TextChatClient{
            override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls++;return TextChatResult.Failure(ChatFailure.NETWORK)}
        }}
        var app:AppState?=null
        val dir=File(context.getExternalFilesDir(null),"evidence-first-encounter").apply{mkdirs()}
        fun shot(name:String){
            val bitmap=instrument.uiAutomation.takeScreenshot()
            File(dir,"realtime-$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
        try{
            runBlocking{Store(context,"$namespace.db").use{LocalUpdateRepository(it).acknowledge(InstalledBuild.read(context).versionCode)}}
            ActivityScenario.launch(MainActivity::class.java).use{scenario->
                val deadline=SystemClock.uptimeMillis()+15000
                while(SystemClock.uptimeMillis()<deadline){
                    scenario.onActivity{app=ViewModelProvider(it)[AppState::class.java]}
                    if(app?.ready==true)break
                    SystemClock.sleep(50)
                }
                assertTrue(app?.ready==true);SystemClock.sleep(350)
                shot("closed")
                var x=0f;var y=0f
                scenario.onActivity{x=it.window.decorView.width/2f;y=it.window.decorView.height/2f}
                val tapTime=SystemClock.uptimeMillis()
                for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)){
                    val event=MotionEvent.obtain(tapTime,SystemClock.uptimeMillis(),action,x,y,0).apply{source=InputDevice.SOURCE_TOUCHSCREEN}
                    try{assertTrue(instrument.uiAutomation.injectInputEvent(event,true))}finally{event.recycle()}
                }
                fun at(elapsed:Long){val wait=tapTime+elapsed-SystemClock.uptimeMillis();if(wait>0)SystemClock.sleep(wait)}
                // Capture by elapsed wall time; accessibility searches can wait for animation idle.
                // Visibility/order assertions live in FirstEncounterUiTest; inspect this recording.
                at(900);shot("opening")
                at(1900);shot("open")
                at(2700);shot("advancing")
                at(4300);shot("settled")
                assertEquals("home",app!!.page);assertTrue(app!!.friends.isEmpty());assertEquals(0,calls)
                SystemClock.sleep(600)
            }
        }finally{
            app?.processing?.store?.close();context.deleteDatabase("$namespace.db")
            File(context.noBackupFilesDir,namespace).deleteRecursively()
            AppServices.databaseName=oldDb;AppServices.vaultNamespace=oldVault;AppServices.createTextClient=oldClient
        }
    }
}
