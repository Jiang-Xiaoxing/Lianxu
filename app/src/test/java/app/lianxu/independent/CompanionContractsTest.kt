package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class CompanionContractsTest {
    private val now=1_800_000_000_000L
    private fun proposal(extra:String="")="""{"quote":"谢谢你听我说","memory":"用户感谢倾听","emotion":"happy","valence":1,"relationship":"appreciation","control":"none","targets":[]$extra}"""
    private fun rejected(block:()->Unit){try{block();fail("must reject")}catch(_:IllegalArgumentException){}catch(_:IllegalStateException){}}
    private val snapshotLearning=listOf(
        RoleLearningState("learning-c","viewpoint","陶艺杯底干燥","先查干燥速度","candidate",SourceRef("a",1,1)),
        RoleLearningState("learning-a","viewpoint","陶艺杯底厚度","先统一厚度","active",SourceRef("a",3,1),2,setOf(2L)),
        RoleLearningState("learning-p","habit","提醒方式","少催促","paused",SourceRef("a",5,1)))
    private fun send(considered:List<String>,used:List<String>,judgment:String?)=buildString{
        append("{\"action\":\"send\",\"reason\":\"接续共同经历\",\"text\":\"我又想到杯底干燥的顺序。\"")
        append(",\"consideredLearningIds\":").append(considered.joinToString(",","[","]"){"\"$it\""})
        append(",\"usedLearningIds\":").append(used.joinToString(",","[","]"){"\"$it\""})
        if(judgment!=null)append(",\"judgmentKind\":\"$judgment\"")
        append("}")
    }
    private fun decide(raw:String,learning:List<RoleLearningState>? =snapshotLearning)=CompanionContracts.decision(raw,now,now+3_600_000,learning)
    @Test fun proactiveDecisionRecordsConsideredAndUsedLearningFromTheSnapshot(){
        val d=decide(send(listOf("learning-c","learning-a"),listOf("learning-c"),"inference"))
        assertEquals(listOf("learning-c","learning-a"),d.consideredLearningIds);assertEquals(listOf("learning-c"),d.usedLearningIds);assertEquals("inference",d.judgmentKind)
        assertEquals(listOf("learning-a"),decide(send(listOf("learning-a"),listOf("learning-a"),"evidence_based")).usedLearningIds)
        assertTrue(decide(send(listOf("learning-p"),emptyList(),null)).usedLearningIds.isEmpty()) // weighed but not used
        val legacy=CompanionContracts.decision("""{"action":"silent","reason":"没有新线索","text":""}""",now,now+1000)
        assertTrue(legacy.consideredLearningIds.isEmpty()&&legacy.usedLearningIds.isEmpty())
    }
    @Test fun proactiveDecisionRejectsUnknownPausedUnconsideredOrOverclaimedLearning(){
        rejected{decide(send(listOf("learning-x"),emptyList(),null))}
        rejected{decide(send(listOf("learning-p"),listOf("learning-p"),"inference"))}
        rejected{decide(send(listOf("learning-a"),listOf("learning-c"),"inference"))}
        rejected{decide(send(listOf("learning-c"),listOf("learning-c"),"evidence_based"))}
        rejected{decide(send(listOf("learning-c"),listOf("learning-c"),null))}
        rejected{decide(send(listOf("learning-c"),listOf("learning-c"),"sure"))}
        rejected{decide(send(listOf("learning-c","learning-c"),emptyList(),null))}
        rejected{decide(send(listOf("learning-c"),emptyList(),null),null)}
        rejected{decide(send(listOf("learning-c"),listOf("learning-c"),"inference"),emptyList())}
    }
    @Test fun persistedProactiveLearningUseRoundTripsAndDamagedRowsClaimNoInfluence(){
        val (c,u)=ProactiveLearningUse.decode(ProactiveLearningUse.encode(listOf("a","b")),ProactiveLearningUse.encode(listOf("b")))
        assertEquals(listOf("a","b"),c);assertEquals(listOf("b"),u)
        val none=emptyList<String>() to emptyList<String>()
        assertEquals(none,ProactiveLearningUse.decode(null,null))
        assertEquals(none,ProactiveLearningUse.decode("[\"a\"]","[\"z\"]"))
        assertEquals(none,ProactiveLearningUse.decode("{",null))
        assertEquals(none,ProactiveLearningUse.decode("[1]","[]"))
    }
    @Test fun groundedExperienceHasNoInventedTimeOrScore(){val p=CompanionContracts.experience(proposal(),"谢谢你听我说",now,emptySet());assertEquals("appreciation",p.relationship);assertNull(p.reconsider)}
    @Test fun unresolvedControlCannotFallThroughToAnUnverifiedSuccessReply(){
        assertNull(ExperienceProposal().controlReply())
        assertNotNull(ExperienceProposal(controlChecked=true).controlReply())
        assertTrue(ExperienceProposal(controlChecked=true).controlReply()!!.contains("尚未执行"))
        assertNotNull(ExperienceProposal(control="correct",targets=listOf(1)).controlReply())
    }
    @Test fun mismatchedQuoteAndUnknownEmotionCannotManufactureAffect(){rejected{CompanionContracts.experience(proposal(),"天气不错",now,emptySet())};rejected{CompanionContracts.experience(proposal().replace("happy","unknown"),"谢谢你听我说",now,emptySet())}}
    @Test fun integerOverflowCannotBypassValenceRange(){rejected{CompanionContracts.experience(proposal().replace(":1,",":4294967297,"),"谢谢你听我说",now,emptySet())}}
    @Test fun controlRequiresAuthorizedRequestAndSameRoleTargets(){
        val raw="""{"quote":"忘掉昨天的面试","memory":"","emotion":"unknown","valence":0,"relationship":"none","control":"forget","targets":[10],"controlAuthorized":true,"controlIntent":"forget","requestQuote":"忘掉昨天的面试"}"""
        assertEquals(listOf(10L),CompanionContracts.experience(raw,"忘掉昨天的面试",now,setOf(10)).targets)
        rejected{CompanionContracts.experience(raw,"忘掉昨天的面试",now,setOf(11))}
        rejected{CompanionContracts.experience(raw.replace("\"controlAuthorized\":true","\"controlAuthorized\":false"),"昨天的面试",now,setOf(10))}
    }
    @Test fun correctionUsesItsOwnExplicitQuoteWithoutOverwritingTheIndependentFactQuote(){
        val raw="""{"quote":"我现在喜欢茶","memory":"用户现在喜欢茶","control":"correct","controlQuote":"更正消息1","targets":[1],"controlAuthorized":true,"controlIntent":"correct","requestQuote":"更正消息1"}"""
        val p=CompanionContracts.experience(raw,"更正消息1，我现在喜欢茶",now,setOf(1))
        assertEquals("我现在喜欢茶",p.quote);assertEquals("更正消息1",p.controlQuote)
        rejected{CompanionContracts.experience(raw.replace("更正消息1","更正消息9"),"更正消息1，我现在喜欢茶",now,setOf(1))}
    }
    @Test fun postponementCanBeImmediateShortOrBeyondLegacyExpiry(){val expiry=now+3_600_000
        for(next in listOf(now,now+1000,expiry,expiry+86_400_000)){
            assertEquals(next,CompanionContracts.decision("""{"action":"defer","reason":"再想想","text":"","nextAt":$next}""",now,expiry).nextAt)
        }
        rejected{CompanionContracts.decision("""{"action":"defer","reason":"再想想","text":"","nextAt":${now-1}}""",now,expiry)}
    }
    @Test fun doNotForgetCannotBeAcceptedAsAForgetInstruction(){
        val raw="""{"quote":"不要忘记昨天的面试","memory":"","emotion":"unknown","valence":0,"relationship":"none","control":"forget","targets":[10],"controlAuthorized":true,"controlIntent":"forget","requestQuote":"忘掉昨天的面试"}"""
        rejected{CompanionContracts.experience(raw,"不要忘记昨天的面试",now,setOf(10))}
    }
    @Test fun silentAndCancelledHaveNoUserFacingMessage(){for(a in listOf("silent","cancel")){assertEquals(a,CompanionContracts.decision("""{"action":"$a","reason":"没有新线索","text":""}""",now,now+1000).action);rejected{CompanionContracts.decision("""{"action":"$a","reason":"没有新线索","text":"催一下"}""",now,now+1000)}}}
    @Test fun visiblePreviewOrUnfocusedWindowIsNotRead(){
        fun seen(foreground:Boolean=true,focused:Boolean=true,top:Float=20f,bottom:Float=120f)=MessageVisibility.eligible(top,bottom,10f,200f,0f,500f,0f,300f,foreground,focused)
        assertTrue(seen());assertFalse(seen(foreground=false));assertFalse(seen(focused=false));assertFalse(seen(top=501f,bottom=600f));assertFalse(seen(top=-100f,bottom=100f))
    }
    @Test fun longMessageNeedsItsBeginningAndMeaningfulVisibleArea(){
        assertTrue(MessageVisibility.eligible(0f,5000f,0f,250f,0f,500f,0f,300f,true,true))
        assertFalse(MessageVisibility.eligible(-2000f,3000f,0f,250f,0f,500f,0f,300f,true,true))
        assertFalse(MessageVisibility.eligible(450f,5450f,0f,250f,0f,500f,0f,300f,true,true))
    }
}
