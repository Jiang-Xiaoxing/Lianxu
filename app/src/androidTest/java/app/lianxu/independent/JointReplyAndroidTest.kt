package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Real Runner/SQLite with isolated synthetic requests; no provider traffic.
 *  Visible answers are plain text; this fixture explicitly drains a one-turn review AFTER delivery. */
class JointReplyAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store;private lateinit var repo:ProcessingRepository;private lateinit var vault:ModelVault
    private lateinit var name:String;private lateinit var scope:CoroutineScope
    private val requests=mutableListOf<TextChatRequest>()
    private val reviewRecords=mutableMapOf<Long,JSONObject>()
    private var lastAwaited:Pair<String,Int>?=null
    @Before fun prepare()=runBlocking {
        name="joint-reply-${System.nanoTime()}";store=Store(context,"$name.db");repo=ProcessingRepository(store);vault=ModelVault(context,name)
        store.add(listOf(Friend("a","合成甲","只属于角色设定的简介")),false)
        repo.saveMemoryRules(MemoryRules(reviewRounds=1))
        store.savePersona(UserPersona("合成用户","同学","只属于用户资料的介绍"))
        vault.save(ModelConfig("fake","合成模型","custom","https://example.invalid/v1","fake-model",64000,4096),"synthetic-only")
        vault.setPurpose(ModelPurpose.CHAT,"fake");scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    }
    /** Cancel and join before closing the store: the runner's NonCancellable finally still writes. */
    @After fun close(){runBlocking{scope.cancel();scope.coroutineContext[kotlinx.coroutines.Job]?.join()};vault.list().forEach{vault.delete(it.id)};store.close();context.deleteDatabase("$name.db")}
    private fun runner(reply:suspend(TextChatRequest)->TextChatResult):ConversationRunner {
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
            requests+=request
            syntheticCompanionStage(request)?.let{return it}
            return reply(request)
        }}
        return ConversationRunner(scope,store,vault,client,repo,MemoryEngine(scope,repo,ModelAccess(vault),client,{}),{},{})
    }
    private suspend fun review(){
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
            requests+=request
            val rows=JSONArray(request.messages.last{it.role=="user"}.content)
            val records=JSONArray()
            for(i in 0 until rows.length()){val id=rows.getJSONObject(i).getLong("source");records.put(JSONObject().put("source",id).put("record",reviewRecords[id]?:JSONObject.NULL))}
            return TextChatResult.Success(JSONObject().put("records",records).toString())
        }}
        ReplyAccounting(store,ModelAccess(vault),client).drain("a")
    }
    private suspend fun done():ChatTurn {
        val turn=withTimeout(10000){while(true){val t=ChatLedger(store).all().lastOrNull();if(t!=null&&(t.id to t.attempt)!=lastAwaited&&t.state!=TurnState.PENDING)return@withTimeout t;delay(10)};error("unreachable")}
        lastAwaited=turn.id to turn.attempt
        // The test waits explicitly so existing score/source assertions still exercise publication.
        // ConversationRunner itself never awaits this review.
        review();return turn
    }
    private fun contract(request:TextChatRequest)=request.messages.firstOrNull{"[companion.reply.v1]" in it.content}
    private fun protocolC(request:TextChatRequest)=contract(request)!=null
    private fun source(request:TextChatRequest)=request.currentSourceTurn()
    private fun recorded(request:TextChatRequest)=request.recordTaken()
    private fun controlSucceeded(request:TextChatRequest)=request.messages.any{it.role=="tool"&&it.name==JointControl.NAME&&runCatching{JSONObject(it.content).optBoolean("success")}.getOrDefault(false)}
    /** Synthetic accounting payload for the record round; [mutate] edits the same sections the parsers read. */
    private fun accounting(request:TextChatRequest,mutate:(JSONObject)->Unit):JSONObject {
        val s=source(request)
        val payload=request.withRelationInput(JSONObject(RoleModelPayload.json(s)),s)
        // The field guide is now on the later review request, not the visible reply request.
        if(!payload.has("关系输入"))payload.put("关系输入",JSONObject(RelationInputContract.example(s)))
        // The experience guide now travels with the record tool, not the reply prompt.
        run{
            payload.put("experience",JSONObject().put("quote",request.latestUserText()).put("memory","")
                .put("emotion","unknown").put("valence",0).put("relationship","none").put("control","none").put("targets",JSONArray())
                .put("reconsider",JSONObject.NULL).put("claimKind","interaction").put("controlIntent","none").put("requestQuote",""))
        }
        mutate(payload);return payload
    }
    /** Capture synthetic review output while returning the visible answer immediately. */
    private fun combined(request:TextChatRequest,text:String,mutate:(JSONObject)->Unit={}):TextChatResult {
        reviewRecords[source(request)]=accounting(request,mutate)
        return TextChatResult.Success(text)
    }
    @Test fun fullDefaultToolsAt16kKeepPersonaAndDropOptionalRecord()=runBlocking {
        // Extreme-value pressure case, not normal use: at a ~16k window the record field guide cannot fit
        // next to every default tool, so the optional accounting round is dropped and she still replies.
        // 16000/2000 is deliberately not the legacy 16384/2048 pair (the vault lifts that untouched pair
        // to the new 1M/64K default, which this test must not inherit).
        val model=vault.list().single();vault.save(model.copy(contextTokens=16000,outputTokens=2000),"synthetic-only")
        val r=runner{combined(it,"容量验证回复")};assertTrue(r.send("a","你好"){})
        val turn=done();assertEquals("turn ended as ${turn.failure}",TurnState.COMPLETED,turn.state)
        val replyRequests=requests.filter(::protocolC)
        assertEquals(1,replyRequests.size)
        val request=replyRequests.single();val joined=request.messages.joinToString("\n"){it.content}
        assertTrue("只属于角色设定的简介" in joined);assertTrue("只属于用户资料的介绍" in joined)
        assertTrue("[companion.reply.v1]" in joined)
        assertFalse("the optional record guide must be the part that is dropped","[companion.record.v1]" in joined)
        // The dropped guide is the one that carries the learning/relationship field rules.
        assertFalse("the dropped record guide carries usedLearningIds","usedLearningIds" in joined)
        assertFalse("[companion.interpret.v2]" in joined)
        assertFalse("Protocol C reply must not force a JSON envelope",request.jsonObject)
        val tools=request.tools.toString();assertTrue(JointControl.NAME in tools);assertTrue("relation_event_search" in tools)
        assertFalse("no record tool is offered when its guide does not fit",CompanionRecord.NAME in tools)
        assertEquals("容量验证回复",store.messages().single{!it.mine}.text)
    }
    @Test fun ordinaryMessageRepliesBeforePrivateReviewAndRetainsOriginalInput()=runBlocking {
        val r=runner{combined(it,"合成自然回复")};assertTrue(r.send("a","你好"){})
        assertEquals(TurnState.COMPLETED,done().state);assertEquals(2,requests.size)
        // A successful record is folded into a short note: its payload is not dialogue.
        val followUp=requests[1]
        assertFalse(followUp.messages.any{it.role=="tool"&&it.name==CompanionRecord.NAME})
        assertTrue(followUp.messages.any{"[多轮后台回顾]" in it.content})
        assertEquals(listOf("你好\n\n"+CompanionPrompt.THINKING_REMINDER),requests.first().messages.filter{it.role=="user"}.map{it.content})
        assertTrue(requests.first().messages.any{"只属于角色设定" in it.content})
        val joined=requests.first().messages.joinToString("\n"){it.content}
        assertTrue("当前权限与交流边界" in joined)
        assertEquals(1,Regex(Regex.escape("只属于角色设定的简介")).findAll(joined).count())
        assertEquals(1,Regex(Regex.escape("[人设与成长共同规则]")).findAll(joined).count())
        assertEquals(1,store.messages().count{it.text=="合成自然回复"})
        assertEquals(1,CompanionRepository(store).roleContext("a",SourceRef("a",0,0)).entries.size)
    }
    @Test fun failedRequestRetriesWithoutExtractingTheSameInputAgain()=runBlocking {
        var fail=true;val r=runner{if(fail)TextChatResult.Failure(ChatFailure.TIMEOUT)else combined(it,"重试成功")}
        assertTrue(r.send("a","合成第一次"){});val first=done();assertEquals(TurnState.FAILED,first.state)
        withTimeout(10000){while("a" in r.sending)delay(10)}
        fail=false;r.retry(first)
        withTimeout(10000){while(ChatLedger(store).all().single().attempt==1)delay(10)}
        assertEquals(TurnState.COMPLETED,done().state);assertEquals(3,requests.size)
        assertEquals(0,requests.count{it.messages.any{m->"[companion.interpret.v2]" in m.content}})
        assertEquals(1,store.messages().count{it.mine})
    }
    @Test fun cancelledReplyNeverBecomesAnActionOrLearning()=runBlocking {
        val started=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
        val r=runner{request->started.complete(Unit);withContext(NonCancellable){finish.await()};combined(request,"不应保存")}
        assertTrue(r.send("a","合成取消"){});withTimeout(10000){started.await()};r.cancel("a");finish.complete(Unit)
        assertEquals(TurnState.CANCELLED,done().state);delay(50)
        assertTrue(store.messages().none{it.text=="不应保存"})
        assertTrue(CompanionRepository(store).roleContext("a",SourceRef("a",0,0)).actions.isEmpty())
        // Cleanup must really run after a cancel: the role lock and the foreground mark are released.
        withTimeout(5000){while(CompanionRuntime.mutex("a").isLocked||CompanionRuntime.busy("a"))delay(10)}
    }
    @Test fun stagedWriteNeverCommitsWhenTheReplyFails()=runBlocking {
        repo.saveMemoryRules(MemoryRules(immediateWrite=true))
        // The staged write is offered once; whatever follows is the reply round, which fails here.
        // Keying this on the record receipt would loop forever because this fixture never records.
        val r=runner{request->
            if(request.messages.none{it.role=="tool"})TextChatResult.Success("",listOf(ChatToolCall("stage","agent_memory_update","""{"op":"add","target":"","category":"STATE_FACT","about":"USER","content":"失败候选不应保存","evidence":["S1"]}""")))
            else TextChatResult.Failure(ChatFailure.NETWORK)
        }
        assertTrue(r.send("a","合成事实原话"){});assertEquals(TurnState.FAILED,done().state)
        assertEquals(2,requests.size);assertTrue(repo.memories("a").isEmpty())
        assertTrue(store.messages().none{it.text=="失败候选不应保存"})
    }
    @Test fun stagedMemoryReceiptFailsWhenAControlDiscardsIt()=runBlocking {
        repo.saveMemoryRules(MemoryRules(immediateWrite=true))
        var target=0L;var mode=false;var stageDone=false;var controlDone=false
        val r=runner{request->
            when{
                !mode->combined(request,"先前的合成事情"){json->json.getJSONObject("experience").put("memory","先前的合成事情")}
                !stageDone->{stageDone=true;TextChatResult.Success("",listOf(ChatToolCall("stage","agent_memory_update","""{"op":"add","target":"","category":"STATE_FACT","about":"USER","content":"会被丢掉的事实","evidence":["S1"]}""")))}
                !controlDone->{controlDone=true;TextChatResult.Success("",listOf(ChatToolCall("forget",JointControl.NAME,"""{"action":"forget","quote":"忘记先前那件事","targets":[$target],"authorization":"current_direct_request"}""")))}
                else->TextChatResult.Success("嗯，我把它放下了。")
            }
        }
        assertTrue(r.send("a","先前的合成事情"){});assertEquals(TurnState.COMPLETED,done().state)
        withTimeout(10000){while("a" in r.sending)delay(10)}
        target=store.messages().first{it.mine}.id
        mode=true
        assertTrue(r.send("a","忘记先前那件事"){});withTimeout(10000){while(ChatLedger(store).all().size<2)delay(10)}
        // Wait for the runner's own finally so settleActivities cannot race the database close.
        withTimeout(10000){while("a" in r.sending)delay(10)}
        val turn=done()
        assertEquals(TurnState.COMPLETED,turn.state)
        // The control withdrew the source the staged candidate cited; the turn then committed.
        val stage=turn.toolActivities.single{it.tool=="agent_memory_update"}
        assertEquals("failed",stage.state);assertEquals("没记下",stage.label())
        assertEquals("根据后续操作，这条没有保存",stage.detail)
        assertTrue(repo.memories("a").none{it.text=="会被丢掉的事实"})
    }
    @Test fun badAccountingSectionIsIgnoredAndThePlainReplyStillSaves()=runBlocking {
        val r=runner{combined(it,"最终合成回复"){json->json.getJSONObject("appraisal").remove("meaning")}}
        assertTrue(r.send("a","合成轻松交流"){});assertEquals(TurnState.COMPLETED,done().state)
        assertEquals(2,requests.size);assertEquals(0,requests.count{it.messages.any{m->"[companion.interpret.v2]" in m.content}})
        val messages=store.messages();assertEquals(2,messages.size)
        assertEquals("最终合成回复",messages.single{!it.mine}.text)
        val state=CompanionRepository(store).roleContext("a",SourceRef("a",0,0))
        assertEquals(1,state.entries.size);assertNull(state.entries.single().flow.appraisal);assertEquals(1,state.actions.size)
    }
    @Test fun controlCandidateCannotLeakTextAndForgettingStopsTheOldSource()=runBlocking {
        var target=0L;var mode=false
        val r=runner{request->
            if(mode&&!controlSucceeded(request)){
                TextChatResult.Success("",listOf(ChatToolCall("control",JointControl.NAME,"""{"action":"forget","quote":"忘记先前那件事","targets":[$target],"authorization":"current_direct_request"}""")))
            }else{
                combined(request,if(mode)"嗯，这件事我放下了，我们聊点别的。" else "不该显示的旧信息"){json->
                    val e=json.getJSONObject("experience")
                    if(mode)e.put("controlIntent","forget").put("requestQuote","忘记先前那件事")
                    else e.put("memory","先前的合成事情")
                }
            }
        }
        assertTrue(r.send("a","先前的合成事情"){});assertEquals(TurnState.COMPLETED,done().state)
        withTimeout(10000){while("a" in r.sending)delay(10)}
        target=store.messages().first{it.mine}.id;mode=true
        assertTrue(r.send("a","忘记先前那件事"){});withTimeout(10000){while(ChatLedger(store).all().size<2)delay(10)};assertEquals(TurnState.COMPLETED,done().state)
        assertEquals(4,requests.size)
        assertEquals(1,store.messages().count{it.text=="不该显示的旧信息"})
        assertEquals("嗯，这件事我放下了，我们聊点别的。",store.messages().last().text)
        assertTrue(CompanionRepository(store).experiences("a").isEmpty())
    }
    @Test fun unofferedAccountingToolIsRejectedWithoutSavingAnAnswer()=runBlocking {
        val r=runner{TextChatResult.Success("",listOf(ChatToolCall("record",CompanionRecord.NAME,"{}")))}
        assertTrue(r.send("a","合成原话"){});val turn=done()
        assertEquals(TurnState.FAILED,turn.state);assertEquals(ChatFailure.TOOLS_UNAVAILABLE,turn.failure)
        assertEquals(1,requests.size);assertTrue(store.messages().none{!it.mine})
    }
    @Test fun hypothesisCannotBecomePositiveRelationshipExperience()=runBlocking {
        var hypothetical=true
        val r=runner{request->
            combined(request,"合成回复"){json->
                json.getJSONObject("experience").put("claimKind",if(hypothetical)"hypothesis"else"reported_event")
                json.put("关系输入",JSONObject().put("消息",source(request).toString()).put("信息类别","关于关系").put("P","中").put("C","无").put("V","无").put("B","无"))
            }
        }
        assertTrue(r.send("a","假如我为你准备了礼物"){});val first=done();assertEquals(TurnState.COMPLETED,first.state)
        val core=CompanionRepository(store);assertNull(core.cached(first)!!.relationInput!!.p)
        withTimeout(10000){while("a" in r.sending)delay(10)}
        hypothetical=false;assertTrue(r.send("a","我为你准备了礼物"){});withTimeout(10000){while(ChatLedger(store).all().size<2)delay(10)};val second=done()
        assertEquals(TurnState.COMPLETED,second.state);assertEquals(0.6,core.cached(second)!!.relationInput!!.p!!,0.0)
    }
    @Test fun ordinaryStreamAndNativeReasoningAppearBeforeCompletion()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val client=object:TextChatClient {
            override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest)=error("stream expected")
            override suspend fun streamEvents(config:ModelConfig,key:String,request:TextChatRequest,onEvent:suspend(TextStreamEvent)->Unit):TextChatResult {
                val result=combined(request,"完整校验后显示")
                if(result is TextChatResult.Success&&result.toolCalls.isNotEmpty())return result
                onEvent(TextStreamEvent.Reasoning("可能包含旧来源的合成思考"))
                onEvent(TextStreamEvent.Content("完整校验后显示"))
                entered.complete(Unit);release.await();return result
            }
        }
        val r=ConversationRunner(scope,store,vault,client,repo,MemoryEngine(scope,repo,ModelAccess(vault),client,{}),{},{})
        assertTrue(r.send("a","合成当前原话"){})
        try {withTimeout(10000){entered.await()}
            assertEquals("完整校验后显示",r.previews["a"]!!.text);assertEquals("可能包含旧来源的合成思考",r.previews["a"]!!.reasoning)
            assertTrue(store.messages().none{!it.mine})
        }finally{release.complete(Unit)}
        assertEquals(TurnState.COMPLETED,done().state)
        assertEquals("完整校验后显示",store.messages().single{!it.mine}.text)
    }
    @Test fun correctionCandidateStopsOldSourceBeforeConfirmation()=runBlocking {
        var target=0L;var mode=false
        val r=runner{request->
            if(mode&&!controlSucceeded(request)){
                TextChatResult.Success("",listOf(ChatToolCall("control",JointControl.NAME,"""{"action":"correct","quote":"更正先前那件事，我改周五去","targets":[$target],"authorization":"current_direct_request"}""")))
            }else{
                combined(request,if(mode)"嗯，这件事我放下了，我们聊点别的。" else "不该显示的旧信息"){json->
                    val e=json.getJSONObject("experience")
                    if(mode)e.put("controlIntent","correct").put("requestQuote","更正先前那件事，我改周五去").put("memory","我改周五去").put("quote","我改周五去")
                    else e.put("memory","先前的合成事情")
                }
            }
        }
        assertTrue(r.send("a","先前的合成事情"){});assertEquals(TurnState.COMPLETED,done().state)
        withTimeout(10000){while("a" in r.sending)delay(10)}
        target=store.messages().first{it.mine}.id;mode=true
        assertTrue(r.send("a","更正先前那件事，我改周五去"){});withTimeout(10000){while(ChatLedger(store).all().size<2)delay(10)};assertEquals(TurnState.COMPLETED,done().state)
        assertEquals(3,requests.count(::protocolC)) // First reply, control call, post-control reply; reviews are separate.
        assertEquals(1,store.messages().count{it.text=="不该显示的旧信息"})
        assertEquals("嗯，这件事我放下了，我们聊点别的。",store.messages().last().text)
        assertEquals("我改周五去",CompanionRepository(store).experiences("a").single().text)
    }
    @Test fun failedControlCanCorrectArgumentsThenReplyNaturally()=runBlocking {
        var target=0L;var mode=false;var attempts=0
        val r=runner{request->
            if(mode&&!controlSucceeded(request)){
                attempts++;val id=if(attempts==1)999999L else target
                TextChatResult.Success("",listOf(ChatToolCall("control-$attempts",JointControl.NAME,"""{"action":"forget","quote":"别再记着那件事","targets":[$id],"authorization":"current_direct_request"}""")))
            }else{
                if(mode)assertFalse(request.messages.any{"独特的旧资料XYZ" in it.content})
                combined(request,if(mode)"好，我听你的。" else "听见啦"){json->
                    val e=json.getJSONObject("experience")
                    if(mode)e.put("controlIntent","forget").put("requestQuote","别再记着那件事")
                    else e.put("memory","独特的旧资料XYZ")
                }
            }
        }
        assertTrue(r.send("a","独特的旧资料XYZ"){});assertEquals(TurnState.COMPLETED,done().state)
        withTimeout(10000){while("a" in r.sending)delay(10)};target=store.messages().first{it.mine}.id;mode=true
        assertTrue(r.send("a","别再记着那件事"){});withTimeout(10000){while(ChatLedger(store).all().size<2)delay(10)}
        assertEquals(TurnState.COMPLETED,done().state);assertEquals(2,attempts);assertEquals(5,requests.size)
        assertEquals("好，我听你的。",store.messages().last().text)
    }
    @Test fun unknownControlTargetProducesModelClarificationWithoutMutation()=runBlocking {
        val r=runner{combined(it,"你指的是哪一件事呀？"){json->json.getJSONObject("experience").put("controlIntent","uncertain").put("requestQuote","那件事别记了")}}
        assertTrue(r.send("a","那件事别记了"){});assertEquals(TurnState.COMPLETED,done().state)
        assertEquals(2,requests.size);assertEquals("你指的是哪一件事呀？",store.messages().last().text)
        assertEquals("none",CompanionRepository(store).cached(ChatLedger(store).all().single())?.control?:"none")
    }
    @Test fun appliedControlSurvivesReplyFailureAndManualRetry()=runBlocking {
        var mode=false;var target=0L;var fail=true;var controls=0
        val r=runner{request->
            if(mode&&!protocolC(request))return@runner TextChatResult.Success("我记得你的要求，已经放下了。")
            // A retried attempt already carries the applied-control receipt; asking for the control
            // again there would be a second request for something the program has already executed.
            val applied=request.messages.any{(it.role=="tool"&&it.name==JointControl.NAME)||(it.role=="system"&&it.content.contains("这项操作已经完成，不要重复执行"))}
            if(mode&&!applied){
                controls++;TextChatResult.Success("",listOf(ChatToolCall("control",JointControl.NAME,"""{"action":"forget","quote":"忘记那件事","targets":[$target],"authorization":"current_direct_request"}""")))
            }else if(mode&&fail)TextChatResult.Failure(ChatFailure.NETWORK)
            else combined(request,if(mode)"我记得你的要求，已经放下了。" else "合成回复"){json->
                val e=json.getJSONObject("experience")
                if(mode)e.put("controlIntent","forget").put("requestQuote","忘记那件事")
                else e.put("memory","先前事情")
            }
        }
        assertTrue(r.send("a","先前事情"){});assertEquals(TurnState.COMPLETED,done().state)
        withTimeout(10000){while("a" in r.sending)delay(10)};target=store.messages().first{it.mine}.id;mode=true
        assertTrue(r.send("a","忘记那件事"){});withTimeout(10000){while(ChatLedger(store).all().size<2)delay(10)}
        val failed=done();assertEquals(TurnState.FAILED,failed.state)
        withTimeout(10000){while("a" in r.sending)delay(10)};fail=false;r.retry(failed)
        withTimeout(10000){while(ChatLedger(store).all().last().attempt==1)delay(10)}
        assertEquals(TurnState.COMPLETED,done().state);assertEquals(1,controls)
        assertEquals(2,store.messages().count{!it.mine});assertTrue(CompanionRepository(store).experiences("a").isEmpty())
    }
    @Test fun withdrawnSourceNeverReturnsAfterForget()=runBlocking {
        var target=0L;var phase=0
        val secret="WITHDRAWN_SYNTHETIC_7788"
        val r=runner{request->
            when(phase){
                0->combined(request,secret)
                1->{phase=2;TextChatResult.Success("",listOf(ChatToolCall("forget",JointControl.NAME,"""{"action":"forget","quote":"忘记之前那件事","targets":[$target],"authorization":"current_direct_request"}""")))}
                else->{
                    assertFalse(request.messages.any{secret in it.content})
                    combined(request,"嗯，我们换个话题。"){json->json.getJSONObject("experience").put("controlIntent","forget").put("requestQuote","忘记之前那件事")}
                }
            }
        }
        assertTrue(r.send("a",secret){});assertEquals(TurnState.COMPLETED,done().state)
        withTimeout(10000){while("a" in r.sending)delay(10)}
        target=store.messages().first{it.mine}.id;phase=1
        assertTrue(r.send("a","忘记之前那件事"){});withTimeout(10000){while(ChatLedger(store).all().size<2)delay(10)}
        assertEquals(TurnState.COMPLETED,done().state);assertEquals(4,requests.size)
    }
    @Test fun stopRemindingKeepsIndependentFeelingAndRelationshipAcrossReopen()=runBlocking {
        var target=0L;var control=false
        val input="别再提醒那件事了，谢谢你一直关心我"
        val r=runner{request->
            if(control&&!controlSucceeded(request)){
                TextChatResult.Success("",listOf(ChatToolCall("stop",JointControl.NAME,"""{"action":"stop_reminding","quote":"别再提醒那件事了","targets":[$target],"authorization":"current_direct_request"}""")))
            }else{
                combined(request,"嗯，听你这么说我也很开心。"){json->
                    if(control){
                        val s=source(request)
                        json.getJSONObject("experience").put("controlIntent","stop_reminding").put("requestQuote","别再提醒那件事了").put("relationship","appreciation")
                        json.put("feelings",JSONArray("""[{"kind":"joy","degree":"mild","object":"这次感谢","reason":"对方感谢我的关心","evidence":[{"source":$s,"layer":"fact"}]}]"""))
                        json.put("关系输入",JSONObject("""{"消息":"$s","信息类别":"关于关系","P":"轻","C":"无","V":"无","B":"无"}"""))
                    }
                }
            }
        }
        assertTrue(r.send("a","先前的合成事情"){});assertEquals(TurnState.COMPLETED,done().state)
        withTimeout(10000){while("a" in r.sending)delay(10)}
        target=store.messages().first{it.mine}.id;control=true
        assertTrue(r.send("a",input){});withTimeout(10000){while(ChatLedger(store).all().size<2)delay(10)}
        assertEquals(TurnState.COMPLETED,done().state)
        withTimeout(10000){while("a" in r.sending)delay(10)}
        val current=store.messages().last{it.mine}.id
        suspend fun verify(){
            val c=CompanionRepository(store).roleContext("a",SourceRef("a",0,0))
            assertEquals("joy",c.entries.single{it.source.turnId==current}.flow.feelings.single().kind)
            assertEquals(.3,c.relationInputs.single{it.source.turnId==current}.p!!,0.0)
        }
        verify()
        // A real restart stops background work before the database goes away; closing it from under a
        // live writer would hold the file lock and make the reopen look like a data loss.
        scope.cancel();withContext(Dispatchers.IO){Thread.sleep(300)}
        store.close();store=Store(context,"$name.db");verify()
    }

    @Test fun livePreambleFailureRetryAndSuccessStayInOrderAndSurviveReopen()=runBlocking {
        var mode=false;var phase=0;var target=0L
        val shown=List(4){CompletableDeferred<Unit>()};val proceed=List(4){CompletableDeferred<Unit>()}
        val client=object:TextChatClient {
            override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest)=error("stream only")
            override suspend fun streamEvents(config:ModelConfig,key:String,request:TextChatRequest,onEvent:suspend(TextStreamEvent)->Unit):TextChatResult {
                if(!mode)return combined(request,"先前回复")
                val index=phase++
                if(index<2){
                    val body=if(index==0)"删好了。"else"刚才没删掉，我再试一次。"
                    onEvent(TextStreamEvent.Reasoning("合成实时思考"));onEvent(TextStreamEvent.Content(body))
                    shown[index].complete(Unit);proceed[index].await()
                    val id=if(index==0)999999L else target
                    return TextChatResult.Success(body,listOf(ChatToolCall("control-$index",JointControl.NAME,"""{"action":"forget","quote":"忘记之前那件事","targets":[$id],"authorization":"current_direct_request"}""")))
                }
                val result=combined(request,"这次处理好了。")
                if(result is TextChatResult.Success&&result.toolCalls.isNotEmpty())return result
                onEvent(TextStreamEvent.Reasoning("合成实时思考"));onEvent(TextStreamEvent.Content("这次处理好了。"))
                shown[2].complete(Unit);proceed[2].await()
                return result
            }
        }
        val r=ConversationRunner(scope,store,vault,client,repo,MemoryEngine(scope,repo,ModelAccess(vault),client,{}),{},{})
        assertTrue(r.send("a","先前事情"){});assertEquals(TurnState.COMPLETED,done().state)
        withTimeout(10000){while("a" in r.sending)delay(10)};target=store.messages().first{it.mine}.id;mode=true
        assertTrue(r.send("a","忘记之前那件事"){})
        try {
            withTimeout(10000){shown[0].await()};assertEquals("删好了。",r.previews["a"]!!.text)
            assertEquals("合成实时思考",r.previews["a"]!!.reasoning);assertTrue(r.previews["a"]!!.tools.isEmpty())
            proceed[0].complete(Unit);withTimeout(10000){shown[1].await()}
            assertEquals(listOf("failed"),r.previews["a"]!!.tools.map{it.state})
            assertEquals("删好了。",r.previews["a"]!!.tools.single().before)
            assertEquals("刚才没删掉，我再试一次。",r.previews["a"]!!.text)
            proceed[1].complete(Unit);withTimeout(10000){shown[2].await()}
            // Only the control receipts are asserted here; the record round adds its own write receipt.
            val controls=r.previews["a"]!!.tools.filter{it.kind==ChatToolActivity.OTHER}
            assertEquals(listOf("failed","success"),controls.map{it.state})
            assertEquals(listOf("删好了。","刚才没删掉，我再试一次。"),controls.map{it.before})
            // The record receipt stays staged until the turn commits; only then does it turn green.
            assertTrue(r.previews["a"]!!.tools.none{it.tool==CompanionRecord.NAME})
            assertEquals("这次处理好了。",r.previews["a"]!!.text)
        }finally{proceed.forEach{it.complete(Unit)}}
        assertEquals(TurnState.COMPLETED,done().state);withTimeout(10000){while("a" in r.sending)delay(10)}
        val t=ChatLedger(store).all().last();val controls=t.toolActivities.filter{it.kind==ChatToolActivity.OTHER}
        assertEquals(listOf("failed","success"),controls.map{it.state})
        assertEquals(listOf("删好了。","刚才没删掉，我再试一次。"),controls.map{it.before})
        assertEquals(0,t.toolActivities.count{it.tool==CompanionRecord.NAME})
        assertTrue(store.messages().none{it.text=="删好了。"});assertTrue(CompanionRepository(store).experiences("a").isEmpty())
        // Same restart rule as above: stop background work before the database is closed.
        scope.cancel();withContext(Dispatchers.IO){Thread.sleep(300)}
        store.close();store=Store(context,"$name.db")
        assertEquals(t.toolActivities,ChatLedger(store).all().last().toolActivities)
    }

    @Test fun replyAndProactiveRoundsHaveNoTemplateTone()=runBlocking {
        val r=runner{combined(it,"合成回复")};assertTrue(r.send("a","合成问候"){})
        assertEquals(TurnState.COMPLETED,done().state)
        val joined=requests.flatMap{req->req.messages.map{it.content}}.joinToString("\n")
        assertFalse("a reply must not be steered by the template tone","主动交流语气" in joined)
        assertFalse("the steady template line is tone too","不催促、不施压" in joined)
        assertTrue(RelationshipPrompt.LABEL in joined);assertFalse("当前关系小结" in joined)
        assertFalse("按模板语气" in joined);assertTrue("总和无需为1" in joined)
        // Both paths carry independent values and evidence, without prescribing expression.
        val snapshot=CompanionRepository(store).snapshot("a")
        assertFalse("主动交流语气" in snapshot.proactivePrompt);assertFalse("当前关系小结" in snapshot.proactivePrompt);assertFalse("按模板语气" in snapshot.proactivePrompt)
        assertFalse("主动交流语气" in snapshot.replyPrompt)
        assertTrue(RelationshipPrompt.LABEL in snapshot.proactivePrompt)
        assertTrue(snapshot.references.any{it.label==CompanionPrompt.TONE_LABEL})
    }

    @Test fun unrelatedRoundUsesTheEmptyWordingEvenThoughExperiencesExist()=runBlocking {
        val r=runner{req->combined(req,"合成回复"){p->p.getJSONObject("experience").put("memory","旅人明天上班还得加班")}}
        assertTrue(r.send("a","明天还要上班啊啊啊"){});assertEquals(TurnState.COMPLETED,done().state)
        assertEquals(1,CompanionRepository(store).experiences("a").size)
        withTimeout(10000){while("a" in r.sending)delay(10)}
        requests.clear()
        val before=ChatLedger(store).all().map{it.id}.toSet()
        assertTrue(r.send("a","哎"){})
        val next=withTimeout(10000){while(true){val t=ChatLedger(store).all().lastOrNull{it.id !in before};if(t!=null&&t.state!=TurnState.PENDING)return@withTimeout t;delay(10)};error("unreachable")}
        assertEquals(TurnState.COMPLETED,next.state)
        val first=requests.first().messages.first().content
        assertFalse("a saved experience that does not match this turn must not be promised","下面是你现在还记得的事" in first)
        assertTrue("这一轮没有想起具体的事" in first)
        assertFalse("an empty experience list must not print its header","手机保存的经历" in first)
    }

    @Test fun toolReserveCoversThePerTurnRecordGuide(){
        val ledger=ChatLedger(store);val turn=runBlocking{ledger.begin("a","合成原话","synthetic")}
        val refs=runBlocking{repo.references("a","合成原话",turn.userMessageId)}
        val tools=MemoryTools(repo,turn,"合成原话",refs,recordTool={mapOf("success" to false,"error" to "reserve_only")},recordNeedsExperience=true,active={true})
        val guide=ConservativeTokens.tokens(com.google.gson.Gson().toJson(CompanionRecord.definition(turn.userMessageId,true)))
        assertTrue("the reserved budget must cover the definition the request actually carries",tools.reservedTokens()>=guide)
    }

    @Test fun adaptiveReplyAlsoDropsTheToneReference()=runBlocking {        repo.saveMemoryRules(MemoryRules(adaptiveContext=true))
        val r=runner{combined(it,"合成回复")};assertTrue(r.send("a","合成问候"){})
        assertEquals(TurnState.COMPLETED,done().state)
        val joined=requests.flatMap{req->req.messages.map{it.content}}.joinToString("\n")
        assertFalse("主动交流语气" in joined)
        assertFalse("不催促、不施压" in joined)
        assertTrue("other adaptive references stay","当前权限与交流边界" in joined)
        assertTrue(RelationshipPrompt.LABEL in joined);assertFalse("当前关系小结" in joined);assertFalse("按模板语气" in joined)
    }

    @Test fun explanationFoundBySearchIsCommittedToTheLedger()=runBlocking {
        // Seed an old broken promise. The reply board no longer carries its evaluation, so the only
        // way back to it is relation_event_search plus the record payload that cites what was found.
        val ledger=ChatLedger(store);val core=CompanionRepository(store);val at=AppServices.companionClock()
        val old=ledger.begin("a","那天我放你鸽子了","unused");val oldRef=core.sourceFor(old)
        val snapshot=core.snapshot("a")
        val seed=ExperienceProposal(relationInput=RelationInput(oldRef,at,at/RelationshipProjector.DAY,"关于关系",v=.9),timingReferenceAt=at)
        check(core.accept(old,seed,snapshot){true});check(ledger.finish(old.id,1,"那天确实爽约了"));repo.tx{core.finish(it,old,snapshot,seed)}

        val before=ChatLedger(store).all().map{it.id}.toSet()
        var step=0
        val r=runner{request->
            when(step++){
                0->TextChatResult.Success("",listOf(ChatToolCall("search","relation_event_search","{\"query\":\"放你鸽子\"}")))
                1->{
                    assertTrue("查到的旧事要出现在工具回执里",request.messages.any{it.role=="tool"&&it.name=="relation_event_search"})
                    val payload=accounting(request){it.put("关系输入",JSONObject()
                        .put("消息",source(request).toString()).put("信息类别","关于关系")
                        .put("P","无").put("C","无").put("V","无").put("B","无")
                        .put("解释",JSONObject().put("事件",oldRef.turnId).put("判断","真误会").put("类别","工作忙")))}
                    reviewRecords[source(request)]=payload
                    TextChatResult.Success("那天是我不好")
                }
                else->TextChatResult.Success("那天是我不好")
            }
        }
        assertTrue(r.send("a","那天到底怎么回事"){})
        val turn=withTimeout(10000){while(true){val t=ChatLedger(store).all().lastOrNull{it.id !in before};if(t!=null&&t.state!=TurnState.PENDING)return@withTimeout t;delay(10)};error("unreachable")}
        assertEquals("turn ended as ${turn.failure}",TurnState.COMPLETED,turn.state)
        review()
        val committed=CompanionRepository(store).relationship("a").inputs.lastOrNull{it.source.turnId!=oldRef.turnId}
        assertNotNull("这次解释要真的记进账本",committed)
        assertEquals("解释要指向查到的那个旧来源",oldRef,committed!!.explains)
    }

}
