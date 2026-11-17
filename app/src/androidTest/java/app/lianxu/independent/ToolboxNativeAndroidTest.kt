package app.lianxu.independent

import android.graphics.Bitmap
import android.graphics.Rect
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File

/** Real Android clock and touch injection; no Compose test clock intercepts the hold/drag. */
class ToolboxNativeAndroidTest {
    @get:Rule val fixture=MockChatFixture({object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("No network")}})
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val automation get()=instrumentation.uiAutomation
    private lateinit var activity:MainActivity
    private lateinit var vm:AppState
    private val evidence get()=File(instrumentation.targetContext.getExternalFilesDir(null),"toolbox-native").apply{mkdirs()}
    @Before fun open(){
        instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await{instrumentation.runOnMainSync{ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()?.let{activity=it;vm=ViewModelProvider(it)[AppState::class.java]}};::vm.isInitialized&&vm.ready}
        instrumentation.runOnMainSync{vm.tab=2;vm.open("toolbox")}
        try{await{runCatching{card("image");true}.getOrDefault(false)}}catch(e:Exception){shot("launch-failure");throw e}
        Thread.sleep(350)
    }
    @After fun close(){if(::activity.isInitialized)instrumentation.runOnMainSync{activity.finish()}}
    private fun await(condition:()->Boolean){val end=SystemClock.uptimeMillis()+10000;while(!condition()){check(SystemClock.uptimeMillis()<end){"native toolbox condition timeout"};Thread.sleep(30)}}
    private fun awaitOrder(expected:List<String>)=await{runCatching{expected==expected.sortedBy{card(it).top}}.getOrDefault(false)}
    private fun card(id:String):Rect {
        val title=mapOf("image" to "图片理解","video" to "视频理解","memory" to "记忆工具","contact" to "主动联系").getValue(id)
        fun find(node:android.view.accessibility.AccessibilityNodeInfo):android.view.accessibility.AccessibilityNodeInfo? {
            if(node.text?.toString()?.contains(title)==true)return node
            for(i in 0 until node.childCount)node.getChild(i)?.let{find(it)?.let{return it}}
            return null
        }
        val node=requireNotNull(find(requireNotNull(automation.rootInActiveWindow))){"没有找到卡片 $id"}
        return Rect().also{node.getBoundsInScreen(it)}
    }
    private fun shot(name:String){automation.takeScreenshot().let{b->File(evidence,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
    private fun touch(action:Int,x:Float,y:Float,down:Long){
        val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0).apply{source=InputDevice.SOURCE_TOUCHSCREEN}
        try{assertTrue(automation.injectInputEvent(event,true))}finally{event.recycle()}
    }
    private fun drag(cancel:Boolean=false,multi:Boolean=false){
        val from=card("image");val to=card("memory");val down=SystemClock.uptimeMillis()
        val x=from.centerX().toFloat();val start=from.centerY().toFloat();val end=to.centerY().toFloat()
        touch(MotionEvent.ACTION_DOWN,x,start,down);Thread.sleep(650);shot(if(cancel)"cancel-held"else"01-lifted")
        for(step in 1..20){touch(MotionEvent.ACTION_MOVE,x,start+(end-start)*step/20f,down);Thread.sleep(25);if(step==10)shot(if(cancel)"cancel-moving"else"02-moving")}
        shot(if(cancel)"cancel-before-release"else"03-before-release")
        assertTrue("held card did not move",card("image").top>from.top+(to.top-from.top)/2)
        if(multi){
            val props=Array(2){i->MotionEvent.PointerProperties().apply{id=i;toolType=MotionEvent.TOOL_TYPE_FINGER}}
            fun event(action:Int,count:Int=2){val coords=Array(count){i->MotionEvent.PointerCoords().apply{this.x=x+i*30;this.y=end;pressure=1f;size=1f}}
                val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,count,props,coords,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
                try{assertTrue(automation.injectInputEvent(e,true))}finally{e.recycle()}
            }
            event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT));Thread.sleep(100)
            event(MotionEvent.ACTION_MOVE);event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT));event(MotionEvent.ACTION_UP,1)
        }else touch(if(cancel)MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP,x,end,down)
    }
    @Test fun realClockDragReordersPersistsAndMovesOtherCards(){
        val recording=Thread{
            val target=File(evidence,"toolbox-native-drag.mp4").absolutePath
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("screenrecord --time-limit 6 $target")).use{it.readBytes()}
        }.apply{start()}
        try{
            Thread.sleep(200);drag()
            val expected=listOf("video","memory","image","contact")
            await{runBlocking{ToolboxPreferences.loadOrder(vm.processing.store)==expected}}
            awaitOrder(expected);shot("04-settled")
            assertEquals("toolbox",vm.page)
            assertEquals(expected,expected.sortedBy{card(it).top})
            val old=activity;instrumentation.runOnMainSync{activity.recreate()}
            await{var changed=false;instrumentation.runOnMainSync{ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()?.let{if(it!==old){activity=it;changed=true}}};changed}
            awaitOrder(expected)
            assertEquals(expected,expected.sortedBy{card(it).top});shot("05-recreated")
        }finally{recording.join(8000)}
    }
    @Test fun actualCancelledDragRestoresOrder(){
        drag(cancel=true)
        awaitOrder(ToolboxPreferences.defaultOrder)
        assertEquals(ToolboxPreferences.defaultOrder,runBlocking{ToolboxPreferences.loadOrder(vm.processing.store)})
        assertEquals(ToolboxPreferences.defaultOrder,ToolboxPreferences.defaultOrder.sortedBy{card(it).top})
        assertEquals("toolbox",vm.page);shot("06-cancelled-restored")
    }
    @Test fun secondFingerCancelsAnActuallyMovingCard(){
        drag(multi=true)
        awaitOrder(ToolboxPreferences.defaultOrder)
        assertEquals(ToolboxPreferences.defaultOrder,runBlocking{ToolboxPreferences.loadOrder(vm.processing.store)})
        assertEquals(ToolboxPreferences.defaultOrder,ToolboxPreferences.defaultOrder.sortedBy{card(it).top});assertEquals("toolbox",vm.page)
        shot("07-multifinger-restored")
    }
    @Test fun compactDarkLargeTypeDragCanScrollAtEdgeWithoutOpening(){
        instrumentation.runOnMainSync{activity.setContent{val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.6f)){LianxuTheme(true){Surface(Modifier.fillMaxSize(),color=Chrome){Box(Modifier.safeDrawingPadding()){Box(Modifier.width(320.dp).height(480.dp)){ToolboxScreen(vm)}}}}}
        }}
        await{runCatching{card("image");true}.getOrDefault(false)};Thread.sleep(350);shot("08-compact-dark-before")
        val from=card("image");val x=from.exactCenterX();val start=from.exactCenterY();val end=480*instrumentation.targetContext.resources.displayMetrics.density-24f;val down=SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN,x,start,down);Thread.sleep(650)
        for(step in 1..25){touch(MotionEvent.ACTION_MOVE,x,start+(end-start)*step/25f,down);Thread.sleep(30)}
        Thread.sleep(900);shot("09-compact-edge-held");touch(MotionEvent.ACTION_UP,x,end,down)
        await{runBlocking{ToolboxPreferences.loadOrder(vm.processing.store)==listOf("video","memory","contact","image")}}
        assertEquals("toolbox",vm.page);Thread.sleep(350);shot("10-compact-edge-settled")
    }
    @Test fun splitSettingsMergeAndFullBackupRestoresOrderModeAndPausedPermissions()=runBlocking{
        val repo=vm.processing;val old=MemoryRules(recallRounds=8,recallTokens=6000,recallPerCall=1800,adaptiveContext=true)
        repo.saveMemoryRules(old);repo.saveMemoryTools(false,true)
        repo.saveMemoryCounts(old.copy(recentRounds=58,summaryRounds=18,relatedCount=23,reviewRounds=9))
        val expected=old.copy(recentRounds=58,summaryRounds=18,relatedCount=23,reviewRounds=9,proactiveRecall=false,immediateWrite=true)
        assertEquals(expected,repo.memoryRules().first)
        repo.saveMemoryTools(true,false);assertEquals(expected.copy(proactiveRecall=true,immediateWrite=false),repo.memoryRules().first)
        val order=listOf("contact","memory","video","image")
        val linName=repo.store.friends().first{it.id=="seed-lin"}.name
        val anName=repo.store.friends().first{it.id=="seed-an"}.name
        ToolboxPreferences.saveOrder(repo.store,order);repo.store.setPreference(ToolboxPreferences.VIDEO_KEY,"FRAMES")
        vm.companion.setPolicy("seed-lin",true,CompanionRuntime.owner)
        val vault=ModelVault(instrumentation.targetContext,fixture.namespace,repo.store);val backup=BackupRepository(repo.store,vault)
        val exported=backup.export();val bytes=ByteArrayOutputStream().also{BackupArchive.write(exported.root,exported.files,it)}.toByteArray()
        val pack=BackupArchive.read(bytes.inputStream())
        ToolboxPreferences.saveOrder(repo.store,ToolboxPreferences.defaultOrder);repo.store.setPreference(ToolboxPreferences.VIDEO_KEY,"ORIGINAL")
        backup.commit(backup.preview(pack,"REPLACE"),true)
        assertEquals(order,ToolboxPreferences.loadOrder(repo.store));assertEquals(ToolboxPreferences.VideoMode.FRAMES,ToolboxPreferences.videoMode(repo.store))
        val restored=repo.store.friends()
        assertEquals("awaiting_user_resume",vm.companion.contactPolicies()[restored.first{it.name==linName}.id])
        assertEquals(expected.copy(proactiveRecall=true,immediateWrite=false),repo.memoryRules().first)
        val friend=restored.first{it.name==anName}
        backup.deleteRole(backup.previewRoleDeletion(friend.id),true)
        try{vm.companion.setPolicy(friend.id,true,CompanionRuntime.owner);fail("deleted role must not get permission")}catch(_:IllegalStateException){}
        assertFalse(friend.id in vm.companion.contactPolicies())
    }
}
