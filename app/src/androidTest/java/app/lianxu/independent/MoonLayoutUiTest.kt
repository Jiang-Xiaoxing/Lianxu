package app.lianxu.independent

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource

/** Isolated synthetic database and a client which rejects every network request. */
class MoonLayoutUiTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="moon-layout-${System.nanoTime()}.db"
    private lateinit var previous:String
    private lateinit var vm:AppState
    @get:Rule val method=org.junit.rules.TestName()
    @get:Rule(order=0)val fixture=object:ExternalResource(){
        override fun before(){previous=AppServices.databaseName;AppServices.databaseName=name}
        override fun after(){if(::vm.isInitialized)vm.processing.store.close();context.deleteDatabase(name);AppServices.databaseName=previous}
    }
    @get:Rule(order=1)val ui=createAndroidComposeRule<ComponentActivity>()
    @Before fun show(){
        ui.runOnUiThread{vm=ViewModelProvider(ui.activity,object:ViewModelProvider.Factory{
            @Suppress("UNCHECKED_CAST")override fun <T:androidx.lifecycle.ViewModel> create(modelClass:Class<T>):T=
                AppState(ui.activity.application,object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("No network allowed")},"no-vault-$name") as T
        })[AppState::class.java]}
        ui.waitUntil(15000){vm.ready}
        runBlocking{val code=InstalledBuild.read(context).versionCode;if(method.methodName!="updateNoticeAcknowledgesAndHistoryRemainsAvailable"&&LocalReleaseNotes.forVersion(code)!=null)LocalUpdateRepository(vm.processing.store).acknowledge(code)}
        ui.setContent{LianxuTheme{Lianxu(vm)}}
    }
    @Test fun userPersonaFieldsPersistTogether(){
        ui.onNodeWithTag("tab-3").performClick()
        ui.onNodeWithTag("nickname").performTextReplacement("合成用户")
        ui.onNodeWithTag("persona-address").performScrollTo().performTextReplacement("小星")
        ui.onNodeWithTag("persona-introduction").performScrollTo().performTextReplacement("合成自我介绍")
        ui.onNodeWithText("保存资料").performScrollTo().performClick()
        ui.waitUntil(10000){!vm.busy&&vm.persona.address=="小星"}
        assertEquals(UserPersona("合成用户","小星","合成自我介绍"),runBlocking{vm.processing.store.persona()})
    }
    @Test fun creationStoresDefinitionExpressionAndOpening(){
        ui.runOnIdle{vm.open("create")}
        ui.onNodeWithTag("friend-name").performTextReplacement("合成同伴")
        ui.onNodeWithTag("friend-bio").performScrollTo().performTextReplacement("这是合成角色设定")
        ui.onNodeWithTag("friend-expression").performScrollTo().performTextReplacement("简短自然")
        ui.onNodeWithTag("friend-opening").performScrollTo().performTextReplacement("你好，今天怎么样？")
        ui.onNodeWithTag("create-friend").performScrollTo().performClick()
        ui.waitUntil(10000){!vm.busy&&vm.friends.size==1}
        val p=runBlocking{vm.rolePrompts(vm.friends.single().id)}
        assertEquals("这是合成角色设定",p.characterDefinition);assertEquals("简短自然",p.expression);assertEquals("你好，今天怎么样？",p.opening)
    }
    @Test fun aboutUsesInstalledVersionAndAuthor(){
        ui.runOnIdle{vm.open("settings-info")}
        ui.onNodeWithText("江晓星").assertExists()
        ui.onNodeWithTag("settings-version").performScrollTo().assertTextContains(InstalledBuild.read(context).versionName,substring=true)
        ui.onNodeWithText("第三方致谢").performScrollTo().assertExists()
    }
    @Test fun emptyTabsAndIndependentRelationshipReturn(){
        ui.onNodeWithTag("roles-empty-chat").assertIsDisplayed()
        ui.onNodeWithTag("tab-1").performClick()
        ui.onNodeWithTag("roles-empty-contacts").assertIsDisplayed()
        ui.runOnIdle{vm.createCompanion("合成关系角色","合成角色设定","","","")}
        ui.waitUntil(10000){!vm.busy&&vm.friends.size==1}
        val id=vm.friends.single().id
        ui.runOnIdle{vm.open("relationships");vm.open("relationship-role",id)}
        ui.onNodeWithTag("processing-relationship").assertIsDisplayed()
        ui.onNodeWithTag("back").performClick()
        ui.runOnIdle{assertEquals("relationships",vm.page);vm.open("detail",id);vm.open("relationship-role",id)}
        ui.onNodeWithTag("back").performClick()
        ui.runOnIdle{assertEquals("detail",vm.page)}
    }
    @Test fun characterEditCancelAndSaveRemainSeparate(){
        ui.runOnIdle{vm.createCompanion("合成原名","原设定","原表达","原开场","")}
        ui.waitUntil(10000){!vm.busy&&vm.friends.size==1}
        val id=vm.friends.single().id
        ui.runOnIdle{vm.open("detail",id);vm.open("role-prompts",id)}
        ui.onNodeWithTag("role-name").performTextReplacement("未保存草稿")
        ui.onNodeWithTag("back").performClick()
        ui.runOnIdle{assertEquals("合成原名",vm.friends.single().name);vm.open("role-prompts",id)}
        ui.onNodeWithTag("role-name").assertTextContains("合成原名",substring=true)
        ui.onNodeWithTag("role-name").performTextReplacement("合成新名")
        ui.onNodeWithTag("role-definition").performScrollTo().performTextReplacement("新设定")
        ui.onNodeWithTag("role-expression").performScrollTo().performTextReplacement("新表达")
        ui.onNodeWithTag("role-opening").performScrollTo().performTextReplacement("新开场")
        ui.onNodeWithTag("config-save").performClick()
        ui.waitUntil(10000){!vm.busy&&vm.page=="detail"}
        val p=runBlocking{vm.rolePrompts(id)}
        assertEquals("合成新名",vm.friends.single().name)
        assertEquals("新设定",p.characterDefinition);assertEquals("新表达",p.expression);assertEquals("新开场",p.opening)
    }
    @Test fun updateNoticeAcknowledgesAndHistoryRemainsAvailable(){
        ui.waitUntil(10000){ui.onAllNodesWithText("知道了").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("知道了").performClick()
        ui.waitUntil(10000){runBlocking{LocalUpdateRepository(vm.processing.store).pending(InstalledBuild.read(context).versionCode)}==null}
        ui.runOnIdle{vm.open("settings-updates")}
        ui.onNodeWithText(LocalReleaseNotes.forVersion(InstalledBuild.read(context).versionCode)!!.title).assertExists()
        ui.onNodeWithTag("settings-back").performClick()
        ui.onNodeWithTag("roles-empty-chat").assertIsDisplayed()
    }
    @Test fun backgroundStopsWindowFramesAndResumeKeepsHome(){
        val frames=java.util.concurrent.atomic.AtomicInteger()
        val thread=android.os.HandlerThread("synthetic-frame-observer").apply{start()}
        val listener=android.view.Window.OnFrameMetricsAvailableListener{_,_,_->frames.incrementAndGet()}
        ui.activityRule.scenario.onActivity{it.window.addOnFrameMetricsAvailableListener(listener,android.os.Handler(thread.looper))}
        try {
            ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            android.os.SystemClock.sleep(500)
            frames.set(0)
            android.os.SystemClock.sleep(1200)
            assertEquals("Stopped activity must not continue rendering animation frames",0,frames.get())
            ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            ui.onNodeWithTag("home-title").assertTextEquals("恋叙")
        } finally {
            ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            ui.activityRule.scenario.onActivity{it.window.removeOnFrameMetricsAvailableListener(listener)}
            thread.quitSafely()
        }
    }
}
