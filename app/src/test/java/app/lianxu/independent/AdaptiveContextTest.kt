package app.lianxu.independent

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class AdaptiveContextTest {
    private val friend=Friend("a","合成角色","合成设定")
    private fun rounds(n:Int,length:Int=4)=(1..n).flatMap{listOf(Message(it*2L,"a","甲".repeat(length),true,0),Message(it*2L+1,"a","乙".repeat(length),false,1))}
    private fun source(n:Long)=SourceRef("a",n,1)

    @Test fun shortHistoryCanExceedFortyWithoutDroppingAnyMessage(){
        val history=rounds(70)
        val result=CompanionPrompt.build(friend,RolePrompts(),history,"今天好吗",adaptive=true) as PromptBuild.Ready
        assertEquals(history.map{it.text},result.request.messages.filter{it.sourceMessageId.isNotBlank()}.map{it.content.substring(it.sourcePrefix)})
        // Fixed-round mode no longer blocks past the limit either; it only waits on a real token overflow.
        assertTrue(CompanionPrompt.build(friend,RolePrompts(),history,"今天好吗") is PromptBuild.Ready)
    }
    @Test fun longHistorySummarizesBeforeFortyAndLeavesMandatoryInputsWhole(){
        val result=CompanionPrompt.build(friend,RolePrompts(),rounds(10,400),"当前原话",capacity=16000,outputLimit=2048,adaptive=true)
        assertEquals(ChatFailure.CONTEXT_NEEDS_MEMORY,(result as PromptBuild.Blocked).reason)
        val huge=listOf(PromptReference("锁定核心","甲".repeat(12000),true))
        assertEquals(ChatFailure.CONTEXT_CAPACITY,(CompanionPrompt.build(friend,RolePrompts(),emptyList(),"当前原话",huge,capacity=16000,adaptive=true) as PromptBuild.Blocked).reason)
    }
    @Test fun optionalOverflowNoLongerBlocksReplyAndNoFixedFifteenCeiling(){
        val refs=(1..24).map{PromptReference("记忆$it","garden "+"x".repeat(250),memoryId="m$it",related=true,priority=0.8)}
        val small=CompanionPrompt.build(friend,RolePrompts(),emptyList(),"garden",refs,capacity=4096,outputLimit=1024,adaptive=true) as PromptBuild.Ready
        assertTrue(small.selectedReferences.isNotEmpty());assertTrue(small.selectedReferences.size<15)
        assertTrue(small.estimatedInputTokens<=AdaptiveContext.inputLimit(4096,1024))
        val large=CompanionPrompt.build(friend,RolePrompts(),emptyList(),"garden",refs,capacity=32000,adaptive=true) as PromptBuild.Ready
        assertEquals(24,large.selectedReferences.size)
    }
    @Test fun exactDuplicateSourcesDeduplicateButSeparateEventsAndSeparateFactsSurvive(){
        val first=PromptReference("旧记忆","周五面试",memoryId="m1",sources=setOf(source(1)),priority=1.0)
        val duplicate=first.copy(label="新经历",memoryId="e1")
        val anotherEvent=first.copy(label="另一场面试",memoryId="m2",sources=setOf(source(3)))
        val anotherFact=first.copy(label="同轮另一事实",memoryId="m3",text="准备简历")
        val selected=AdaptiveContext.references("面试",listOf(first,duplicate,anotherEvent,anotherFact),4000)
        assertEquals(3,selected.size);assertTrue(selected.any{it.memoryId=="m2"});assertTrue(selected.any{it.memoryId=="m3"})
    }
    @Test fun weakVectorOnlyCandidatesAreNotPaddedToFifteen(){
        val refs=(1..15).map{PromptReference("记忆$it","完全不同的话题",memoryId="m$it",priority=0.01)}
        assertTrue(AdaptiveContext.references("面试",refs,4000).isEmpty())
        val boundary=PromptReference("用户边界","不要谈住址",true)
        assertEquals(listOf(boundary),AdaptiveContext.references("面试",refs+boundary,4000))
    }
    @Test fun toolSchemaAndOutputReserveReduceAvailableSpace(){
        val refs=(1..20).map{PromptReference("记忆$it","garden "+"x".repeat(200),memoryId="m$it",priority=1.0)}
        fun build(reserve:Int)=CompanionPrompt.build(friend,RolePrompts(),emptyList(),"garden",refs,capacity=8000,adaptive=true,toolReserve=reserve) as PromptBuild.Ready
        val without=build(0);val with=build(3000)
        assertTrue(with.selectedReferences.size<without.selectedReferences.size)
        assertTrue(with.estimatedInputTokens+3000+2048<8000)
    }
    @Test fun completePrefixUsesVolumeAndKeepsNewestRound(){
        assertEquals(0,AdaptiveContext.summaryCount(listOf(100),20))
        assertEquals(2,AdaptiveContext.summaryCount(listOf(400,400,100,100),500))
        assertEquals(1,AdaptiveContext.summaryCount(listOf(850,50,50,50),500))
        assertEquals(3,AdaptiveContext.summaryCount(listOf(100,100,100,900),200))
        assertFalse(AdaptiveContext.needsSummary(700,1000,70));assertTrue(AdaptiveContext.needsSummary(800,1000,4))
    }
    @Test fun historicalRoleStateIsBoundedWithoutChangingAuthoritativeState(){
        val facts=(1..300).map{CompanionExperience(source(it.toLong()),if(it==1)"周五面试，需要准备简历"else"无关旅行记录 "+"合成".repeat(70),"合成原句")}
        val learning=listOf(RoleLearningState("habit","habit","面试","先倾听", "paused",source(300),version=3))
        val full=RoleContext("a",source(301),(1..301).associate{it.toLong() to source(it.toLong())},emptyList(),emptyList(),learning,facts)
        val selected=AdaptiveContext.roleView(full,"面试",1200)
        assertTrue(ConservativeTokens.tokens(Gson().toJson(selected))<=1200)
        assertTrue(selected.facts.any{it.source==source(1)});assertEquals("paused",selected.learning.single().state)
        assertEquals(300,full.facts.size);assertEquals(301,full.knownSources.size)
    }
}
