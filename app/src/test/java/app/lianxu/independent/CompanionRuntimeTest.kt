package app.lianxu.independent

import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CompanionRuntimeTest {
    @Test fun foregroundCancelsBothRunningAndWaitingDuplicateBackgroundJobs(){
        val role=UUID.randomUUID().toString();val running=Job();val waiting=Job();val other=Job()
        try{
            CompanionRuntime.register(role,running);CompanionRuntime.register(role,waiting);CompanionRuntime.register("other-$role",other)
            CompanionRuntime.foreground(role)
            assertFalse(running.isActive);assertFalse(waiting.isActive);assertTrue(other.isActive);assertTrue(CompanionRuntime.busy(role))
        }finally{CompanionRuntime.leave(role);CompanionRuntime.unregister(role,running);CompanionRuntime.unregister(role,waiting);CompanionRuntime.unregister("other-$role",other);other.cancel()}
    }
    @Test fun finishingCancelledForegroundAttemptDoesNotReleaseItsReplacement(){
        val role=UUID.randomUUID().toString()
        CompanionRuntime.foreground(role);CompanionRuntime.foreground(role)
        CompanionRuntime.leave(role);assertTrue(CompanionRuntime.busy(role))
        val background=Job();CompanionRuntime.register(role,background);assertFalse(background.isActive)
        CompanionRuntime.leave(role);assertFalse(CompanionRuntime.busy(role));CompanionRuntime.unregister(role,background)
    }
}
