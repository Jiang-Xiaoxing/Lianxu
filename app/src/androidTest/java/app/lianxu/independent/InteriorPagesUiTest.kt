package app.lianxu.independent

import android.content.Context
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Native screen evidence. Every record, credential and response is synthetic and isolated. */
class InteriorPagesUiTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val token=System.nanoTime().toString()
    private val database="interior-$token.db"
    private val namespace="interior-$token"
    private lateinit var oldDatabase:String
    private lateinit var oldNamespace:String
    private lateinit var oldClient:()->TextChatClient
    private lateinit var vm:AppState
    private lateinit var models:ModelConfigState
    private val calls=AtomicInteger()
    private var memoryId=""
    private val first=Friend("interior-xia","许知夏","喜欢散步、看电影，也愿意认真听你聊生活里的小事。")
    private val second=Friend("interior-zhou","周予安","喜欢读书与旅行，习惯把日子过得慢一点。")
    private val third=Friend("interior-ning","宁川","对音乐和摄影充满好奇，珍惜每一次不赶时间的聊天。")

    @get:Rule(order=0) val fixture=object:ExternalResource(){
        override fun before(){
            oldDatabase=AppServices.databaseName;oldNamespace=AppServices.vaultNamespace;oldClient=AppServices.createTextClient
            AppServices.databaseName=database;AppServices.vaultNamespace=namespace
            AppServices.createTextClient={object:TextChatClient{
                override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("No chat request is permitted in native visual acceptance")
            }}
            runBlocking{Store(context,database).use{store->
                LocalUpdateRepository(store).acknowledge(InstalledBuild.read(context).versionCode)
                store.add(listOf(first,second,third),false)
                store.exchange(first,"今天下班后走了很久，终于慢慢放松下来。","那就把今天的疲惫留在路上吧。走到了什么样的地方？")
                store.exchange(first,"河边。风有一点凉，但很舒服。","听起来是很适合放空的傍晚。下次也留一点这样的时间给自己。")
                store.exchange(second,"下次见面聊聊你最近读的书。","好，我把最喜欢的一段留着，到时候慢慢讲给你听。")
                store.exchange(third,"刚听到一首很喜欢的歌。","那就先收藏起来，等你想聊的时候，再说说它让你想起了什么。")
            }}
            ModelVault(context,namespace).save(ModelConfig("interior-service","合成测试服务","deepseek","https://example.invalid/v1","synthetic-chat-model"),"synthetic-key-only")
        }
        override fun after(){
            ModelVault(context,namespace).let{vault->vault.list().forEach{vault.delete(it.id)}}
            if(::vm.isInitialized)vm.processing.store.close()
            context.deleteDatabase(database)
            AppServices.databaseName=oldDatabase;AppServices.vaultNamespace=oldNamespace;AppServices.createTextClient=oldClient
        }
    }
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()

    @Before fun setup(){
        ui.activityRule.scenario.onActivity{activity->
            vm=ViewModelProvider(activity)[AppState::class.java]
            models=ViewModelProvider(activity,object:ViewModelProvider.Factory{
                @Suppress("UNCHECKED_CAST") override fun <T:androidx.lifecycle.ViewModel> create(modelClass:Class<T>):T =
                    ModelConfigState(activity.application,object:ModelDirectoryClient{
                        override suspend fun request(config:ModelConfig,key:String,fetchModels:Boolean):ConfigResult{
                            check(key=="synthetic-key-only");calls.incrementAndGet()
                            return ConfigResult.Success(listOf("synthetic-chat-model","synthetic-reasoning-model"))
                        }
                    },namespace) as T
            })[ModelConfigState::class.java]
        }
        ui.waitUntil(15000){vm.ready&&models.configs.size==1}
        runBlocking{
            memoryId=vm.processing.manualSave(first.id,null,"喜欢在傍晚沿着河边散步，不喜欢太甜的饮料。",MemoryCategory.PREFERENCE_BOUNDARY,true,true,MemoryAbout.USER,listOf("日常","饮食")).id
            vm.processing.manualSave(first.id,null,"一起聊过下班后散步的事。那天河边的风有点凉。",MemoryCategory.EXPERIENCE_MEMORY,false,false,MemoryAbout.BOTH,listOf("散步"))
            vm.processing.manualSave(first.id,null,"周五一起聊最近读的书。",MemoryCategory.COMMITMENT_OPEN,false,false,MemoryAbout.BOTH,listOf("阅读"))
            vm.refreshProcessing()
        }
    }
    private fun hideIme(){
        ui.activityRule.scenario.onActivity{activity->
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(activity.window.decorView.windowToken,0)
            activity.window.decorView.clearFocus()
        };ui.waitForIdle()
    }
    private fun shot(name:String,hideKeyboard:Boolean=true){
        if(hideKeyboard)hideIme()
        ui.waitForIdle();android.os.SystemClock.sleep(220)
        val label=InstrumentationRegistry.getArguments().getString("evidenceLabel","light")
        val dir=File(context.filesDir,"interior-evidence/$label").apply{mkdirs()}
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertNotNull(bitmap)
        File(dir,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    private fun open(page:String,id:String=first.id){ui.runOnIdle{vm.open(page,id)};ui.waitForIdle()}

    @Test fun allReachablePagesAndImportantInteractions(){
        ui.onNodeWithTag("home-title").assertTextEquals("恋叙");shot("01-chat-home")
        ui.onNodeWithTag("add-menu").performClick();shot("02-add-menu")
        ui.onNodeWithText("创建角色").performClick();shot("03-create-role")
        ui.onNodeWithTag("back").performClick()
        ui.onNodeWithTag("role-actions-${first.id}").performClick();shot("04-role-actions")
        ui.onNodeWithTag("role-delete-${first.id}").performClick();ui.onNodeWithTag("role-delete-cancel").performClick()
        assertEquals(3,vm.friends.size)
        ui.onNodeWithTag("tab-1").performClick();shot("05-contacts")
        ui.onNodeWithText(first.name).performClick();shot("06-role-profile")
        ui.onNodeWithText("发消息").performScrollTo();shot("36-role-profile-actions")
        ui.onNodeWithTag("role-prompts-open").performScrollTo().performClick();shot("07-role-editor")
        ui.onNodeWithTag("role-name").performTextReplacement("取消后不应保存")
        hideIme();ui.onNodeWithTag("back").performClick();assertEquals(first.name,vm.friends.first{it.id==first.id}.name)
        open("chat");shot("08-chat-detail")
        ui.onNodeWithTag("message-input").performTextInput("明天再一起聊聊。")
        shot("09-chat-keyboard",false);hideIme()
        ui.onNodeWithTag("chat-attach").performClick();shot("10-attachment-state")
        ui.onNodeWithTag("close-attachment-info").performClick()
        ui.onNodeWithTag("back").performClick();open("chat")
        ui.onNodeWithTag("message-input").assertTextContains("明天再一起聊聊。",substring=true)
        ui.onNodeWithTag("back").performClick();ui.runOnIdle{vm.home();vm.tab=2};shot("11-settings")
        ui.onNodeWithTag("settings-info").performScrollTo();shot("37-settings-more")
        ui.onNodeWithTag("settings-models").performScrollTo().performClick();shot("12-model-overview")
        ui.onNodeWithTag("config-add-top").performClick();shot("13-model-providers")
        ui.onNodeWithTag("provider-search").performTextInput("DeepSeek");hideIme();ui.onNodeWithTag("provider-deepseek").performClick();shot("14-model-new")
        ui.onNodeWithTag("config-back").performClick()
        ui.onNodeWithTag("config-edit-interior-service").performClick();shot("15-model-editor")
        ui.onNodeWithTag("config-advanced").performScrollTo().performClick()
        ui.onNodeWithTag("config-memory-output").performScrollTo();shot("38-model-advanced")
        ui.onNodeWithTag("config-advanced").performScrollTo().performClick()
        assertEquals(0,calls.get())
        ui.onNodeWithTag("config-fetch").performScrollTo().performClick()
        ui.waitUntil(10000){models.models.size==2};shot("16-model-catalog")
        ui.onNodeWithTag("model-options").performScrollToNode(hasText("synthetic-reasoning-model"))
        ui.onNodeWithText("synthetic-reasoning-model").performClick()
        assertEquals("synthetic-chat-model",ModelVault(context,namespace).list().single().modelId)
        ui.onNodeWithTag("config-back").performClick();ui.onNodeWithTag("config-back").performClick()
        open("processing");shot("17-memory-roles")
        ui.onNodeWithTag("processing-friend-${first.id}").performClick();shot("18-memory-list")
        ui.onNodeWithText("我写的").performClick();ui.onNodeWithTag("memory-list").performScrollToNode(hasTestTag("memory-$memoryId"))
        ui.onNodeWithTag("memory-$memoryId").performClick();shot("19-memory-detail")
        ui.onNodeWithTag("memory-edit").performClick();shot("20-memory-editor")
        ui.onNodeWithText("取消").performClick();ui.onNodeWithTag("back").performClick()
        ui.onNodeWithText("我写的").performClick();shot("21-memory-mine")
        ui.onNodeWithText("她记下的").performClick()
        ui.onNodeWithText("更多",substring=true).performClick();ui.onNodeWithTag("memory-maintenance").performClick();shot("22-memory-maintenance")
        ui.onNodeWithTag("back").performClick()
        open("settings-memory");shot("26-memory-rules")
        open("role-archive");shot("27-backup-diagnostics")
        open("settings-display");shot("28-display-settings")
        open("settings-info");shot("29-about")
        ui.onNodeWithText("第三方致谢").performScrollTo();shot("30-about-details")
        open("settings-updates");shot("31-updates")
        ui.runOnIdle{vm.home();vm.tab=3};shot("32-my-profile")
        ui.onNodeWithText("保存资料").performScrollTo();shot("33-my-profile-fields")
        ui.runOnIdle{vm.home();vm.tab=0}
        repeat(4){ui.onNodeWithTag("add-menu").performClick();ui.onNodeWithText("创建角色").performClick();ui.onNodeWithTag("back").performClick()}
        ui.onNodeWithTag("home-title").assertTextEquals("恋叙")
        assertEquals(1,calls.get())
        assertEquals(3,vm.friends.size)
        assertEquals(3,runBlocking{vm.processing.memories(first.id).size})
    }

    @Test fun emptyStatesStayEmptyAfterReopening(){
        runBlocking{vm.friends.toList().forEach{friend->vm.deleteRole(vm.previewRoleDeletion(friend.id),true)}}
        ui.waitUntil(10000){vm.friends.isEmpty()}
        ui.runOnIdle{vm.home();vm.tab=0};ui.onNodeWithTag("roles-empty-chat").assertIsDisplayed();shot("34-empty-chat")
        ui.onNodeWithTag("tab-1").performClick();ui.onNodeWithTag("roles-empty-contacts").assertIsDisplayed();shot("35-empty-contacts")
        ui.onNodeWithTag("tab-0").performClick();assertTrue(vm.friends.isEmpty())
        assertEquals(0,calls.get())
    }
}
