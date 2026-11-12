package app.lianxu.independent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CompanionModelIsolationTest {
    private val now=1_800_000_000_000L
    private val config=ModelConfig("synthetic","synthetic","custom","https://example.invalid/v1","synthetic")
    private val snapshot=CompanionSnapshot("A",1,1,"stamp",listOf(SourceRef("A",1,1)),"OLD_PRIVATE_SENTINEL:以前喜欢咖啡","disabled")
    private val fact="""{"quote":"我现在喜欢茶","memory":"用户现在喜欢茶","emotion":"unknown","valence":0,"relationship":"none","control":"none","targets":[],"reconsider":null,"claimKind":"reported_event","controlIntent":"none","requestQuote":""}"""
    private fun controlFact(intent:String,quote:String)=com.google.gson.JsonParser.parseString(fact).asJsonObject.apply{addProperty("controlIntent",intent);addProperty("requestQuote",quote)}.toString()
    private class ScriptedClient(private val replies:List<String>):TextChatClient {
        val requests=mutableListOf<TextChatRequest>()
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
            requests+=request
            return TextChatResult.Success(replies.getOrElse(requests.lastIndex){replies.last()})
        }
    }
    @Test fun independentExperienceNeverReceivesHistoricalContext()=runBlocking {
        val client=ScriptedClient(listOf(fact))
        val p=CompanionModel(client).interpret(config,"synthetic","我现在喜欢茶",snapshot,now)
        assertEquals(1,client.requests.size)
        assertFalse(client.requests.single().messages.any{"OLD_PRIVATE_SENTINEL" in it.content})
        assertEquals("用户现在喜欢茶",p.memory)
        assertEquals("none",p.control)
    }
    @Test fun contextualControlCannotSmuggleFactsAffectOrScheduleIntoIndependentExperience()=runBlocking {
        val input="更正消息1，我现在喜欢茶"
        val lookup="""{"control":"correct","quote":"更正消息1","targets":[1],"authorization":"current_direct_request","memory":"OLD_PRIVATE_SENTINEL","emotion":"angry","valence":-1,"relationship":"boundary","reconsider":{"reason":"OLD_PRIVATE_SENTINEL","notBefore":${now+900000},"expiresAt":${now+1800000}}}"""
        val client=ScriptedClient(listOf(controlFact("correct","更正消息1"),lookup))
        val p=CompanionModel(client).interpret(config,"synthetic",input,snapshot,now)
        assertEquals(2,client.requests.size)
        assertFalse(client.requests.first().messages.any{"OLD_PRIVATE_SENTINEL" in it.content})
        assertTrue(client.requests.last().messages.any{"OLD_PRIVATE_SENTINEL" in it.content})
        assertEquals("用户现在喜欢茶",p.memory);assertEquals("我现在喜欢茶",p.quote)
        assertEquals("correct",p.control);assertEquals("更正消息1",p.controlQuote);assertEquals(listOf(1L),p.targets)
        assertEquals("unknown",p.emotion);assertEquals(0,p.valence);assertEquals("none",p.relationship);assertNull(p.reconsider)
        assertEquals(p,CompanionContracts.experience(com.google.gson.Gson().toJson(p),input,now,setOf(1)).copy(controlChecked=p.controlChecked))
    }
    @Test fun contextualControlRejectsSourcesOutsideTheCurrentRole()=runBlocking {
        val client=ScriptedClient(listOf(controlFact("correct","更正消息2"),"""{"control":"correct","quote":"更正消息2","targets":[2],"authorization":"current_direct_request"}"""))
        try {CompanionModel(client).interpret(config,"synthetic","更正消息2，我现在喜欢茶",snapshot,now);fail("foreign source must be rejected")}
        catch(failure:CompanionModelFailure){assertEquals(ChatFailure.STRUCTURED_FORMAT,failure.failure)}
    }
    @Test fun malformedIncompleteAndUngroundedRepliesFailAfterBoundedRecovery()=runBlocking {
        val bad=listOf("{}","not JSON","```json\n$fact\n```",fact.dropLast(2),fact.replace("\"valence\":0","\"valence\":\"zero\""),fact.replace("我现在喜欢茶","昨天去了秘密地点"),fact.replace(",\"emotion\":\"unknown\"",""))
        for(raw in bad){val client=ScriptedClient(listOf(raw))
            try{CompanionModel(client).interpret(config,"synthetic","我现在喜欢茶",snapshot,now);fail("invalid reply accepted: $raw")}
            catch(f:CompanionModelFailure){assertEquals(ChatFailure.STRUCTURED_FORMAT,f.failure)}
            assertEquals(3,client.requests.size)
        }
    }
    @Test fun renamedControlFieldsCannotSilentlyBecomeNoOperation()=runBlocking {
        val client=ScriptedClient(listOf(controlFact("correct","更正消息1"),"""{"behavior":"correct","quote":"更正消息1","affected_sources":[1]}"""))
        try{CompanionModel(client).interpret(config,"synthetic","更正消息1，我现在喜欢茶",snapshot,now);fail("renamed control must fail")}
        catch(f:CompanionModelFailure){assertEquals(ChatFailure.STRUCTURED_FORMAT,f.failure)}
        assertEquals(4,client.requests.size)
    }
    @Test fun capturedToolLikeJsonCannotSilentlyContinueOrdinaryChat()=runBlocking {
        val client=ScriptedClient(listOf("""{"tools":[{"recipient_name":"journal_memory_recall"}],"stop":true}"""))
        try{CompanionModel(client).interpret(config,"synthetic","请回查旧经历",snapshot,now);fail("invalid extraction must fail")}
        catch(f:CompanionModelFailure){assertEquals(ChatFailure.STRUCTURED_FORMAT,f.failure)}
        assertEquals(3,client.requests.size)
    }
    @Test fun validEmptyJudgmentIsNotAFormatFailure()=runBlocking {
        val empty=com.google.gson.GsonBuilder().serializeNulls().create().toJson(ExperienceProposal(claimKind="none"))
        val client=ScriptedClient(listOf(empty));val p=CompanionModel(client).interpret(config,"synthetic","今天随便聊聊",snapshot,now)
        assertEquals("",p.memory);assertFalse(p.interpretationUnknown);assertEquals(1,client.requests.size)
    }
    @Test fun semanticControlDoesNotRequireTheOldChineseTriggerWords()=runBlocking {
        val input="别让那段旧事继续左右你对我的看法，我现在喜欢茶"
        val direct="别让那段旧事继续左右你对我的看法"
        val lookup="""{"control":"forget","quote":"$direct","targets":[1],"authorization":"current_direct_request"}"""
        val p=CompanionModel(ScriptedClient(listOf(controlFact("forget",direct),lookup))).interpret(config,"synthetic",input,snapshot,now)
        assertEquals("forget",p.control);assertTrue(p.controlAuthorized);assertEquals(listOf(1L),p.targets)
    }
    @Test fun negatedOrQuotedRequestsDoNotAuthorizeControl()=runBlocking {
        for(input in listOf("不要忘记昨天那件事","他引用了一句：忘掉昨天那件事")){
            val client=ScriptedClient(listOf(com.google.gson.GsonBuilder().serializeNulls().create().toJson(ExperienceProposal(claimKind="interaction"))))
            val p=CompanionModel(client).interpret(config,"synthetic",input,snapshot,now)
            assertEquals("none",p.control);assertFalse(p.controlAuthorized);assertEquals(1,client.requests.size)
        }
    }
}
