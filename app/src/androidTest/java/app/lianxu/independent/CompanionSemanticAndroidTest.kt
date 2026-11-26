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
    @Test fun backgroundReviewLeavesLegacyIndexEmpty():Unit=runBlocking {
        vault.setPurpose(ModelPurpose.CHAT,config.id)
        ProcessingRepository(store).saveMemoryRules(MemoryRules(reviewRounds=1))
        val t=ledger.begin("a","做陶艺杯子",config.id)
        assertTrue(ledger.finish(t.id,t.attempt,"合成回复"))
        val accounting=ReplyAccounting(store,access,reviewer(t))
        assertTrue(accounting.drain("a"))
        assertTrue(accounting.drain("a"))
        assertEquals(1,reviews)
        assertEquals(0,store.readableDatabase.rawQuery("SELECT COUNT(*) FROM companion_vectors",null).use { it.moveToFirst(); it.getInt(0) })
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
    @Test fun productionRecallToolReturnsLexicalExperienceToTheNextModelRequest():Unit=runBlocking {
        val first=event("做陶艺杯子")
        val repo=ProcessingRepository(store);repo.saveMemoryRules(MemoryRules(proactiveRecall=true))
        core.beginInput("a");val t=ledger.begin("a","陶艺","synthetic-chat")
        val tools=MemoryTools(repo,t,"陶艺",repo.references("a","陶艺",t.userMessageId)){true}
        var steps=0
        val fakeChat=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
            steps++
            return if(steps==1)TextChatResult.Success("",listOf(ChatToolCall("synthetic-call","agent_recall_search","""{"query":"陶艺","limit":3}""")))
            else {assertTrue(request.messages.any{it.role=="tool"&&"做陶艺杯子" in it.content&&first.userMessageId.toString() in it.content});TextChatResult.Success("来自经历的合成答案")}
        }}
        val result=tools.run(config,"synthetic-only",TextChatRequest(listOf(WireMessage("system","仅合成测试"),WireMessage("user","陶艺"))),fakeChat)
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
