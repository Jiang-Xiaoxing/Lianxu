package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class DomainTest {
    private fun friends()=(1..3).map { Friend("$it","朋友$it","合成资料") }
    @Test fun oldCallbackCannotReplaceNewBatch() { val s=SearchGate(); val old=s.begin(); val fresh=s.begin(); assertTrue(s.complete(fresh,friends())); assertFalse(s.complete(old,friends().reversed())); assertEquals("1",s.candidates.first().id) }
    @Test fun cancelRejectsLateResult() { val s=SearchGate(); val id=s.begin(); s.cancel(); assertFalse(s.complete(id,friends())); assertTrue(s.candidates.isEmpty()) }
    @Test fun selectOneManyAllNone() { val s=SearchGate(); s.complete(s.begin(),friends()); s.toggle("1"); assertEquals(setOf("1"),s.selected); s.toggle("2"); assertEquals(2,s.selected.size); s.selectAll(); assertEquals(3,s.selected.size); s.clearSelection(); assertTrue(s.selected.isEmpty()); s.toggle("unknown"); assertTrue(s.selected.isEmpty()) }
    @Test(expected=IllegalArgumentException::class) fun rejectWrongCandidateCount() {val s=SearchGate(); s.complete(s.begin(),friends().take(2))}
}
