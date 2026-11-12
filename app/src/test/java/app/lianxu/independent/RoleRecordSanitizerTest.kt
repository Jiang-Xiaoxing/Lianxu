package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class RoleRecordSanitizerTest {
    private val current=SourceRef("r",2,1)
    private val old=SourceRef("r",1,1)
    private val evidence=listOf(RoleEvidence(current))
    private val context=RoleContext("r",current,mapOf(1L to old,2L to current),emptyList(),emptyList(),emptyList(),emptyList())
    private fun flow()=RoleUnderstanding(appraisal=null,feelings=listOf(RoleFeeling("joy","mild","交流","当前问候",evidence)),goal=null,
        choices=listOf(RoleChoice("a","acknowledge","回应",evidence)),selected="a",judgment=null,feedback=emptyList(),
        learning=RoleLearning("learn","interest","交流","喜欢分享","propose",evidence),usedLearningIds=emptyList(),consideredLearningIds=emptyList(),future=null,dependencies=evidence)
    @Test fun missingDependencyDropsOnlyTheDependentRecord(){
        val invalid=flow().copy(appraisal=RoleAppraisal("事","含义","reported","相关","user_report","chat","new_event",emptyList(),listOf(RoleEvidence(old))))
        val errors=mutableListOf<RelationIssue>()
        val kept=RoleRecordSanitizer.sanitize(invalid,context,"问候"){f,c->errors+=RelationIssue(f,c)}
        assertNull(kept.appraisal);assertEquals(1,kept.feelings.size);assertNotNull(kept.learning)
        assertTrue(errors.contains(RelationIssue("appraisal","DEPENDENCY_SET")))
        RoleUnderstandingContract.validateAccepted(kept,context,"问候")
    }
    @Test fun inconsistentChoiceAndExtraDependenciesDoNotInvalidateGoodRecords(){
        val invalid=flow().copy(selected="missing",dependencies=evidence+RoleEvidence(SourceRef("another",90,1)))
        val errors=mutableListOf<RelationIssue>()
        val kept=RoleRecordSanitizer.sanitize(invalid,context,"问候"){f,c->errors+=RelationIssue(f,c)}
        assertTrue(kept.choices.isEmpty());assertEquals("",kept.selected);assertEquals(1,kept.feelings.size)
        assertEquals(evidence,kept.dependencies);assertTrue(errors.any{it.field=="choices"});assertTrue(errors.any{it.field=="dependencies"})
        RoleUnderstandingContract.validateAccepted(kept,context,"问候")
    }
}
