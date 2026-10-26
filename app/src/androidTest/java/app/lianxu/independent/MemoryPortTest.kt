package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate

/** Synthetic stores and credentials only. No production database, phone or provider is used. */
class MemoryPortTest {
    private val c get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store;private lateinit var repo:ProcessingRepository;private lateinit var vault:ModelVault
    private lateinit var dbName:String;private lateinit var scope:CoroutineScope
    private val config=ModelConfig("chat","Synthetic","custom","https://example.invalid/v1","mock",64000,2048)
    @Before fun setup()=runBlocking{
        dbName="port-${System.nanoTime()}.db";store=Store(c,dbName);repo=ProcessingRepository(store)
        store.add(listOf(Friend("a","A","Synthetic"),Friend("b","B","Synthetic")),false)
        vault=ModelVault(c,"port-${System.nanoTime()}");vault.save(config,"synthetic-only");vault.setPurpose(ModelPurpose.CHAT,config.id)
        scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    }
    @After fun close():Unit=runBlocking{scope.coroutineContext.job.cancelAndJoin();vault.list().forEach{vault.delete(it.id)};store.close();c.deleteDatabase(dbName)}
    private fun client(block:suspend(TextChatRequest)->TextChatResult)=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest)=block(request)}
    private suspend fun completed(input:String="garden",friend:String="a",reply:String="Synthetic reply"):SourceDialogue{
        val l=ChatLedger(store);val t=l.begin(friend,input,config.id);assertTrue(l.finish(t.id,1,reply));return repo.sources(friend).last()
    }
    private fun summaryOp(evidence:String="S1",id:String="one",text:String="我记得对方安排了下周三散步。")=JSONObject().put("id",id).put("op","ADD").put("target","N1").put("category","EXPERIENCE_MEMORY").put("about","USER").put("text",text).put("core",false).put("date_expression","下周三").put("evidence",JSONArray().put(evidence))
    private fun receipt(d:JSONObject,ops:JSONArray)=TextChatResult.Success(JSONObject().put("batch_id",d.getString("batch_id")).put("character_id","a").put("operations",ops).put("operation_count",ops.length()).put("complete",true).put("end_marker",d.getString("batch_id")).toString())
    private fun args(op:String="add",target:String?=null,text:String="我记得对方喜欢花园。")=JSONObject().put("op",op).put("category","PREFERENCE_BOUNDARY").put("about","USER").put("content",text).put("evidence",JSONArray().put("S1")).apply{target?.let{put("target",it)}}
    private fun request(input:String)=TextChatRequest(listOf(WireMessage("system","Synthetic rules"),WireMessage("user",input),WireMessage("system","Synthetic time")))
    private suspend fun runtime(turn:ChatTurn)=MemoryTools(repo,turn,ChatLedger(store).context(turn.id).input,repo.references(turn.friendId,ChatLedger(store).context(turn.id).input,turn.userMessageId)){true}
    private suspend fun write(tools:MemoryTools,turn:ChatTurn,arguments:JSONObject,fail:Boolean=false):TextChatResult{
        var n=0
        return tools.run(config,"synthetic-only",request(ChatLedger(store).context(turn.id).input),client{r->
            if(n++==0)TextChatResult.Success("",listOf(ChatToolCall("call-1","agent_memory_update",arguments.toString())))
            else {assertTrue(r.messages.any{it.role=="tool"});if(fail)TextChatResult.Failure(ChatFailure.NETWORK) else TextChatResult.Success("Synthetic final reply")}
        })
    }
    private suspend fun commit(turn:ChatTurn,tools:MemoryTools)=ChatLedger(store).finishWithMemory(turn.id,turn.attempt,"Synthetic final reply",onCompleted={tools.commit(it)})
    private suspend fun automatic():StoredMemory{
        val source=completed("garden preference")
        val m=StoredMemory("auto","a",MemoryCategory.PREFERENCE_BOUNDARY,"garden preference",about=MemoryAbout.USER,evidence=listOf(MemoryEvidence(source.source,"user",source.user.text,source.user.text,source.user.created,source.zone)))
        repo.tx{repo.saveMemory(it,m)};return m
    }
    @Test fun summaryFallbackShortEvidenceAuxiliaryAndFortyTwentyAreConnected()=runBlocking{
        val old=repo.manualSave("a",null,"garden plan",MemoryCategory.STATE_FACT,false,true,MemoryAbout.USER)
        repeat(41){completed("下周三去花园散步 $it")};val first=repo.sources("a").first();var calls=0
        val e=MemoryEngine(scope,repo,ModelAccess(vault),client{r->
            calls++;val d=JSONObject(r.messages.last().content)
            assertEquals(20,d.getJSONArray("target_dialogue").length());assertEquals(40,d.getJSONArray("target_sources").length())
            assertTrue(d.getJSONArray("auxiliary_dialogue").length() in 1..6)
            val existing=d.getJSONArray("existing_memories");assertTrue((0 until existing.length()).any{existing.getJSONObject(it).getString("id")==old.id&&existing.getJSONObject(it).getString("ref").startsWith("M")})
            assertTrue(r.messages.first().content.contains("第一人称"))
            receipt(d,JSONArray().put(summaryOp().put("model_date","1900-01-01")))
        },{})
        assertTrue(e.ensureRoom("a").success);assertEquals(1,calls);assertEquals(21,repo.recentSources("a").size)
        val m=repo.memories("a").first{!it.manual};assertEquals(MemoryDates.resolve("下周三",first.user.created,first.zone),m.eventDate)
        assertEquals(first.user.text,m.evidence.single().snapshot);assertEquals(first.source,m.evidence.single().source)
    }
    @Test fun badItemDoesNotStopFollowingGoodItemOrRetireDirtyBatch()=runBlocking{
        completed("下周三去花园散步");var calls=0
        val e=MemoryEngine(scope,repo,ModelAccess(vault),client{r->calls++;val d=JSONObject(r.messages.last().content)
            receipt(d,JSONArray().put(summaryOp("R1","bad")).put(summaryOp("S1","good")))}, {})
        assertFalse(e.ensureRoom("a",forceOne=true).success);assertEquals(3,calls)
        assertEquals(1,repo.memories("a").size);assertEquals(1,repo.recentSources("a").size);assertEquals(0L,repo.cursor("a"))
        assertEquals("FORMAT",repo.memoryStatus("a").error)
    }
    @Test fun summaryDoubtBecomesCandidateAndUnknownDatesRemainEmpty()=runBlocking{
        completed("那年我去过花园")
        val e=MemoryEngine(scope,repo,ModelAccess(vault),client{r->receipt(JSONObject(r.messages.last().content),JSONArray().put(summaryOp(text="我记得对方曾去过花园。").put("date_expression","那年").put("model_date","2020-01-01").put("doubt","参与者需要确认")))}, {})
        assertTrue(e.ensureRoom("a",forceOne=true).success);assertTrue(repo.memories("a").isEmpty())
        val candidate=repo.candidates("a").single();assertNull(candidate.proposed.eventDate);assertTrue(candidate.valid)
        assertTrue(repo.references("a","garden",999).valid.isEmpty());assertEquals(1,repo.references("a","garden",999).pending.size)
    }
    @Test fun nativeToolWritesOnlyWithSuccessfulFinalReplyAndOriginalEvidence()=runBlocking{
        val l=ChatLedger(store);val t=l.begin("a","我喜欢花园",config.id);val tools=runtime(t)
        assertTrue(write(tools,t,args()) is TextChatResult.Success);assertTrue(repo.memories("a").isEmpty())
        assertTrue(commit(t,tools));val m=repo.memories("a").single();assertFalse(m.manual);assertEquals(t.userMessageId,m.evidence.single().source.turnId)
        assertEquals("我喜欢花园",m.evidence.single().snapshot);assertTrue(repo.references("a","花园",999).valid.any{it.memoryId==m.id})
    }
    @Test fun failedOrCancelledToolReplyCannotChangeExistingMemory()=runBlocking{
        val old=automatic();val l=ChatLedger(store);val t=l.begin("a","garden changed",config.id);val tools=runtime(t)
        assertTrue(write(tools,t,args("update","M1"),true) is TextChatResult.Failure);l.fail(t.id,1,ChatFailure.NETWORK)
        assertFalse(commit(t,tools));assertEquals(old,repo.memories("a").single())
        val next=l.retry(t.id,config.id)!!;val second=runtime(next);write(second,next,args("update","M1"));val lease=ChatAttemptLease();lease.cancel()
        assertFalse(l.finishWithMemory(next.id,next.attempt,"late",lease,onCompleted={second.commit(it)}));assertEquals(old,repo.memories("a").single())
    }
    @Test fun toolVersionMismatchRollsBackReplyAndMemoryTogether()=runBlocking{
        val l=ChatLedger(store);val t=l.begin("a","garden",config.id);val tools=runtime(t);write(tools,t,args())
        repo.tx{it.execSQL("UPDATE chat_turns SET source_revision=source_revision+1 WHERE id=?",arrayOf(t.id))}
        try{commit(t,tools);fail()}catch(e:ProcessingRejected){assertEquals("SOURCE_CHANGED",e.code)}
        assertTrue(repo.memories("a").isEmpty());assertEquals(TurnState.PENDING,l.all().single().state)
    }
    @Test fun unresolvedCandidateCanBeClarifiedWithoutTouchingManualOrLockedMemory()=runBlocking{
        val old=automatic();repo.tx{db->repo.candidate(db,"a",old.id,old.copy(text="maybe ocean",version=2),"uncertain","candidate-one")}
        val l=ChatLedger(store);val t=l.begin("a","我确认喜欢花园",config.id);val tools=runtime(t);write(tools,t,args("update","M1"));assertTrue(commit(t,tools))
        assertTrue(repo.candidates("a").isEmpty());assertEquals(2,repo.memories("a").single().version)
        val manual=repo.manualSave("a",null,"manual ocean",MemoryCategory.STATE_FACT,false,true,MemoryAbout.USER)
        val t2=l.begin("a","manual ocean changed",config.id);val refs=repo.references("a","manual ocean changed",t2.userMessageId)
        val index=refs.valid.map{it.memoryId}.filter{it.isNotBlank()}.distinct().indexOf(manual.id)+1
        val protected=MemoryTools(repo,t2,"manual ocean changed",refs){true};write(protected,t2,args("update","M$index"));assertTrue(commit(t2,protected))
        assertEquals(manual,repo.memories("a").first{it.id==manual.id});assertTrue(repo.candidates("a").any{it.targetId==manual.id})
    }
    @Test fun sequentialClarificationThenCompletionKeepsLatestTextAndResolvesOnce()=runBlocking{
        val old=automatic();repo.tx{db->repo.candidate(db,"a",old.id,old.copy(text="maybe ocean",version=2),"uncertain","candidate-one")}
        val t=ChatLedger(store).begin("a","我确认喜欢花园并已完成约定",config.id);val tools=runtime(t);var count=0
        val last=JSONObject().put("op","complete").put("target","M1").put("evidence",JSONArray().put("S1"))
        val result=tools.run(config,"synthetic-only",request("clarify"),client{if(count++==0)TextChatResult.Success("",listOf(
            ChatToolCall("first","agent_memory_update",args("update","M1","我确认对方喜欢花园。").toString()),ChatToolCall("second","agent_memory_update",last.toString()))) else TextChatResult.Success("final")})
        assertTrue(result is TextChatResult.Success);assertTrue(commit(t,tools));assertTrue(repo.candidates("a").isEmpty())
        val m=repo.memories("a").single();assertEquals("我确认对方喜欢花园。",m.text);assertEquals(MemoryLife.COMPLETED,m.life);assertEquals(3,m.version)
    }
    @Test fun unknownAndCrossCharacterTargetsAreRejectedInsteadOfCreatingMemory()=runBlocking{
        val other=repo.manualSave("b",null,"garden",MemoryCategory.STATE_FACT,false,true,MemoryAbout.USER)
        val t=ChatLedger(store).begin("a","garden",config.id);val tools=runtime(t)
        var calls=0;tools.run(config,"synthetic-only",request("garden"),client{r->if(calls++==0)TextChatResult.Success("",listOf(ChatToolCall("one","agent_memory_update",args("update",other.id).toString())))else{assertTrue(r.messages.last().content.contains("UNKNOWN_TARGET"));TextChatResult.Success("final")}})
        assertTrue(commit(t,tools));assertTrue(repo.memories("a").isEmpty());assertEquals(other,repo.memories("b").single())
    }
    @Test fun recallStopsAtThreeRoundsAndCountsFullReturnedBodies()=runBlocking{
        val source=completed("history",reply="history detail ".repeat(1000));completed("other role",friend="b")
        val t=ChatLedger(store).begin("a","please recall",config.id);val tools=runtime(t);var calls=0;val payloads=mutableListOf<String>()
        val result=tools.run(config,"synthetic-only",request("please recall"),client{r->
            r.messages.lastOrNull{it.role=="tool"}?.let{payloads+=it.content;assertTrue(ConservativeTokens.tokens(it.content)<=1500)}
            if(r.tools.isEmpty())TextChatResult.Success("final")else TextChatResult.Success("",listOf(ChatToolCall("read-${calls++}","agent_history_read",JSONObject().put("message_id",source.assistant.id.toString()).put("before",0).put("after",0).put("text_offset",calls*1000).toString())))
        })
        assertTrue(result is TextChatResult.Success);assertEquals(3,calls)
        val usage=JSONObject(store.preference("memory-recall-usage:${t.id}","{}"));assertEquals(3,usage.getInt("rounds"));assertTrue(usage.getInt("tokens")<=4000)
        assertTrue(payloads.all{!it.contains("other role")})
    }
    @Test fun retainedReferencesExpireWithTheirOriginalVisibleTurnAndShareFifteenCap()=runBlocking{
        val memory=repo.manualSave("a",null,"garden",MemoryCategory.STATE_FACT,false,true,MemoryAbout.USER)
        val l=ChatLedger(store);val t=l.begin("a","garden",config.id);assertTrue(repo.references("a","garden",t.userMessageId).valid.any{it.memoryId==memory.id});l.finish(t.id,1,"reply")
        assertTrue(repo.references("a","unrelated",998).valid.any{it.memoryId==memory.id})
        repeat(20){repo.manualSave("a",null,"ocean $it",MemoryCategory.STATE_FACT,false,true,MemoryAbout.USER)}
        val full=repo.references("a","ocean",997).valid;assertEquals(15,full.count{it.related});assertFalse(full.any{it.memoryId==memory.id})
        val b=MemoryBatchRepository(repo);val w=b.prepare("a",repo.recentSources("a"),"test");b.beginAttempt(w,"test");assertTrue(b.complete(w,emptySet(),0,w.id))
        assertFalse(repo.references("a","unrelated",996).valid.any{it.memoryId==memory.id})
    }
    @Test fun uncertaintyInitialThreeTurnsAreIdempotentAndThenNeedRelevance()=runBlocking{
        val old=automatic();repo.tx{db->repo.candidate(db,"a",old.id,old.copy(text="garden question",version=2),"uncertain","candidate-one")}
        for(id in 100L..102L){assertEquals(1,repo.references("a","unrelated",id).pending.size);assertEquals(1,repo.references("a","unrelated",id).pending.size)}
        assertTrue(repo.references("a","unrelated",103).pending.isEmpty());assertEquals(1,repo.references("a","garden",104).pending.size)
    }
    @Test fun expiredReferenceChangesGuardAndIsNeverInjected()=runBlocking{
        val m=repo.manualSave("a",null,"garden",MemoryCategory.STATE_FACT,false,true,MemoryAbout.USER)
        repo.tx{repo.saveMemory(it,m.copy(version=2,expiresAt=System.currentTimeMillis()+250))}
        val refs=repo.references("a","garden",99);assertTrue(refs.valid.isNotEmpty());delay(350)
        assertFalse(repo.referenceCurrent("a",refs.stamp));assertTrue(repo.references("a","garden",100).valid.isEmpty())
    }
    @Test fun nativeToolHttpEncodingAndResponseValidationUseOnlyExplicitlyOfferedTools()=runBlocking{
        val response="""{"choices":[{"finish_reason":"tool_calls","message":{"tool_calls":[{"id":"call-1","type":"function","function":{"name":"agent_history_search","arguments":"{\"query\":\"garden\"}"}}]}}]}"""
        TextTlsMock(response=response).use{s->
            val result=s.client().complete(config.copy(baseUrl=s.base),"synthetic-only",request("garden").copy(tools=buildAgentRecallToolDefinitions(true))) as TextChatResult.Success
            assertEquals("agent_history_search",result.toolCalls.single().name);assertTrue(JSONObject(s.body).getJSONArray("tools").length()>0)
            assertFalse(s.body.contains("_companion"))
        }
        TextTlsMock(response=response).use{s->assertEquals(ChatFailure.TOOLS_UNAVAILABLE,(s.client().complete(config.copy(baseUrl=s.base),"synthetic-only",request("garden")) as TextChatResult.Failure).reason)}
        val call=ChatToolCall("call-1","agent_history_search","{\"query\":\"garden\"}")
        TextTlsMock().use{s->
            val wire=request("garden").messages+WireMessage("assistant","",listOf(call))+WireMessage("tool","{\"success\":true}",toolCallId=call.id,name=call.name)
            assertTrue(s.client().complete(config.copy(baseUrl=s.base),"synthetic-only",request("garden").copy(messages=wire)) is TextChatResult.Success)
            val rows=JSONObject(s.body).getJSONArray("messages");assertEquals("call-1",rows.getJSONObject(rows.length()-1).getString("tool_call_id"))
        }
    }
    @Test fun conversationRunnerCommitsToolMemoryAndReplyUsingTheRealCallback()=runBlocking{
        var calls=0;val mocked=client{r->if(calls++==0){assertTrue(r.messages.last().content.contains("时间"));TextChatResult.Success("",listOf(ChatToolCall("one","agent_memory_update",args().toString())))}else TextChatResult.Success("Synthetic final")}
        val access=ModelAccess(vault);val memory=MemoryEngine(scope,repo,access,mocked,{});val relation=RelationshipEngine(scope,repo,access,mocked,{})
        val runner=ConversationRunner(scope,store,vault,mocked,repo,memory,relation,{},{});
        withContext(Dispatchers.Main){assertTrue(runner.send("a","我喜欢花园",{}))}
        withTimeout(10000){while(runner.sending.isNotEmpty())delay(20)}
        assertEquals(2,calls);assertEquals(1,repo.memories("a").size);assertEquals(TurnState.COMPLETED,ChatLedger(store).all().single().state)
    }
    @Test fun relativeDateRenderingKeepsUnknownsAndCrossYearDatesExplicit(){
        val today=LocalDate.of(2026,10,26)
        assertEquals("今天：我记得散步",MemoryDates.display("我记得散步","2026-10-26",today))
        assertEquals("昨天：散步",MemoryDates.display("散步","2026-10-25",today))
        assertEquals("2025年12月25日：散步",MemoryDates.display("散步","2025-12-25",today))
        assertEquals("散步",MemoryDates.display("散步",null,today))
    }
}
