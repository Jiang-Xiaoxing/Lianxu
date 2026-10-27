package app.lianxu.independent

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MemoryCapacityWaitTest {
    @Test fun chatWaitsForBackgroundSummaryThenRechecksCapacity()=runBlocking {
        val g=MemorySummaryGuard();val t=g.begin("a",ChatAttemptLease(),10000){}!!;var calls=0
        val chat=async(start=CoroutineStart.UNDISPATCHED){awaitMemoryCapacity("a",g){calls++;ProcessingRun(true)}}
        assertEquals(0,calls);assertFalse(chat.isCompleted);g.finish(t);assertTrue(chat.await().success);assertEquals(1,calls)
    }
    @Test fun manualDuplicateStillFailsAdmissionWhileChatIsWaiting()=runBlocking {
        val g=MemorySummaryGuard();val t=g.begin("a",ChatAttemptLease(),10000){}!!
        val chat=async(start=CoroutineStart.UNDISPATCHED){awaitMemoryCapacity("a",g){ProcessingRun(true)}}
        assertNull(g.begin("a",ChatAttemptLease(),10000){});g.finish(t);assertTrue(chat.await().success)
    }
    @Test fun cancellingChatDoesNotCancelTheBackgroundSummary()=runBlocking {
        val g=MemorySummaryGuard();var cancelled=false;val t=g.begin("a",ChatAttemptLease(),10000){cancelled=true}!!
        val chat=launch(start=CoroutineStart.UNDISPATCHED){awaitMemoryCapacity("a",g){fail("must still wait");ProcessingRun(true)}}
        chat.cancelAndJoin();assertFalse(cancelled);assertTrue(g.current(t));g.finish(t)
    }
    @Test fun waitingHasABoundAndDoesNotReleaseAnotherTasksProtection()=runBlocking {
        val g=MemorySummaryGuard();val t=g.begin("a",ChatAttemptLease(),10000){}!!
        assertEquals("SUMMARY_TIMEOUT",awaitMemoryCapacity("a",g,25){fail("must still wait");ProcessingRun(true)}.code)
        assertTrue(g.current(t));g.finish(t)
    }
    @Test fun anotherRoleIsNotBlocked()=runBlocking {
        val g=MemorySummaryGuard();val t=g.begin("a",ChatAttemptLease(),10000){}!!
        assertTrue(awaitMemoryCapacity("b",g){ProcessingRun(true)}.success);assertTrue(g.current(t));g.finish(t)
    }
    @Test fun anAdmissionRaceIsRetriedInsteadOfTurningIntoAChatFailure()=runBlocking {
        val g=MemorySummaryGuard();var calls=0
        assertTrue(awaitMemoryCapacity("a",g){if(calls++==0)ProcessingRun(false,"BUSY")else ProcessingRun(true)}.success);assertEquals(2,calls)
    }
    @Test fun realSummaryFailureIsPreservedAndAnOwnedAttemptIsCancelledWithChat()=runBlocking {
        val g=MemorySummaryGuard();assertEquals("CAPACITY",awaitMemoryCapacity("a",g){ProcessingRun(false,"CAPACITY")}.code)
        var released=false;val entered=CompletableDeferred<Unit>()
        val chat=launch(start=CoroutineStart.UNDISPATCHED){awaitMemoryCapacity("a",g){val t=g.begin("a",ChatAttemptLease(),10000){}!!;try{entered.complete(Unit);awaitCancellation()}finally{g.finish(t);released=true}}}
        entered.await();chat.cancelAndJoin()
        assertTrue(released);g.requireEditable("a")
    }
    @Test fun anAdmittedSummaryDoesNotInheritTheTimeAlreadySpentWaiting()=runBlocking {
        val g=MemorySummaryGuard()
        assertTrue(awaitMemoryCapacity("a",g,25){delay(50);ProcessingRun(true)}.success)
    }
}
