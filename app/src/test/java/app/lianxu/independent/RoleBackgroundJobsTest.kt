package app.lianxu.independent

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RoleBackgroundJobsTest {
    @Test fun cancellationStopsAllTargetJobsAndPreservesAnotherRolesRequest()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val jobs=RoleBackgroundJobs(scope)
            val a=jobs.launch("a"){awaitCancellation()}
            val second=jobs.launch("a"){awaitCancellation()}
            val reply=CompletableDeferred<String>();var received=""
            val b=jobs.launch("b"){received=reply.await()}
            jobs.cancel("a");a.join();second.join()
            assertTrue(a.isCancelled&&second.isCancelled);assertTrue(b.isActive)
            reply.complete("other role finished");b.join();assertEquals("other role finished",received)
        }finally{scope.cancel()}
    }
    @Test fun completedJobsDoNotInterfereWithSubsequentCancellation()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val jobs=RoleBackgroundJobs(scope)
            jobs.launch("a"){}.join();jobs.cancel("missing")
            val pending=jobs.launch("a"){awaitCancellation()}
            jobs.cancel("a");pending.join();assertTrue(pending.isCancelled)
        }finally{scope.cancel()}
    }
}
