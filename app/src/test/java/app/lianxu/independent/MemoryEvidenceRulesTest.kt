package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class MemoryEvidenceRulesTest {
    private fun proof(turn:Long=1,side:String="user",quote:String="coffee",snapshot:String="coffee and tea")=MemoryEvidence(SourceRef("a",turn,1),side,quote,snapshot,100,"UTC")
    private fun fact(text:String="No coffee",e:List<MemoryEvidence> = listOf(proof()))=StoredMemory("one","a",MemoryCategory.PREFERENCE_BOUNDARY,text,evidence=e,about=MemoryAbout.USER)
    @Test fun sameSourceAndFactCannotBeStrengthenedByAnotherIdCoreOrTopic(){
        val old=fact();val repeated=old.copy(id="another",version=2,core=true,tags=listOf("new topic"))
        assertTrue(MemoryEvidenceRules.repeated(old,repeated,listOf(proof(quote="tea"))))
        assertFalse(old.core);assertEquals(1,old.version)
    }
    @Test fun twoFactsInOneMessageRemainDifferent(){assertFalse(MemoryEvidenceRules.sameFact(fact("No coffee"),fact("Likes tea")))}
    @Test fun differentWordingsAndNegationAreNotGuessedEquivalent(){
        assertFalse(MemoryEvidenceRules.sameFact(fact("Likes coffee"),fact("Dislikes coffee")))
        assertFalse(MemoryEvidenceRules.sameFact(fact("No coffee"),fact("Stopped drinking coffee")))
        assertTrue(MemoryEvidenceRules.sameFact(fact(" No  coffee\n"),fact()))
    }
    @Test fun differentSubjectsRolesAndDatesDoNotCollapse(){
        val a=fact();for(b in listOf(a.copy(friendId="b"),a.copy(about=MemoryAbout.CHARACTER),a.copy(category=MemoryCategory.STATE_FACT),a.copy(eventDate="2026-10-27"),a.copy(dueDate="2026-10-28")))assertFalse(MemoryEvidenceRules.sameFact(a,b))
    }
    @Test fun lifecycleChangeIsNotMistakenForAReplay(){assertFalse(MemoryEvidenceRules.repeated(fact(),fact().copy(life=MemoryLife.COMPLETED),listOf(proof())))}
    @Test fun repeatedQuotedSpansAreOneSourceAndKeepTheWholeOriginal(){
        val merged=MemoryEvidenceRules.merge(listOf(proof()),listOf(proof(quote="tea"),proof()))
        assertEquals(1,merged.size);assertEquals("coffee and tea",merged.single().quote)
        assertEquals(merged,MemoryEvidenceRules.merge(merged,listOf(proof())))
    }
    @Test fun independentSourcesCanSupportTheSameFact(){
        val old=fact();assertFalse(MemoryEvidenceRules.repeated(old,old,listOf(proof(2))))
        assertEquals(2,MemoryEvidenceRules.merge(old.evidence,listOf(proof(2))).size)
    }
    @Test fun messageSidesAndSourceRevisionsAreDistinct(){
        assertEquals(3,MemoryEvidenceRules.merge(listOf(proof()),listOf(proof(side="assistant"),proof().copy(source=SourceRef("a",1,2)))).size)
    }
    @Test fun noSourceNeverCountsAsAnAutomaticReplay(){assertFalse(MemoryEvidenceRules.repeated(fact(e=emptyList()),fact(),emptyList()))}
    @Test fun conflictingSnapshotsFailInsteadOfInventingEvidence(){
        val result=runCatching{MemoryEvidenceRules.merge(listOf(proof()),listOf(proof(snapshot="changed")))}
        assertEquals("SOURCE_CHANGED",(result.exceptionOrNull() as ProcessingRejected).code)
    }
    @Test fun contextAcceptsOwnWritesButNotLateExternalChanges(){
        assertTrue(MemoryEvidenceRules.contextCurrent(1,null,1));assertTrue(MemoryEvidenceRules.contextCurrent(1,2,2))
        assertTrue(MemoryEvidenceRules.contextCurrent(3,2,3));assertFalse(MemoryEvidenceRules.contextCurrent(1,2,3))
        assertFalse(MemoryEvidenceRules.contextCurrent(1,null,null));assertFalse(MemoryEvidenceRules.contextCurrent(3,2,2))
    }
    @Test fun validationOnlyResultsCannotShiftModelMemoryNumbers(){
        val selected=mapOf("middle-shown" to 1,"z-shown" to 2)
        val work=MemoryWork("batch","a",emptyList(),1,"character","config",selected,resultVersions=mapOf("a-hidden" to 7))
        val sent=MemoryNumberedReferences(emptyList(),selected.keys);val decoded=MemoryNumberedReferences(emptyList(),work.existingVersions.keys)
        assertEquals("M1",decoded.memoryRef("middle-shown"));assertEquals("M2",decoded.memoryRef("z-shown"))
        selected.keys.forEach{assertEquals(sent.memoryRef(it),decoded.memoryRef(it))};assertFalse("a-hidden" in work.existingVersions)
    }
}
