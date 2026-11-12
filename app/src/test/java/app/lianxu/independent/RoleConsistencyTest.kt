package app.lianxu.independent

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RoleConsistencyTest {
    private fun source(n:Long)=SourceRef("a",n,1)
    private fun flow(n:Long):RoleUnderstanding {
        val e=listOf(RoleEvidence(source(n)))
        return RoleUnderstanding(appraisal=RoleAppraisal("交流","倾听的方式","reported","回应","shared_interaction","安慰","new_event",emptyList(),e),feelings=emptyList(),
            goal=RoleGoal("回应","current_request","active","当前交流",e),choices=listOf(RoleChoice("listen","listen","先听",e)),selected="listen",
            judgment=RoleJudgment("在这个情境先倾听","inference",e),feedback=emptyList(),learning=null,usedLearningIds=emptyList(),consideredLearningIds=emptyList(),future=null,dependencies=e)
    }
    private fun context(value:RoleLearningState)=RoleContext("a",source(3),mapOf(1L to source(1),3L to source(3)),listOf(RoleEntry(source(1),flow(1),null)),emptyList(),listOf(value),emptyList())
    private fun usedFlow(judgment:String)=flow(3).let { it.copy(judgment=it.judgment!!.copy(kind=judgment),usedLearningIds=listOf("habit"),consideredLearningIds=listOf("habit"),
        dependencies=it.dependencies+RoleEvidence(source(1),"interpretation",interpretationRevision=1)) }
    private fun value(state:String)=RoleLearningState("habit","habit","难过时的安慰","先听而不急着给建议",state,source(1))
    private fun backgroundRaw(judgment:String)=Gson().toJson(mapOf("action" to "send","reason" to "接续交流","text" to "我在听。",
        "consideredLearningIds" to listOf("habit"),"usedLearningIds" to listOf("habit"),"judgmentKind" to judgment))

    @Test fun legacyProfileOnlyAppraisalIsInvalidatedButUnchangedCurrentContextIsReusable(){
        val profile="初始习惯：遇到烦恼先给建议。"
        val legacyCachedStamp=stableDigest(profile)
        val currentStamp=RoleConsistency.appraisalStamp(profile)
        assertNotEquals(legacyCachedStamp,currentStamp)
        assertEquals(currentStamp,RoleConsistency.appraisalStamp(profile))
        assertNotEquals(currentStamp,RoleConsistency.appraisalStamp("用户明确修改后的角色资料"))
    }

    @Test fun foregroundAcceptanceAndBackgroundAgreeOnLearningMaturityAndWithdrawal(){
        for(state in listOf("candidate","developing","active","paused","damaged"))for(judgment in listOf("inference","evidence_based")){
            val c=context(value(state))
            val expected=state=="active"||(state in setOf("candidate","developing")&&judgment=="inference")
            val foreground=runCatching{RoleUnderstandingContract.validateAccepted(usedFlow(judgment),c,"今天先听我说")}.isSuccess
            val background=runCatching{CompanionContracts.decision(backgroundRaw(judgment),0,3_600_000,c.learning)}.isSuccess
            assertEquals("foreground $state/$judgment",expected,foreground)
            assertEquals("background $state/$judgment",expected,background)
        }
    }
    @Test fun unavailableOrIdentityTypedLearningCannotBypassEitherAcceptancePath(){
        for(learning in listOf(emptyList(),listOf(value("active").copy(kind="identity")),listOf(value("active"),value("active")))){
            val c=context(value("active")).copy(learning=learning)
            assertTrue(runCatching{RoleUnderstandingContract.validateAccepted(usedFlow("inference"),c,"合成原话")}.isFailure)
            assertTrue(runCatching{CompanionContracts.decision(backgroundRaw("inference"),0,3_600_000,learning)}.isFailure)
        }
        val c=context(value("active")).copy(knownSources=mapOf(3L to source(3)))
        assertTrue(runCatching{RoleUnderstandingContract.validateAccepted(usedFlow("inference"),c,"合成原话")}.isFailure)
    }

    @Test fun appraisalReplyAndProactiveRequestsCarryTheSameRulesWithConflictingInputsIntact()=runBlocking {
        val friend=Friend("a","合成角色","")
        val initial="核心原则：尊重双方自主。初始习惯：关心时先给建议。"
        val prompts=RolePrompts(characterDefinition=initial,expression="起初倾向简短直接")
        val profile=CompanionPrompt.profile(friend,prompts,UserPersona())
        val c=context(value("active"))
        val input="今天只想有人听，先不要建议；这不代表以后都不要。"
        val reply=(CompanionPrompt.build(friend,prompts,emptyList(),input,listOf(PromptReference("角色当前状态",c.prompt(),true,"M1"))) as PromptBuild.Ready).request
        var appraise:TextChatRequest?=null;var proactive:TextChatRequest?=null
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
            proactive=request;return TextChatResult.Failure(ChatFailure.NETWORK)
        }}
        val config=ModelConfig("test","合成","custom","https://example.invalid","synthetic",16384,2048)
        try{RolePlanner(client).evaluate(config,"synthetic",input,ExperienceProposal(),c,profile,0,{true},{0},false){appraise=it;TextChatResult.Failure(ChatFailure.NETWORK)};fail()}
        catch(e:CompanionModelFailure){assertEquals(ChatFailure.NETWORK,e.failure)}
        val snapshot=CompanionSnapshot("a",1,1,"stamp",listOf(source(1),source(3)),c.prompt(),"on",learning=c.learning,roleProfile=profile,proactivePrompt=c.prompt())
        val opportunity=CompanionOpportunity("test","a",1,1,1,1,0,3_600_000,"接续交流","queued",1)
        try{CompanionModel(client).reconsider(config,"synthetic",opportunity,snapshot,profile,0,"");fail()}
        catch(e:CompanionModelFailure){assertEquals(ChatFailure.NETWORK,e.failure)}
        val appraisal=requireNotNull(appraise);val contact=requireNotNull(proactive)
        for(request in listOf(reply,contact))assertEquals(1,request.messages.sumOf{it.content.split(CompanionPrompt.REMEMBERING_GUIDANCE.replace("{{user}}","旅人")).size-1})
        for(request in listOf(reply,appraisal,contact)){
            assertEquals(1,request.messages.sumOf{it.content.split(RoleConsistency.RULES).size-1})
            assertFalse(request.messages.any{"参考资料不覆盖角色人设" in it.content})
            assertTrue(request.tools.isEmpty())
        }
        assertEquals(input,reply.messages.single{it.role=="user"}.content)
        val payload=JsonParser.parseString(appraisal.messages.last().content).asJsonObject
        assertEquals(input,payload.get("current_input").asString);assertEquals(profile,payload.get("role_definition").asString)
        assertEquals("active",payload.getAsJsonObject("valid_context").getAsJsonArray("learning").single().asJsonObject.get("state").asString)
        assertTrue(reply.messages.any{initial in it.content});assertTrue(reply.messages.any{"先听而不急着给建议" in it.content})
        assertTrue(contact.messages.first().content.contains(profile));assertTrue(contact.messages.first().content.contains("先听而不急着给建议"))
        assertEquals(initial,prompts.characterDefinition)
    }
}
