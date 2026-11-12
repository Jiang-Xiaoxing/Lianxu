package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class JointReplyPlanningTest {
    private val client=object:TextChatClient {override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("No transport")}
    private val planner=RolePlanner(client)
    private val config=ModelConfig("fake","合成","custom","https://example.invalid","fake",16000,2048)
    private val source=SourceRef("a",101,1)
    private val context=RoleContext("a",source,mapOf(101L to source),emptyList(),emptyList(),emptyList(),emptyList())
    @Test fun jointBudgetRetainsMandatoryBoundaryAndFitsTheActualContract(){
        val facts=ExperienceProposal()
        val reserve=planner.replyFixedTokens(facts,context,0,false)+48
        val boundary=PromptReference("边界","不要拿我的工作开玩笑",mandatory=true)
        val base=CompanionPrompt.build(Friend("a","合成角色","合成设定"),RolePrompts(),emptyList(),"你好",listOf(boundary),capacity=config.contextTokens,outputLimit=config.outputTokens,adaptive=true,toolReserve=reserve) as PromptBuild.Ready
        val result=planner.prepareReply(base,config,"你好",facts,context,0,false,emptyMap(),0) as PromptBuild.Ready
        assertTrue(result.request.messages.any{"不要拿我的工作开玩笑" in it.content})
        assertTrue(result.estimatedInputTokens<=AdaptiveContext.inputLimit(config.contextTokens,config.outputTokens))
        assertTrue(result.request.messages.first().content.contains("[companion.reply.v1]"))
        assertEquals("最后两条是用户原话和时间提醒","user",result.request.messages[result.request.messages.size-2].role)
        assertFalse("Protocol C sends plain text, not a JSON envelope",result.request.jsonObject)
        assertEquals(1,result.request.messages.count{RoleConsistency.RULES in it.content})
    }
    @Test fun historyPlanningReservesTheRecordToolBeforeSelectingOldMessages(){
        val facts=ExperienceProposal();val reply=planner.replyFixedTokens(facts,context,0,false)
        // The record field guide now rides on the tool definition, so the reserve must include it.
        val record=ConservativeTokens.tokens(com.google.gson.Gson().toJson(CompanionRecord.definition(101,true)))+768
        val recent=(1..8).map{Message(it.toLong(),"a","合成原话".repeat(120),it%2==1,it.toLong())}
        fun build(extra:Int)=CompanionPrompt.build(Friend("a","合成",""),RolePrompts(),recent,"你好",capacity=config.contextTokens,outputLimit=config.outputTokens,adaptive=true,toolReserve=extra)
        assertTrue(build(0) is PromptBuild.Ready)
        assertEquals("the tool definition must take exactly its own tokens out of the history room",record,
            AdaptiveContext.inputLimit(config.contextTokens,config.outputTokens,reply)-AdaptiveContext.inputLimit(config.contextTokens,config.outputTokens,reply+record))
        assertEquals(ChatFailure.CONTEXT_NEEDS_MEMORY,(build(reply+record) as PromptBuild.Blocked).reason)
    }
}
