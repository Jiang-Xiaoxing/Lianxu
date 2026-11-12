package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class RoleUnderstandingTest {
    private fun ref(n:Long)=SourceRef("a",n,1)
    private fun flow(n:Long,learning:RoleLearning?=null,eventRelation:String="new_event",certainty:String="reported",used:List<String> = emptyList(),feedback:List<RoleFeedback> = emptyList()):RoleUnderstanding {
        val e=listOf(RoleEvidence(ref(n)))
        val all=(e+learning?.evidence.orEmpty()).distinct()
        return RoleUnderstanding(appraisal=RoleAppraisal("一次有来源的交流","我对共同解题产生兴趣",certainty,"探索共同问题","shared_interaction","puzzle",eventRelation,emptyList(),e),feelings=listOf(RoleFeeling("curiosity","moderate","问题的解法","共同探索的新理解",e)),goal=RoleGoal("探索一个问题","role_interest","active","我的好奇与当前话题有关",e),choices=listOf(RoleChoice("x","explore","愿意理解解法",e)),selected="x",judgment=RoleJudgment("我开始发现这个活动也有值得投入的部分","inference",e),feedback=feedback,learning=learning,usedLearningIds=used,consideredLearningIds=used,future=null,dependencies=all)
    }
    private fun action(n:Long,flow:RoleUnderstanding)=RoleActualAction(ref(n),flow.selectedChoice()!!.action,flow.appraisal!!.situation,n+1,"确实提交的一段合成交流",flow.usedLearningIds,flow.consideredLearningIds,flow.goal!!.origin,null,null)
    private fun entries(kind:String="interest",relation:String="progress",certain:String="reported",withResultEvidence:Boolean=true):List<RoleEntry> {
        val first=flow(1,RoleLearning("candidate",kind,"共同解题","也许这个活动有可取之处","propose",listOf(RoleEvidence(ref(1)))))
        val evidence=listOf(RoleEvidence(ref(3)))+if(withResultEvidence)listOf(RoleEvidence(ref(1),"action",2))else emptyList()
        val second=flow(3,RoleLearning("candidate",kind,"共同解题","在新的共同结果后，我对共同解题更有兴趣","revise",evidence),relation,certain)
        return listOf(RoleEntry(ref(1),first,action(1,first)),RoleEntry(ref(3),second,action(3,second)))
    }
    @Test fun groundedInterestCanEvolveWithoutAnyApprovalOrPositiveFeedback(){
        val result=RoleLearningProjector.project(entries()).single()
        assertEquals("active",result.state);assertEquals(2,result.version);assertTrue(result.content.contains("更有兴趣"));assertEquals(setOf(2L),result.supportingResults)
    }
    @Test fun oneNewOpinionIsStillACandidate(){assertEquals("candidate",RoleLearningProjector.project(entries().take(1)).single().state)}
    @Test fun repeatedMentionDoesNotManufactureIndependentResults(){assertEquals("developing",RoleLearningProjector.project(entries(relation="repetition")).single().state)}
    @Test fun unknownOrUnlinkedEvidenceDoesNotBecomeAnEstablishedPreference(){
        assertEquals("developing",RoleLearningProjector.project(entries(certain="unknown")).single().state)
        assertEquals("developing",RoleLearningProjector.project(entries(withResultEvidence=false)).single().state)
    }
    @Test fun removingTheCausalCandidateRemovesItsLaterProjection(){assertTrue(RoleLearningProjector.project(entries().drop(1)).isEmpty())}
    @Test fun disagreementDoesNotPunishAnEvidenceBasedViewpoint(){
        val prior=entries(kind="viewpoint");val used=prior.last().flow.copy(usedLearningIds=listOf("candidate"),consideredLearningIds=listOf("candidate"))
        val modified=prior.dropLast(1)+RoleEntry(ref(3),used,action(3,used));val f=flow(5,feedback=listOf(RoleFeedback(4,"rejected","我不同意这个看法")))
        assertEquals("active",RoleLearningProjector.project(modified+RoleEntry(ref(5),f,action(5,f))).single().state)
    }
    @Test fun actualFailureCanPauseAScopedHabitWithoutUserApproval(){
        val prior=entries(kind="habit");val used=prior.last().flow.copy(usedLearningIds=listOf("candidate"),consideredLearningIds=listOf("candidate"))
        val modified=prior.dropLast(1)+RoleEntry(ref(3),used,action(3,used))
        val feedback=flow(5,feedback=listOf(RoleFeedback(4,"unhelpful","这次这种做法反而添乱")))
        assertEquals("paused",RoleLearningProjector.project(modified+RoleEntry(ref(5),feedback,action(5,feedback))).single().state)
    }
    @Test fun proactiveActionLearningUseParticipatesInLaterResults(){
        val prior=entries(kind="habit")
        val proactive=RoleActualAction(ref(3),"share","proactive_interest_share",10,"合成主动交流",listOf("candidate"),listOf("candidate"),"role_interest",null,null)
        val f=flow(11,feedback=listOf(RoleFeedback(10,"unhelpful","这次主动提这个反而添乱")))
        val all=prior+RoleEntry(ref(11),f,action(11,f))
        assertEquals("paused",RoleLearningProjector.project(all,all.mapNotNull{it.action}+proactive).single().state)
        // Without recorded use (the old emptyList() projection) the same result could not reach the habit.
        val unrecorded=proactive.copy(usedLearningIds=emptyList(),consideredLearningIds=emptyList())
        assertEquals("active",RoleLearningProjector.project(all,all.mapNotNull{it.action}+unrecorded).single().state)
    }
    @Test fun explicitCounterevidenceCanReviseAnInterestWithoutApproval(){
        val prior=entries();val change=RoleLearning("candidate","interest","共同解题","现有经历不足，先搁置这个偏好","pause",listOf(RoleEvidence(ref(5))))
        val f=flow(5,change)
        assertEquals("paused",RoleLearningProjector.project(prior+RoleEntry(ref(5),f,action(5,f))).single().state)
    }
    @Test fun cachedRevisionOrPauseDoesNotBecomeItsOwnRetryEvidence(){
        val previous=entries().take(1);val actual=previous.mapNotNull{it.action}
        val before=RoleContext("a",ref(3),mapOf(1L to ref(1),3L to ref(3)),previous,actual,RoleLearningProjector.project(previous,actual),listOf(CompanionExperience(ref(3),"新的实际结果","新的实际结果")))
        for(phase in listOf("revise","pause")){
            val change=RoleLearning("candidate","interest","共同解题","根据新结果调整看法",phase,listOf(RoleEvidence(ref(3)),RoleEvidence(ref(1),"action",2)))
            val candidate=flow(3,change).let{it.copy(dependencies=it.dependencies+RoleEvidence(ref(1),"interpretation",interpretationRevision=1))}
            RoleUnderstandingContract.validateAccepted(candidate,before,"新的实际结果")
            // C1 is durable, but C2 has not saved an action for the failed reply.
            val saved=previous+RoleEntry(ref(3),candidate,null)
            val retry=before.copy(entries=saved,learning=RoleLearningProjector.project(saved,actual))
            assertEquals(ref(3),retry.learning.single().source)
            repeat(2){RoleUnderstandingContract.validateAccepted(candidate,retry,"新的实际结果")}
            assertEquals(before.learning,retry.beforeCurrent().learning)
            assertEquals(before.facts,retry.beforeCurrent().facts)
            assertEquals(1,retry.beforeCurrent().actions.size)
            val self=RoleEvidence(ref(3),"interpretation",interpretationRevision=1)
            val invalid=candidate.copy(judgment=candidate.judgment!!.copy(evidence=listOf(self)),dependencies=candidate.dependencies+self)
            assertTrue(runCatching{RoleUnderstandingContract.validateAccepted(invalid,retry,"新的实际结果")}.isFailure)
        }
    }
    @Test fun typedSourceValidationRejectsForeignRoleAndSelfSupportingInterpretation(){
        val source=ref(1);val context=RoleContext("a",source,mapOf(1L to source),emptyList(),emptyList(),emptyList(),emptyList())
        val valid=flow(1);RoleUnderstandingContract.validateAccepted(valid,context,"合成原话")
        val bad=RoleEvidence(SourceRef("b",1,1))
        val foreign=valid.copy(appraisal=valid.appraisal!!.copy(evidence=listOf(bad)),dependencies=(valid.dependencies+bad))
        try{RoleUnderstandingContract.validateAccepted(foreign,context.copy(knownSources=mapOf(1L to bad.source)),"合成原话");fail()}catch(_:RoleContractViolation){}
        val circular=RoleEvidence(source,"interpretation",interpretationRevision=1)
        try{RoleUnderstandingContract.validateAccepted(valid.copy(appraisal=valid.appraisal!!.copy(evidence=listOf(circular)),dependencies=valid.dependencies+circular),context,"合成原话");fail()}catch(_:RoleContractViolation){}
    }
}
