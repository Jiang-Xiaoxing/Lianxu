package app.lianxu.independent

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Production routing and a per-test synthetic database; no live model credentials. */
class RoleCreationGuideTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrument.targetContext
    private lateinit var namespace:String
    private lateinit var oldDb:String
    private lateinit var oldVault:String
    private lateinit var oldClient:()->TextChatClient
    private lateinit var app:AppState
    private val calls=AtomicInteger()
    private var scale by mutableFloatStateOf(1f)
    @get:Rule(order=0)val fixture=object:TestWatcher(){
        override fun starting(description:Description){
            namespace="creation-guide-${System.nanoTime()}"
            oldDb=AppServices.databaseName;oldVault=AppServices.vaultNamespace;oldClient=AppServices.createTextClient
            AppServices.databaseName="$namespace.db";AppServices.vaultNamespace=namespace
            AppServices.createTextClient={object:TextChatClient{
                override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
                    calls.incrementAndGet();return TextChatResult.Failure(ChatFailure.NETWORK)
                }
            }}
            runBlocking{Store(context,"$namespace.db").use{store->
                if(description.methodName in setOf("existingUsersKeepTheirOriginalFormAndData","aNewOrdinaryFormNeverReusesAnEarlierSavedCreation"))store.add(listOf(Friend("keep-existing","已有合成角色","不修改")),false)
                LocalUpdateRepository(store).acknowledge(InstalledBuild.read(context).versionCode)
            }}
        }
        override fun finished(description:Description){
            try{if(::app.isInitialized)app.processing.store.close();context.deleteDatabase("$namespace.db");File(context.noBackupFilesDir,namespace).deleteRecursively()}
            finally{AppServices.databaseName=oldDb;AppServices.vaultNamespace=oldVault;AppServices.createTextClient=oldClient}
            assertEquals(0,calls.get())
        }
    }
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>(effectContext=object:MotionDurationScale{override val scaleFactor get()=scale})
    @Before fun ready(){ui.runOnIdle{app=ViewModelProvider(ui.activity)[AppState::class.java]};ui.waitUntil(15000){app.ready};ui.waitForIdle()}
    private fun editable(){ui.waitUntil(10000){ui.onAllNodes(hasTestTag("create-card") and hasStateDescription("可填写")).fetchSemanticsNodes().isNotEmpty()}}
    private fun enter(){ui.onNodeWithTag("first-encounter").performClick();ui.onNodeWithTag("first-encounter-create").performClick();editable();ui.onNodeWithTag("friend-name").assertIsDisplayed()}
    private fun next(){editable();ui.onNodeWithTag("create-next").performScrollTo().assertIsEnabled().performClick();ui.waitForIdle();editable()}
    private fun original(){ui.onNodeWithTag("create-use-original").performScrollTo().performClick();ui.waitForIdle()}
    private fun base(){enter();ui.onNodeWithTag("friend-name").performTextInput("合成·小树");next();ui.onNodeWithTag("friend-bio").performTextInput("喜欢分享日常的虚构角色。");next()}
    private fun confirm(){base();next();next();original();ui.onNodeWithTag("create-step-confirm").assertExists()}
    private fun shot(label:String){
        ui.waitForIdle();instrument.waitForIdleSync();SystemClock.sleep(150)
        val dir=File(context.getExternalFilesDir(null),"evidence-creation-guide").apply{mkdirs()}
        val image=instrument.uiAutomation.takeScreenshot()
        File(dir,"$label.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }

    @Test fun requiredQuestionsDoNotCreateEarlyAndAllFieldsPersist(){
        enter();next();ui.onNodeWithTag("create-validation").assertIsDisplayed();ui.onNodeWithTag("create-step-name").assertExists();assertTrue(app.friends.isEmpty())
        ui.onNodeWithTag("friend-name").performTextInput("合成·无性别设定");shot("name");next()
        next();ui.onNodeWithTag("create-validation").assertIsDisplayed();ui.onNodeWithTag("create-step-definition").assertExists()
        ui.onNodeWithTag("friend-bio").performTextInput("只写性格与经历，不要求性别。");shot("definition");next()
        ui.onNodeWithTag("friend-relationship").performTextInput("相识多年的朋友");shot("relationship");next()
        ui.onNodeWithTag("friend-expression").performTextInput("简短、自然")
        ui.onNodeWithTag("friend-opening").performScrollTo().performTextInput("今天过得怎么样？")
        ui.onNodeWithTag("friend-prompt").performScrollTo().performTextInput("尊重彼此的节奏。")
        shot("more");next();original();assertTrue(app.friends.isEmpty());shot("confirm")
        ui.onNodeWithTag("create-friend").performScrollTo().performClick();ui.waitUntil(15000){app.page=="chat"&&!app.busy}
        assertEquals(1,app.friends.size)
        val p=runBlocking{app.rolePrompts(app.friends.single().id)}
        assertEquals("只写性格与经历，不要求性别。",p.characterDefinition);assertEquals("相识多年的朋友",p.initialRelationship)
        assertEquals("简短、自然",p.expression);assertEquals("今天过得怎么样？",p.opening);assertEquals("尊重彼此的节奏。",p.extra)
        assertEquals("今天过得怎么样？",app.messages.single().text)
    }
    @Test fun emptyRelationshipContinuesWithoutASeparateSkipAction(){
        base();ui.onNodeWithTag("create-skip-relationship").assertDoesNotExist();ui.onNodeWithText("跳过").assertDoesNotExist();shot("relationship-empty-next")
        next();ui.onNodeWithTag("create-step-more").assertExists();next();original()
        ui.onNodeWithTag("create-friend").performScrollTo().performClick();ui.waitUntil(15000){app.page=="chat"}
        assertEquals("",runBlocking{app.rolePrompts(app.friends.single().id)}.initialRelationship)
        assertTrue(app.messages.isEmpty())
    }
    @Test fun filledRelationshipSurvivesNextBackAndEditing(){
        base();ui.onNodeWithTag("friend-relationship").performTextInput("旧识");next()
        ui.onNodeWithTag("create-back").performClick();editable();ui.onNodeWithTag("friend-relationship").assertTextContains("旧识")
        ui.onNodeWithTag("friend-relationship").performTextReplacement("相识多年的朋友");shot("relationship-edited-next");next();next();original()
        ui.onNodeWithTag("create-friend").performScrollTo().performClick();ui.waitUntil(15000){app.page=="chat"}
        assertEquals("相识多年的朋友",runBlocking{app.rolePrompts(app.friends.single().id)}.initialRelationship)
    }
    @Test fun clearingARelationshipUsesNextAsTheSkip(){
        base();ui.onNodeWithTag("friend-relationship").performTextInput("随后清空的旧内容");next()
        ui.onNodeWithTag("create-back").performClick();editable();ui.onNodeWithTag("friend-relationship").performTextClearance();next();next();original()
        ui.onNodeWithTag("create-friend").performScrollTo().performClick();ui.waitUntil(15000){app.page=="chat"}
        assertEquals("",runBlocking{app.rolePrompts(app.friends.single().id)}.initialRelationship)
    }
    @Test fun previousKeepExitReentryAndRecreationPreserveDraft(){
        base();ui.onNodeWithTag("friend-relationship").performTextInput("旧识")
        ui.onNodeWithTag("create-back").performClick();ui.onNodeWithTag("friend-bio").assertTextContains("喜欢分享日常的虚构角色。")
        ui.onNodeWithTag("create-cancel").performClick();ui.onNodeWithTag("create-keep-exit").performClick()
        ui.onNodeWithTag("first-encounter-create").assertIsDisplayed().performClick()
        ui.onNodeWithTag("friend-bio").assertTextContains("喜欢分享日常的虚构角色。")
        next();ui.onNodeWithTag("friend-relationship").assertTextContains("旧识")
        ui.activityRule.scenario.recreate();ui.onNodeWithTag("friend-relationship").assertTextContains("旧识")
        assertTrue(app.friends.isEmpty());shot("restored-draft")
    }
    @Test fun explicitDiscardReturnsToAnEmptyNameStep(){
        base();ui.onNodeWithTag("create-cancel").performClick();ui.onNodeWithTag("create-discard").performClick()
        ui.onNodeWithTag("first-encounter-create").performClick();ui.onNodeWithTag("friend-name").assertTextContains("")
        assertEquals("",ui.onNodeWithTag("friend-name").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertTrue(app.friends.isEmpty())
    }
    @Test fun repeatedNextAndBackDuringMotionCannotSkipAQuestion(){
        enter();ui.onNodeWithTag("friend-name").performTextInput("合成")
        ui.mainClock.autoAdvance=false
        ui.onNodeWithTag("create-next").performScrollTo().performClick()
        ui.mainClock.advanceTimeByFrame()
        ui.onNodeWithTag("create-next").assertIsNotEnabled()
        repeat(5){ui.onNodeWithTag("create-next").performTouchInput{click(center)}}
        instrument.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.mainClock.advanceTimeBy(100);shot("leaving-controlled")
        ui.mainClock.advanceTimeBy(800);ui.mainClock.autoAdvance=true
        ui.onNodeWithTag("friend-bio").assertIsDisplayed();ui.onNodeWithTag("friend-relationship").assertDoesNotExist()
    }
    @Test fun recreationMidTransitionReturnsToAnEditableCurrentQuestion(){
        enter();ui.onNodeWithTag("friend-name").performTextInput("重建合成名")
        ui.mainClock.autoAdvance=false;ui.onNodeWithTag("create-next").performScrollTo().performClick();ui.mainClock.advanceTimeBy(100)
        ui.activityRule.scenario.recreate();ui.mainClock.autoAdvance=true
        ui.onNodeWithTag("friend-name").assertTextContains("重建合成名");ui.onNodeWithTag("create-next").assertIsEnabled()
        next();ui.onNodeWithTag("friend-bio").assertIsDisplayed()
    }
    @Test fun reducedMotionStartsWithoutSlidingCards(){
        ui.runOnUiThread{scale=0f};enter();ui.onNodeWithTag("friend-name").performTextInput("少动画")
        next();ui.onNodeWithTag("friend-bio").assertIsEnabled().performTextInput("仍然可以填写")
        next();ui.onNodeWithTag("friend-relationship").assertIsEnabled();shot("reduced-motion")
    }
    @Test fun reducingMotionMidTransitionSettlesTheRequestedQuestion(){
        var local by mutableStateOf(RoleCreationDraft(name="少动画中途合成"))
        ui.activity.setContent{LianxuTheme{RoleCreationGuide(local,{local=it},false,onExit={},onCreate={})}}
        ui.waitForIdle();ui.mainClock.autoAdvance=false
        ui.onNodeWithTag("create-next").performScrollTo().performClick();ui.mainClock.advanceTimeBy(100)
        // Change state on the UI thread, without asking a frozen test clock to become idle.
        ui.runOnUiThread{scale=0f};ui.mainClock.advanceTimeByFrame();ui.mainClock.autoAdvance=true
        ui.onNodeWithTag("friend-bio").assertIsEnabled();assertEquals(RoleCreationStep.DEFINITION,local.step)
        shot("reduced-mid-transition")
    }
    @Test fun failedWriteKeepsDraftAndCanRetryWithoutASecondRole(){
        confirm();val db=app.processing.store.writableDatabase
        ui.runOnIdle{db.execSQL("PRAGMA query_only=ON")}
        try{
            ui.onNodeWithTag("create-friend").performScrollTo().performClick();ui.waitUntil(15000){!app.busy&&app.error.isNotBlank()}
            assertTrue(app.friends.isEmpty());ui.onNodeWithTag("create-step-confirm").assertExists();ui.onNodeWithTag("create-friend").assertIsEnabled()
        }finally{ui.runOnIdle{db.execSQL("PRAGMA query_only=OFF")}}
        ui.onNodeWithTag("create-friend").performScrollTo().performClick();ui.waitUntil(15000){app.page=="chat"}
        assertEquals(1,app.friends.size)
    }
    @Test fun repeatedConfirmationUsesOnlyOneCreationId(){
        confirm()
        val click=requireNotNull(ui.onNodeWithTag("create-friend").performScrollTo().fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action)
        val back=requireNotNull(ui.onNodeWithTag("create-back").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action)
        val cancel=requireNotNull(ui.onNodeWithTag("create-cancel").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action)
        ui.runOnIdle{repeat(8){click()};back();cancel()}
        ui.waitUntil(15000){app.page=="chat"&&!app.busy}
        assertEquals(1,runBlocking{app.processing.store.friends()}.size)
        ui.onNodeWithTag("create-keep-exit").assertDoesNotExist()
    }
    @Test fun committedRoleWithFailedReloadRetriesOpeningWithoutDuplicating(){
        confirm();val db=app.processing.store.writableDatabase
        ui.runOnIdle{db.execSQL("ALTER TABLE messages RENAME TO messages_creation_fault")}
        try{
            ui.onNodeWithTag("create-friend").performScrollTo().performClick();ui.waitUntil(15000){!app.busy&&app.savedCreationId!=null}
            assertEquals("角色已保存，打开未完成，请重试。",app.error)
            ui.onNodeWithText("打开已创建的角色").assertExists();ui.onNodeWithTag("create-back").assertIsNotEnabled()
            ui.onNodeWithTag("create-friend").performScrollTo().performClick()
            ui.waitUntil(15000){!app.busy&&app.savedCreationId!=null}
            assertEquals("角色已保存，打开未完成，请重试。",app.error)
            assertEquals(1,runBlocking{app.processing.store.friends()}.size)
        }finally{ui.runOnIdle{db.execSQL("ALTER TABLE messages_creation_fault RENAME TO messages")}}
        ui.onNodeWithTag("create-friend").performScrollTo().performClick();ui.waitUntil(15000){app.page=="chat"}
        assertEquals(1,runBlocking{app.processing.store.friends()}.size)
    }
    @Test fun smallViewportAndLongTextKeepTheNextActionReachable(){
        var local by mutableStateOf(RoleCreationDraft(step=RoleCreationStep.DEFINITION,name="小屏合成"))
        ui.activity.setContent{LianxuTheme{
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.6f)){
                Box(Modifier.size(280.dp,380.dp)){RoleCreationGuide(local,{local=it},false,onExit={},onCreate={})}
            }
        }}
        val text="长内容与表情😀。".repeat(700)
        ui.onNodeWithTag("friend-bio").performTextInput(text)
        ui.onNodeWithTag("create-next").performScrollTo().assertIsDisplayed().performClick()
        ui.onNodeWithTag("friend-relationship").assertExists();assertEquals(text,local.definition);shot("small-long-content")
    }
    @Test fun keyboardLeavesTheCurrentNextActionReachable(){
        enter();ui.onNodeWithTag("friend-name").performClick().performTextInput("键盘合成")
        ui.waitUntil(5000){ui.activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==true}
        ui.onNodeWithTag("create-next").performScrollTo().assertIsDisplayed()
        val keyboardTop=ui.activity.window.decorView.height-ui.activity.window.decorView.rootWindowInsets.getInsets(android.view.WindowInsets.Type.ime()).bottom
        assertTrue(ui.onNodeWithTag("create-next").fetchSemanticsNode().boundsInWindow.bottom<=keyboardTop+2f)
        shot("keyboard-name");next();ui.onNodeWithTag("friend-bio").assertIsDisplayed()
    }
    @Test fun existingUsersKeepTheirOriginalFormAndData(){
        ui.runOnIdle{app.open("create")}
        ui.onNodeWithTag("role-creation-guide").assertDoesNotExist()
        ui.onNodeWithTag("friend-name").performTextInput("已有用户新角色")
        ui.onNodeWithTag("friend-bio").performScrollTo().performTextInput("原表单仍可用")
        ui.onNodeWithTag("friend-expression").performScrollTo().performTextInput("简洁")
        ui.onNodeWithTag("friend-opening").performScrollTo().performTextInput("你好")
        ui.onNodeWithTag("friend-prompt").performScrollTo().performTextInput("保留自定义")
        ui.onNodeWithTag("create-friend").performScrollTo().performClick();ui.waitUntil(15000){app.friends.size==2&&!app.busy}
        assertTrue(app.friends.any{it.id=="keep-existing"&&it.bio=="不修改"})
        val role=app.friends.single{it.id!="keep-existing"};val p=runBlocking{app.rolePrompts(role.id)}
        assertEquals("简洁",p.expression);assertEquals("你好",p.opening);assertEquals("保留自定义",p.extra)
    }
    @Test fun aNewOrdinaryFormNeverReusesAnEarlierSavedCreation(){
        ui.runOnIdle{app.open("create")}
        ui.onNodeWithTag("friend-name").performTextInput("已保存的A")
        ui.onNodeWithTag("friend-bio").performScrollTo().performTextInput("A的设定")
        val db=app.processing.store.writableDatabase
        ui.runOnIdle{db.execSQL("ALTER TABLE messages RENAME TO messages_creation_fault")}
        try{
            ui.onNodeWithTag("create-friend").performScrollTo().performClick();ui.waitUntil(15000){!app.busy&&app.savedCreationId!=null}
            ui.onNodeWithText("打开已创建的角色").assertExists()
        }finally{ui.runOnIdle{db.execSQL("ALTER TABLE messages_creation_fault RENAME TO messages")}}
        // Simulate leaving through an external navigation, then open a genuinely new form.
        ui.runOnIdle{app.home()};ui.waitForIdle();ui.runOnIdle{app.open("create")}
        ui.onNodeWithTag("friend-name").performTextInput("新表单的B")
        ui.onNodeWithTag("friend-bio").performScrollTo().performTextInput("B的设定")
        ui.onNodeWithTag("create-friend").performScrollTo().performClick();ui.waitUntil(15000){!app.busy&&app.page=="chat"}
        val roles=runBlocking{app.processing.store.friends()}
        assertEquals(3,roles.size);assertTrue(roles.any{it.name=="已保存的A"});assertTrue(roles.any{it.name=="新表单的B"})
    }
}
