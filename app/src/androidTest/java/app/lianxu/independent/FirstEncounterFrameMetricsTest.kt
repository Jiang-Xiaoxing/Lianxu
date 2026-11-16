package app.lianxu.independent

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Native clocks only. The measured window contains no screenshot/accessibility polling. */
class FirstEncounterFrameMetricsTest {
    @Test fun sampleAfterAnIdenticalWarmup(){
        val instrument=InstrumentationRegistry.getInstrumentation()
        val automation=instrument.uiAutomation
        val context=instrument.targetContext
        val args=InstrumentationRegistry.getArguments()
        val label=requireNotNull(args.getString("door-perf-label"))
        require(label.matches(Regex("[A-Za-z0-9_-]{1,60}")))
        val recording=requireNotNull(args.getString("door-perf-recording"));require(recording in setOf("on","off"))
        val namespace="door-perf-${System.nanoTime()}"
        val oldDb=AppServices.databaseName;val oldVault=AppServices.vaultNamespace;val oldClient=AppServices.createTextClient
        var calls=0
        AppServices.databaseName="$namespace.db";AppServices.vaultNamespace=namespace
        AppServices.createTextClient={object:TextChatClient{
            override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls++;return TextChatResult.Failure(ChatFailure.NETWORK)}
        }}
        val thread=HandlerThread("door-frame-metrics").apply{start()}
        val handler=Handler(thread.looper)
        val rows=ConcurrentLinkedQueue<LongArray>()
        var tapNs=0L;var endNs=0L;var refreshRate=0f
        var app:AppState?=null
        val metrics=intArrayOf(FrameMetrics.INTENDED_VSYNC_TIMESTAMP,FrameMetrics.VSYNC_TIMESTAMP,
            FrameMetrics.TOTAL_DURATION,FrameMetrics.UNKNOWN_DELAY_DURATION,FrameMetrics.INPUT_HANDLING_DURATION,
            FrameMetrics.ANIMATION_DURATION,FrameMetrics.LAYOUT_MEASURE_DURATION,FrameMetrics.DRAW_DURATION,
            FrameMetrics.SYNC_DURATION,FrameMetrics.COMMAND_ISSUE_DURATION,FrameMetrics.SWAP_BUFFERS_DURATION,
            FrameMetrics.GPU_DURATION,FrameMetrics.DEADLINE,FrameMetrics.FIRST_DRAW_FRAME)
        val listener=Window.OnFrameMetricsAvailableListener{_,frame,lost->
            // FrameMetrics is reused by Android: copy values now, never retain the callback object.
            val values=LongArray(metrics.size+1)
            metrics.forEachIndexed{index,id->values[index]=frame.getMetric(id)}
            values[metrics.size]=lost.toLong();rows.add(values)
        }
        fun hasNode(node:AccessibilityNodeInfo?,matches:(AccessibilityNodeInfo)->Boolean):Boolean{
            if(node==null)return false
            if(matches(node))return true
            return (0 until node.childCount).any{hasNode(node.getChild(it),matches)}
        }
        fun awaitNode(message:String,matches:(AccessibilityNodeInfo)->Boolean){
            val deadline=SystemClock.uptimeMillis()+5000
            while(SystemClock.uptimeMillis()<deadline){
                if(hasNode(automation.rootInActiveWindow,matches))return
                SystemClock.sleep(50)
            }
            fail(message)
        }
        fun cycle(measure:Boolean){
            ActivityScenario.launch(MainActivity::class.java).use{scenario->
                val limit=SystemClock.uptimeMillis()+15000
                while(SystemClock.uptimeMillis()<limit){
                    scenario.onActivity{app=ViewModelProvider(it)[AppState::class.java]}
                    if(app?.ready==true)break
                    SystemClock.sleep(40)
                }
                assertTrue(app?.ready==true)
                SystemClock.sleep(450)
                // Accessibility checks stay outside the timing window, including warm-up.
                awaitNode("closed invitation missing"){
                    it.isEnabled&&it.isClickable&&it.contentDescription?.toString()=="轻触，开始相遇"
                }
                var x=0f;var y=0f
                scenario.onActivity{activity->
                    x=activity.window.decorView.width/2f;y=activity.window.decorView.height/2f
                    refreshRate=activity.display?.refreshRate?:0f
                    if(measure)activity.window.addOnFrameMetricsAvailableListener(listener,handler)
                }
                val down=SystemClock.uptimeMillis()
                if(measure)tapNs=System.nanoTime()
                for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)){
                    val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0).apply{source=InputDevice.SOURCE_TOUCHSCREEN}
                    try{assertTrue(automation.injectInputEvent(event,true))}finally{event.recycle()}
                }
                SystemClock.sleep(4400)
                if(measure){
                    endNs=System.nanoTime()
                    scenario.onActivity{it.window.removeOnFrameMetricsAvailableListener(listener)}
                    val drained=CountDownLatch(1);handler.post{drained.countDown()};assertTrue(drained.await(3,TimeUnit.SECONDS))
                }
                for(text in listOf("创建角色","恢复已有数据")){
                    awaitNode("final enabled action missing: $text"){
                        it.isEnabled&&it.isClickable&&hasNode(it){child->child.text?.toString()==text}
                    }
                }
                assertEquals("home",app!!.page);assertTrue(app!!.friends.isEmpty())
            }
        }
        try{
            runBlocking{Store(context,"$namespace.db").use{LocalUpdateRepository(it).acknowledge(InstalledBuild.read(context).versionCode)}}
            cycle(false)
            cycle(true)
            val selected=rows.filter{it[0] in tapNs..endNs&&it[13]==0L}
            assertTrue("FrameMetrics did not produce a measurable sample",selected.size>=10)
            assertEquals(0,calls)
            val output=JSONObject().put("label",label).put("recording",recording).put("sdk",Build.VERSION.SDK_INT)
                .put("model",Build.MODEL).put("refreshHz",refreshRate).put("tapNs",tapNs).put("endNs",endNs)
                .put("warmupCycles",1).put("screenshotsDuringMeasurement",0).put("applicationModelRequests",calls)
                .put("startInvitationVerified",true).put("finalActionsEnabledVerified",true)
                .put("columns",JSONArray(listOf("intendedVsyncNs","vsyncNs","totalNs","unknownDelayNs","inputNs","animationNs","layoutNs","drawNs","syncNs","commandNs","swapNs","gpuNs","deadlineNs","firstDraw","callbackFramesLost")))
                .put("frames",JSONArray(selected.map{JSONArray(it.toList())}))
            val dir=File(context.getExternalFilesDir(null),"evidence-door-perf").apply{mkdirs()}
            val target=File(dir,"$label.json");check(!target.exists());target.writeText(output.toString(2))
            println("door-perf label=$label recording=$recording samples=${selected.size}")
        }finally{
            app?.processing?.store?.close();context.deleteDatabase("$namespace.db");File(context.noBackupFilesDir,namespace).deleteRecursively()
            AppServices.databaseName=oldDb;AppServices.vaultNamespace=oldVault;AppServices.createTextClient=oldClient
            thread.quitSafely();thread.join(3000)
        }
    }
}
