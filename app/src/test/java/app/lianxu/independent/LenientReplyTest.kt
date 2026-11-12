package app.lianxu.independent

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LenientReplyTest {
    private val source=SourceRef("a",2,1)
    private val context=RoleContext("a",source,mapOf(2L to source),emptyList(),emptyList(),emptyList(),emptyList())
    private val config=ModelConfig("test","test","custom","https://example.invalid","test",64000,2048)
    private val wire="""{"schemaVersion":1,"appraisal":{"event":"问候","meaning":"交流","certainty":"reported","relevance":"回应","attribution":"user_report","situation":"chat","eventRelation":"new_event","uncertainty":[],"evidence":[{"source":2,"layer":"fact"}]},"feelings":[{"kind":"joy","degree":"mild","object":"交流","reason":"问候","evidence":[{"source":2,"layer":"fact"}]}],"goal":{"description":"回应","origin":"current_request","state":"active","reason":"问候","evidence":[{"source":2,"layer":"fact"}]},"choices":[{"id":"respond","action":"acknowledge","reason":"问候","evidence":[{"source":2,"layer":"fact"}]}],"selected":"respond","judgment":{"position":"回应","kind":"inference","evidence":[{"source":2,"layer":"fact"}]},"feedback":[],"learning":{"id":"","kind":"interest","scope":"问候","content":"喜欢交流","phase":"propose","evidence":[{"source":2,"layer":"fact"}]},"usedLearningIds":[],"consideredLearningIds":[],"future":null,"关系输入":{"消息":"2","信息类别":"关于关系","P":"轻","C":"无","V":"无","B":"无"}}"""
    private suspend fun respond(raw:String,reply:String="自然回复"):Pair<RolePlanner.Reply,Int>{
        var calls=0
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls++;return TextChatResult.Success(reply)}}
        val value=RolePlanner(client).respond(config,"synthetic","问候",ExperienceProposal(),context,0,TextChatRequest(listOf(WireMessage("user","问候"))),{true},{0},false){request,record->
            if(record!=null)record(raw)
            client.complete(config,"synthetic",request)
        }
        return value to calls
    }
    @Test fun replyRequestCarriesNoFieldGuideAndNoJsonEnvelope(){
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("Prompt preparation must not call a model")}
        val base=CompanionPrompt.build(Friend("a","合成角色","成年"),RolePrompts(),emptyList(),"问候",emptyList(),capacity=64000,outputLimit=2048) as PromptBuild.Ready
        val ready=RolePlanner(client).prepareReply(base,config,"问候",ExperienceProposal(),context,0,false,emptyMap(),0) as PromptBuild.Ready
        val system=ready.request.messages.first().content.let{assertTrue("回话规矩要和人设放在开头",it.contains("[companion.reply.v1]"));"[companion.reply.v1]"+it.substringAfter("[companion.reply.v1]")}
        assertTrue(system.contains("[companion.reply.v1]"))
        assertFalse("the field guide belongs to the tool, not the reply prompt","[companion.record.v1]" in system)
        assertFalse("the reply prompt must never mention a final JSON envelope","最终JSON" in system)
        assertFalse("The visible reply is plain text, not a JSON envelope",ready.request.jsonObject)
        assertFalse(system.contains("\"reply\":\""))
        // The same guide still reaches the model, attached to the tool it describes.
        @Suppress("UNCHECKED_CAST") val function=CompanionRecord.definition(2,false)["function"] as Map<String,Any>
        val guide=function["description"] as String
        assertTrue("[companion.record.v1]" in guide);assertTrue("非正文/回复编号/自编序号" in guide)
        assertTrue("the sample carries this turn's source",guide.contains("\"消息\":\"2\""))
        assertTrue(guide.contains("调用 companion_record 时，参数顶层必填"))
        assertFalse("no leftover JSON-envelope wording anywhere",guide.contains("最终JSON"))
        assertTrue(CompanionRecord.NAME in function["name"].toString())
    }
    @Test fun everyMalformedNonReplySectionStillRecordsAndRepliesInOneCall()=runBlocking{
        val mutations=listOf<(JsonObject)->Unit>(
            {it.addProperty("feelings","bad")},{it.getAsJsonArray("feelings")[0].asJsonObject.addProperty("degree","wrong")},
            {it.addProperty("learning",true)},{it.getAsJsonObject("learning").getAsJsonArray("evidence")[0].asJsonObject.addProperty("source",999)},
            {it.remove("appraisal")},{it.addProperty("goal",42)},{it.addProperty("judgment",false)},
            {it.remove("choices")},{it.addProperty("usedLearningIds",42)},{it.addProperty("future",false)},{it.addProperty("feedback",false)},
            {it.remove("关系输入")},{it.addProperty("关系输入",false)},{it.getAsJsonObject("关系输入").addProperty("P","wrong")},
            {it.getAsJsonObject("关系输入").addProperty("消息","999")},{it.addProperty("schemaVersion",2)},
            {it.remove("feelings");it.addProperty("learning",false);it.addProperty("关系输入",false)}
        )
        for(mutate in mutations){
            val json=JsonParser.parseString(wire).asJsonObject;mutate(json)
            val (reply,calls)=respond(json.toString());assertEquals("自然回复",reply.text);assertEquals(1,calls)
            assertNotNull(reply.understanding)
            RoleUnderstandingContract.validateAccepted(reply.understanding!!,context,"问候")
        }
    }
    @Test fun invalidFeelingKeepsLearningAndRelationButLogsOnlyCode()=runBlocking{
        val json=JsonParser.parseString(wire).asJsonObject;json.getAsJsonArray("feelings")[0].asJsonObject.addProperty("kind","private invalid text")
        val errors=mutableListOf<Pair<String,String>>()
        val flow=RoleUnderstandingContract.parse(json.toString(),"问候",ExperienceProposal(),context,0,false,false,true){f,c->errors+=f to c}
        assertTrue(flow.feelings.isEmpty());assertNotNull(flow.learning);assertEquals(listOf("feelings.item" to "FEELING_KIND"),errors)
        val (reply,calls)=respond(json.toString());assertNotNull(reply.understanding!!.learning);assertNotNull(reply.relationInput);assertEquals(1,calls)
    }
    @Test fun invalidCoreDoesNotDiscardIndependentFeelingsOrLearning()=runBlocking{
        val json=JsonParser.parseString(wire).asJsonObject;json.remove("goal");json.remove("appraisal")
        val flow=respond(json.toString()).first.understanding!!
        assertNull(flow.goal);assertNull(flow.appraisal);assertEquals(1,flow.feelings.size);assertNotNull(flow.learning)
        RoleUnderstandingContract.validateAccepted(flow,context,"问候")
    }
    @Test fun blankPlainReplyIsARealFailureWithoutInventingText()=runBlocking{
        for(reply in listOf("","   ")){
            val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=TextChatResult.Success(reply)}
            try{
                RolePlanner(client).respond(config,"synthetic","问候",ExperienceProposal(),context,0,TextChatRequest(listOf(WireMessage("user","问候"))),{true},{0},false){request,_->client.complete(config,"synthetic",request)}
                fail("Expected invalid text")
            }catch(e:CompanionModelFailure){assertEquals(ChatFailure.REPLY_TEXT_INVALID,e.failure)}
        }
    }
    @Test fun recordingIsOptionalAndNoRecordLeavesNoUnderstanding()=runBlocking{
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=TextChatResult.Success("只是自然回话")}
        val reply=RolePlanner(client).respond(config,"synthetic","问候",ExperienceProposal(),context,0,TextChatRequest(listOf(WireMessage("user","问候"))),{true},{0},false){request,_->client.complete(config,"synthetic",request)}
        assertEquals("只是自然回话",reply.text);assertNull(reply.understanding);assertNull(reply.relationInput)
    }
    @Test fun legacyJsonEnvelopePublishesOnlyItsReply()=runBlocking{
        val (reply,_)=respond(wire,"""{"reply":"自然回复","appraisal":{}}""")
        assertEquals("自然回复",reply.text)
    }
    @Test fun legacyJsonEnvelopeWithoutReplyIsAFailure()=runBlocking{
        try{respond(wire,"""{"appraisal":{}}""");fail("Expected invalid text")}
        catch(e:CompanionModelFailure){assertEquals(ChatFailure.REPLY_TEXT_INVALID,e.failure)}
    }
    @Test fun backgroundSendUsesSameLenientRecordRule()=runBlocking{
        var calls=0
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls++;return TextChatResult.Success("""{"action":"send","text":"自然问候","reason":[],"usedLearningIds":false}""")}}
        val snapshot=CompanionSnapshot("a",0,0,"",emptyList(),"当前关系小结","enabled")
        val opportunity=CompanionOpportunity("o","a",2,1,0,0,0,10000000,"问候","running",1)
        val result=CompanionModel(client).reconsider(config,"synthetic",opportunity,snapshot,"人设",0,"")
        assertEquals("自然问候",result.text);assertTrue(result.usedLearningIds.isEmpty());assertEquals("",result.reason);assertEquals(1,calls)
    }
}
