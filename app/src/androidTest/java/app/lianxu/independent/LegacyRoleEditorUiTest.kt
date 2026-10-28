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

/** Synthetic UI regression: opening, renaming or changing style must keep the effective role definition. */
class LegacyRoleEditorUiTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val database="legacy-editor-${System.nanoTime()}.db"
    private lateinit var previous:String
    private lateinit var vm:AppState
    @get:Rule(order=0)val fixture=object:ExternalResource(){
        override fun before(){previous=AppServices.databaseName;AppServices.databaseName=database}
        override fun after(){if(::vm.isInitialized)vm.processing.store.close();context.deleteDatabase(database);AppServices.databaseName=previous}
    }
    @get:Rule(order=1)val ui=createAndroidComposeRule<ComponentActivity>()
    @Before fun show(){
        ui.runOnUiThread{vm=ViewModelProvider(ui.activity,object:ViewModelProvider.Factory{
            @Suppress("UNCHECKED_CAST")override fun <T:androidx.lifecycle.ViewModel> create(modelClass:Class<T>):T=
                AppState(ui.activity.application,object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("No network allowed")},"no-vault-$database") as T
        })[AppState::class.java]}
        ui.waitUntil(15000){vm.ready}
        runBlocking{LocalUpdateRepository(vm.processing.store).acknowledge(InstalledBuild.read(context).versionCode)}
        ui.setContent{LianxuTheme{Lianxu(vm)}}
    }
    private fun open(friend:Friend,prompts:RolePrompts){
        runBlocking{
            val store=vm.processing.store
            store.add(listOf(if(friend.bio.isBlank())friend.copy(bio="合成旧数据占位") else friend),false)
            // Current creation requires a biography; construct the older/imported shape only in this isolated fixture.
            if(friend.bio.isBlank())store.writableDatabase.update("friends",android.content.ContentValues().apply{put("bio",friend.bio)},"id=?",arrayOf(friend.id))
            store.updateRolePrompts(friend.id){prompts};vm.refreshProcessing()
        }
        ui.runOnIdle{vm.open("detail",friend.id);vm.open("role-prompts",friend.id)}
        ui.waitUntil(10000){runCatching{ui.onNodeWithTag("config-save").assertIsEnabled();true}.getOrDefault(false)}
    }
    private fun verifySave(friend:Friend,prompts:RolePrompts=RolePrompts(),styleOnly:Boolean=false){
        val expected=prompts.definition(friend)
        open(friend,prompts)
        ui.onNodeWithTag("role-definition").assertTextContains(expected,substring=true)
        if(styleOnly)ui.onNodeWithTag("role-expression").performScrollTo().performTextReplacement("新的合成说话方式")
        else ui.onNodeWithTag("role-name").performTextReplacement("仅修改名字")
        ui.onNodeWithTag("config-save").performClick()
        ui.waitUntil(10000){!vm.busy&&vm.page=="detail"}
        val savedFriend=vm.friends.single{it.id==friend.id}
        val saved=runBlocking{vm.rolePrompts(friend.id)}
        assertEquals(expected,saved.characterDefinition)
        assertEquals(friend.bio,savedFriend.bio);assertEquals(friend.prompt,savedFriend.prompt)
        assertEquals(if(styleOnly)friend.name else "仅修改名字",savedFriend.name)
        assertEquals(if(styleOnly)"新的合成说话方式" else prompts.expression,saved.expression)
        val ready=CompanionPrompt.build(savedFriend,saved,emptyList(),"合成消息") as PromptBuild.Ready
        val system=ready.request.messages.first{it.role=="system"}.content
        assertTrue(system.contains(expected))
        if(prompts.characterDefinition.isNotBlank()){
            assertFalse(system.contains(friend.bio));assertFalse(system.contains(friend.prompt))
        }
    }
    @Test fun dualLegacyFieldsSurviveRename()=verifySave(Friend("both","旧角色","旧简介\n完整第二行","旧自写要求"))
    @Test fun biographyOnlySurvivesRename()=verifySave(Friend("bio","旧角色","只有旧简介",""))
    @Test fun promptOnlySurvivesRename()=verifySave(Friend("prompt","旧角色","","只有旧自写要求"))
    @Test fun explicitDefinitionKeepsPriority()=verifySave(Friend("new","新角色","不应合入的旧简介","不应合入的旧要求"),RolePrompts(characterDefinition="明确的新角色设定",expression="原表达"))
    @Test fun styleOnlySaveKeepsBothLegacyFields()=verifySave(Friend("style","旧角色","说话方式编辑前的简介","说话方式编辑前的要求"),styleOnly=true)
    @Test fun cancellingAllDefinitionVariantsLeavesStoredFieldsUntouched(){
        val cases=listOf(
            Friend("cancel-both","旧角色","旧简介","旧要求") to RolePrompts(),
            Friend("cancel-bio","旧角色","旧简介","") to RolePrompts(),
            Friend("cancel-prompt","旧角色","","旧要求") to RolePrompts(),
            Friend("cancel-new","新角色","旧简介","旧要求") to RolePrompts(characterDefinition="新设定")
        )
        for((friend,prompts) in cases){
            open(friend,prompts)
            val original=runBlocking{vm.processing.store.preference(RolePromptStore.key(friend.id),"")}
            ui.onNodeWithTag("role-name").performTextReplacement("未保存名字")
            ui.onNodeWithTag("role-definition").performScrollTo().performTextReplacement("未保存设定")
            ui.onNodeWithTag("back").performClick()
            assertEquals(friend,vm.friends.single{it.id==friend.id})
            assertEquals(original,runBlocking{vm.processing.store.preference(RolePromptStore.key(friend.id),"")})
        }
    }
}
