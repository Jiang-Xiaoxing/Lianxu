package app.lianxu.independent

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Window
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** One focused motion check and recording. Isolated synthetic state; no API request. */
class CompanionBirdPreviewTest {
    @Test fun nativePreviewAndMotionBoundaries(){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val database="birds-preview-${System.nanoTime()}.db"
        val oldDb=AppServices.databaseName;val oldVault=AppServices.vaultNamespace;val oldClient=AppServices.createTextClient
        val frames=AtomicInteger()
        val handlerThread=HandlerThread("brush-frame-check").apply{start()}
        val listener=Window.OnFrameMetricsAvailableListener{_,_,_->frames.incrementAndGet()}
        fun shell(command:String)=instrumentation.uiAutomation.executeShellCommand(command).use{fd->android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use{it.readText().trim()}}
        val previousScale=shell("settings get global animator_duration_scale")
        val dir=File(context.filesDir,"birds-preview").apply{mkdirs()}
        fun capture(name:String){val bitmap=instrumentation.uiAutomation.takeScreenshot();File(dir,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}
        fun clickLabel(label:String){
            val root=instrumentation.uiAutomation.rootInActiveWindow
            fun find(node:android.view.accessibility.AccessibilityNodeInfo):android.view.accessibility.AccessibilityNodeInfo?{
                if(node.text?.toString()==label||node.contentDescription?.toString()==label)return node
                for(i in 0 until node.childCount){val child=node.getChild(i)?:continue;find(child)?.let{return it}}
                return null
            }
            val node=checkNotNull(find(root)){"Missing native control: $label"}
            val target=generateSequence(node){it.parent}.first{it.isClickable}
            assertTrue(target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            SystemClock.sleep(250)
        }
        var app:AppState?=null
        try{
            AppServices.databaseName=database;AppServices.vaultNamespace="birds-vault-$database"
            AppServices.createTextClient={object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("No API request permitted")}}
            runBlocking{Store(context,database).use{store->
                LocalUpdateRepository(store).acknowledge(InstalledBuild.read(context).versionCode)
                val a=Friend("brush-xia","许知夏","喜欢散步，也愿意认真听你聊日常。")
                val b=Friend("brush-zhou","周予安","喜欢读书与旅行。")
                store.add(listOf(a,b),false)
                store.exchange(a,"今天去河边散步了。","那就把今天的疲惫留在路上吧。")
                store.exchange(b,"下次聊聊你最近读的书。","好，我把最喜欢的一段留着。")
            }}
            shell("settings put global animator_duration_scale 1")
            ActivityScenario.launch(MainActivity::class.java).use{scenario->
                scenario.onActivity{activity->app=ViewModelProvider(activity)[AppState::class.java];activity.window.addOnFrameMetricsAvailableListener(listener,Handler(handlerThread.looper))}
                val deadline=SystemClock.elapsedRealtime()+10000
                while(app?.ready!=true&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(50)
                assertTrue(app?.ready==true)
                SystemClock.sleep(500);frames.set(0);SystemClock.sleep(700)
                val active=frames.get();assertTrue("Visible brush should animate",active>5)
                capture("home-real-size")
                clickLabel("添加角色");clickLabel("创建角色");assertEquals("create",app!!.page)
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);SystemClock.sleep(250);assertEquals("home",app!!.page)
                clickLabel("通讯录");assertEquals(1,app!!.tab);SystemClock.sleep(450);frames.set(0);SystemClock.sleep(650)
                val hidden=frames.get();assertTrue("Other tabs must stop brush frames",hidden<=1)
                scenario.onActivity{app!!.tab=0};SystemClock.sleep(250)
                scenario.moveToState(Lifecycle.State.CREATED);SystemClock.sleep(250);frames.set(0);SystemClock.sleep(650)
                val background=frames.get();assertEquals("Background must stop brush frames",0,background)
                scenario.moveToState(Lifecycle.State.RESUMED);SystemClock.sleep(300)
                shell("settings put global animator_duration_scale 0");SystemClock.sleep(350);frames.set(0);SystemClock.sleep(650)
                val reduced=frames.get();assertTrue("Remove animations must preserve a static composition",reduced<=1)
                capture("reduced-motion-static")
                shell("settings put global animator_duration_scale 1");SystemClock.sleep(500)
                File(dir,"motion-checks.json").writeText("""{"visible_frames":$active,"hidden_tab_frames":$hidden,"background_frames":$background,"reduced_motion_frames":$reduced,"synthetic_data":true,"api_requests":0}""")
                scenario.onActivity{it.window.removeOnFrameMetricsAvailableListener(listener)}
            }
        }finally{
            shell("settings put global animator_duration_scale $previousScale")
            app?.processing?.store?.close();context.deleteDatabase(database)
            AppServices.databaseName=oldDb;AppServices.vaultNamespace=oldVault;AppServices.createTextClient=oldClient
            handlerThread.quitSafely()
        }
    }
}
