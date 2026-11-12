package app.lianxu.independent

import android.app.NotificationManager
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.TimeUnit

class CompanionFlowUiTest {
    @Volatile private var clock=1_800_000_000_000L
    @Volatile private var fail=false
    /** Bad private review must not prevent the already-visible reply. */
    @Volatile private var malformedRecord=false
    private val reviewCalls=java.util.concurrent.atomic.AtomicInteger()
    private fun experience(input:String)=org.json.JSONObject().put("quote",input).put("memory",input).put("emotion","calm").put("valence",0)
        .put("relationship","none").put("control","none").put("targets",org.json.JSONArray()).put("reconsider",org.json.JSONObject.NULL)
        .put("claimKind","interaction").put("controlIntent","none").put("requestQuote","")
    @get:Rule(order=0) val fixture=MockChatFixture(wrapFinalReply=false,clientFactory={object:TextChatClient{
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
            check(key=="synthetic-key-only")
            if(fail)return TextChatResult.Failure(ChatFailure.NETWORK)
            val system=request.messages.filter{it.role=="system"}.joinToString{it.content}
            if(system.contains("[companion.reconsider.v1]"))return TextChatResult.Success("""{"action":"send","reason":"合成事件已经结束，可以温和关心","text":"【合成回访】事情结束了，想说说今天吗？"}""")
            if(system.contains("[多轮后台回顾]")){
                reviewCalls.incrementAndGet()
                if(malformedRecord)return TextChatResult.Success("{\"records\":[]}")
                val rows=org.json.JSONArray(request.messages.last{it.role=="user"}.content);val records=org.json.JSONArray()
                for(i in 0 until rows.length()){
                    val row=rows.getJSONObject(i);val source=row.getLong("source")
                    val payload=org.json.JSONObject(RoleModelPayload.json(source)).put("experience",experience(row.getString("用户原话")))
                        .put("关系输入",org.json.JSONObject(RelationInputContract.example(source)))
                    records.put(org.json.JSONObject().put("source",source).put("record",payload))
                }
                return TextChatResult.Success(org.json.JSONObject().put("records",records).toString())
            }
            if(!malformedRecord&&ImmediateContact.NAME in request.tools.toString()&&request.messages.none{it.role=="tool"&&it.name==ImmediateContact.NAME}){
                val source=request.currentSourceTurn()
                val future=org.json.JSONObject().put("kind","follow_up").put("reason","合成事件结束后的关心").put("notBefore",clock+900_000L)
                    .put("evidence",org.json.JSONArray().put(org.json.JSONObject().put("source",source).put("layer","fact")))
                return TextChatResult.Success("",listOf(ChatToolCall("contact-${System.nanoTime()}",ImmediateContact.NAME,org.json.JSONObject().put("future",future).toString())))
            }
            return TextChatResult.Success("【合成回复】我记下了，愿你今天顺利。")
        }
    }})
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun vm()=ViewModelProvider(ui.activity)[AppState::class.java]
    private fun shell(command:String){android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use{it.readBytes()}}
    @Before fun prepare(){
        AppServices.companionClock={clock}
        shell("cmd statusbar collapse")
        ModelVault(context,fixture.namespace).setPurpose(ModelPurpose.CHAT,"fixture")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        ui.waitUntil(15000){vm().ready}
        runBlocking{vm().processing.saveMemoryRules(MemoryRules(reviewRounds=1))}
    }
    @After fun reset(){shell("cmd statusbar collapse");AppServices.companionClock=System::currentTimeMillis;WorkManager.getInstance(context).cancelAllWork();context.getSystemService(NotificationManager::class.java).cancelAll()}
    private fun shot(name:String){ui.waitForIdle();val automation=InstrumentationRegistry.getInstrumentation().uiAutomation;automation.waitForIdle(100,3000);Thread.sleep(350);val folder=File(context.getExternalFilesDir(null),"companion-phase1-evidence").apply{mkdirs()};automation.takeScreenshot().let{b->File(folder,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
    private fun open(){ui.runOnIdle{vm().open("chat","seed-lin")}}
    /** 陪伴与消息从聊天顶栏「⋯」进角色资料，再点「陪伴与消息」那一行。 */
    private fun openCompanionPage(){open();ui.onNodeWithTag("chat-role-detail").performClick();ui.onNodeWithTag("role-companion-open").performScrollTo().performClick();ui.onNodeWithTag("companion-page").assertExists()}
    private fun waitForEnabledCompanionSwitch(){
        var lastFailure:Throwable?=null
        try{ui.waitUntil(10000){try{ui.onNodeWithTag("companion-enabled").assertIsEnabled();true}catch(e:AssertionError){lastFailure=e;false}}}
        catch(e:Throwable){
            val folder=File(context.getExternalFilesDir(null),"companion-phase1-evidence").apply{mkdirs()}
            val tree=runCatching{ui.onAllNodes(isRoot(),useUnmergedTree=true).printToString()}.getOrElse{"Semantics unavailable: $it"}
            File(folder,"ui-switch-failure.txt").writeText("page=${vm().page}; role=${vm().activeId}; ready=${vm().ready}; policy=${vm().companionPolicy}\nLast assertion: ${lastFailure?.stackTraceToString()}\nOriginal: ${e.stackTraceToString()}\n$tree")
            runCatching{shot("ui-switch-failure")}
            throw AssertionError("Companion switch did not become enabled; original assertion: ${lastFailure?.message}",e)
        }
    }
    private fun assertVisibleOrRecord(text:String,name:String){
        try{ui.onNodeWithText(text).assertIsDisplayed()}
        catch(e:AssertionError){
            val folder=File(context.getExternalFilesDir(null),"companion-phase1-evidence").apply{mkdirs()}
            fun bounds(tag:String)=runCatching{ui.onNodeWithTag(tag,useUnmergedTree=true).fetchSemanticsNode().let{"${it.boundsInWindow}"}}.getOrElse{"unavailable: ${it.message}"}
            val target=runCatching{ui.onNodeWithText(text,useUnmergedTree=true).fetchSemanticsNode().boundsInWindow.toString()}.getOrElse{"unavailable: ${it.message}"}
            val tree=runCatching{ui.onRoot(useUnmergedTree=true).printToString()}.getOrElse{"Semantics unavailable: $it"}
            File(folder,"$name.txt").writeText("target=$target\nhistory=${bounds("chat-history")}\ninput=${bounds("message-input")}\nturns=${vm().turns}\nOriginal: ${e.stackTraceToString()}\n$tree")
            runCatching{shot(name)}
            throw e
        }
    }
    /** 旧版这里是「理解格式坏了就整轮失败」；现行流程记录坏了不让这轮失败：原话保留、照常回复，但不留经历、不安排回访、不写记忆。 */
    @Test fun malformedRecordsKeepTheInputAndStillReplyWithoutAnExperience(){
        malformedRecord=true
        val store=Store(context,fixture.database);val repo=CompanionRepository(store){clock}
        runBlocking{ProcessingRepository(store).saveMemoryRules(MemoryRules(proactiveRecall=true,immediateWrite=true,reviewRounds=1))}
        open();ui.onNodeWithTag("message-input").performTextInput("【合成查询】请帮我想想以前聊过什么")
        ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        try{ui.waitUntil(20000){vm().turns.any{it.state==TurnState.COMPLETED}}}
        catch(e:Throwable){throw AssertionError("turns=${vm().turns}",e)}
        assertFalse(vm().turns.any{it.state==TurnState.FAILED})
        ui.waitUntil(20000){reviewCalls.get()>=3}
        assertTrue(runBlocking{repo.experiences("seed-lin").isEmpty()});assertTrue(runBlocking{repo.queued().isEmpty()})
        assertTrue(runBlocking{ProcessingRepository(store).memories("seed-lin").isEmpty()})
        assertTrue(vm().messages.any{it.mine&&"合成查询" in it.text});assertTrue(vm().messages.any{"【合成回复】" in it.text})
        assertVisibleOrRecord("【合成回复】我记下了，愿你今天顺利。","ui-malformed-record-reply-visibility")
        shot("07-malformed-record-still-replies");store.close()
    }
    /** 回访真失败过：陪伴与消息页出「上次没发出去」卡（真连不上才说网络连不上），开关下面一行出「重新安排未完成的回访」，ⓘ 一句解释，点了重新排上。 */
    @Test fun aFailedContactShowsTheCardAndTheRetryRow(){
        openCompanionPage();waitForEnabledCompanionSwitch();ui.onNodeWithTag("companion-enabled").performClick()
        ui.waitUntil(10000){vm().companionPolicy=="enabled"}
        ui.onNodeWithTag("companion-background-close").performClick()
        open();ui.onNodeWithTag("message-input").performTextInput("【合成测试】今天做一个练习面试")
        ui.onNodeWithTag("send-message").performClick()
        ui.waitUntil(20000){vm().turns.any{it.state==TurnState.COMPLETED}}
        val store=Store(context,fixture.database);val repo=CompanionRepository(store){clock}
        val opportunity=runBlocking{repo.queued()}.single{it.reason=="合成事件结束后的关心"}
        ui.runOnIdle{vm().home()};clock+=900_000L;fail=true
        val work=OneTimeWorkRequestBuilder<CompanionWorker>().setInputData(workDataOf("opportunity" to opportunity.id)).build()
        WorkManager.getInstance(context).enqueue(work).result.get(15,TimeUnit.SECONDS)
        ui.waitUntil(25000){runBlocking{repo.latestContactFailure("seed-lin")}!=null}
        assertEquals(ContactFailureKind.NETWORK,runBlocking{repo.latestContactFailure("seed-lin")}!!.kind)
        fail=false
        openCompanionPage()
        ui.waitUntil(10000){ui.onAllNodesWithTag("companion-retry").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("companion-last-failure-summary",useUnmergedTree=true).assertTextContains("网络连不上",substring=true)
        assertTrue(ui.onAllNodesWithText("不保证准时",substring=true).fetchSemanticsNodes().isEmpty())
        shot("08-retry-row")
        ui.onNodeWithTag("companion-retry-info").performClick()
        ui.onNodeWithTag("companion-retry-hint",useUnmergedTree=true).assertTextEquals(CompanionHints.RETRY)
        shot("09-retry-hint")
        shell("input keyevent 4");ui.waitUntil(5000){ui.onAllNodesWithTag("companion-retry-hint").fetchSemanticsNodes().isEmpty()}
        ui.onNodeWithTag("companion-retry").performClick()
        ui.waitUntil(10000){runBlocking{repo.queued()}.any{it.id==opportunity.id}}
        ui.waitUntil(10000){ui.onAllNodesWithTag("companion-retry").fetchSemanticsNodes().isEmpty()}
        store.close()
    }
    @Test fun changingRolesWhileUpdatingPermissionKeepsTheOriginalTarget(){
        ui.runOnIdle{vm().open("chat","seed-lin");vm().setCompanionEnabled(true);vm().open("chat","seed-an")}
        ui.waitUntil(10000){runBlocking{vm().companion.policy("seed-lin")=="enabled"}}
        assertEquals("disabled",runBlocking{vm().companion.policy("seed-an")})
    }
    @Test fun offscreenHistoryAndAnotherRoleStayUnreadUntilActuallyVisible(){
        val store=Store(context,fixture.database);val repo=CompanionRepository(store){clock}
        val ids=runBlocking{
            val friend=store.friends().single{it.id=="seed-lin"}
            (1..12).map{index->
                val body=if(index==12)"【合成长消息】"+"这是长消息的正文，仅用于可见性测试。\n".repeat(120)else"【合成历史$index】"+"保留屏外未读。".repeat(16)
                store.exchange(friend,"合成输入$index",body)
                val message=store.messages().last()
                store.writableDatabase.insertOrThrow("companion_receipts",null,android.content.ContentValues().apply{put("message_id",message.id);put("role",friend.id);put("delivery","chat")})
                message.id
            }
        }
        ui.runOnIdle{vm().foreground()}
        ui.waitUntil(10000){vm().messages.any{it.id==ids.last()}}
        open()
        ui.waitUntil(10000){runBlocking{repo.receipts("seed-lin").any{it.readAt!=null}}}
        assertNull(runBlocking{repo.receipts("seed-lin").single{it.messageId==ids.last()}.readAt})
        ui.runOnIdle{vm().open("chat","seed-an")}
        assertNull(runBlocking{repo.receipts("seed-lin").single{it.messageId==ids.last()}.readAt})
        open()
        val targetIndex=chatTimeline(vm().messages.filter{it.friendId=="seed-lin"}).indexOfFirst{it is ChatRow.Group&&it.value.messages.any{m->m.id==ids.last()}}
        ui.onNodeWithTag("chat-history").performScrollToIndex(targetIndex)
        ui.waitUntil(10000){runBlocking{repo.receipts("seed-lin").single{it.messageId==ids.last()}.readAt!=null}}
        assertTrue(runBlocking{repo.receipts("seed-lin").any{it.readAt==null}})
        shot("05-long-message-visible-offscreen-history-unread")
        store.close()
    }
    @Test fun realChatWorkerNotificationTargetAndVisibleReceipt(){
        openCompanionPage()
        waitForEnabledCompanionSwitch()
        ui.onNodeWithTag("companion-enabled").performClick()
        ui.waitUntil(10000){vm().companionPolicy=="enabled"}
        ui.onNodeWithTag("companion-background-hint").assertExists()
        ui.onNodeWithText(CompanionHints.BACKGROUND).assertExists()
        ui.onNodeWithTag("companion-background-close").performClick()
        ui.onNodeWithTag("companion-background-hint").assertDoesNotExist()
        ui.onNodeWithTag("companion-page").assertExists()
        assertEquals("enabled",vm().companionPolicy);shot("00-companion-controls")
        // 页面只留两个开关；经历概览不再显示在页上，直接核对本地记录。
        assertFalse(runBlocking{vm().companion.snapshot("seed-lin").overview}.contains("disabled"))
        open()
        ui.onNodeWithTag("message-input").performTextInput("【合成测试】今天做一个练习面试")
        ui.onNodeWithTag("send-message").performClick()
        try{ui.waitUntil(20000){vm().turns.any{it.state==TurnState.COMPLETED}}}
        catch(e:Throwable){
            val folder=File(context.getExternalFilesDir(null),"companion-phase1-evidence").apply{mkdirs()}
            File(folder,"ui-reply-failure.txt").writeText("page=${vm().page}; role=${vm().activeId}; ready=${vm().ready}; busy=${vm().busy}; error=${vm().error}\nturns=${vm().turns}\nmessages=${vm().messages}\n${ui.onAllNodes(isRoot(),useUnmergedTree=true).printToString()}")
            runCatching{shot("ui-reply-failure")};throw e
        }
        shot("01-real-chat-synthetic-model")
        openCompanionPage()
        ui.waitUntil(10000){vm().companionPolicy=="enabled"}
        ui.waitUntil(10000){runBlocking{vm().companion.snapshot("seed-lin").overview}.contains("【合成测试】今天做一个练习面试")}
        shot("06-companion-page-after-chat")
        open()
        val store=Store(context,fixture.database);val repo=CompanionRepository(store){clock}
        // 评估里定的回访之外，关系输入也会另排一个关系主动联系机会；这里只跑评估定的那一个。
        val opportunity=runBlocking{repo.queued()}.single{it.reason=="合成事件结束后的关心"}
        // Close the chat before a real WorkManager execution window, and advance only the injected clock.
        ui.runOnIdle{vm().home()};clock+=900_000
        val work=OneTimeWorkRequestBuilder<CompanionWorker>().setInputData(workDataOf("opportunity" to opportunity.id)).build()
        WorkManager.getInstance(context).enqueue(work).result.get(15,TimeUnit.SECONDS)
        ui.waitUntil(25000){runBlocking{repo.receipts("seed-lin").any{it.requestedAt!=null}}}
        val receipt=runBlocking{repo.receipts("seed-lin").single{it.requestedAt!=null}}
        assertNull(receipt.readAt)
        val manager=context.getSystemService(NotificationManager::class.java)
        ui.waitUntil(10000){manager.activeNotifications.any{it.tag==CompanionNotifications.TAG&&it.id==receipt.messageId.toInt()}}
        val active=manager.activeNotifications.single{it.tag==CompanionNotifications.TAG}
        assertEquals(receipt.messageId.toInt(),active.id)
        shell("cmd statusbar expand-notifications")
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300,5000)
        shot("02-worker-committed-notification-unread")
        assertNull(runBlocking{repo.receipts("seed-lin").single{it.messageId==receipt.messageId}.readAt})
        shell("cmd statusbar collapse")
        active.notification.contentIntent.send()
        ui.waitUntil(15000){vm().page=="chat"&&vm().activeId=="seed-lin"}
        ui.onNodeWithTag("message-${receipt.messageId}").assertIsDisplayed()
        ui.waitUntil(10000){runBlocking{repo.receipts("seed-lin").single{it.messageId==receipt.messageId}.readAt!=null}}
        ui.waitUntil(10000){vm().companionReceipts.firstOrNull{it.messageId==receipt.messageId}?.readAt!=null}
        assertNotNull(runBlocking{repo.receipts("seed-lin").single{it.messageId==receipt.messageId}.clickedAt})
        ui.onNodeWithTag("read-${receipt.messageId}").assertTextEquals("已读")
        shot("03-notification-opens-own-message-read")
        ui.runOnIdle{vm().open("chat","seed-an")};ui.activityRule.scenario.recreate()
        ui.waitUntil(10000){vm().page=="chat"&&vm().activeId=="seed-an"}
        open();ui.onNodeWithTag("chat-history").performScrollToNode(hasTestTag("message-${receipt.messageId}"))
        ui.onNodeWithTag("message-${receipt.messageId}").performTouchInput{longClick()}
        shot("04-source-controls-dialog")
        ui.onNodeWithTag("companion-forget").performClick()
        ui.waitUntil(10000){runBlocking{!repo.snapshot("seed-lin").prompt.contains("今天做一个练习面试")}}
        assertTrue(runBlocking{store.messages().any{it.text.contains("今天做一个练习面试")}})
        ui.onNodeWithTag("companion-forget").assertDoesNotExist()
        shot("04-forgotten-source-original-chat-preserved")
        store.close()
    }
}
