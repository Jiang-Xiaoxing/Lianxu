package app.lianxu.independent
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.*
import org.junit.*
import org.junit.Assert.*

class MemoryCountsEngineTest{
    private val c get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store;private lateinit var repo:ProcessingRepository;private lateinit var vault:ModelVault;private lateinit var name:String;private lateinit var scope:CoroutineScope
    @Before fun setup()=runBlocking{name="counts-${System.nanoTime()}.db";store=Store(c,name);repo=ProcessingRepository(store);store.add(listOf(Friend("a","A","Synthetic"),Friend("b","B","Synthetic")),false);vault=ModelVault(c,"counts-${System.nanoTime()}");val cfg=ModelConfig("memory","Synthetic","custom","https://example.invalid/v1","memory",contextTokens=64000,outputTokens=2048);vault.save(cfg,"synthetic-key-only");vault.setPurpose(ModelPurpose.MEMORY,cfg.id);scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)}
    @After fun close(){scope.cancel();vault.list().forEach{vault.delete(it.id)};store.close();c.deleteDatabase(name)}
    private suspend fun turns(n:Int,text:String="Synthetic user",id:String="a"){val l=ChatLedger(store);repeat(n){val t=l.begin(id,text,"synthetic");assertTrue(l.finish(t.id,t.attempt,"Synthetic reply"))}}
    private fun receipt(request:TextChatRequest):TextChatResult{val d=JSONObject(request.messages.last().content);return TextChatResult.Success(JSONObject().put("batch_id",d.getString("batch_id")).put("character_id","a").put("operations",JSONArray()).put("operation_count",0).put("complete",true).put("end_marker",d.getString("batch_id")).toString())}
    @Test fun fortyNoRequestFortyOneRetiresExactlyEarliestTwenty()=runBlocking{
        turns(40);turns(1,id="b");val targets=mutableListOf<List<Long>>()
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{val a=JSONObject(request.messages.last().content).getJSONArray("target_dialogue");targets+=(0 until a.length()).map{a.getJSONObject(it).getLong("turn")};return receipt(request)}}
        val e=MemoryEngine(scope,repo,ModelAccess(vault),client,{})
        assertTrue(e.ensureRoom("a","x".repeat(12000)).success);assertTrue(targets.isEmpty());turns(1);val originals=repo.sources("a");assertTrue(e.ensureRoom("a").success)
        assertEquals(listOf(originals.take(20).map{it.source.turnId}),targets);assertEquals(21,repo.recentSources("a").size);assertEquals(82,store.messages().count{it.friendId=="a"});assertEquals(1,repo.recentSources("b").size)
    }
    @Test fun continuousBatchesKeepCountAndAllRawMessages()=runBlocking{
        turns(101);val sizes=mutableListOf<Int>();val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{sizes+=JSONObject(request.messages.last().content).getJSONArray("target_dialogue").length();return receipt(request)}}
        assertTrue(MemoryEngine(scope,repo,ModelAccess(vault),client,{}).ensureRoom("a").success);assertEquals(listOf(20,20,20,20),sizes);assertEquals(21,repo.recentSources("a").size);assertEquals(202,store.messages().count{it.friendId=="a"})
    }
    @Test fun backgroundCheckStartsOnlyAfterFortyFirstSavedReply()=runBlocking{
        turns(40);val calls=java.util.concurrent.atomic.AtomicInteger();val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls.incrementAndGet();return receipt(request)}}
        val e=MemoryEngine(scope,repo,ModelAccess(vault),client,{});e.afterChat("a");delay(200);assertEquals(0,calls.get());turns(1);e.afterChat("a")
        withTimeout(10000){while(repo.recentSources("a").size!=21)delay(10)};assertEquals(1,calls.get())
    }
    @Test fun incompleteFailureRestartAndManualRetryKeepSameTwenty()=runBlocking{
        repo.saveMemoryRules(MemoryRules());turns(41);var batch="";var calls=0
        val failClient=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls++;val d=JSONObject(request.messages.last().content);if(batch.isEmpty())batch=d.getString("batch_id") else assertEquals(batch,d.getString("batch_id"));return TextChatResult.Success("{}")}}
        assertFalse(MemoryEngine(scope,repo,ModelAccess(vault),failClient,{}).ensureRoom("a").success);assertEquals(3,calls);assertEquals(41,repo.recentSources("a").size);assertEquals(0L,repo.cursor("a"))
        store.close();store=Store(c,name);repo=ProcessingRepository(store);assertEquals(MemoryRules(),repo.memoryRules().first)
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{val d=JSONObject(request.messages.last().content);assertEquals(batch,d.getString("batch_id"));assertEquals(20,d.getJSONArray("target_dialogue").length());return receipt(request)}}
        assertTrue(MemoryEngine(scope,repo,ModelAccess(vault),client,{}).ensureRoom("a",manualRetry=true).success);assertEquals(21,repo.recentSources("a").size)
    }
    @Test fun changedRulesRejectLateBatchAndNewSettingsPersist()=runBlocking{
        turns(41);val b=MemoryBatchRepository(repo);val w=b.prepare("a",repo.recentSources("a").take(20),"synthetic");b.beginAttempt(w,"test");repo.saveMemoryRules(MemoryRules(46,17,9))
        try{b.complete(w,emptySet(),0,w.id);fail()}catch(e:ProcessingRejected){assertEquals("SOURCE_CHANGED",e.code)};assertEquals(0L,repo.cursor("a"));assertEquals(41,repo.recentSources("a").size)
        try{b.beginAttempt(w,"stale-retry");fail()}catch(e:ProcessingRejected){assertEquals("SOURCE_CHANGED",e.code)}
        var calls=0;val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls++;assertEquals(20,JSONObject(request.messages.last().content).getJSONArray("target_dialogue").length());return receipt(request)}}
        val engine=MemoryEngine(scope,repo,ModelAccess(vault),client,{});assertFalse(engine.ensureRoom("a",forceOne=true).success);assertEquals(0,calls);assertTrue(engine.ensureRoom("a",forceOne=true,manualRetry=true).success);assertEquals(1,calls);assertEquals(21,repo.recentSources("a").size)
        store.close();store=Store(c,name);repo=ProcessingRepository(store);assertEquals(MemoryRules(46,17,9),repo.memoryRules().first)
    }
    @Test fun summaryCapacityCannotShrinkConfiguredTwenty()=runBlocking{
        turns(41,"x".repeat(1500));val cfg=vault.list().single();vault.save(cfg.copy(contextTokens=16000),"synthetic-key-only");var calls=0
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls++;return receipt(request)}}
        val result=MemoryEngine(scope,repo,ModelAccess(vault),client,{}).ensureRoom("a");assertFalse(result.success);assertEquals("CAPACITY",result.code);assertEquals(0,calls);assertEquals(41,repo.recentSources("a").size);assertEquals("CAPACITY",repo.memoryStatus("a").error);assertEquals(20,MemoryBatchRepository(repo).pending("a")!!.targets.size)
    }
    @Test fun relatedCountRankingNoPaddingCoreSeparateAndFailedTurnsIgnored()=runBlocking{
        for(i in 1..20)repo.manualSave("a",null,"Synthetic $i",MemoryCategory.STATE_FACT,false,true,MemoryAbout.USER,listOf(if(i==20)"garden tea" else "garden"))
        repo.manualSave("a",null,"CORE",MemoryCategory.STATE_FACT,true,true,MemoryAbout.USER);repo.manualSave("b",null,"OTHER",MemoryCategory.STATE_FACT,false,true,MemoryAbout.BOTH,listOf("garden"))
        val refs=repo.references("a","garden tea",1).valid;assertEquals(15,refs.count{it.related});assertTrue("Synthetic 20" in refs.first{it.related}.text);assertEquals(1,refs.count{it.mandatory});assertFalse(refs.any{"OTHER" in it.text});assertEquals(0,repo.references("a","missing",2).valid.count{it.related})
        val l=ChatLedger(store);val t=l.begin("a","pending","synthetic");assertEquals(0,repo.recentSources("a").size);l.fail(t.id,1,ChatFailure.TIMEOUT);assertEquals(0,repo.recentSources("a").size);val retry=l.retry(t.id,"synthetic")!!;l.finish(retry.id,retry.attempt,"reply");assertEquals(1,repo.recentSources("a").size);assertFalse(l.finish(retry.id,retry.attempt,"duplicate"));assertEquals(1,repo.recentSources("a").size)
    }
}
