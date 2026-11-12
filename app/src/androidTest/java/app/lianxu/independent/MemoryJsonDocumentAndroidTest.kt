package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Fictional data and a fake transport; the documented provider identity selects framing only. */
class MemoryJsonDocumentAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store
    private lateinit var repo:ProcessingRepository
    private lateinit var vault:ModelVault
    private lateinit var scope:CoroutineScope
    private lateinit var database:String
    private val config=ModelConfig("json","Synthetic","custom","https://api.xiaomimimo.com/v1","mimo-v2.6-flash",64000,2048)
    @Before fun setup()=runBlocking{
        database="json-document-${System.nanoTime()}.db";store=Store(context,database);repo=ProcessingRepository(store)
        store.add(listOf(Friend("a","A","Synthetic")),false)
        vault=ModelVault(context,"json-document-${System.nanoTime()}");vault.save(config,"synthetic-key-only");vault.setPurpose(ModelPurpose.MEMORY,config.id)
        scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val ledger=ChatLedger(store);val turn=ledger.begin("a","I made a blue cup.",config.id);assertTrue(ledger.finish(turn.id,turn.attempt,"Synthetic reply"))
    }
    @After fun close()=runBlocking{scope.coroutineContext.job.cancelAndJoin();vault.list().forEach{vault.delete(it.id)};store.close();context.deleteDatabase(database);Unit}
    private fun operation(id:String="one",target:String="N1")=JSONObject().put("id",id).put("op","ADD").put("target",target).put("category","EXPERIENCE_MEMORY").put("about","USER").put("text","我记得对方做了一只蓝色杯子。").put("core",false).put("date_expression",JSONObject.NULL).put("evidence",JSONArray().put("S1"))
    private fun document(data:JSONObject,operations:JSONArray=JSONArray().put(operation()))=JSONObject().put("batch_id",data.getString("batch_id")).put("character_id","a").put("operations",operations).put("operation_count",operations.length()).put("complete",true).put("end_marker",data.getString("batch_id"))
    private suspend fun executeSummary(body:(JSONObject)->String,afterChunks:suspend()->Unit={},transportFailure:Boolean=false):ProcessingRun {
        val client=object:TextChatClient{
            override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("Streaming path expected")
            override suspend fun stream(config:ModelConfig,key:String,request:TextChatRequest,onChunk:suspend(String)->Unit):TextChatResult{
                assertTrue(request.jsonObject);assertTrue(request.messages.first().content.contains("一个完整JSON对象"));assertFalse(request.messages.first().content.contains("每个操作一行"))
                val text=body(JSONObject(request.messages.last().content))
                text.chunked(7).forEach{onChunk(it)};afterChunks()
                return if(transportFailure)TextChatResult.Failure(ChatFailure.NETWORK)else TextChatResult.Success(text)
            }
        }
        return MemoryEngine(scope,repo,ModelAccess(vault),client,{}).ensureRoom("a",forceOne=true)
    }
    private suspend fun assertUncommitted(){assertTrue(repo.memories("a").isEmpty());assertEquals(1,repo.recentSources("a").size);assertEquals(0L,repo.cursor("a"));val work=MemoryBatchRepository(repo).pending("a")!!;assertTrue(MemoryBatchRepository(repo).applied(work).isEmpty())}
    @Test fun prettyPrintedDocumentAcrossTransportChunksCommitsOnce()=runBlocking{
        assertTrue(executeSummary({document(it).toString(2)}).success)
        assertEquals(1,repo.memories("a").size);assertEquals(0,repo.recentSources("a").size);assertEquals(2,store.messages().size)
    }
    @Test fun capturedSingleOperationWithoutCompletionNeverWrites()=runBlocking{
        var calls=0
        val result=executeSummary({data->calls++;JSONObject().put("type","operation").put("batch_id",data.getString("batch_id")).put("character_id","a").put("operation",operation()).toString()})
        assertFalse(result.success);assertEquals("INCOMPLETE",result.code);assertEquals(3,calls);assertUncommitted()
    }
    @Test fun invalidOperationRollsBackEarlierValidOperation()=runBlocking{
        assertFalse(executeSummary({document(it,JSONArray().put(operation()).put(operation("two","N2").put("category","UNKNOWN"))).toString(2)}).success)
        assertUncommitted()
    }
    @Test fun wrongCompletionCountRollsBackAllOperations()=runBlocking{
        assertFalse(executeSummary({document(it).put("operation_count",2).toString(2)}).success);assertUncommitted()
    }
    @Test fun truncatedDocumentNeverWrites()=runBlocking{
        assertFalse(executeSummary({document(it).toString(2).dropLast(2)}).success);assertUncommitted()
    }
    @Test fun trailingDocumentNeverWrites()=runBlocking{
        assertFalse(executeSummary({document(it).toString()+"{}"}).success);assertUncommitted()
    }
    @Test fun fullDocumentWithTransportFailureNeverPublishes()=runBlocking{
        assertFalse(executeSummary({document(it).toString(2)},transportFailure=true).success);assertUncommitted()
    }
    @Test fun changedConfigurationRejectsLateCompleteDocument()=runBlocking{
        assertFalse(executeSummary({document(it).toString(2)},afterChunks={vault.save(config.copy(modelId="changed-model"),"synthetic-key-only")}).success)
        assertUncommitted()
    }
}
