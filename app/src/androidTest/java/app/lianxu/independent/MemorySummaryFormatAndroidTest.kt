package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.CopyOnWriteArrayList

/** Synthetic stores and transport only; real engine, guarded whole-batch validation and persistence. */
class MemorySummaryFormatAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String;private lateinit var store:Store;private lateinit var repo:ProcessingRepository;private lateinit var vault:ModelVault;private lateinit var scope:CoroutineScope
    private val requests=CopyOnWriteArrayList<JSONObject>()
    @Before fun setup()=runBlocking{
        name="summary-format-${System.nanoTime()}";store=Store(context,"$name.db");repo=ProcessingRepository(store);store.add(listOf(Friend("a","A","Synthetic")),false)
        val ledger=ChatLedger(store);val turn=ledger.begin("a","synthetic coffee fact","synthetic");assertTrue(ledger.finish(turn.id,turn.attempt,"synthetic reply"))
        vault=ModelVault(context,name);val config=ModelConfig("synthetic","Synthetic","custom","https://api.xiaomimimo.com/v1","mimo-v2.6-flash",64000,2048)
        vault.save(config,"synthetic-only");vault.setPurpose(ModelPurpose.MEMORY,config.id);scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    }
    @After fun close()=runBlocking{scope.coroutineContext.job.cancelAndJoin();vault.list().forEach{vault.delete(it.id)};store.close();context.deleteDatabase("$name.db");Unit}
    private fun op(id:String,target:String,text:String="synthetic coffee fact")=JSONObject().put("id",id).put("op","ADD").put("target",target).put("category","STATE_FACT").put("about","USER").put("text",text).put("evidence",JSONArray().put("S1"))
    private fun client(receipt:(Int,JSONObject)->Unit={_,_->},reply:suspend(Int,JSONObject)->List<JSONObject>)=object:TextChatClient{
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
            assertTrue(request.jsonObject);assertTrue(request.messages.first().content.contains("完整格式示例"));assertTrue(request.messages.first().content.contains("example-op-1"))
            val d=JSONObject(request.messages.last().content);requests+=d;val ops=reply(requests.size,d)
            val saved=d.getJSONArray("already_saved_operations");val keys=(0 until saved.length()).map{saved.getJSONObject(it).getString("id")}.toMutableSet()
            ops.forEachIndexed{i,o->keys+=o.optString("id").ifBlank{"missing-$i"}}
            val response=JSONObject().put("batch_id",d.getString("batch_id")).put("character_id","a").put("operations",JSONArray().apply{ops.forEach{put(it)}}).put("operation_count",keys.size).put("complete",true).put("end_marker",d.getString("batch_id"))
            receipt(requests.size,response);return TextChatResult.Success(response.toString())
        }
    }
    private fun engine(c:TextChatClient)=MemoryEngine(scope,repo,ModelAccess(vault),c,{})
    @Test fun everyMissingIdIsReportedAndNothingPublishesUntilTheRepairedBatch(){runBlocking{
        val result=engine(client{n,d->
            if(n==2){
                assertTrue(repo.memories("a").isEmpty());assertEquals(0L,repo.cursor("a"))
                val errors=d.getJSONObject("validation_feedback").getJSONArray("errors");assertEquals(5,errors.length())
                repeat(5){i->assertEquals("operations[${i+1}]",errors.getJSONObject(i).getString("operation"));assertTrue(errors.getJSONObject(i).getString("problem").contains("id"))}
                assertEquals(requests[0].getJSONArray("target_sources").toString(),d.getJSONArray("target_sources").toString());assertEquals(requests[0].getString("batch_id"),d.getString("batch_id"))
            }
            (1..5).map{op("fact-$it","N$it","synthetic coffee fact $it").also{o->if(n==1)o.remove("id")}}
        }).ensureRoom("a",forceOne=true)
        assertTrue(result.code,result.success);assertEquals(2,requests.size);assertEquals(5,repo.memories("a").size);assertTrue(repo.recentSources("a").isEmpty())
    }}
    @Test fun failedMissingIdBatchKeepsSourcesAndCanBeManuallyRecovered(){runBlocking{
        var repaired=false
        val e=engine(client{_,_->listOf(op("stable","N1").also{if(!repaired)it.remove("id")})})
        val failed=e.ensureRoom("a",forceOne=true);assertFalse(failed.success);assertEquals("FORMAT",failed.code);assertEquals(3,requests.size)
        assertTrue(repo.memories("a").isEmpty());assertEquals(1,repo.recentSources("a").size);assertEquals(0L,repo.cursor("a"));assertTrue(requireNotNull(MemoryBatchRepository(repo).failure("a")).detail.contains("id"))
        repaired=true;assertTrue(e.ensureRoom("a",forceOne=true,manualRetry=true).success);assertEquals(1,repo.memories("a").size);assertNull(MemoryBatchRepository(repo).failure("a"))
    }}
    @Test fun repairedMReferenceUpdatesTheActuallyProvidedMemoryAndExactDuplicatesSaveOnce(){runBlocking{
        val source=repo.sources("a").single();val proof=listOf(MemoryEvidence(source.source,"user",source.user.text,source.user.text,source.user.created,source.zone))
        repo.tx{db->repo.saveMemory(db,StoredMemory("z-shown","a",MemoryCategory.STATE_FACT,"original z",about=MemoryAbout.USER,evidence=proof));repo.saveMemory(db,StoredMemory("a-other","a",MemoryCategory.STATE_FACT,"original a",about=MemoryAbout.USER,evidence=proof))}
        val result=engine(client{n,d->
            val rows=d.getJSONArray("existing_memories");assertEquals("a-other",rows.getJSONObject(0).getString("id"));assertEquals("M1",rows.getJSONObject(0).getString("ref"));assertEquals("z-shown",rows.getJSONObject(1).getString("id"));assertEquals("M2",rows.getJSONObject(1).getString("ref"))
            if(n==2){assertEquals(requests[0].getJSONArray("existing_memories").toString(),rows.toString());assertEquals("original z",repo.memories("a").single{it.id=="z-shown"}.text)}
            val update=op("stable-update","M2").put("op","UPDATE").put("category","").put("evidence",JSONArray().put(if(n==1)"S999"else"S1"))
            listOf(update,JSONObject(update.toString()))
        }).ensureRoom("a",forceOne=true)
        assertTrue(result.code+" / "+MemoryBatchRepository(repo).failure("a")?.detail,result.success);assertEquals(2,requests.size)
        assertEquals("synthetic coffee fact",repo.memories("a").single{it.id=="z-shown"}.text);assertEquals(2,repo.memories("a").single{it.id=="z-shown"}.version)
        assertEquals("original a",repo.memories("a").single{it.id=="a-other"}.text)
        val raw=repo.read{db->db.rawQuery("SELECT json FROM memory_operations WHERE op_key='stable-update'",null).use{it.moveToFirst();JSONObject(it.getString(0))}}
        assertEquals("z-shown",raw.getString("target"))
    }}
    @Test fun legacySavedNNumberCannotBeReusedForANewFact(){runBlocking{
        val b=MemoryBatchRepository(repo);val source=repo.sources("a").single();val old=b.prepare("a",listOf(source),requireNotNull(ModelAccess(vault).memoryCredential()).stamp)
        b.beginAttempt(old,"legacy");val original=b.apply(old,MemoryNumberedReferences(old.targets,old.existingVersions.keys).normalize(op("old-saved","N1","legacy saved fact")));b.fail(old,"INTERRUPTED")
        val result=engine(client{n,d->
            val saved=d.getJSONArray("already_saved_operations");assertEquals(1,saved.length());assertEquals("new:N1",saved.getJSONObject(0).getString("target"))
            if(n==2)assertTrue(d.getJSONObject("validation_feedback").toString().contains("OPERATION_CHANGED"))
            listOf(op("new-stable",if(n==1)"N1"else"N2","different new fact"))
        }).ensureRoom("a",forceOne=true,manualRetry=true)
        assertTrue(result.code,result.success);assertEquals(2,requests.size);assertEquals("legacy saved fact",repo.memories("a").single{it.id==original}.text)
        assertEquals(2,repo.memories("a").size);assertTrue(repo.memories("a").none{it.id in setOf("M1","N1","N2")})
    }}
    @Test fun receiptFieldErrorsReachTheCorrespondingRepairRequest(){runBlocking{
        val result=engine(client({n,response->if(n==1){response.remove("end_marker");response.put("character_id","wrong-character")} else if(n==2)response.put("operation_count","wrong")}){n,d->
            if(n>1){val errors=d.getJSONObject("validation_feedback").getJSONArray("errors");val all=(0 until errors.length()).map{errors.getJSONObject(it)};val receipt=all.single{it.getString("operation")=="receipt"};assertTrue(receipt.toString(),receipt.getString("problem").contains(if(n==2)"end_marker"else"operation_count"));if(n==2){assertEquals(7,errors.length());repeat(5){i->assertTrue(errors.getJSONObject(i).getString("problem").contains("id"))};assertTrue(all.single{it.getString("operation")=="header"}.getString("problem").contains("character_id"))};assertTrue(repo.memories("a").isEmpty());assertEquals(0L,repo.cursor("a"))}
            (1..5).map{op("receipt-stable-$it","N$it","synthetic receipt $it").also{if(n==1)it.remove("id")}}
        }).ensureRoom("a",forceOne=true)
        assertTrue(result.code,result.success);assertEquals(3,requests.size);assertEquals(5,repo.memories("a").size)
    }}
    @Test fun mixedMissingIdAndInvalidCoreAreBothReportedBeforeRepair(){runBlocking{
        val result=engine(client{n,d->
            if(n==2){val errors=d.getJSONObject("validation_feedback").getJSONArray("errors");assertEquals(2,errors.length());assertTrue(errors.getJSONObject(0).getString("problem").contains("id"));assertEquals("second",errors.getJSONObject(1).getString("operation"));for(field in listOf("core","date_type","evidence[1].turn"))assertTrue(errors.toString(),errors.getJSONObject(1).getString("problem").contains(field));assertTrue(repo.memories("a").isEmpty())}
            listOf(op("first","N1","synthetic first").also{if(n==1)it.remove("id")},op("second","N2","synthetic second").put("core",if(n==1)JSONObject()else false).also{if(n==1)it.put("date_type","week").put("evidence",JSONArray().put(JSONObject().put("turn",JSONObject()).put("version",1).put("side","user").put("quote","synthetic coffee fact")))})
        }).ensureRoom("a",forceOne=true)
        assertTrue(result.code,result.success);assertEquals(2,requests.size);assertEquals(2,repo.memories("a").size)
    }}
}
