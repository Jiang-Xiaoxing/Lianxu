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
        if(styleOnly){
            ui.onNodeWithTag("role-tab-1").performClick()
            ui.onNodeWithTag("role-expression").performScrollTo().performTextReplacement("新的合成说话方式")
        }
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
    private fun shot(name:String) {
        ui.waitForIdle()
        val bmp=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(context.getExternalFilesDir(null),"$name.png").outputStream().use {
            check(bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))
        }
        bmp.recycle()
    }
    private fun tab(index:Int) {
        ui.onNodeWithTag("role-tab-$index").performClick()
        ui.onNodeWithTag("role-tab-$index").assertIsSelected()
    }
    @Test fun pageAndIndicatorMoveTogetherBeforeFingerRelease() {
        open(Friend("gesture","林澈","合成角色"),RolePrompts())
        val first=ui.onNodeWithTag("tab-panel-0",useUnmergedTree=true)
        val second=ui.onNodeWithTag("tab-panel-1",useUnmergedTree=true)
        val mover=ui.onNodeWithTag("role-tab-indicator",useUnmergedTree=true)
        val initial=mover.fetchSemanticsNode().boundsInRoot
        val width=first.fetchSemanticsNode().boundsInRoot.width
        ui.onRoot().performTouchInput {
            down(androidx.compose.ui.geometry.Offset(width*.79f,height*.65f))
            moveBy(androidx.compose.ui.geometry.Offset(-width*.10f,0f),32)
            moveBy(androidx.compose.ui.geometry.Offset(-width*.23f,0f),64)
        }
        try {
            val visible=second.fetchSemanticsNode().boundsInRoot.width / width
            val slide=(mover.fetchSemanticsNode().boundsInRoot.left-initial.left)/initial.width
            assertTrue("手指未抬起时应可见两页，下一页比例 $visible",visible in .1f.. .6f)
            assertEquals("滑块跟随页面",visible,slide,.03f)
        } finally {
            ui.onRoot().performTouchInput {
                moveBy(androidx.compose.ui.geometry.Offset(-width*.36f,0f),32)
                up()
            }
        }
        ui.onNodeWithTag("role-tab-1").assertIsSelected()
        second.performTouchInput { swipeRight(startX=width*.2f,endX=width*.8f) }
        ui.onNodeWithTag("role-tab-0").assertIsSelected()
    }
    @Test fun agreementHintOnlyAppearsInAnEmptyEditor() {
        val friend=Friend("agreement-hint","林澈","在旧书店工作。")
        val hint="在这里写下你们聊天时的小约定。比如：我说想静一静时，先别追问。"
        open(friend,RolePrompts())
        val agreement="我想静一静时，请给我一点时间。"
        for(value in listOf("",agreement)) {
            tab(3)
            val field=ui.onNodeWithTag("role-extra")
            if(value.isNotEmpty()) field.performTextReplacement(value)
            val placeholder=ui.onNodeWithText(hint,useUnmergedTree=true)
            if(value.isEmpty()) placeholder.assertIsDisplayed() else placeholder.assertDoesNotExist()
            assertEquals(value,field.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
            ui.onNodeWithText("${value.length} / 8000").assertIsDisplayed()
            shot(if(value.isEmpty()) "agreement-empty" else "agreement-filled")
            ui.onNodeWithTag("config-save").performClick()
            ui.waitUntil(10000){!vm.busy && vm.page=="detail"}
            val saved=runBlocking{vm.rolePrompts(friend.id)}
            assertEquals(value,saved.extra)
            val ready=CompanionPrompt.build(friend,saved,emptyList(),"合成消息") as PromptBuild.Ready
            val sent=CompanionPrompt.forSend(ready.request,emptyList())
            assertFalse(sent.messages.any { hint in it.content })
            assertEquals(value.isNotEmpty(),sent.messages.any { "[用户附加提示]\n$agreement" in it.content })
            if(value.isEmpty()) ui.runOnIdle{vm.open("role-prompts",friend.id)}
        }
    }
    @Test fun fourTabsKeepDraftsSelectionAndRoleScopedThinking() {
        val first=Friend("synthetic-role-a","林澈","在旧书店工作，喜欢散步。")
        val other=Friend("synthetic-role-b","晴川","合成角色乙")
        val store=vm.processing.store
        runBlocking {
            store.add(listOf(other),false)
            store.updateRolePrompts(other.id){it.copy(thinkingPrompt="乙的思考")}
        }
        open(first,RolePrompts(extra="原有聊天约定",initialRelationship="在书店相识"))
        shot("role")
        ui.onNodeWithTag("role-name").performTextReplacement("新名字")
        ui.onNodeWithTag("tab-panel-0").performTouchInput { swipeLeft(startX=width*.8f,endX=width*.2f) }
        ui.onNodeWithTag("role-tab-1").assertIsSelected()
        val field=ui.onNodeWithTag("role-thinking")
        field.performScrollTo().assertTextEquals(CompanionPrompt.DEFAULT_THINKING_BODY)
        shot("dialogue")
        field.performTextReplacement("我会先看看 {{user}} 提到的这件事。".repeat(18))
        field.performScrollTo()
        field.performTouchInput {
            val point=androidx.compose.ui.geometry.Offset(35f,20f)
            down(point);advanceEventTime(viewConfiguration.longPressTimeoutMillis+120L)
            moveTo(point+androidx.compose.ui.geometry.Offset(65f,0f));up()
        }
        val selection=field.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange]
        assertFalse("长按拖动后应有非空选区",selection.collapsed)
        ui.onNodeWithTag("role-tab-1").assertIsSelected()
        field.performTextReplacement("这是用来验证整页纵向滚动的合成长文。".repeat(90))
        val page=ui.onNodeWithTag("role-page-1")
        val fixedTop=ui.onNodeWithTag("config-save").fetchSemanticsNode().boundsInRoot
        val scroll=page.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
        val before=scroll.value()
        page.performTouchInput {
            if (before < scroll.maxValue()/2) swipeUp(startY=height*.74f,endY=height*.30f)
            else swipeDown(startY=height*.30f,endY=height*.74f)
        }
        ui.waitForIdle()
        assertTrue("纵向拖动应滚动整页",kotlin.math.abs(scroll.value()-before)>1f)
        assertEquals(fixedTop,ui.onNodeWithTag("config-save").fetchSemanticsNode().boundsInRoot)
        ui.onNodeWithTag("role-tab-1").assertIsSelected()
        field.performTextReplacement("按 {{character}} 的口吻想。")
        tab(2)
        ui.onNodeWithTag("role-initial-relationship").assertTextContains("在书店相识")
        shot("relationship")
        tab(3)
        ui.onNodeWithTag("role-extra").assertTextContains("原有聊天约定")
        shot("agreement")
        ui.onNodeWithTag("role-extra").performTextReplacement("新的聊天约定")
        tab(0)
        ui.onNodeWithTag("role-name").assertTextContains("新名字")
        tab(1)
        field.performScrollTo().assertTextContains("按 {{character}} 的口吻想。")
        ui.onNodeWithTag("config-save").performClick()
        ui.waitUntil(10000){!vm.busy && vm.page=="detail"}
        val saved=runBlocking{vm.rolePrompts(first.id)}
        assertEquals("新的聊天约定",saved.extra)
        assertEquals("按 {{character}} 的口吻想。",saved.thinkingPrompt)
        val request=(CompanionPrompt.build(vm.friends.single{it.id==first.id},saved,emptyList(),"合成输入") as PromptBuild.Ready).request
        val sent=CompanionPrompt.forSend(request,emptyList())
        val thinking=sent.messages.single{it.thinkingContext}.content
        assertTrue(thinking.contains("[思考语言]\n按 新名字 的口吻想。"))
        assertTrue(sent.messages.last().thinkingContext);assertFalse(sent.messages.first().content.contains("[思考语言]"))
        assertEquals("合成输入",sent.messages.single{it.role=="user"}.content)
        for (blank in listOf(false,true)) {
            ui.runOnIdle{vm.open("role-prompts",first.id)}
            tab(1)
            field.performScrollTo()
            if(blank) field.performTextReplacement("  \n  ") else {
                ui.onNodeWithTag("role-thinking-reset").performScrollTo().performClick()
                field.assertTextEquals(CompanionPrompt.DEFAULT_THINKING_BODY)
            }
            ui.onNodeWithTag("config-save").performClick()
            ui.waitUntil(10000){!vm.busy && vm.page=="detail"}
            assertEquals("",runBlocking{vm.rolePrompts(first.id)}.thinkingPrompt)
            assertEquals("乙的思考",runBlocking{vm.rolePrompts(other.id)}.thinkingPrompt)
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
