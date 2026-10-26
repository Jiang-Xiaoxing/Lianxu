package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class MemoryFinishTest {
    @Test fun toolsAreIndependentAndDefaultOff() {
        val defaults=MemoryRules()
        assertFalse(defaults.proactiveRecall);assertFalse(defaults.immediateWrite)
        assertTrue(defaults.copy(proactiveRecall=true).valid())
        assertTrue(defaults.copy(immediateWrite=true).valid())
        assertEquals(15,defaults.relatedCount)
    }
    @Test fun recallLimitsRejectOverflowAndInconsistentBudgets() {
        val r=MemoryRules()
        listOf(r.copy(recallRounds=0),r.copy(recallRounds=101),r.copy(recallTokens=255),r.copy(recallTokens=64001),r.copy(recallPerCall=127),r.copy(recallPerCall=4001)).forEach{assertFalse(it.valid())}
        assertTrue(r.copy(recallRounds=100,recallTokens=64000,recallPerCall=64000).valid())
    }
    @Test fun birthdayDoesNotInventYearAndLeapDayIsPreserved() {
        assertEquals(null to "02-29",MemoryDates.birthday("2月29日"))
        assertEquals("2000-02-29" to "02-29",MemoryDates.birthday("2000年2月29日"))
        assertEquals(null to null,MemoryDates.birthday("2001年2月29日"))
        assertEquals(null to null,MemoryDates.birthday("不知道生日"))
        assertEquals(null to null,MemoryDates.birthday("4月31日"))
    }
    @Test fun annualDateValidationUsesLeapCapableMonthDay() {
        assertEquals("02-29",MemoryDates.monthDay("02-29"))
        for(value in listOf("2-29","02-30","13-01","00-01")) assertTrue(runCatching{MemoryDates.monthDay(value)}.isFailure)
    }
}
