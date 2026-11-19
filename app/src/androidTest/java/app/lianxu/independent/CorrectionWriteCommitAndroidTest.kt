package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Isolated SQLite and production runner/tool transactions with a synthetic client; never HTTP. */
class CorrectionWriteCommitAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String;private lateinit var store:Store;private lateinit var repo:ProcessingRepository
    private lateinit var vault:ModelVault;private lateinit var scope:CoroutineScope;private lateinit var runner:ConversationRunner
    private var sequence=0
    private lateinit var respond:suspend(TextChatRequest)->TextChatResult
    private val old="我长期喜欢桂花乌龙"
    private val fresh="我其实长期喜欢茉莉白茶"
    private val input="更正旧偏好，$fresh，请停用旧来源并记住新偏好"
    private val client=object:TextChatClient {
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest)=respond(request)
    }
    @Before fun prepare():Unit=runBlocking {
        name="correction-commit-${System.nanoTime()}";store=Store(context,"$name.db");repo=ProcessingRepository(store)
        store.add(listOf(Friend("a","合成角色","仅测试")),false)
        store.savePreference(MemoryRules.KEY,MemoryRules(recentRounds=40,summaryRounds=20,immediateWrite=true).json().put("version",1).toString())
        vault=ModelVault(context,name,store)
        vault.save(ModelConfig("fake","Synthetic","custom","https://example.invalid/v1","fake",64000,4096),"synthetic-only")
        vault.setPurpose(ModelPurpose.CHAT,"fake")
        scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        runner=ConversationRunner(scope,store,vault,client,repo,MemoryEngine(scope,repo,ModelAccess(vault),client,{}),{},{})
    }
    @After fun close():Unit=runBlocking {
        scope.cancel();scope.coroutineContext[Job]?.join()
        vault.list().forEach{vault.delete(it.id)};store.close();assertTrue(context.deleteDatabase("$name.db"));assertFalse(context.getDatabasePath("$name.db").exists());Unit
    }
    private fun call(name:String,args:JSONObject):TextChatResult=TextChatResult.Success("",listOf(ChatToolCall("call-${++sequence}",name,args.toString())))
    private fun add(content:String,quote:String=content,evidence:String="S1")=call("agent_memory_update",JSONObject().put("op","add").put("content",content)
        .put("quote",quote).put("evidence",JSONArray().put(evidence)).put("category","PREFERENCE_BOUNDARY").put("about","USER"))
    private fun control(action:String,target:Long,quote:String)=call(JointControl.NAME,JSONObject().put("action",action).put("targets",JSONArray().put(target)).put("quote",quote).put("authorization","current_direct_request"))
    private fun result(request:TextChatRequest,name:String)=JSONObject(request.messages.last{it.role=="tool"&&it.name==name}.content)
    private fun staged(request:TextChatRequest){val r=result(request,"agent_memory_update");assertTrue(r.getBoolean("success"));assertEquals("staged_until_reply_commit",r.getString("status"))}
    private fun applied(request:TextChatRequest,action:String){val r=result(request,JointControl.NAME);assertTrue(r.getBoolean("success"));assertEquals(action,r.getString("action"))}
    private suspend fun until(predicate:suspend()->Boolean)=withTimeout(15000){while(!predicate())delay(10)}
    private suspend fun send(text:String):ChatTurn {
        val count=ChatLedger(store).all().size
        withContext(Dispatchers.Main){assertTrue(runner.send("a",text){})}
        until{val turns=ChatLedger(store).all();turns.size==count+1&&turns.last().state!=TurnState.PENDING&&withContext(Dispatchers.Main){"a" !in runner.sending}}
        return ChatLedger(store).all().last()
    }
    private suspend fun seed():Long {
        var step=0;respond={request->if(step++==0)add(old)else{staged(request);TextChatResult.Success("记下了")}}
        val turn=send(old);assertEquals(TurnState.COMPLETED,turn.state)
        assertEquals(old,repo.memories("a").single{it.valid}.text)
        return turn.userMessageId
    }
    private suspend fun assertOldStoppedAndNewAbsent() {
        assertTrue(repo.memories("a").none{it.valid})
        assertTrue(repo.candidates("a").none{it.valid})
        assertEquals(old,store.messagesFor("a").first{it.mine}.text)
        assertTrue(ChatLedger(store).all().last().toolActivities.none{it.tool=="agent_memory_update"&&it.state=="success"})
    }
    @Test fun correctionThenNewWriteCommitsAndLinksOnlyTheNewMemory()=runBlocking {
        val target=seed();var step=0
        respond={request->when(step++){0->control("correct",target,input);1->{applied(request,"correct");add(fresh)};else->{staged(request);TextChatResult.Success("更正并记住了")}}}
        val turn=send(input);assertEquals(TurnState.COMPLETED,turn.state)
        val memory=repo.memories("a").single{it.valid}
        assertEquals(fresh,memory.text);assertEquals(turn.userMessageId,memory.evidence.single().source.turnId)
        assertFalse(repo.memories("a").first{it.text==old}.valid)
        val activity=turn.toolActivities.single{it.tool=="agent_memory_update"}
        assertEquals("success",activity.state);assertEquals(memory.id,activity.memoryId)
    }
    @Test fun controlDiscardsTheEarlierOldStageButKeepsTheLaterFreshStage()=runBlocking {
        val target=seed();var step=0
        respond={request->when(step++){0->add(old,old,"S2");1->{staged(request);control("correct",target,input)};2->{applied(request,"correct");add(fresh)};else->{staged(request);TextChatResult.Success("更正完成")}}}
        val turn=send(input);assertEquals(TurnState.COMPLETED,turn.state)
        assertEquals(listOf(fresh),repo.memories("a").filter{it.valid}.map{it.text})
        val writes=turn.toolActivities.filter{it.tool=="agent_memory_update"}
        assertEquals(2,writes.size);assertEquals("failed",writes[0].state);assertNull(writes[0].memoryId)
        assertEquals("success",writes[1].state);assertNotNull(writes[1].memoryId)
    }
    @Test fun theSameStageRepeatedAfterControlCannotLinkTheEarlierFailedCapsule()=runBlocking {
        val target=seed();var step=0
        respond={request->when(step++){0->add(fresh);1->{staged(request);control("correct",target,input)};2->{applied(request,"correct");add(fresh)};else->{staged(request);TextChatResult.Success("只保留更正后的写入")}}}
        val turn=send(input);assertEquals(TurnState.COMPLETED,turn.state)
        assertEquals(listOf(fresh),repo.memories("a").filter{it.valid}.map{it.text})
        val writes=turn.toolActivities.filter{it.tool=="agent_memory_update"};assertEquals(2,writes.size)
        assertEquals(writes[0].operation,writes[1].operation)
        assertEquals("failed",writes[0].state);assertNull(writes[0].memoryId)
        assertEquals("success",writes[1].state);assertNotNull(writes[1].memoryId)
    }
    @Test fun forgettingDiscardsStagesAndRejectsWritingTheBlockedSourceAgain()=runBlocking {
        val target=seed();val text="请忘掉旧偏好，原聊天保留";var step=0
        respond={request->when(step++){0->add(old,old,"S2");1->{staged(request);control("forget",target,text)};else->{
            applied(request,"forget")
            assertFalse(request.tools.any{toolNameOf(it)=="agent_memory_update"})
            assertFalse(request.messages.any{"[记忆工具编号]" in it.content})
            TextChatResult.Success("已停用")}}}
        val turn=send(text);assertEquals(TurnState.COMPLETED,turn.state)
        assertFalse(CompanionRepository.aiAllowed(store.readableDatabase,"a",target))
        assertFalse(CompanionRepository.aiAllowed(store.readableDatabase,"a",turn.userMessageId))
        assertOldStoppedAndNewAbsent()
    }
    @Test fun aFailedReplyAfterCorrectionDoesNotCommitTheNewStage()=runBlocking {
        val target=seed();var step=0
        respond={request->when(step++){0->control("correct",target,input);1->{applied(request,"correct");add(fresh)};else->{staged(request);TextChatResult.Failure(ChatFailure.NETWORK)}}}
        assertEquals(TurnState.FAILED,send(input).state);assertOldStoppedAndNewAbsent()
    }
    @Test fun aCancelledReplyAfterCorrectionDoesNotCommitEvenIfTheClientReturnsLate()=runBlocking {
        val target=seed();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var step=0
        respond={request->when(step++){0->control("correct",target,input);1->{applied(request,"correct");add(fresh)};else->{staged(request);withContext(NonCancellable){entered.complete(Unit);release.await();TextChatResult.Success("迟到回复")}}}}
        try {
            withContext(Dispatchers.Main){assertTrue(runner.send("a",input){})}
            withTimeout(15000){entered.await()}
            withContext(Dispatchers.Main){runner.cancel("a")};release.complete(Unit)
            until{ChatLedger(store).all().last().state==TurnState.CANCELLED&&withContext(Dispatchers.Main){"a" !in runner.sending}}
            assertEquals(TurnState.CANCELLED,ChatLedger(store).all().last().state)
            assertOldStoppedAndNewAbsent()
        }finally{release.complete(Unit)}
    }
}
