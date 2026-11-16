package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class UpdatesPresentationTest {
    @Test fun creationReleaseSummaryKeepsFullChangesAndLimits(){
        val note=LocalReleaseNotes.forVersion(62)!!
        assertEquals("1.0.3",note.version);assertEquals(6,note.changes.size)
        assertEquals(3,UpdatesPresentation.noticeSummary(note).size)
        assertTrue(UpdatesPresentation.noticeSummary(note).all{it.length<=32})
        assertTrue(note.changes.any{"记忆整理和识图" in it});assertTrue(note.limits.any{"不能自动保证" in it})
        assertTrue(note.limits.any{"不要卸载" in it})
    }
    @Test fun startupHighlightsAreBriefAndDoNotReplaceFullVersionFacts(){
        val note=LocalReleaseNotes.forVersion(61)!!
        val highlights=UpdatesPresentation.noticeSummary(note)
        assertEquals(3,highlights.size)
        assertTrue(highlights.all{it.length<=32})
        assertTrue(highlights.joinToString("").length<UpdatesPresentation.summary(note).joinToString("").length)
        assertEquals(7,note.changes.size)
        assertEquals(3,note.limits.size)
        assertTrue(note.limits.any{"没有修复" in it})
        assertTrue(highlights.none{"主动联系" in it||"准点" in it})
    }
    @Test fun startupHighlightsStayMatchedToEachOtherKnownVersion(){
        LocalReleaseNotes.history(60).forEach{note->
            assertEquals(UpdatesPresentation.summary(note),UpdatesPresentation.noticeSummary(note))
        }
    }
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
