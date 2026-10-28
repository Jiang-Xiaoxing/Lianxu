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
        runBlocking{val code=InstalledBuild.read(context).versionCode;if(LocalReleaseNotes.forVersion(code)!=null)LocalUpdateRepository(vm.processing.store).acknowledge(code)}
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
}
