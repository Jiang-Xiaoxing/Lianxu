package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Isolated store and fake transport; exercises the real reply/tool loop, never a provider. */
class ForgetReplyAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store;private lateinit var repo:ProcessingRepository;private lateinit var core:CompanionRepository
    private lateinit var vault:ModelVault;private lateinit var scope:CoroutineScope;private lateinit var name:String
    private val oldText="合成旧事：我喜欢紫色风筝"
    private val input="请忘记我喜欢紫色风筝这件事。我们继续聊今天的天气吧。"
    private var target=0L
    @Before fun setup()=runBlocking {
        name="forget-reply-${System.nanoTime()}";store=Store(context,"$name.db");repo=ProcessingRepository(store);core=CompanionRepository(store)
        store.add(listOf(Friend("a","合成甲","成年合成角色")),false);repo.saveMemoryRules(MemoryRules(immediateWrite=true))
        vault=ModelVault(context,name);vault.save(ModelConfig("fake","合成模型","custom","https://example.invalid","fake",64000,4096),"synthetic-only");vault.setPurpose(ModelPurpose.CHAT,"fake")
        scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val ledger=ChatLedger(store);val t=ledger.begin("a",oldText,"fake");target=t.userMessageId
        val head=core.snapshot("a");val p=ExperienceProposal(quote=oldText,memory=oldText)
        assertTrue(core.accept(t,p,head){true});assertTrue(ledger.finish(t.id,t.attempt,"听到了"));repo.tx{core.finish(it,t,head,p)}
        val evidence=MemoryEvidence(SourceRef("a",target,1),"user",oldText,oldText,System.currentTimeMillis(),"Asia/Shanghai")
        repo.tx{repo.saveMemory(it,StoredMemory("old","a",MemoryCategory.STATE_FACT,oldText,evidence=listOf(evidence),about=MemoryAbout.USER))}
    }
    @After fun close()=runBlocking {
        scope.coroutineContext.job.cancelAndJoin();vault.list().forEach{vault.delete(it.id)};store.close();context.deleteDatabase("$name.db");Unit
    }
    private fun runner(reply:suspend(TextChatRequest)->TextChatResult):ConversationRunner {
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest)=reply(request)}
        return ConversationRunner(scope,store,vault,client,repo,MemoryEngine(scope,repo,ModelAccess(vault),client,{}),{},{})
    }
    private fun forget(id:Long=target)=TextChatResult.Success("",listOf(ChatToolCall("forget",JointControl.NAME,
        JSONObject().put("action","forget").put("quote","请忘记我喜欢紫色风筝这件事").put("targets",org.json.JSONArray().put(id)).put("authorization","current_direct_request").toString())))
    private fun result(request:TextChatRequest,name:String)=request.messages.lastOrNull{it.role=="tool"&&it.name==name}?.let{JSONObject(it.content)}
    private fun outcome(request:TextChatRequest,success:Boolean):String {
        val system=request.messages.filter{it.role=="system"}.joinToString("\n"){it.content}
        assertEquals(1,Regex.fromLiteral(ControlPresentation.LABEL).findAll(system).count())
        assertEquals(!success,system.contains(ControlPresentation.NOT_COMPLETED))
        assertFalse(system.contains("本轮已执行工具回执"));assertFalse(system.contains("\"status\":\"applied\""))
        return system
    }
    private suspend fun finished(r:ConversationRunner,attempt:Int=1)=withTimeout(10000){
        while(true){val t=ChatLedger(store).all().last();if(t.userMessageId!=target&&t.attempt==attempt&&t.state!=TurnState.PENDING&&"a" !in r.sending)return@withTimeout t;delay(10)}
        error("unreachable")
    }
    @Test fun originalWordsSurviveForgetAndInvalidCurrentSourceCannotBeStaged()=runBlocking {
        var calls=0
        val r=runner{request->
            calls++;assertEquals(input,request.latestUserText())
            assertFalse(request.messages.any{"用户已明确要求停止使用指定经历；操作已在手机完成，只确认结果。" in it.content})
            when(calls){
                1->{outcome(request,false);forget()}
                2->{outcome(request,true);assertEquals(true,result(request,JointControl.NAME)!!.getBoolean("success"));TextChatResult.Success("",listOf(ChatToolCall("rewrite","agent_memory_update","""{"op":"add","category":"STATE_FACT","about":"USER","content":"我喜欢紫色风筝","evidence":["S1"]}""")))}
                else->{assertEquals(false,result(request,"agent_memory_update")!!.getBoolean("success"));assertEquals("SOURCE_CHANGED",result(request,"agent_memory_update")!!.getString("error"));TextChatResult.Success("好，我们聊今天的天气。")}
            }
        }
        assertTrue(r.send("a",input){});val t=finished(r)
        assertEquals(TurnState.COMPLETED,t.state);assertEquals(3,calls)
        assertEquals("success",t.toolActivities.single{it.tool==JointControl.NAME}.state)
        assertEquals("failed",t.toolActivities.single{it.tool=="agent_memory_update"}.state)
        assertEquals(input,store.messages().single{it.id==t.userMessageId}.text)
        assertTrue(repo.sources("a").isEmpty());assertTrue(core.experiences("a").isEmpty())
        assertFalse(repo.memories("a").single().valid)
        assertFalse(repo.read{ReplyAccountingQueue.valid(it,ReplyAccountingQueue.pending(it,"a").single())})
    }
    @Test fun rejectedControlKeepsOriginalAndReportsFailureWithoutMutation()=runBlocking {
        var calls=0
        val r=runner{request->calls++;assertEquals(input,request.latestUserText())
            if(calls==1)forget(999999)else{
                assertEquals(false,result(request,JointControl.NAME)!!.getBoolean("success"))
                outcome(request,false)
                TextChatResult.Success("还没找到那条记录，这次没有忘记操作。")
            }
        }
        assertTrue(r.send("a",input){});val t=finished(r)
        assertEquals(TurnState.COMPLETED,t.state);assertEquals(2,calls)
        assertEquals("failed",t.toolActivities.single().state)
        assertTrue(repo.memories("a").single().valid);assertEquals(oldText,core.experiences("a").single().text)
    }
    @Test fun retryReadsOriginalAndReusesSuccessEvenWhenModelCallsAgain()=runBlocking {
        var calls=0;var retry=false
        val r=runner{request->calls++;assertEquals(input,request.latestUserText())
            when{
                calls==1->forget()
                !retry->TextChatResult.Failure(ChatFailure.NETWORK)
                result(request,JointControl.NAME)==null->{outcome(request,true);forget()}
                else->{assertEquals(true,result(request,JointControl.NAME)!!.getBoolean("success"));TextChatResult.Success("好，我们继续聊。")}
            }
        }
        assertTrue(r.send("a",input){});val failed=finished(r);assertEquals(TurnState.FAILED,failed.state)
        store.writableDatabase.execSQL("CREATE TRIGGER forbid_repeat_control BEFORE INSERT ON companion_blocks BEGIN SELECT RAISE(ABORT,'control must not execute twice'); END")
        retry=true;r.retry(failed);assertEquals(TurnState.COMPLETED,finished(r,2).state)
        assertEquals(4,calls);assertFalse(repo.memories("a").single().valid);assertTrue(core.experiences("a").isEmpty())
    }
    private suspend fun cachedResult(action:String,words:String,expected:String){
        val ledger=ChatLedger(store);val t=ledger.begin("a",words,"fake")
        val args=JSONObject().put("action",action).put("quote",words).put("authorization","current_direct_request").put("targets",org.json.JSONArray().put(target))
        val p=JointControl.parse(args,words,System.currentTimeMillis(),setOf(target),null)
        assertTrue(core.accept(t,p,core.snapshot("a")){true});ledger.fail(t.id,t.attempt,ChatFailure.NETWORK)
        var calls=0;val r=runner{request->calls++;assertEquals(words,request.latestUserText());assertTrue(outcome(request,true).contains(expected));TextChatResult.Success("好，接着聊。")}
        r.retry(ledger.all().last());assertEquals(TurnState.COMPLETED,finished(r,2).state);assertEquals(1,calls)
    }
    @Test fun cachedCorrectionDoesNotClaimNewMemoryWasWritten()=runBlocking {cachedResult("correct","更正之前那件事，我现在不喜欢了","以实际写入结果为准")}
    @Test fun cachedStopReminderDoesNotClaimTheExperienceWasForgotten()=runBlocking {cachedResult("stop_reminding","别再提醒那件事了","这次没有执行遗忘")}
}
