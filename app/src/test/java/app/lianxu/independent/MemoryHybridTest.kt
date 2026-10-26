package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class MemoryHybridTest {
    private fun m(id:String,text:String,version:Int=1)=StoredMemory(id,"a",MemoryCategory.STATE_FACT,text,manual=true,version=version,about=MemoryAbout.USER)
    @Test fun emptyVectorMapKeepsExactLexicalOrderScoresAndTieRules(){
        val values=listOf(m("z","garden"),m("a","garden"),m("late","2026-10-26 garden"))
        val old=MemoryRecall.select("a","2026-10-26 garden",values,emptyList(),15)
        val zero=MemoryRecall.select("a","2026-10-26 garden",values,emptyList(),15,values.associate{"${it.id}@1" to 0.0})
        assertEquals(old,zero);assertTrue(old.first().memory.id=="late")
        assertEquals(listOf("a","z"),MemoryRecall.select("a","garden",values.take(2),emptyList(),15).map{it.memory.id})
    }
    @Test fun semanticScoresUseMaxInsteadOfAddingDuplicateSimilarity(){
        val item=m("one","garden")
        val hit=MemoryRecall.select("a","garden",listOf(item),emptyList(),15,mapOf("one@1" to .8)).single()
        assertEquals(1.0,hit.direct,0.0);assertTrue(hit.points.total in 0.0..100.0)
        val semantic=MemoryRecall.select("a","unrelated",listOf(item),emptyList(),15,mapOf("one@1" to .8)).single()
        assertEquals(.8,semantic.direct,0.0)
    }
    @Test fun versionRoleExpiryAndMalformedVectorsCannotCreateHits(){
        val item=m("one","garden",2)
        assertTrue(MemoryRecall.select("a","ocean",listOf(item),emptyList(),15,mapOf("one@1" to 1.0)).isEmpty())
        for(v in listOf(Double.NaN,Double.POSITIVE_INFINITY,-1.0,1.1))assertTrue(MemoryRecall.select("a","ocean",listOf(item),emptyList(),15,mapOf("one@2" to v)).isEmpty())
        assertTrue(MemoryRecall.select("b","ocean",listOf(item),emptyList(),15,mapOf("one@2" to 1.0)).isEmpty())
        assertTrue(MemoryRecall.select("a","ocean",listOf(item.copy(expiresAt=1)),emptyList(),15,mapOf("one@2" to 1.0)).isEmpty())
    }
    @Test fun hybridStillHasFixedBoundAndCombinedConfigurableCount(){
        val values=(0..120).map{m(it.toString(),"2026-10-26 garden")}
        val vectors=values.associate{"${it.id}@1" to 1.0}
        for(limit in listOf(1,7,15,21)){
            val hits=MemoryRecall.select("a","2026-10-26 garden",values,emptyList(),limit,vectors)
            assertEquals(limit,hits.size);assertTrue(hits.all{it.score<=MemoryRecall.MAX_RAW_SCORE&&it.points.total in 0.0..100.0})
        }
        assertEquals(2.19,MemoryRecall.MAX_RAW_SCORE,0.000001)
    }
    @Test fun independentVectorMathRejectsInvalidDimensionsAndZeroVectors(){
        assertEquals(1.0,MemoryVectorIndex.cosine(doubleArrayOf(1.0,0.0),doubleArrayOf(1.0,0.0)),0.0)
        assertEquals(0.0,MemoryVectorIndex.cosine(doubleArrayOf(1.0,0.0),doubleArrayOf(0.0,1.0)),0.0)
        assertFalse(MemoryVectorIndex.valid(doubleArrayOf(0.0,0.0)))
        assertFalse(MemoryVectorIndex.valid(doubleArrayOf(Double.NaN)))
        assertFalse(MemoryVectorIndex.valid(DoubleArray(8193){1.0}))
    }
}
