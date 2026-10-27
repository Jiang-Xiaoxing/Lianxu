package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class MemorySummaryGuardTest {
    private fun blocked(action:()->Unit){assertEquals("MEMORY_SUMMARIZING",(runCatching(action).exceptionOrNull() as ProcessingRejected).code)}
    @Test fun successAndFailureFinallyBothReleaseProtection(){
        for(success in listOf(true,false)){
            val g=MemorySummaryGuard();val t=g.begin("a",ChatAttemptLease(),10000){}!!
            try{blocked{g.requireEditable("a")};if(!success)throw IllegalStateException("synthetic")}
            catch(_:IllegalStateException){}finally{g.finish(t)}
            g.requireEditable("a");assertTrue(g.roles.value.isEmpty());assertFalse(t.lease.isActive())
        }
    }
    @Test fun cancellationRevokesLateCommitAndAllowsRestart(){
        val g=MemorySummaryGuard();var cancelled=0;val old=g.begin("a",ChatAttemptLease(),10000){cancelled++}!!
        g.stop("a");assertEquals(1,cancelled);assertFalse(old.lease.guard{fail("late write");true})
        val fresh=g.begin("a",ChatAttemptLease(),10000){}!!
        g.finish(old);g.timeout(old);blocked{g.requireEditable("a")};assertTrue(g.current(fresh));g.finish(fresh)
    }
    @Test fun deadlineAlsoExpiresWithoutACooperatingWorkerOrWatchdog(){
        var clock=0L;var cancelled=0;val g=MemorySummaryGuard{clock};val t=g.begin("a",ChatAttemptLease(),300000){cancelled++}!!
        clock=299999;blocked{g.requireEditable("a")};clock=300000;g.requireEditable("a")
        assertTrue(t.timedOut);assertEquals(1,cancelled);assertFalse(t.lease.isActive());g.timeout(t);assertEquals(1,cancelled)
    }
    @Test fun duplicateStartsAreRejectedAndRolesStayIndependent(){
        val g=MemorySummaryGuard();val a=g.begin("a",ChatAttemptLease(),10000){}!!
        assertNull(g.begin("a",ChatAttemptLease(),10000){});g.requireEditable("b")
        val b=g.begin("b",ChatAttemptLease(),10000){}!!;g.stop("a");assertTrue(g.current(b));assertFalse(g.current(a));g.finish(b)
    }
    @Test fun allRoleReplacementIsBlockedButReadAndAnotherRoleRemainAvailable(){
        val g=MemorySummaryGuard();val t=g.begin("a",ChatAttemptLease(),10000){}!!
        blocked{g.requireAllEditable()};g.requireEditable("b");g.finish(t);g.requireAllEditable()
    }
    @Test fun processLocalOwnershipIsNotRestoredAsAPermanentLock(){
        val old=MemorySummaryGuard();old.begin("a",ChatAttemptLease(),10000){}!!
        val restarted=MemorySummaryGuard();restarted.requireEditable("a");assertTrue(restarted.roles.value.isEmpty())
    }
    @Test fun startAndManualTransactionCannotInterleave(){
        val g=MemorySummaryGuard();val entered=java.util.concurrent.CountDownLatch(1);val finished=java.util.concurrent.CountDownLatch(1)
        var started:MemorySummaryGuard.Ticket?=null
        AppMutationGate.read{
            g.requireEditable("a")
            Thread{entered.countDown();started=g.begin("a",ChatAttemptLease(),10000){};finished.countDown()}.start()
            assertTrue(entered.await(1,java.util.concurrent.TimeUnit.SECONDS));assertFalse(finished.await(30,java.util.concurrent.TimeUnit.MILLISECONDS))
        }
        assertTrue(finished.await(1,java.util.concurrent.TimeUnit.SECONDS));blocked{g.requireEditable("a")};g.finish(started!!)
    }
}
