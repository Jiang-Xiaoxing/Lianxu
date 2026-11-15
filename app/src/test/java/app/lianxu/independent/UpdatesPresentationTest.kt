package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class UpdatesPresentationTest {
    @Test fun localExperienceIsNotAddedToPublishedVersionFacts(){
        val published=LocalReleaseNotes.forVersion(60)!!
        assertEquals("1.0.1",published.version)
        assertEquals(4,published.changes.size)
        assertFalse(published.changes.any{"分页" in it||"暖灰棕" in it})
        assertEquals(3,UpdatesPresentation.experienceFor(60)!!.size)
    }
    @Test fun experienceIsOnlyShownForItsActualBaseBuild(){
        for(code in listOf(0L,16L,59L,61L,99L))assertNull(UpdatesPresentation.experienceFor(code))
    }
    @Test fun latestSummariesKeepOriginalRecordsAvailable(){
        for(code in listOf(59L,60L)){
            val original=LocalReleaseNotes.forVersion(code)!!
            assertEquals(3,UpdatesPresentation.summary(original).size)
            assertTrue(original.changes.size>UpdatesPresentation.summary(original).size)
            assertTrue(original.limits.isNotEmpty())
        }
    }
    @Test fun olderVersionsKeepEveryOriginalChangeAndWarning(){
        val history=LocalReleaseNotes.history(60)
        assertEquals(60L,history.first().code)
        assertEquals(16L,history.last().code)
        assertTrue(history.none{it.version=="0.9.0"||it.version=="0.8.0"})
        history.forEach{assertTrue(UpdatesPresentation.summary(it).size in 1..3);assertTrue(it.changes.isNotEmpty());assertTrue(it.limits.isNotEmpty())}
        assertEquals(17,LocalReleaseNotes.forVersion(58)!!.changes.size)
        assertTrue(LocalReleaseNotes.forVersion(16)!!.limits.any{"没有密码保护" in it})
    }
}
