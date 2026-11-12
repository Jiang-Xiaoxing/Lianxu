package app.lianxu.independent

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ChatInputQueueTest {
    @Test fun rapidSubmissionsPersistInOrderEvenWhenReplyWaiterIsCancelled()=runBlocking {
        val q=ChatInputQueue<String>(this);val gate=CompletableDeferred<Unit>();val saved=mutableListOf<String>()
        val a=q.submit("a"){gate.await();saved+="第一句";"第一句"}
        val waiter=launch{a.await();error("cancelled generation must not start")}
        yield();waiter.cancelAndJoin()
        val b=q.submit("a"){assertEquals("第一句",it);saved+="补一句";"补一句"}
        val c=q.submit("a"){assertEquals("补一句",it);saved+="再补一句";"再补一句"}
        gate.complete(Unit);c.await();b.await()
        assertEquals(listOf("第一句","补一句","再补一句"),saved)
    }
    @Test fun anotherRoleDoesNotWaitForTheFirstRolesStorage()=runBlocking {
        val q=ChatInputQueue<String>(this);val gate=CompletableDeferred<Unit>()
        val a=q.submit("a"){gate.await();"甲"}
        val b=q.submit("b"){assertNull(it);"乙"}
        assertEquals("乙",withTimeout(1000){b.await()});assertFalse(a.isCompleted)
        gate.complete(Unit);a.await();Unit
    }
    @Test fun anInputStorageFailureDoesNotPoisonTheNextSubmission()=runBlocking {
        val q=ChatInputQueue<String>(this)
        val failed=q.submit("a"){throw IllegalStateException("synthetic storage failure")}
        assertTrue(runCatching{failed.await()}.isFailure)
        assertEquals("重试输入",q.submit("a"){assertNull(it);"重试输入"}.await())
    }
    @Test fun lateReplyCannotWriteAfterLeaseRevocation()=runBlocking {
        val old=ChatAttemptLease();val fresh=ChatAttemptLease();var value=""
        old.cancel()
        assertFalse(old.commit{value="旧答案";true})
        assertTrue(fresh.commit{value="新答案";true})
        assertEquals("新答案",value)
    }
    @Test fun cancellingParentDoesNotEraseAnAlreadyAcceptedInput()=runBlocking {
        val parent=Job();val scope=CoroutineScope(coroutineContext+parent)
        val q=ChatInputQueue<String>(scope);val gate=CompletableDeferred<Unit>()
        val accepted=q.submit("a"){gate.await();"已保存"}
        parent.cancel();gate.complete(Unit)
        assertEquals("已保存",accepted.await())
    }
}
