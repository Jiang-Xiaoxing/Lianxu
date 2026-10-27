package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Only private synthetic databases, fake vectors and a fake text client. */
class MemorySemanticAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String;private lateinit var store:Store;private lateinit var repo:ProcessingRepository
    private lateinit var vault:ModelVault;private lateinit var scope:CoroutineScope
    @Before fun setup()=runBlocking {
        name="semantic-${System.nanoTime()}";store=Store(context,"$name.db");repo=ProcessingRepository(store)
        store.add(listOf(Friend("a","A","合成"),Friend("b","B","合成")),false)
        vault=ModelVault(context,name);scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val c=ModelConfig("summary","合成总结","custom","https://example.invalid/v1","mock",64000,8192)
        vault.save(c,"synthetic-summary-only");vault.setPurpose(ModelPurpose.MEMORY,c.id)
        vault.save(c.copy(id="embedding",name="合成向量"),"synthetic-embedding-only");vault.setPurpose(ModelPurpose.EMBEDDING,"embedding")
    }
    @After fun close()=runBlocking {scope.cancel();vault.list().forEach{vault.delete(it.id)};store.close();context.deleteDatabase("$name.db");Unit}
    private suspend fun source(text:String):SourceDialogue {val l=ChatLedger(store);val t=l.begin("a",text,"synthetic");assertTrue(l.finish(t.id,t.attempt,"合成回复"));return repo.sources("a").last()}
    private fun operation(s:SourceDialogue,text:String,id:String="one",target:String="new:one")=JSONObject().put("id",id).put("op","ADD").put("target",target).put("category","PREFERENCE_BOUNDARY").put("about","USER").put("text",text).put("core",false).put("evidence",JSONArray().put(JSONObject().put("turn",s.source.turnId).put("version",s.source.version).put("side","user").put("quote",s.user.text)))
    private suspend fun old():StoredMemory {
        val s=source("我一直不喝咖啡。");val b=MemoryBatchRepository(repo);val w=b.prepare("a",listOf(s),"synthetic");b.beginAttempt(w,"synthetic")
        val id=b.apply(w,operation(s,"我不喝咖啡了"));assertTrue(b.complete(w,setOf("one"),1,w.id));return repo.memories("a").single{it.id==id}
    }
    private class Embeddings:MemoryEmbeddingClient {
        val requests=mutableListOf<List<String>>()
        var values:(String)->DoubleArray={doubleArrayOf(1.0,0.0)}
        var fail=false
        override suspend fun embed(credential:ModelCredential,texts:List<String>,timeout:Int):List<DoubleArray>{requests+=texts;if(fail)error("synthetic failure");return texts.map(values)}
    }
    private class Model(var texts:List<String>):TextChatClient {
        var summaries=0;var judgments=0;var incomplete=false
        var beforeJudge:suspend()->Unit={}
        var verdict:(String,String)->String={a,b->if("咖啡" in a&&"咖啡" in b)"DUPLICATE"else"DIFFERENT"}
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
            val d=JSONObject(request.messages.last().content)
            if(!d.has("facts")){
                summaries++;val ops=JSONArray();texts.forEachIndexed{i,t->ops.put(JSONObject().put("id","op$i").put("op","ADD").put("target","N${i+1}").put("category","PREFERENCE_BOUNDARY").put("about","USER").put("text",t).put("core",false).put("evidence",JSONArray().put("S1")))}
                return TextChatResult.Success(JSONObject().put("batch_id",d.getString("batch_id")).put("character_id","a").put("operations",ops).put("operation_count",ops.length()).put("complete",true).put("end_marker",d.getString("batch_id")).toString())
            }
            judgments++;beforeJudge();val facts=d.getJSONArray("facts").let{a->(0 until a.length()).map{a.getJSONObject(it)}.associateBy{it.getString("ref")}}
            val response=JSONObject().put("batch_id",d.getString("batch_id")).put("complete",true)
            for(kind in listOf("new_old","new_new")){
                val rows=JSONArray();val pairs=d.getJSONArray(kind)
                for(i in 0 until pairs.length()){
                    val p=pairs.getJSONObject(i);val a=facts.getValue(p.getString("left")).getString("text");val b=facts.getValue(p.getString("right")).getString("text")
                    rows.put(JSONObject().put("id",p.getString("id")).put("verdict",verdict(a,b)).put("left_quote",a).put("right_quote",b).put("reason","合成配对判断"))
                }
                response.put(kind,rows)
            }
            if(incomplete&&response.getJSONArray("new_new").length()>0)response.getJSONArray("new_new").remove(0)
            return TextChatResult.Success(response.toString())
        }
    }
    private fun engine(model:Model,embedding:Embeddings,timeout:Long=300000)=MemoryEngine(scope,repo,ModelAccess(vault),model,{},timeout).also{it.semanticEmbeddingClient=embedding}
    @Test fun onePoolAndOneJudgmentCoverNewOldAndNewNewWithEvidenceAndCacheReuse()=runBlocking {
        val old=old();source("我不喝咖啡，咖啡已经不喝了，我喜欢茶。")
        val m=Model(listOf("我不喝咖啡","我不再喝咖啡","我喜欢茶"));val e=Embeddings()
        assertTrue(engine(m,e).ensureRoom("a",forceOne=true).success)
        assertEquals(1,m.summaries);assertEquals(1,m.judgments);assertEquals(1,e.requests.size)
        val values=repo.memories("a");assertEquals(2,values.size);val coffee=values.single{it.id==old.id}
        assertEquals(old.text,coffee.text);assertEquals(2,coffee.evidence.size);assertEquals(2,coffee.version)
        assertEquals(3,repo.read{it.rawQuery("SELECT 1 FROM memory_operations WHERE batch_id<>(SELECT batch_id FROM memory_retired ORDER BY rowid LIMIT 1)",null).use{c->c.count}})
        MemoryVectorIndex(repo,ModelAccess(vault),e).index("a");assertEquals(1,e.requests.size)
    }
    @Test fun newNewDuplicateDoesNotNeedAnOldMemoryAndKeepsBothAliases()=runBlocking {
        source("我不喝咖啡，已经不喝咖啡了，也喜欢茶。");val m=Model(listOf("我不喝咖啡","我不再喝咖啡","我喜欢茶"));val e=Embeddings()
        assertTrue(engine(m,e).ensureRoom("a",forceOne=true).success);assertEquals(2,repo.memories("a").size);assertEquals(1,m.judgments)
        val aliases=repo.read{db->db.rawQuery("SELECT result_id FROM memory_operations WHERE op_key IN('op0','op1') ORDER BY op_key",null).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}};assertEquals(2,aliases.size);assertEquals(aliases[0],aliases[1])
    }
    @Test fun noEmbeddingConfigKeepsOriginalSummaryAndMakesNoExtraCalls()=runBlocking {
        vault.setPurpose(ModelPurpose.EMBEDDING,"");source("我喜欢茶。");val m=Model(listOf("我喜欢茶"));val e=Embeddings()
        assertTrue(engine(m,e).ensureRoom("a",forceOne=true).success);assertEquals(1,m.summaries);assertEquals(0,m.judgments);assertTrue(e.requests.isEmpty())
    }
    @Test fun noSuspiciousGroupMakesNoJudgmentRequest()=runBlocking {
        old();source("我喜欢茶。");val m=Model(listOf("我喜欢茶"));val e=Embeddings().apply{values={if("咖啡" in it)doubleArrayOf(1.0,0.0)else doubleArrayOf(0.0,1.0)}}
        assertTrue(engine(m,e).ensureRoom("a",forceOne=true).success);assertEquals(0,m.judgments);assertEquals(2,repo.memories("a").size)
    }
    @Test fun incompleteJudgmentsFallBackToAllOriginalFactsWithoutAPartialMerge()=runBlocking {
        val before=old();source("我不喝咖啡，也喜欢茶。");val m=Model(listOf("我不喝咖啡","我不再喝咖啡","我喜欢茶")).apply{incomplete=true};val e=Embeddings()
        assertTrue(engine(m,e).ensureRoom("a",forceOne=true).success);assertEquals(4,repo.memories("a").size);assertEquals(before,repo.memories("a").single{it.id==before.id})
    }
    @Test fun protectedDuplicateBecomesOneCandidateAndNeverOverwritesTheManualMemory()=runBlocking {
        val automatic=old();val protected=repo.manualSave("a",automatic.id,automatic.text,automatic.category,false,true,MemoryAbout.USER)
        source("我仍不喝咖啡，咖啡不喝。");val m=Model(listOf("我不喝咖啡","我不再喝咖啡"));val e=Embeddings()
        assertTrue(engine(m,e).ensureRoom("a",forceOne=true).success);assertEquals(protected,repo.memories("a").single());assertEquals(1,repo.candidates("a").size)
        assertTrue(repo.candidates("a").single().proposed.evidence.isNotEmpty())
    }
    @Test fun correctionRequiresCandidateConfirmationAndPreservesThePreviousFact()=runBlocking {
        val before=old();source("更正一下，我现在喜欢喝咖啡了。");val m=Model(listOf("我现在喜欢喝咖啡")).apply{verdict={_,_->"CORRECTION"}};val e=Embeddings()
        assertTrue(engine(m,e).ensureRoom("a",forceOne=true).success);assertEquals(before,repo.memories("a").single())
        val candidate=repo.candidates("a").single();assertEquals("我现在喜欢喝咖啡",candidate.proposed.text);assertTrue(candidate.valid)
        repo.resolveCandidate("a",candidate.id,true,before.version);assertEquals(candidate.proposed.text,repo.memories("a").single().text)
    }
    @Test fun lateJudgmentCannotOverwriteAConcurrentAutomaticCorrection()=runBlocking {
        val before=old();source("我不喝咖啡。");val m=Model(listOf("我不喝咖啡"));val e=Embeddings()
        m.beforeJudge={repo.tx{db->repo.saveMemory(db,before.copy(text="并发写入的正确事实",version=before.version+1))}}
        assertFalse(engine(m,e).ensureRoom("a",forceOne=true).success)
        assertEquals(listOf("并发写入的正确事实"),repo.memories("a").map{it.text});assertEquals(1,repo.recentSources("a").size)
    }
    @Test fun cancelDuringJudgmentRejectsItsLateDecisionAndReleasesManualEditing()=runBlocking {
        old();source("我不喝咖啡。");val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val m=Model(listOf("我不喝咖啡")).apply{beforeJudge={withContext(NonCancellable){entered.complete(Unit);release.await()}}};val e=Embeddings();val engine=engine(m,e)
        val job=scope.async{engine.ensureRoom("a",forceOne=true)};withTimeout(5000){entered.await()};engine.cancel("a");repo.manualSave("b",null,"其他角色",MemoryCategory.STATE_FACT,false,true,MemoryAbout.USER)
        store.memorySummary.requireEditable("a");release.complete(Unit);runCatching{job.await()};assertEquals(1,repo.memories("a").size);assertEquals(1,repo.recentSources("a").size)
    }
    @Test fun invalidReceiptRollsBackTheWholeResolvedBatch()=runBlocking {
        val s=source("我喜欢茶，也喜欢散步。");val b=MemoryBatchRepository(repo);val w=b.prepare("a",listOf(s),"synthetic");b.beginAttempt(w,"synthetic")
        val ops=listOf(operation(s,"我喜欢茶","one","new:one"),operation(s,"我喜欢散步","two","new:two"));val snapshot=b.previewSemantic(w,ops){true}
        assertTrue(repo.memories("a").isEmpty());assertTrue(runCatching{b.applySemantic(w,ops,ops,snapshot,1,w.id,{true},ChatAttemptLease())}.isFailure)
        assertTrue(repo.memories("a").isEmpty());assertTrue(b.applied(w).isEmpty());assertEquals(0L,repo.cursor("a"))
    }
}
