package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class CompanionSemanticAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val now=1_800_000_000_000L
    private lateinit var store:Store
    private lateinit var core:CompanionRepository
    private lateinit var ledger:ChatLedger
    private lateinit var vault:ModelVault
    private lateinit var access:ModelAccess
    private lateinit var database:String
    private lateinit var namespace:String
    private val config=ModelConfig("synthetic-embedding","synthetic","custom","https://example.invalid/v1","synthetic-embedding")
    private var calls=0
    private var reviews=0
    private var dimension=2
    private var beforeReturn:(suspend()->Unit)?=null
    private val fake=object:MemoryEmbeddingClient {
        override suspend fun embed(credential:ModelCredential,texts:List<String>,timeout:Int):List<DoubleArray>{
            assertEquals("synthetic-only",credential.key);calls++
            val result=texts.map{if("木工" in it)doubleArrayOf(0.0,1.0)else doubleArrayOf(1.0,0.0)}.map{it.copyOf(dimension)}
            beforeReturn?.invoke();return result
        }
    }
    @Before fun setup()=runBlocking {
        database="semantic-${System.nanoTime()}.db";namespace="semantic-${System.nanoTime()}"
        store=Store(context,database);core=CompanionRepository(store){now};ledger=ChatLedger(store)
        store.add(listOf(Friend("a","合成甲","合成"),Friend("b","合成乙","合成")),false)
        vault=ModelVault(context,namespace,store);access=ModelAccess(vault)
        vault.save(config,"synthetic-only");vault.setPurpose(ModelPurpose.EMBEDDING,config.id)
    }
    @After fun close(){vault.delete(config.id);store.close();context.deleteDatabase(database)}
    private suspend fun event(text:String,role:String="a"):ChatTurn {
        core.beginInput(role);val t=ledger.begin(role,text,"synthetic-chat")
        val p=ExperienceProposal(quote=text,memory=text);val head=core.snapshot(role)
        assertTrue(core.accept(t,p,head){true});val after=core.snapshot(role)
        assertTrue(ledger.finishWithMemory(t.id,t.attempt,"合成回复",expectedStamp=ledger.context(t.id).stamp,onCompleted={core.finish(it,t,after,p)}){core.current(store.readableDatabase,after)})
        return t
    }
    @Test fun newExperienceEmbedsAndReachesProductionPromptThroughSemanticParaphrase():Unit=runBlocking {
        val t=event("做陶艺杯子");val index=CompanionVectorIndex(core,access,fake)
        index.index("a");assertEquals(1,calls);index.index("a");assertEquals(1,calls)
        val query="制作饮水器皿";assertEquals(0.0,MemoryRecall.relevance(query,"做陶艺杯子"),0.0)
        val scores=index.scores("a",query);assertEquals(1.0,scores.values.single(),0.0)
        val snapshot=core.snapshot("a",query,scores,200)
        val friend=store.friends().single{it.id=="a"}
        val built=CompanionPrompt.build(friend,RolePrompts(),emptyList(),query,listOf(PromptReference("新经历",snapshot.prompt,true))) as PromptBuild.Ready
        assertTrue(built.request.messages.any{"做陶艺杯子" in it.content&&"消息 ${t.userMessageId}，版本 1" in it.content})
        assertEquals(t.userMessageId.toString(),index.search("a",query,3).single().startMessageId)
    }
    @Test fun completedBackgroundReviewIndexesNewExperienceBeforeTheNextParaphrasedQuery():Unit=runBlocking {
        vault.setPurpose(ModelPurpose.CHAT,config.id)
        val repo=ProcessingRepository(store);repo.saveMemoryRules(MemoryRules(reviewRounds=1))
        val t=ledger.begin("a","做陶艺杯子",config.id);assertTrue(ledger.finish(t.id,t.attempt,"合成回复"))
        val index=CompanionVectorIndex(core,access,fake)
        index.index("a");assertEquals(0,calls) // No experience exists at reply completion.
        val accounting=ReplyAccounting(store,access,reviewer(t),fake)
        assertTrue(accounting.drain("a"));assertEquals(1,calls)
        val relation=core.relationship("a").inputs;assertEquals(1,relation.size)
        assertTrue(accounting.drain("a"));assertEquals(1,calls);assertEquals(1,reviews);assertEquals(relation,core.relationship("a").inputs)
        val query="制作饮水器皿";assertEquals(0.0,MemoryRecall.relevance(query,"做陶艺杯子"),0.0)
        assertEquals(t.userMessageId.toString(),index.search("a",query,3).single().startMessageId)
        val scores=index.scores("a",query);assertEquals(1.0,scores.values.single(),0.0)
        val prompt=core.snapshot("a",query,scores,200).prompt
        assertTrue(prompt.contains("做陶艺杯子"));assertTrue(prompt.contains("消息 ${t.userMessageId}，版本 1"))
        core.forget("a",t.userMessageId);assertTrue(index.search("a",query,3).isEmpty())
        assertEquals("做陶艺杯子",store.messages().single{it.mine}.text)
    }
    private fun reviewer(t:ChatTurn)=object:TextChatClient{
            override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
                reviews++
                val row=org.json.JSONArray(request.messages.last{it.role=="user"}.content).getJSONObject(0)
                assertEquals(t.userMessageId,row.getLong("source"));assertEquals("合成回复",row.getString("已实际说过的正文"))
                val record=org.json.JSONObject(RoleModelPayload.json(t.userMessageId)).put("experience",org.json.JSONObject()
                    .put("quote","做陶艺杯子").put("memory","做陶艺杯子").put("emotion","unknown").put("valence",0)
                    .put("relationship","none").put("control","none").put("targets",org.json.JSONArray())
                    .put("reconsider",org.json.JSONObject.NULL).put("claimKind","interaction").put("controlIntent","none").put("requestQuote",""))
                    .put("关系输入",org.json.JSONObject("""{"消息":"${t.userMessageId}","信息类别":"关于关系","P":"轻","C":"无","V":"无","B":"无"}"""))
                return TextChatResult.Success("""{"records":[{"source":${t.userMessageId},"record":$record}]}""")
            }
        }

    @Test fun committedReviewSurvivesIndexFailureAndExistingRebuildDoesNotRepeatAccounting():Unit=runBlocking { indexFailure(false) }
    @Test fun committedReviewSurvivesIndexCancellationAndExistingRebuildDoesNotRepeatAccounting():Unit=runBlocking { indexFailure(true) }
    private suspend fun indexFailure(cancel:Boolean)=coroutineScope {
        vault.setPurpose(ModelPurpose.CHAT,config.id)
        val repo=ProcessingRepository(store);repo.saveMemoryRules(MemoryRules(reviewRounds=1))
        val t=ledger.begin("a","做陶艺杯子",config.id);assertTrue(ledger.finish(t.id,t.attempt,"合成回复"))
        beforeReturn={if(cancel)throw CancellationException("synthetic index cancellation")else throw java.io.IOException("synthetic index failure")}
        val accounting=ReplyAccounting(store,access,reviewer(t),fake)
        if(cancel){val job=launch{accounting.drain("a")};job.join();assertTrue(job.isCancelled)}
        else assertTrue(accounting.drain("a"))
        assertEquals(1,reviews);assertEquals(1,core.experiences("a").size)
        val relation=core.relationship("a").inputs;assertEquals(1,relation.size);assertTrue((relation.single().p?:0.0)>0)
        assertTrue(repo.read{ReplyAccountingQueue.pending(it,"a").isEmpty()})
        assertEquals(0,store.readableDatabase.rawQuery("SELECT count(*) FROM companion_vectors",null).use{it.moveToFirst();it.getInt(0)})
        beforeReturn=null
        assertTrue(accounting.drain("a"));assertEquals(1,reviews);assertEquals(1,calls);assertEquals(relation,core.relationship("a").inputs)
        // Same rebuild entry called by MemoryEngine.afterChat/recover; no new scheduler or review.
        val index=CompanionVectorIndex(core,access,fake);index.index("a");assertEquals(2,calls)
        assertEquals(t.userMessageId.toString(),index.search("a","制作饮水器皿",3).single().startMessageId)
        assertEquals(1,reviews);assertEquals(relation,core.relationship("a").inputs)
        core.forget("a",t.userMessageId);assertTrue(index.search("a","制作饮水器皿",3).isEmpty())
    }
    @Test fun withdrawnAndLateIndexResponseCannotReappear():Unit=runBlocking {
        val t=event("做陶艺杯子");val index=CompanionVectorIndex(core,access,fake)
        beforeReturn={core.forget("a",t.userMessageId)};index.index("a");beforeReturn=null
        assertTrue(core.experiences("a").isEmpty());assertTrue(index.search("a","制作饮水器皿",3).isEmpty())
        assertEquals(0,store.readableDatabase.rawQuery("SELECT COUNT(*) FROM companion_vectors",null).use{it.moveToFirst();it.getInt(0)})
    }
    @Test fun roleAndConfigurationChangesCannotReuseOldVectors():Unit=runBlocking {
        event("做陶艺杯子","a");event("木工活动","b");val index=CompanionVectorIndex(core,access,fake)
        index.index("a");index.index("b")
        assertTrue(index.search("a","制作饮水器皿",3).all{"木工" !in it.content})
        vault.save(config.copy(modelId="another-embedding"),"synthetic-only")
        assertTrue(index.scores("a","制作饮水器皿").isEmpty());index.index("a");assertTrue(index.scores("a","制作饮水器皿").isNotEmpty())
    }
    @Test fun dimensionChangeInvalidatesInsteadOfMixingSpaces():Unit=runBlocking {
        event("做陶艺杯子");val index=CompanionVectorIndex(core,access,fake);index.index("a");dimension=3
        assertTrue(index.scores("a","制作饮水器皿").isEmpty());index.index("a")
        assertTrue(index.scores("a","制作饮水器皿").isNotEmpty())
    }
    @Test fun noEmbeddingUsesLexicalFallbackWithoutNetworkAndPromptHonorsBudget():Unit=runBlocking {
        event("做陶艺杯子");vault.setPurpose(ModelPurpose.EMBEDDING,"");val index=CompanionVectorIndex(core,access,fake)
        assertTrue(index.search("a","陶艺",3).isNotEmpty());assertTrue(index.search("a","制作饮水器皿",3).isEmpty());assertEquals(0,calls)
        assertFalse(core.snapshot("a","陶艺",tokenBudget=0).prompt.contains("做陶艺杯子"))
    }
    @Test fun productionRecallToolReturnsSemanticOnlyExperienceToTheNextModelRequest():Unit=runBlocking {
        val first=event("做陶艺杯子");val index=CompanionVectorIndex(core,access,fake);index.index("a")
        val repo=ProcessingRepository(store);repo.saveMemoryRules(MemoryRules(proactiveRecall=true))
        core.beginInput("a");val t=ledger.begin("a","制作饮水器皿","synthetic-chat")
        val tools=MemoryTools(repo,t,"制作饮水器皿",repo.references("a","制作饮水器皿",t.userMessageId),index){true}
        var steps=0
        val fakeChat=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
            steps++
            return if(steps==1)TextChatResult.Success("",listOf(ChatToolCall("synthetic-call","agent_recall_search","""{"query":"制作饮水器皿","limit":3}""")))
            else {assertTrue(request.messages.any{it.role=="tool"&&"做陶艺杯子" in it.content&&first.userMessageId.toString() in it.content});TextChatResult.Success("来自经历的合成答案")}
        }}
        val result=tools.run(config,"synthetic-only",TextChatRequest(listOf(WireMessage("system","仅合成测试"),WireMessage("user","制作饮水器皿"))),fakeChat)
        assertEquals("来自经历的合成答案",(result as TextChatResult.Success).text);assertEquals(2,steps)
    }
    @Test fun cancelledIndexAndSourceRevisionCannotPublishLateCache():Unit=runBlocking {
        val t=event("做陶艺杯子");val index=CompanionVectorIndex(core,access,fake)
        val started=CompletableDeferred<Unit>();val resume=CompletableDeferred<Unit>()
        beforeReturn={started.complete(Unit);resume.await()}
        val job=launch{index.index("a")};started.await();job.cancel();resume.complete(Unit);job.join();beforeReturn=null
        assertEquals(0,store.readableDatabase.rawQuery("SELECT COUNT(*) FROM companion_vectors",null).use{it.moveToFirst();it.getInt(0)})
        index.index("a")
        SourceChanges(ProcessingRepository(store)).let{changes->changes.confirm(changes.preview("a",t.userMessageId,"user","改成木工活动",false))}
        assertTrue(index.scores("a","制作饮水器皿").isEmpty())
    }
}
