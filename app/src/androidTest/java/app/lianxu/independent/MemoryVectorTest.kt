package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

class MemoryVectorTest {
    private val c get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store;private lateinit var repo:ProcessingRepository;private lateinit var vault:ModelVault;private lateinit var name:String
    private val config=ModelConfig("embedding","Synthetic embedding","custom","https://example.invalid/v1","mock",64000,2048)
    @Before fun setup()=runBlocking{
        name="vectors-${System.nanoTime()}.db";store=Store(c,name);repo=ProcessingRepository(store);store.add(listOf(Friend("a","A","Synthetic"),Friend("b","B","Synthetic")),false)
        vault=ModelVault(c,"vectors-${System.nanoTime()}");vault.save(config,"synthetic-only")
    }
    @After fun close(){vault.list().forEach{vault.delete(it.id)};store.close();c.deleteDatabase(name)}
    private suspend fun memory(text:String="garden",friend:String="a")=repo.manualSave(friend,null,text,MemoryCategory.STATE_FACT,false,true,MemoryAbout.USER)
    private class Fake:MemoryEmbeddingClient {
        val requests=mutableListOf<List<String>>()
        var action:suspend(List<String>)->List<DoubleArray> = {rows->rows.map{doubleArrayOf(1.0,0.0)}}
        override suspend fun embed(credential:ModelCredential,texts:List<String>,timeout:Int):List<DoubleArray>{requests+=texts;return action(texts)}
    }
    @Test fun unconfiguredVectorPurposeNeverCallsBackend()=runBlocking{
        memory();val fake=Fake();val index=MemoryVectorIndex(repo,ModelAccess(vault),fake)
        index.index("a");assertTrue(index.scores("a","garden").isEmpty());assertTrue(fake.requests.isEmpty())
    }
    @Test fun backgroundDocumentsAreBoundedAndForegroundOnlyEmbedsOneQuery()=runBlocking{
        repeat(40){memory("garden $it")};vault.setPurpose(ModelPurpose.EMBEDDING,config.id);val fake=Fake();val index=MemoryVectorIndex(repo,ModelAccess(vault),fake)
        index.index("a");assertEquals(listOf(16,16),fake.requests.map{it.size});val scores=index.scores("a","query")
        assertEquals(32,scores.size);assertEquals(listOf("query"),fake.requests.last());assertEquals(3,fake.requests.size)
        index.index("a");assertEquals(8,fake.requests.last().size);assertEquals(40,index.scores("a","query").size)
    }
    @Test fun editedDeletedExpiredAndCrossRoleRecordsCannotUseStaleVectors()=runBlocking{
        val a=memory();val b=memory(friend="b");vault.setPurpose(ModelPurpose.EMBEDDING,config.id);val fake=Fake();val index=MemoryVectorIndex(repo,ModelAccess(vault),fake)
        index.index("a");index.index("b");assertEquals(setOf("${a.id}@1"),index.scores("a","query").keys)
        assertEquals(setOf("${b.id}@1"),index.scores("b","query").keys)
        repo.manualSave("a",a.id,"changed",a.category,false,true,MemoryAbout.USER)
        val before=fake.requests.size;assertTrue(index.scores("a","query").isEmpty());assertEquals(before,fake.requests.size)
        index.index("a");val current=repo.memories("a").single();repo.tx{repo.saveMemory(it,current.copy(version=current.version+1,expiresAt=1))}
        assertTrue(index.scores("a","query").isEmpty());assertTrue(repo.references("a","changed",99).valid.isEmpty())
    }
    @Test fun sourceInvalidationRejectsCachedEmbeddingBeforeAnyQueryCall()=runBlocking{
        val l=ChatLedger(store);val t=l.begin("a","garden",config.id);l.finish(t.id,1,"reply");val source=repo.sources("a").single()
        repo.tx{repo.saveMemory(it,StoredMemory("auto","a",MemoryCategory.STATE_FACT,"garden",about=MemoryAbout.USER,evidence=listOf(MemoryEvidence(source.source,"user","garden","garden",source.user.created,source.zone))))}
        vault.setPurpose(ModelPurpose.EMBEDDING,config.id);val fake=Fake();val index=MemoryVectorIndex(repo,ModelAccess(vault),fake);index.index("a")
        val changes=SourceChanges(repo);changes.confirm(changes.preview("a",source.source.turnId,"user","",true))
        val count=fake.requests.size;assertTrue(index.scores("a","query").isEmpty());assertEquals(count,fake.requests.size)
    }
    @Test fun changedSelectionInvalidatesCacheWithoutSendingDocumentsToFallback()=runBlocking{
        memory();vault.setPurpose(ModelPurpose.EMBEDDING,config.id);val fake=Fake();val index=MemoryVectorIndex(repo,ModelAccess(vault),fake);index.index("a")
        vault.save(config,"changed-synthetic-key");val count=fake.requests.size
        assertTrue(index.scores("a","query").isEmpty());assertEquals(count,fake.requests.size)
        vault.setPurpose(ModelPurpose.EMBEDDING,"");index.index("a");assertEquals(count,fake.requests.size)
    }
    @Test fun malformedBackendOrTimeoutLeavesMemoryAndKeywordRecallIntact()=runBlocking{
        val m=memory();vault.setPurpose(ModelPurpose.EMBEDDING,config.id);val fake=Fake();val index=MemoryVectorIndex(repo,ModelAccess(vault),fake)
        fake.action={listOf(doubleArrayOf(0.0,0.0))};index.index("a");assertTrue(index.scores("a","query").isEmpty());assertEquals(m,repo.memories("a").single())
        fake.action={rows->rows.map{doubleArrayOf(1.0,0.0)}};index.index("a")
        fake.action={delay(2500);listOf(doubleArrayOf(1.0,0.0))};assertTrue(index.scores("a","query").isEmpty())
        assertTrue(repo.references("a","garden",99).valid.any{it.memoryId==m.id})
    }
    @Test fun cachedForegroundDoesNotWaitForDocumentIndexMutex()=runBlocking{
        memory("old garden");vault.setPurpose(ModelPurpose.EMBEDDING,config.id);val fake=Fake();val index=MemoryVectorIndex(repo,ModelAccess(vault),fake);index.index("a")
        memory("new garden");val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        fake.action={texts->if(texts.any{it.startsWith("new garden")}){started.complete(Unit);release.await()};texts.map{doubleArrayOf(1.0,0.0)}}
        val job=launch(Dispatchers.IO){index.index("a")};try{withTimeout(2000){started.await()};assertEquals(1,withTimeout(1000){index.scores("a","query")}.size)}finally{release.complete(Unit);job.join()}
    }
    @Test fun actualLoopbackTlsEmbeddingPostParsesIndicesAndDoesNotFollowRedirects()=runBlocking{
        val response="""{"data":[{"index":1,"embedding":[0,1]},{"index":0,"embedding":[1,0]}]}"""
        TextTlsMock(response=response).use{s->
            val credential=ModelCredential(config.copy(baseUrl=s.base),"synthetic-only","mock")
            val vectors=s.embeddingClient().embed(credential,listOf("first","second"),3000)
            assertArrayEquals(doubleArrayOf(1.0,0.0),vectors[0],0.0);assertArrayEquals(doubleArrayOf(0.0,1.0),vectors[1],0.0)
            assertTrue(s.headers.startsWith("POST /v1/embeddings "));assertEquals(2,JSONObject(s.body).getJSONArray("input").length())
        }
        TextTlsMock(status=302).use{s->
            try{s.embeddingClient().embed(ModelCredential(config.copy(baseUrl=s.base),"synthetic-only","mock"),listOf("first"),3000);fail()}catch(_:IllegalArgumentException){}
            assertEquals(1,s.calls.get())
        }
    }
}
