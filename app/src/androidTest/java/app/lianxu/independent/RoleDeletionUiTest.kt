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

/** Dedicated generated database. No user role or provider request is used. */
class RoleDeletionUiTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="role-delete-ui-${System.nanoTime()}.db"
    private lateinit var oldName:String
    private lateinit var vm:AppState
    @get:Rule(order=0)val fixture=object:ExternalResource(){
        override fun before(){oldName=AppServices.databaseName;AppServices.databaseName=name}
        override fun after(){if(::vm.isInitialized)vm.processing.store.close();context.deleteDatabase(name);AppServices.databaseName=oldName}
    }
    @get:Rule(order=1)val ui=createAndroidComposeRule<ComponentActivity>()
    @Before fun show(){
        ui.runOnUiThread{vm=ViewModelProvider(ui.activity,object:ViewModelProvider.Factory{
            @Suppress("UNCHECKED_CAST")override fun <T:androidx.lifecycle.ViewModel> create(modelClass:Class<T>):T =
                AppState(ui.activity.application,object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("No request is authorized in this synthetic UI test")},"no-vault-$name") as T
        })[AppState::class.java]}
        ui.waitUntil(15000){vm.ready};ui.setContent{LianxuTheme{Lianxu(vm)}}
    }
    private fun add(vararg friends:Friend)=runBlocking{vm.processing.store.add(friends.toList(),false);vm.refreshProcessing()}
    @Test fun freshUiHasNoDemoRolesInEitherTab(){
        ui.onNodeWithTag("roles-empty-chat").assertIsDisplayed();assertTrue(vm.friends.isEmpty())
        ui.onNodeWithText("林间").assertDoesNotExist();ui.onNodeWithText("时安").assertDoesNotExist()
        ui.onNodeWithTag("tab-1").performClick();ui.onNodeWithTag("roles-empty-contacts").assertIsDisplayed()
    }
    @Test fun cancelPreservesRoleAndConfirmedDeletionRefreshesChatsAndContacts(){
        val a=Friend("a","合成甲","测试角色甲");val b=Friend("b","合成乙","测试角色乙");add(a,b)
        runBlocking{vm.processing.store.exchange(a,"合成原话","合成回答");vm.refreshProcessing()}
        ui.onNodeWithTag("role-delete-a").performClick();ui.onNodeWithText("删除「合成甲」？").assertIsDisplayed()
        ui.onNodeWithText("将从聊天和通讯录同时移除",substring=true).assertExists()
        ui.onNodeWithTag("role-delete-cancel").performClick();assertEquals(2,vm.friends.size)
        ui.onNodeWithTag("tab-1").performClick();ui.onNodeWithText("合成甲").assertExists()
        ui.onNodeWithTag("tab-0").performClick();ui.onNodeWithTag("role-delete-a").performClick();ui.onNodeWithTag("role-delete-confirm").performClick()
        ui.waitUntil(15000){vm.friends.none{it.id==a.id}}
        ui.onNodeWithText("合成甲").assertDoesNotExist();ui.onNodeWithText("合成乙").assertExists()
        ui.onNodeWithTag("tab-1").performClick();ui.onNodeWithText("合成甲").assertDoesNotExist();ui.onNodeWithText("合成乙").assertExists()
        runBlocking{Store(context,name).use{assertEquals(listOf(b),it.friends());assertTrue(it.messages().none{m->m.friendId==a.id})}}
    }
    @Test fun deletingLastRoleShowsBothEmptyStatesWithoutRecreatingIt(){
        add(Friend("only","合成唯一角色","仅测试"))
        ui.onNodeWithTag("role-delete-only").performClick();ui.onNodeWithTag("role-delete-confirm").performClick()
        ui.waitUntil(15000){vm.friends.isEmpty()};ui.onNodeWithTag("roles-empty-chat").assertIsDisplayed()
        ui.onNodeWithTag("tab-1").performClick();ui.onNodeWithTag("roles-empty-contacts").assertIsDisplayed()
        runBlocking{Store(context,name).use{assertTrue(it.friends().isEmpty())}}
    }
}
