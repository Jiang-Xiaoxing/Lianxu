package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

/** 聊天装不下时报对原因：只有总结能拿走整轮时才等总结，单轮太长就是聊天容量不够（不再错报记忆模型装不下）。 */
class ReplyCapacityReasonTest {
    private val client=object:TextChatClient {override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("No transport")}
    private val planner=RolePlanner(client)
    private val config=ModelConfig("fake","合成","custom","https://example.invalid","fake",16000,2048)
    private val source=SourceRef("a",101,1)
    private val context=RoleContext("a",source,mapOf(101L to source),emptyList(),emptyList(),emptyList(),emptyList())
    private val friend=Friend("a","合成角色","合成设定")
    private fun rounds(count:Int,length:Int)=(1..count).flatMap{i->listOf(Message(i*2L-1,"a","加班到很晚",true,i*2L-1),Message(i*2L,"a","长".repeat(length),false,i*2L))}
    private fun reason(recent:List<Message>,reserve:Int,adaptive:Boolean=false,recentLimit:Int=40):ChatFailure?{
        val base=CompanionPrompt.build(friend,RolePrompts(),recent,"你好",capacity=config.contextTokens,outputLimit=config.outputTokens,recentLimit=recentLimit,adaptive=adaptive) as PromptBuild.Ready
        return (planner.prepareReply(base,config,"你好",ExperienceProposal(),context,0,false,emptyMap(),reserve) as? PromptBuild.Blocked)?.reason
    }
    @Test fun oneOverLongRoundIsChatCapacityNotAMemoryProblem(){
        assertEquals(ChatFailure.CONTEXT_CAPACITY,reason(rounds(1,5000),8000))
        assertEquals(ChatFailure.CONTEXT_CAPACITY,reason(rounds(1,5000),8000,adaptive=true))
        // 轮数没超过近期轮数时，总结也拿不走任何一轮。
        assertEquals(ChatFailure.CONTEXT_CAPACITY,reason(rounds(3,1500),8000,recentLimit=3))
    }
    @Test fun roundsASummaryCanTakeAwayStillWaitForTheSummary(){
        assertEquals(ChatFailure.CONTEXT_NEEDS_MEMORY,reason(rounds(6,800),8000,recentLimit=2))
        assertEquals(ChatFailure.CONTEXT_NEEDS_MEMORY,reason(rounds(6,800),8000,adaptive=true))
    }
    @Test fun noHistoryStaysCoreCapacityAndAFittingTurnIsReady(){
        assertEquals(ChatFailure.CORE_CAPACITY,reason(emptyList(),13900))
        assertNull(reason(rounds(1,200),0))
    }
}
