package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Isolated synthetic DB and mock transport; never uses the user's model configuration. */
class AdaptiveMemoryEngineTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store
    private lateinit var repo:ProcessingRepository
    private lateinit var vault:ModelVault
    private lateinit var name:String
    private lateinit var scope:CoroutineScope
    @Before fun setup()=runBlocking {
        name="adaptive-${System.nanoTime()}.db";store=Store(context,name);repo=ProcessingRepository(store)
        store.add(listOf(Friend("a","合成甲","合成设定"),Friend("b","合成乙","隔离角色")),false)
        vault=ModelVault(context,"adaptive-${System.nanoTime()}")
        val cfg=ModelConfig("synthetic","Synthetic","custom","https://example.invalid/v1","synthetic",64000,2048)
        vault.save(cfg,"synthetic-key-only");vault.setPurpose(ModelPurpose.MEMORY,cfg.id)
        repo.saveMemoryRules(MemoryRules(adaptiveContext=true));scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    }
    @After fun close(){scope.cancel();vault.list().forEach{vault.delete(it.id)};store.close();context.deleteDatabase(name)}
    private suspend fun turns(count:Int,size:Int=4,role:String="a"){
        val ledger=ChatLedger(store)
        repeat(count){val t=ledger.begin(role,"甲".repeat(size),"synthetic");assertTrue(ledger.finish(t.id,t.attempt,"乙".repeat(size)))}
    }
    private fun client(visit:(JSONObject)->Unit={})=object:TextChatClient {
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
            assertEquals("synthetic-key-only",key)
            assertTrue(request.messages.sumOf{ConservativeTokens.tokens(it.content)}+request.outputLimit<=config.contextTokens)
            val d=JSONObject(request.messages.last().content);visit(d)
            return TextChatResult.Success(JSONObject().put("batch_id",d.getString("batch_id")).put("character_id","a").put("operations",JSONArray()).put("operation_count",0).put("complete",true).put("end_marker",d.getString("batch_id")).toString())
        }
    }
    @Test fun seventyShortRoundsNeedNoSummaryRequest()=runBlocking {
        turns(70);var calls=0
        val run=MemoryEngine(scope,repo,ModelAccess(vault),client{calls++},{}).ensureRoom("a",historyBudget=16000)
        assertTrue(run.success);assertEquals(0,calls);assertEquals(70,repo.recentSources("a").size)
    }
    @Test fun longDialogueUsesCompletePrefixBeforeFortyAndPreservesOriginals()=runBlocking {
        turns(10,300);turns(1,10,"b")
        val original=repo.sources("a");val expected=AdaptiveContext.summaryCount(original.map{it.tokens},5000)
        var actual=emptyList<Long>()
        val run=MemoryEngine(scope,repo,ModelAccess(vault),client{d->val a=d.getJSONArray("target_dialogue");actual=(0 until a.length()).map{a.getJSONObject(it).getLong("turn")}},{}).ensureRoom("a",forceOne=true,historyBudget=5000)
        assertTrue(run.success);assertEquals(original.take(expected).map{it.source.turnId},actual)
        assertEquals(original.drop(expected),repo.recentSources("a"));assertEquals(original,repo.sources("a"));assertEquals(1,repo.recentSources("b").size)
    }
    @Test fun newAdaptiveBatchFitsSmallerSummaryModelBeforeItStarts()=runBlocking {
        turns(12,900);val config=vault.list().single();vault.save(config.copy(contextTokens=16000),"synthetic-key-only")
        val original=repo.sources("a");val planned=AdaptiveContext.summaryCount(original.map{it.tokens},4000)
        var actual=0
        val run=MemoryEngine(scope,repo,ModelAccess(vault),client{actual=it.getJSONArray("target_dialogue").length()},{}).ensureRoom("a",forceOne=true,historyBudget=4000)
        assertTrue(run.success);assertTrue(actual in 1 until planned);assertEquals(original.drop(actual),repo.recentSources("a"));assertEquals(original,repo.sources("a"))
    }
    @Test fun startedBatchCannotShrinkWhenRetryModelBecomesSmaller()=runBlocking {
        turns(10,900);val original=repo.sources("a")
        val batches=MemoryBatchRepository(repo);val work=batches.prepare("a",original.take(8),"synthetic")
        batches.beginAttempt(work,"synthetic");batches.fail(work,"CANCELLED")
        val config=vault.list().single();vault.save(config.copy(contextTokens=16000),"synthetic-key-only")
        var calls=0
        val run=MemoryEngine(scope,repo,ModelAccess(vault),client{calls++},{}).ensureRoom("a",forceOne=true,manualRetry=true,historyBudget=2000)
        assertFalse(run.success);assertEquals("CAPACITY",run.code);assertEquals(0,calls)
        assertEquals(work.targets,batches.pending("a")!!.targets);assertEquals(original,repo.recentSources("a"))
    }
    @Test fun modePersistsAcrossDatabaseReopenAndDoesNotAlterLegacyCounts()=runBlocking {
        val old=MemoryRules().json().also{it.remove("adaptiveContext")};assertFalse(MemoryRules.from(old).adaptiveContext)
        val rules=MemoryRules(53,17,21,adaptiveContext=true);repo.saveMemoryRules(rules)
        store.close();store=Store(context,name);repo=ProcessingRepository(store)
        assertEquals(rules,repo.memoryRules().first)
    }
    private fun runner(client:TextChatClient):ConversationRunner {
        val access=ModelAccess(vault)
        return ConversationRunner(scope,store,vault,client,repo,MemoryEngine(scope,repo,access,client,{}),{}, {})
    }
    private fun sentCost(request:TextChatRequest):Int {
        val messages=request.messages.map{m->buildMap<String,Any>{
            put("role",m.role);put("content",m.content)
            if(m.name.isNotEmpty())put("name",m.name)
            if(m.toolCallId.isNotEmpty())put("tool_call_id",m.toolCallId)
            if(m.toolCalls.isNotEmpty())put("tool_calls",m.toolCalls.map{mapOf("id" to it.id,"type" to "function","function" to mapOf("name" to it.name,"arguments" to it.arguments))})
        }}
        return estimateLocalMessagesTokens(messages)+ConservativeTokens.tokens(com.google.gson.Gson().toJson(request.tools))+request.outputLimit+request.messages.sumOf{it.continuation?.tokenCost?:0}
    }
    @Test fun productionRunnerKeepsSeventyShortRoundsAndRepliesWithoutForegroundAccounting()=runBlocking {
        turns(70);val config=vault.list().single();vault.save(config.copy(contextTokens=64000),"synthetic-key-only");vault.setPurpose(ModelPurpose.CHAT,config.id)
        var stages=0;var finalMessages=0
        val transport=object:TextChatClient {override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
            assertEquals("synthetic-key-only",key);assertTrue(sentCost(request)<=config.contextTokens)
            syntheticCompanionStage(request)?.let{stages++;return it}
            assertFalse(request.messages.last().content.contains("target_dialogue"));finalMessages=request.messages.count{it.sourceMessageId.isNotBlank()}
            return TextChatResult.Success("合成正式回复")
        }}
        val runner=runner(transport);withContext(Dispatchers.Main){assertTrue(runner.send("a","今天好吗"){})}
        withTimeout(20000){while("a" in runner.sending)delay(20)}
        val last=ChatLedger(store).all().last();assertEquals(TurnState.COMPLETED,last.state)
        assertEquals(0,stages);assertEquals(140,finalMessages);assertEquals(71,repo.recentSources("a").size)
    }
    @Test fun productionRunnerSummarizesLongHistoryThenPublishesReply()=runBlocking {
        turns(10,500);val config=vault.list().single();vault.save(config.copy(contextTokens=16000),"synthetic-key-only");vault.setPurpose(ModelPurpose.CHAT,config.id)
        var summaries=0;var replies=0
        val summary=client{summaries++}
        val transport=object:TextChatClient {override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
            syntheticCompanionStage(request)?.let{return it}
            if(request.messages.any{"target_dialogue" in it.content})return summary.complete(config,key,request)
            replies++;return TextChatResult.Success("合成正式回复")
        }}
        val runner=runner(transport);withContext(Dispatchers.Main){assertTrue(runner.send("a","接着聊"){})}
        withTimeout(20000){while("a" in runner.sending)delay(20)}
        val last=ChatLedger(store).all().last();assertEquals("${last.failure}",TurnState.COMPLETED,last.state)
        assertTrue(summaries>0);assertEquals(1,replies);assertTrue(repo.recentSources("a").size<11);assertEquals(22,store.messages().size)
    }
    @Test fun sameSeventyRoundsAt16kSummarizeWithoutOverflowOrStalling()=runBlocking {
        turns(70);val config=vault.list().single();vault.save(config.copy(contextTokens=16000),"synthetic-key-only");vault.setPurpose(ModelPurpose.CHAT,config.id)
        var summaries=0;var replies=0;var peak=0
        val summary=client{summaries++}
        val transport=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
            val used=sentCost(request);peak=maxOf(peak,used)
            check(used<=config.contextTokens){"Synthetic request exceeded configured capacity: $used"}
            if(request.messages.any{"target_dialogue" in it.content})return summary.complete(config,key,request)
            replies++;return TextChatResult.Success("合成正式回复")
        }}
        val runner=runner(transport);withContext(Dispatchers.Main){assertTrue(runner.send("a","今天好吗"){})}
        withTimeout(20000){while("a" in runner.sending)delay(20)}
        val last=ChatLedger(store).all().last();assertEquals("${last.failure}; summaries=$summaries replies=$replies recent=${repo.recentSources("a").size}",TurnState.COMPLETED,last.state)
        assertTrue(summaries>0);assertEquals(1,replies);assertTrue(peak<=16000)
        assertEquals(71,repo.sources("a").size);assertEquals(142,store.messages().size);assertTrue(repo.recentSources("a").size<71)
        println("CAPACITY_16K summaries=$summaries replies=$replies peak_input_plus_output=$peak raw_rounds=71")
    }
}
