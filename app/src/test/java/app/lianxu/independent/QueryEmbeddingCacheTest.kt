package app.lianxu.independent

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class QueryEmbeddingCacheTest {
    private val config=ModelConfig("synthetic","合成向量","custom","https://example.invalid/v1","synthetic")
    private fun credential(stamp:String="one")=ModelCredential(config,"synthetic-key-only",stamp)
    private class Client(private val response:suspend(Int)->List<DoubleArray> = {listOf(doubleArrayOf(1.0,0.5))}):MemoryEmbeddingClient {
        var calls=0;private set
        val inputs=mutableListOf<List<String>>()
        override suspend fun embed(credential:ModelCredential,texts:List<String>,timeout:Int):List<DoubleArray>{inputs+=texts;return response(++calls)}
    }
    @Test fun bothRetrievalPoolsReuseExactQueryButCannotMutateTheCachedVector()=runBlocking {
        val cache=QueryEmbeddingCache();val first=Client();val second=Client()
        val result=cache.vector(credential(),"同一原话",5000,first)
        result[0]=99.0
        assertArrayEquals(doubleArrayOf(1.0,0.5),cache.vector(credential(),"同一原话",1000,second),0.0)
        assertEquals(1,first.calls);assertEquals(0,second.calls)
        assertEquals(listOf(listOf("同一原话")),first.inputs)
    }
    @Test fun exactTextCredentialStampAndAttemptStaySeparate()=runBlocking {
        val cache=QueryEmbeddingCache();val client=Client()
        cache.vector(credential(),"原话",1000,client)
        cache.vector(credential(),"原话 ",1000,client)
        cache.vector(credential("changed"),"原话",1000,client)
        QueryEmbeddingCache().vector(credential(),"原话",1000,client)
        assertEquals(4,client.calls)
    }
    @Test fun failureAndInvalidVectorsAreNotCached()=runBlocking {
        val cache=QueryEmbeddingCache();val client=Client{call->when(call){1->error("synthetic network failure");2->listOf(doubleArrayOf(0.0,0.0));else->listOf(doubleArrayOf(1.0,0.5))}}
        repeat(2){assertTrue(runCatching{cache.vector(credential(),"原话",1000,client)}.isFailure)}
        assertArrayEquals(doubleArrayOf(1.0,0.5),cache.vector(credential(),"原话",1000,client),0.0)
        assertEquals(3,client.calls)
    }
    @Test fun cancelledLateResultCannotPopulateTheCache()=runBlocking {
        val cache=QueryEmbeddingCache();val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val client=Client{call->if(call==1){started.complete(Unit);withContext(NonCancellable){release.await()}};listOf(doubleArrayOf(1.0,0.5))}
        val job=launch(start=CoroutineStart.UNDISPATCHED){cache.vector(credential(),"原话",1000,client)}
        started.await();job.cancel();release.complete(Unit);job.join()
        assertTrue(job.isCancelled)
        cache.vector(credential(),"原话",1000,client)
        assertEquals(2,client.calls)
    }
}
