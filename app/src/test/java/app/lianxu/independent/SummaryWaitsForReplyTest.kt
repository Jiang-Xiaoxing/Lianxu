package app.lianxu.independent

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** 总结和回话不撞车：总结的模型调用照常并行，只有保存那一步等她正在回的那句写完。 */
class SummaryWaitsForReplyTest {
    /** Stands in for the chat ledger's snapshot fingerprint: a summary save changes it. */
    private class Source{var stamp=0;val saved=mutableListOf<String>()}

    @Test fun summaryFinishingMidReplyWaitsAndTheReplyStillLands()=runBlocking {
        val role="wait-"+System.nanoTime();val db=Source()
        val modelDone=CompletableDeferred<Unit>();val replyStreamed=CompletableDeferred<Unit>()
        // Her reply: takes its snapshot, streams, then writes only if the snapshot is unchanged.
        val reply=async(start=CoroutineStart.UNDISPATCHED){
            ReplyGate.enter(role)
            try{val seen=db.stamp;replyStreamed.await();if(db.stamp==seen){db.saved+="reply";true}else false}
            finally{ReplyGate.leave(role)}
        }
        var waitedBusy=false
        val summary=async(start=CoroutineStart.UNDISPATCHED){
            modelDone.await() // the summary's model call runs in parallel
            ReplyGate.save(role,10_000,{busy,_->waitedBusy=busy}){db.stamp++;db.saved+="summary"}
        }
        modelDone.complete(Unit);yield()
        assertFalse("the save must wait while she is replying",summary.isCompleted)
        assertTrue(db.saved.isEmpty())
        replyStreamed.complete(Unit)
        assertTrue("the reply is written, not judged SOURCE_CHANGED",reply.await())
        summary.await()
        assertEquals(listOf("reply","summary"),db.saved)
        assertTrue(waitedBusy);assertFalse(ReplyGate.replying(role))
    }
    @Test fun noReplyInProgressSavesRightAway()=runBlocking {
        val role="idle-"+System.nanoTime();var timedOut=true;var busy=true
        assertEquals("ok",ReplyGate.save(role,10_000,{b,t->busy=b;timedOut=t}){"ok"})
        assertFalse(busy);assertFalse(timedOut)
    }
    @Test fun aStuckReplyOnlyDelaysTheSaveUntilTheTimeout()=runBlocking {
        val role="stuck-"+System.nanoTime();ReplyGate.enter(role)
        try{
            var timedOut=false;var ran=false
            ReplyGate.save(role,50,{_,t->timedOut=t}){ran=true}
            assertTrue("after the timeout the save goes ahead as before",ran);assertTrue(timedOut)
        }finally{ReplyGate.leave(role)}
        assertEquals("the gate is clean afterwards","x",withTimeout(1000){ReplyGate.save(role,1000){"x"}})
    }
    @Test fun aReplyStartingDuringTheSaveWaitsForTheSaveToFinish()=runBlocking {
        val role="saving-"+System.nanoTime();val release=CompletableDeferred<Unit>();val order=mutableListOf<String>()
        val summary=async(start=CoroutineStart.UNDISPATCHED){ReplyGate.save(role,10_000){release.await();order+="summary"}}
        val reply=async(start=CoroutineStart.UNDISPATCHED){ReplyGate.enter(role);order+="reply";ReplyGate.leave(role)}
        yield();assertFalse(reply.isCompleted)
        release.complete(Unit);summary.await();reply.await()
        assertEquals(listOf("summary","reply"),order)
    }
    @Test fun aReplyWaitingForTheSummaryItselfDoesNotHoldTheSaveBack()=runBlocking {
        val role="own-"+System.nanoTime()
        ReplyGate.enter(role)
        // The runner steps aside before awaitMemoryCapacity, otherwise the two would wait on each other.
        ReplyGate.leave(role)
        val saved=withTimeout(1000){ReplyGate.save(role,10_000){true}}
        ReplyGate.enter(role);ReplyGate.leave(role)
        assertTrue(saved)
    }
}
