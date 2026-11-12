package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class MemoryAttemptsTest {
    private val sources=mapOf(10L to 1L)
    @Test fun newestUnvoidedAttemptWithSameSourcesAndWindowIsTheOnlyCommittableOne(){
        val a=MemoryAttempts();val first=a.begin("r","b",1,sources,0)
        assertTrue(a.committable(first,sources,0))
        assertFalse(a.committable(first,mapOf(10L to 2L),0));assertFalse(a.committable(first,emptyMap(),0));assertFalse(a.committable(first,sources,5))
        val second=a.begin("r","b",2,sources,0)
        assertTrue(first.voided);assertFalse(a.live(first));assertFalse(a.committable(first,sources,0));assertTrue(a.committable(second,sources,0))
        assertNotEquals(first.id,second.id)
    }
    @Test fun timeoutVoidIsFinalAndRolesAreIndependent(){
        val a=MemoryAttempts();val r=a.begin("r","b",1,sources,0);val other=a.begin("o","c",1,sources,0)
        a.void(r);assertFalse(a.live(r));assertFalse(a.committable(r,sources,0));assertTrue(a.live(other));a.void(null)
    }
    @Test fun lateLogIsWrittenOnlyOncePerAttempt(){
        val a=MemoryAttempts();val r=a.begin("r","b",1,sources,0)
        assertTrue(a.markLate(r));assertFalse(a.markLate(r))
    }
}
