package app.lianxu.independent

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class AutomaticSilenceTest {
    private val day=RelationshipProjector.DAY
    private val p=RelationTemplate.DIRECT.parameters
    private val automatic=RelationInput(SourceRef("r",2,1),day,1,"关于关系",ignored=.2,explanationPolicy=1)
    private val real=RelationInput(SourceRef("r",3,1),2*day,2,"关于关系",disappointment=.3,v=.6)
    private fun feel(saved:RelationFeelingSaved?,rows:List<RelationInput>,now:Long)=
        RelationFeeling.update("r",saved,p,p,RelationStart.FRIENDS,rows,listOf(0,2*day),listOf(day/2),emptyList(),now)

    @Test fun historicalAutomaticRowsAreExcludedWithoutDeletingOrFilteringModelEvidence(){
        val other=automatic.copy(source=SourceRef("r",4,1),ignored=null,p=.3)
        val history=RelationHistory(programInputs=listOf(automatic,other));val original=Gson().toJson(history)
        val inputs=listOf(real)+history.activeProgramInputs()
        assertEquals(listOf(real,other),inputs);assertEquals(original,Gson().toJson(history))
        assertTrue(feel(null,inputs,2*day).cursor.state().disappointment>0)
    }
    @Test fun cachedAndCompactedAutomaticEffectsAreRebuiltOutWhileRealFeelingsRemain(){
        val history=RelationHistory(programInputs=listOf(automatic))
        for(now in listOf(2*day,60*day)){
            // Simulate a persisted old cache, including compacted negative buckets after months.
            val old=feel(null,listOf(automatic,real),now)
            old.cursor.advance(now);old.cursor.compact()
            val stored=old.saved.copy(cursor=RelationSnapshotCodec.encode(old.cursor))
            assertNull(history.withoutAutomaticSilence(stored))
            val fixed=feel(history.withoutAutomaticSilence(stored),listOf(real)+history.activeProgramInputs(),now)
            val clean=feel(null,listOf(real),now)
            assertTrue(fixed.rebuilt);assertEquals(clean.cursor.state(),fixed.cursor.state())
            assertFalse(fixed.saved.ledger.containsKey("2:1"));assertTrue(fixed.saved.ledger.containsKey("3:1"))
            assertSame(fixed.saved,history.withoutAutomaticSilence(fixed.saved))
        }
    }
    @Test fun automaticProvenanceIsRequiredAndUnrelatedCachedModelInputIsKept(){
        val modelWithSameFields=automatic.copy(source=SourceRef("r",9,1))
        val saved=feel(null,listOf(modelWithSameFields,real),2*day).saved
        val history=RelationHistory(programInputs=listOf(automatic))
        assertSame(saved,history.withoutAutomaticSilence(saved))
        assertEquals(setOf("9:1","3:1"),saved.ledger.keys)
    }
    @Test fun promptLeavesFeelingsToModelWithoutAutomaticSilenceRule(){
        val text=RelationInputContract.instruction(1)
        assertTrue(text.contains("自主判断感受"))
        assertTrue(text.contains("不推断已读或故意不回应"))
        assertFalse(text.contains("被晾的失望由程序记录"))
    }
}
