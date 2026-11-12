package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class MemoryRepairFeedbackTest {
    @Test fun budgetIsInitialPlusTwoRepairs(){
        assertEquals(3,MemoryRepairFeedback.MAX_ATTEMPTS)
        assertTrue(MemoryRepairFeedback.retry(1,"UNKNOWN_TARGET"));assertTrue(MemoryRepairFeedback.retry(2,"EVIDENCE"))
        assertFalse(MemoryRepairFeedback.retry(3,"EVIDENCE"))
    }
    @Test fun sourceEditsLateResultsAndAuthFailuresAreNeverRetriedAsRepairs(){
        listOf("SOURCE_CHANGED","SOURCE_EDITED","MEMORY_CHANGED","LATE_DISCARDED","CANCELLED","MODEL_UNAUTHORIZED").forEach{
            assertFalse(it,MemoryRepairFeedback.retry(1,it));assertFalse(it,MemoryRepairFeedback.repairable(it))
        }
        listOf("FORMAT","EVIDENCE","UNKNOWN_TARGET","INCOMPLETE").forEach{assertTrue(it,MemoryRepairFeedback.repairable(it))}
    }
    @Test fun issuesRoundTripWithoutLineBreakInjectionAndAreCapped(){
        val issues=listOf(MemoryRepairFeedback.Issue("one","UNKNOWN_TARGET","target「M7」不是\n已有编号"),MemoryRepairFeedback.Issue("two","EVIDENCE","S9 无效"))
        val decoded=MemoryRepairFeedback.decode(MemoryRepairFeedback.encode(issues))
        assertEquals(2,decoded.size);assertEquals("UNKNOWN_TARGET",decoded[0].code);assertFalse(decoded[0].detail.contains("\n"));assertEquals("S9 无效",decoded[1].detail)
        assertEquals(12,MemoryRepairFeedback.decode(MemoryRepairFeedback.encode((1..20).map{MemoryRepairFeedback.Issue("op$it","FORMAT","bad")})).size)
    }
    @Test fun hintsNameTheOffendingValueAndReasonsAreReadable(){
        assertTrue(MemoryRepairFeedback.hint("UNKNOWN_TARGET","M7","").contains("M7"))
        assertTrue(MemoryRepairFeedback.hint("EVIDENCE","","S9").contains("S9"))
        assertEquals("模型引用了不存在的记忆编号",MemoryRepairFeedback.reason("UNKNOWN_TARGET"))
        assertEquals("超时",MemoryRepairFeedback.reason("SUMMARY_TIMEOUT"))
        assertTrue(MemoryRepairFeedback.reason("SOURCE_EDITED").contains("编辑或撤回"))
        assertTrue(MemoryRepairFeedback.reason("LATE_DISCARDED").contains("晚到"))
    }
    /** 余额不足要说清是 402，而且不能连续自动重试。 */
    @Test fun paymentFailureIsNamedAndNeverRetriedAutomatically(){
        assertFalse(MemoryRepairFeedback.retry(1,"MODEL_PAYMENT"))
        assertFalse(MemoryRepairFeedback.repairable("MODEL_PAYMENT"))
        val reason=MemoryRepairFeedback.reason("MODEL_PAYMENT")
        assertTrue(reason,reason.contains("402"))
        assertTrue(reason,reason.contains("余额不足"))
        assertTrue(ChatFailure.PAYMENT.display.contains("402"))
        assertTrue(ChatFailure.MEMORY_PAYMENT.display.contains("402"))
        assertTrue(ChatFailure.MEMORY_PAYMENT.display.contains("原话"))
        assertEquals(ChatFailure.MEMORY_PAYMENT,memoryFailureFor("MODEL_PAYMENT"))
        assertEquals(ChatFailure.MEMORY_CAPACITY,memoryFailureFor("CAPACITY"))
        assertEquals(ChatFailure.MISSING_MEMORY_MODEL,memoryFailureFor("NO_MEMORY_MODEL"))
        assertEquals(ChatFailure.MEMORY_FAILED,memoryFailureFor("MODEL_SERVER"))
        assertEquals(ChatFailure.MEMORY_RETRY,memoryFailureFor("MANUAL_RETRY"))
        assertTrue(ChatFailure.MEMORY_RETRY.display.contains("重试"))
        assertTrue(processingMessage("MODEL_PAYMENT").contains("402"))
    }
}
