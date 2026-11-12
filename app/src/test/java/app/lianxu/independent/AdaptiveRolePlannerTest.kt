package app.lianxu.independent

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AdaptiveRolePlannerTest {
    private fun ref(n:Long)=SourceRef("a",n,1)
    private fun context(count:Int):RoleContext {
        val entries=(1..count).map{n->val s=ref(n.toLong());val e=listOf(RoleEvidence(s))
            val flow=RoleUnderstanding(appraisal=RoleAppraisal("旧旅行","当时好奇".repeat(80),"reported","旅行","user_report","旅行","new_event",emptyList(),e),feelings=emptyList(),goal=RoleGoal("了解旅行","current_request","satisfied","历史记录",e),choices=listOf(RoleChoice("respond","acknowledge","历史记录",e)),selected="respond",judgment=RoleJudgment("有限认识","inference",e),feedback=emptyList(),learning=null,usedLearningIds=emptyList(),consideredLearningIds=emptyList(),future=null,dependencies=e)
            RoleEntry(s,flow,null)
        }
        return RoleContext("a",ref(1000),(1..count).associate{it.toLong() to ref(it.toLong())}+(1000L to ref(1000)),entries,emptyList(),listOf(RoleLearningState("habit","habit","面试","先倾听","paused",ref(1),version=3)),emptyList())
    }
    @Test fun appraisalActuallySendsBoundedViewWhileKeepingFullStateForValidation()=runBlocking {
        val full=context(180);val config=ModelConfig("a","Synthetic","custom","https://example.invalid","synthetic",16000,2048)
        assertTrue(ConservativeTokens.tokens(Gson().toJson(full))>config.contextTokens)
        var sent:TextChatRequest?=null
        val client=object:TextChatClient {override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("Use provided executor")}
        try {
            RolePlanner(client).evaluate(config,"synthetic","面试",ExperienceProposal(),full,"合成角色",0,{true},{0},false,adaptive=true){request->sent=request;TextChatResult.Failure(ChatFailure.UNAUTHORIZED)}
            fail()
        }catch(e:CompanionModelFailure){assertEquals(ChatFailure.UNAUTHORIZED,e.failure)}
        val request=requireNotNull(sent)
        assertTrue(request.messages.sumOf{ConservativeTokens.tokens(it.content)}+config.outputTokens<config.contextTokens)
        val data=JsonParser.parseString(request.messages.last().content).asJsonObject
        val visible=data.getAsJsonObject("valid_context")
        assertTrue(visible.getAsJsonArray("entries").size()<180)
        assertEquals("paused",visible.getAsJsonArray("learning").single().asJsonObject.get("state").asString)
        assertEquals(180,full.entries.size);assertTrue(data.get("partial_context").asBoolean)
    }
    @Test fun legacyWholeHistoryReproducesCapacityFailureBeforeTransport()=runBlocking {
        val config=ModelConfig("a","Synthetic","custom","https://example.invalid","synthetic",16000,2048)
        val client=object:TextChatClient {override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("Must not send")}
        try {RolePlanner(client).evaluate(config,"synthetic","面试",ExperienceProposal(),context(180),"合成角色",0,{true},{0},false){error("Must not send")};fail()}
        catch(e:CompanionModelFailure){assertEquals(ChatFailure.CONTEXT_CAPACITY,e.failure)}
    }
    @Test fun fullProfileConsumesAppraisalCapacityInBothModesWithoutSilentTruncation()=runBlocking {
        val config=ModelConfig("a","Synthetic","custom","https://example.invalid","synthetic",16000,2048)
        val profile=CompanionPrompt.profile(Friend("a","角色",""),RolePrompts(characterDefinition="设".repeat(8000)),UserPersona(introduction="介".repeat(4000)))
        val client=object:TextChatClient {override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("Must not send")}
        for(adaptive in listOf(false,true)){
            try {RolePlanner(client).evaluate(config,"synthetic","你好",ExperienceProposal(),context(0),profile,0,{true},{0},false,adaptive=adaptive){error("Must not send")};fail()}
            catch(e:CompanionModelFailure){assertEquals(ChatFailure.CONTEXT_CAPACITY,e.failure)}
        }
    }
}
