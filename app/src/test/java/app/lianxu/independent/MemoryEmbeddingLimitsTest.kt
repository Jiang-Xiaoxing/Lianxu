package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class MemoryEmbeddingLimitsTest {
    private fun config(provider:String="openai",base:String="https://api.openai.com/v1",model:String="text-embedding-3-small")=ModelConfig("test","合成",provider,base,model,64000,2048)
    @Test fun knownOpenAiBatchUsesOneCallForBothPoolsAndSplitsOnlyAtLimits(){
        val c=config();val p=MemoryEmbeddingLimits.of(c)
        assertEquals(1,p.batches(c,List(40){"合成文本 $it"}).size)
        assertEquals(listOf(2048,1),p.batches(c,List(2049){"x"}).map{it.size})
    }
    @Test fun bailianLimitsBindToOfficialEndpointAndExactModel(){
        val c=config("bailian-cn","https://dashscope.aliyuncs.com/compatible-mode/v1","text-embedding-v4")
        assertEquals(listOf(10,10,1),MemoryEmbeddingLimits.of(c).batches(c,List(21){"合成"}).map{it.size})
        assertEquals(2048,MemoryEmbeddingLimits.of(c.copy(baseUrl="https://example.invalid/v1")).items)
        assertEquals(2048,MemoryEmbeddingLimits.of(c.copy(modelId="unknown")).items)
    }
    @Test fun contextAndPayloadAreBoundedWithoutTruncatingInput(){
        val c=config();val p=MemoryEmbeddingLimits(2048,64000,40)
        val rows=List(3){"中文".repeat(6)};assertTrue(p.batches(c,rows).size>1);assertEquals(rows,p.batches(c,rows).flatten())
        assertTrue(runCatching{MemoryEmbeddingLimits(20,5,100).batches(c,listOf("中文".repeat(10)))}.isFailure)
    }
}
