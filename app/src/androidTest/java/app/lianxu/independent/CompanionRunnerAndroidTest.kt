package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Production runner + SQLite, synthetic models only. Reply and private batch review are separate.
 * Historical method names stay stable for baseline comparison; source/timing/atomicity protections
 * remain, without requiring the removed foreground accounting step. */
class CompanionRunnerAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store
    private lateinit var repo:ProcessingRepository
    private lateinit var vault:ModelVault
    private lateinit var scope:CoroutineScope
    private lateinit var runner:ConversationRunner
    private lateinit var database:String
    private val config=ModelConfig("runner-synthetic","合成服务","custom","https://example.invalid/v1","synthetic",128000,2048)
    private var invalid=0
    private var reviewCalls=0
    private var empty=false
    private var changeConfig=false
    private var block=false
    private var calls=0
    private var makeFuture=false
    private var contactCalls=0
    private var editPersonaDuringReview=false
    private val requests=mutableListOf<TextChatRequest>()
    private var clock=1_000_000L
    private val originalClock=AppServices.companionClock
    private val entered=CompletableDeferred<Unit>()
    private val release=CompletableDeferred<Unit>()
    @Before fun setup():Unit=runBlocking {
        database="runner-contract-${System.nanoTime()}.db";store=Store(context,database)
        store.add(listOf(Friend("a","合成甲","仅用于测试")),false)
        vault=ModelVault(context,"runner-contract-${System.nanoTime()}",store)
        vault.save(config,"synthetic-only");vault.setPurpose(ModelPurpose.CHAT,config.id)
        repo=ProcessingRepository(store);repo.saveMemoryRules(MemoryRules(reviewRounds=1))
        scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val client=object:TextChatClient {
            override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
                calls++;requests+=request;check(key=="synthetic-only")
                assertFalse("private accounting must not enter the visible reply",CompanionRecord.NAME in request.tools.toString())
                if(block){entered.complete(Unit);withContext(NonCancellable){release.await()}}
                if(changeConfig)vault.save(config.copy(modelId="synthetic-changed"),"synthetic-only")
                if(makeFuture&&contactCalls==0){
                    assertTrue(ImmediateContact.NAME in request.tools.toString());contactCalls++
                    val source=request.currentSourceTurn();val earliest=clock+900_000L;clock+=12_000
                    val future=JSONObject().put("kind","shared_continuation").put("reason","继续合成共同话题")
                        .put("notBefore",earliest).put("evidence",JSONArray().put(JSONObject().put("source",source).put("layer","fact")))
                    return TextChatResult.Success("",listOf(ChatToolCall("contact",ImmediateContact.NAME,JSONObject().put("future",future).toString())))
                }
                return TextChatResult.Success("合成正式回复")
            }
        }
        val access=ModelAccess(vault)
        runner=ConversationRunner(scope,store,vault,client,repo,MemoryEngine(scope,repo,access,client,{}),{}, {})
    }
    @After fun close():Unit=runBlocking{release.complete(Unit);runner.cancelAll();scope.coroutineContext[Job]?.cancelAndJoin();AppServices.companionClock=originalClock;vault.delete(config.id);store.close();context.deleteDatabase(database)}
    private fun experience(input:String)=JSONObject().put("quote",input).put("memory",input)
        .put("emotion","unknown").put("valence",0).put("relationship","none").put("control","none").put("targets",JSONArray())
        .put("reconsider",JSONObject.NULL).put("claimKind","interaction").put("controlIntent","none").put("requestQuote","")
    private suspend fun review():Boolean=ReplyAccounting(store,ModelAccess(vault),object:TextChatClient{
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
            reviewCalls++
            assertTrue(request.messages.any{"[多轮后台回顾]" in it.content})
            if(editPersonaDuringReview)store.savePersona(UserPersona("改后的用户","新称呼","新资料"))
            if(reviewCalls<=invalid)return TextChatResult.Success("{\"records\":[]}")
            val rows=JSONArray(request.messages.last{it.role=="user"}.content);val records=JSONArray()
            for(i in 0 until rows.length()){
                val row=rows.getJSONObject(i);val source=row.getLong("source")
                assertEquals("合成当前原话",row.getString("用户原话"));assertEquals("合成正式回复",row.getString("已实际说过的正文"))
                val payload=JSONObject(RoleModelPayload.json(source)).put("experience",experience(row.getString("用户原话")))
                    .put("关系输入",JSONObject(RelationInputContract.example(source)))
                records.put(JSONObject().put("source",source).put("record",if(empty)JSONObject.NULL else payload))
            }
            return TextChatResult.Success(JSONObject().put("records",records).toString())
        }
    }).drain("a")
    private suspend fun pending()=repo.read{ReplyAccountingQueue.pending(it,"a").map{row->row.source.turnId}}
    private suspend fun send():ChatTurn {
        withContext(Dispatchers.Main){assertTrue(runner.send("a","合成当前原话"){})}
        withTimeout(20000){while("a" in runner.sending)delay(20)}
        return ChatLedger(store).all().single()
    }
    private suspend fun retry(turn:ChatTurn):ChatTurn {
        withContext(Dispatchers.Main){runner.retry(turn)}
        withTimeout(20000){while(ChatLedger(store).all().single().attempt==turn.attempt||"a" in runner.sending)delay(20)}
        return ChatLedger(store).all().single()
    }
    private fun failReplyStorage()=store.writableDatabase.execSQL("CREATE TRIGGER synthetic_fail_c2 BEFORE INSERT ON messages WHEN NEW.mine=0 BEGIN SELECT RAISE(ABORT,'synthetic storage failure'); END")
    private fun healReplyStorage()=store.writableDatabase.execSQL("DROP TRIGGER synthetic_fail_c2")
    private suspend fun enableFuture(){makeFuture=true;AppServices.companionClock={clock};CompanionRepository(store){clock}.setPolicy("a",true,"synthetic-owner")}
    @Test fun twoFormatFailuresRecoverBeforeNormalReplyIsPublished():Unit=runBlocking {
        invalid=2;val t=send();assertEquals(TurnState.COMPLETED,t.state);assertEquals(1,calls)
        assertEquals(0,reviewCalls);assertNull(CompanionRepository(store).cached(t));assertEquals(2,store.messages().size)
        assertTrue(review());assertEquals(3,reviewCalls);assertNotNull(CompanionRepository(store).cached(t)!!.understanding)
        assertTrue(pending().isEmpty());assertTrue(review());assertEquals(3,reviewCalls)
        assertEquals(1,CompanionRepository(store).experiences("a").size)
    }
    @Test fun exhaustedRecordAttemptsKeepTheInputAndStillPublishTheReply():Unit=runBlocking {
        invalid=3;val t=send();assertEquals(TurnState.COMPLETED,t.state);assertNull(t.failure);assertEquals(1,calls)
        assertFalse(review());assertEquals(3,reviewCalls);assertEquals(listOf(t.userMessageId),pending())
        assertEquals("合成当前原话",store.messages().single{it.mine}.text);assertEquals("合成正式回复",store.messages().single{!it.mine}.text)
        assertTrue(CompanionRepository(store).experiences("a").isEmpty());assertNull(CompanionRepository(store).cached(t))
        assertTrue(review());assertTrue(pending().isEmpty());assertEquals(1,CompanionRepository(store).experiences("a").size)
    }
    @Test fun validNoNewMemoryStillAllowsASuccessfulReply():Unit=runBlocking {
        empty=true;assertEquals(TurnState.COMPLETED,send().state);assertEquals(1,calls)
        assertTrue(review());assertEquals(1,reviewCalls);assertTrue(pending().isEmpty())
        assertTrue(CompanionRepository(store).experiences("a").isEmpty());assertEquals(2,store.messages().size)
    }
    @Test fun appraisalLatencyPreservesTheOriginalEarliestTime():Unit=runBlocking {
        enableFuture();val t=send();assertEquals(TurnState.COMPLETED,t.state)
        val saved=CompanionRepository(store){clock}.cached(t)!!
        assertEquals(1_000_000L,saved.timingReferenceAt);assertEquals(1_900_000L,saved.reconsider!!.notBefore)
        assertEquals(Long.MAX_VALUE,saved.reconsider.expiresAt);assertEquals(1_012_000L,clock);assertEquals(2,calls)
        assertEquals(0,reviewCalls);assertTrue(review());assertEquals(saved.reconsider,CompanionRepository(store){clock}.cached(t)!!.reconsider)
    }
    @Test fun aLaterReplyRetryReusesTheAcceptedTimingReference():Unit=runBlocking {
        enableFuture();failReplyStorage()
        val first=send();assertEquals(TurnState.FAILED,first.state);assertEquals(ChatFailure.STORAGE,first.failure)
        val accepted=CompanionRepository(store){clock}.cached(first)!!
        assertEquals(listOf(first.userMessageId),pending());assertTrue(review());assertEquals(0,reviewCalls);clock+=30_000;healReplyStorage()
        val retried=retry(first);assertEquals(TurnState.COMPLETED,retried.state)
        val saved=CompanionRepository(store){clock}.cached(retried)!!
        assertEquals(accepted.reconsider,saved.reconsider);assertEquals(accepted.timingReferenceAt,saved.timingReferenceAt)
        assertEquals(3,calls);assertEquals(1,contactCalls);assertEquals(1,store.messages().count{it.mine})
        assertTrue(review());assertEquals(accepted.reconsider,CompanionRepository(store){clock}.cached(retried)!!.reconsider)
    }
    @Test fun profileEditDuringAppraisalRejectsLateUnderstandingBeforeSaving():Unit=runBlocking {
        store.savePersona(UserPersona("旧用户","旧称呼","旧资料"));val t=send()
        editPersonaDuringReview=true;assertFalse(review());assertEquals(1,reviewCalls)
        assertEquals(TurnState.COMPLETED,t.state);assertNull(CompanionRepository(store).cached(t));assertEquals(2,store.messages().size)
        assertEquals(listOf(t.userMessageId),pending());editPersonaDuringReview=false
        assertTrue(review());assertNotNull(CompanionRepository(store).cached(t));assertTrue(pending().isEmpty())
    }
    @Test fun retryAfterProfileEditReappraisesButReusesIndependentFacts():Unit=runBlocking {
        store.savePersona(UserPersona("旧用户","旧称呼","旧资料"));failReplyStorage()
        val first=send();assertEquals(TurnState.FAILED,first.state);assertNull(CompanionRepository(store).cached(first));assertEquals(listOf(first.userMessageId),pending())
        store.savePersona(UserPersona("新用户","新称呼","新资料"));healReplyStorage()
        val retried=retry(first);assertEquals(TurnState.COMPLETED,retried.state);assertEquals(2,calls)
        fun shown(r:TextChatRequest)=r.messages.joinToString("\n"){it.content}
        assertTrue("旧资料" in shown(requests.first()));assertTrue("新资料" in shown(requests.last()))
        assertEquals(1,store.messages().count{it.mine});assertEquals("合成当前原话",store.messages().single{it.mine}.text)
        assertTrue(review());assertEquals(1,reviewCalls);assertEquals(1,CompanionRepository(store).experiences("a").size)
    }
    @Test fun aLocalCandidateContractFailureIsNotNetworkFailure():Unit=runBlocking {
        val turn=ChatLedger(store).begin("a","合成当前原话",config.id)
        val core=CompanionRepository(store){clock};val snapshot=core.snapshot("a")
        // Scheduling from now is valid; an already-past instant is malformed.
        val invalid=ExperienceProposal(quote="合成当前原话",memory="合成当前原话",reconsider=Reconsideration("合成理由",clock-1,clock+3_600_000))
        try{core.accept(turn,invalid,snapshot){true};fail("Invalid local candidate was accepted")}
        catch(e:CompanionModelFailure){assertEquals(ChatFailure.STRUCTURED_FORMAT,e.failure)}
        assertTrue(core.experiences("a").isEmpty());assertEquals(1,store.messages().size)
    }
    @Test fun failedC1StorageCannotBecomeNormalChat():Unit=runBlocking {
        store.writableDatabase.execSQL("CREATE TRIGGER synthetic_fail_c1 BEFORE INSERT ON companion_sources BEGIN SELECT RAISE(ABORT,'synthetic storage failure'); END")
        val t=send();assertEquals(TurnState.COMPLETED,t.state);assertEquals(1,calls);assertEquals(2,store.messages().size)
        assertFalse(review());assertEquals(listOf(t.userMessageId),pending());assertTrue(CompanionRepository(store).experiences("a").isEmpty())
        store.writableDatabase.execSQL("DROP TRIGGER synthetic_fail_c1")
        assertTrue(review());assertTrue(pending().isEmpty());assertEquals(1,CompanionRepository(store).experiences("a").size)
        assertTrue(review());assertEquals(2,reviewCalls);assertEquals(2,store.messages().size)
    }
    @Test fun failedC2StorageRetainsAcceptedInputWithoutInventingAReply():Unit=runBlocking {
        failReplyStorage();val t=send();assertEquals(TurnState.FAILED,t.state);assertEquals(ChatFailure.STORAGE,t.failure)
        assertEquals("合成当前原话",store.messages().single().text);assertEquals(listOf(t.userMessageId),pending())
        assertTrue(review());assertEquals(0,reviewCalls)
        val state=CompanionRepository(store).roleContext("a",SourceRef("a",0,0))
        assertTrue(state.facts.isEmpty());assertTrue(state.actions.isEmpty());assertTrue(state.entries.isEmpty())
    }
    @Test fun changedConfigurationRejectsLateExtractionBeforeCoreMutation():Unit=runBlocking {
        changeConfig=true;val t=send();assertEquals(TurnState.FAILED,t.state);assertEquals(ChatFailure.SOURCE_CHANGED,t.failure)
        assertEquals(1,calls);assertEquals(1,store.messages().size);assertTrue(CompanionRepository(store).experiences("a").isEmpty())
    }
    @Test fun cancelledExtractionCannotPublishALateResult():Unit=runBlocking {
        block=true;withContext(Dispatchers.Main){runner.send("a","合成当前原话") {}}
        withTimeout(10000){entered.await()};withContext(Dispatchers.Main){runner.cancel("a")};release.complete(Unit)
        withTimeout(10000){while(ChatLedger(store).all().single().state!=TurnState.CANCELLED)delay(20)}
        assertEquals(1,calls);assertEquals(1,store.messages().size);assertTrue(CompanionRepository(store).experiences("a").isEmpty())
    }
}
