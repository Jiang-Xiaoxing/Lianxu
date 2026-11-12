package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

/** Which memories ride in every prompt: the core set is picked by clarity, then filled to a byte/token budget. */
class MemoryCoreSelectionTest {
    private fun memory(id:String,body:String,clarity:Double=90.0)=Triple(id,body,clarity)
    private fun select(rows:List<Triple<String,String,Double>>,ids:Set<String> = emptySet(),goal:Int=4000):List<String> {
        val values=rows.map{(id,body,_)->StoredMemory(id,"a",MemoryCategory.STATE_FACT,body)}
        val clarity=rows.associate{(id,_,c)->id to c}
        return MemoryForgetting.selectCore(values,clarity,ids,goal){m->m.text}.map{it.id}
    }
    @Test fun aFreshMemoryBecomesACoreRowWhenThereIsRoom(){
        assertEquals(listOf("fresh"),select(listOf(memory("fresh","旅人明天上班还得加班"))))
    }
    @Test fun theByteBudgetOnlySkipsRowsThatDoNotFit(){
        val big="一".repeat(1400)          // one row is under the 6KB cap, two are not
        val rows=listOf(memory("first",big,99.0),memory("second",big,98.0),memory("fresh","旅人明天上班还得加班",97.0))
        // The loop keeps trying later candidates, so a small fresh row still squeezes in.
        assertEquals(listOf("first","fresh"),select(rows))
    }
    @Test fun theTokenGoalAlsoCapsTheCoreSet(){
        assertEquals(emptyList<String>(),select(listOf(memory("fresh","旅人明天上班还得加班")),goal=1))
    }
}
