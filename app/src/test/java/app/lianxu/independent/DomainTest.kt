package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class DomainTest {
    private fun friends()=(1..3).map { Friend("$it","朋友$it","合成资料") }
    @Test fun oldCallbackCannotReplaceNewBatch() { val s=SearchGate(); val old=s.begin(); val fresh=s.begin(); assertTrue(s.complete(fresh,friends())); assertFalse(s.complete(old,friends().reversed())); assertEquals("1",s.candidates.first().id) }
    @Test fun cancelRejectsLateResult() { val s=SearchGate(); val id=s.begin(); s.cancel(); assertFalse(s.complete(id,friends())); assertTrue(s.candidates.isEmpty()) }
    @Test fun selectOneManyAllNone() { val s=SearchGate(); s.complete(s.begin(),friends()); s.toggle("1"); assertEquals(setOf("1"),s.selected); s.toggle("2"); assertEquals(2,s.selected.size); s.selectAll(); assertEquals(3,s.selected.size); s.clearSelection(); assertTrue(s.selected.isEmpty()); s.toggle("unknown"); assertTrue(s.selected.isEmpty()) }
    @Test(expected=IllegalArgumentException::class) fun rejectWrongCandidateCount() {val s=SearchGate(); s.complete(s.begin(),friends().take(2))}
    private fun turns()=(1L..10L).map { CompleteTurn(SourceRef("a",it,1),"user","assistant",900) }
    @Test fun memoryUsesCompleteOldestTurnsAndStableBatchId() { val batch=ContextPolicy.plan("a",turns())!!; assertEquals(listOf(1L,2L),batch.turns.map {it.source.turnId}); assertEquals(1800,batch.turns.sumOf{it.tokens}); assertEquals(batch.id,ContextPolicy.plan("a",turns())!!.id) }
    @Test fun memoryWithinBudgetDoesNotRun() {assertNull(ContextPolicy.plan("a",turns().take(8)))}
    @Test(expected=IllegalArgumentException::class) fun memoryRejectsCrossCharacter() {ContextPolicy.plan("b",turns())}
    @Test fun partialCannotRetireAndStaleCommitCannotRetire() {val b=ContextPolicy.plan("a",turns())!!; val v=b.turns.associate {it.source to it.source.version}; assertFalse(ContextPolicy.mayRetire(b,BatchStatus.Partial(setOf("fact")),v)); assertTrue(ContextPolicy.mayRetire(b,BatchStatus.Committed,v)); assertFalse(ContextPolicy.mayRetire(b,BatchStatus.Committed,v + (b.turns.first().source to 2L)))}
    @Test fun customPromptWinsAndDatesRemainUnknown() {assertEquals("custom",ContextPolicy.prompt(" custom ","default")); assertNull(MemoryFact("1","昨天去了公园",emptySet()).exactDate)}
    @Test fun lockedFactRequiresConflictResolution() {val f=MemoryFact("1","茶",emptySet(),locked=true); assertTrue(ContextPolicy.conflict(f,f.copy(text="咖啡"))); assertFalse(ContextPolicy.conflict(f,f))}
}
