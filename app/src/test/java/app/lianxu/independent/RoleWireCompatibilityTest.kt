package app.lianxu.independent

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RoleWireCompatibilityTest {
    private val source=SourceRef("a",2,1)
    private val context=RoleContext("a",source,mapOf(2L to source),emptyList(),emptyList(),emptyList(),emptyList())
    // Reproduces the exported MiMo envelope deviation, with synthetic text only.
    private val wire="""{"appraise":{"event":"收到问候","meaning":"友好交流","certainty":"reported","relevance":"回应问候","attribution":"user_report","situation":"daily_chat","eventRelation":"new_event","uncertainty":[],"evidence":[{"source":2,"layer":"fact"}]},"feelings":[],"goal":{"description":"回应","origin":"current_request","state":"active","reason":"当前问候","evidence":[{"source":2,"layer":"fact"}]},"choices":[{"id":"respond","action":"acknowledge","reason":"回应原话","evidence":[{"source":2,"layer":"fact"}]}],"selected":"respond","judgment":{"position":"自然回应","kind":"inference","evidence":[{"source":2,"layer":"fact"}]},"feedback":[],"learning":null,"usedLearningIds":[],"consideredLearningIds":[],"future":null}"""
    private fun parse(raw:String)=RoleUnderstandingContract.parse(raw,"合成问候",ExperienceProposal(),context,0,false,false)
    private fun invalid(raw:String,code:String){try{parse(raw);fail("Expected $code")}catch(e:RoleContractViolation){assertEquals(code,e.code)}}
    @Test fun knownEnvelopeDeviationDoesNotSpendAnotherModelRequest()=runBlocking {
        var calls=0
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls++;return TextChatResult.Success(wire)}}
        val config=ModelConfig("test","合成","custom","https://example.invalid","synthetic")
        val result=RolePlanner(client).evaluate(config,"synthetic","合成问候",ExperienceProposal(),context,"合成角色",0,{true},{0},false){request->client.complete(config,"synthetic",request)}
        assertEquals(1,calls);assertEquals(1,result.schemaVersion);assertEquals("收到问候",result.appraisal!!.event)
        assertEquals(listOf(RoleEvidence(source)),result.dependencies)
    }
    @Test fun normalizationKeepsAllSemanticFieldsAndCanonicalPayloadUnchanged(){
        val canonical=JsonParser.parseString(wire).asJsonObject.apply{add("appraisal",remove("appraise"));addProperty("schemaVersion",1)}
        assertEquals(parse(canonical.toString()),parse(wire))
    }
    @Test fun conflictingFieldsAndExplicitUnsupportedVersionAreRejected(){
        invalid(JsonParser.parseString(wire).asJsonObject.apply{add("appraisal",get("appraise").deepCopy())}.toString(),"AMBIGUOUS_APPRAISAL_FIELDS")
        invalid(JsonParser.parseString(wire).asJsonObject.apply{addProperty("schemaVersion",2)}.toString(),"SCHEMA_VERSION")
    }
    @Test fun incompleteContentAndInvalidSourcesStillFail(){
        invalid(JsonParser.parseString(wire).asJsonObject.apply{remove("judgment")}.toString(),"INCOMPLETE_ROLE_UNDERSTANDING")
        invalid(wire.replace("\"source\":2","\"source\":999"),"UNKNOWN_SOURCE")
        invalid(wire.replace("\"appraise\":{","\"appraise\":null,\"ignored\":{"),"OBJECT_appraisal")
    }
    @Test fun missingMeaningObjectUncertaintyAndInventedActionsAreNotFilledOrSilentlyDropped(){
        val missing=JsonParser.parseString(wire).asJsonObject.apply{getAsJsonObject("appraise").remove("meaning")}
        invalid(missing.toString(),"MISSING_meaning")
        val objectList=JsonParser.parseString(wire).asJsonObject.apply{
            getAsJsonObject("appraise").add("uncertainty",JsonParser.parseString("""[{"what":"合成疑点","kind":"inference"}]"""))
        }
        invalid(objectList.toString(),"STRING_ARRAY_uncertainty")
        invalid(wire.replace("\"action\":\"acknowledge\"","\"action\":\"playful_guess\""),"CHOICES")
    }
    @Test fun roleRepairKeepsEarlierFieldCorrectionsAndExplainsTheExpectedTypes()=runBlocking {
        val first=JsonParser.parseString(wire).asJsonObject.apply{getAsJsonObject("appraise").remove("meaning")}.toString()
        val second=JsonParser.parseString(wire).asJsonObject.apply{
            getAsJsonObject("appraise").add("uncertainty",JsonParser.parseString("""[{"what":"合成疑点","kind":"inference"}]"""))
        }.toString()
        val corrected=JsonParser.parseString(wire).asJsonObject.apply{
            getAsJsonObject("appraise").add("uncertainty",JsonParser.parseString("""["合成疑点，属于推断"]"""))
        }.toString()
        val requests=mutableListOf<TextChatRequest>()
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
            requests+=request
            return TextChatResult.Success(listOf(first,second,corrected)[requests.lastIndex])
        }}
        val config=ModelConfig("test","合成","custom","https://example.invalid","synthetic",64000,2048)
        val result=RolePlanner(client).evaluate(config,"synthetic","合成问候",ExperienceProposal(),context,"合成角色",0,{true},{0},false){client.complete(config,"synthetic",it)}
        assertEquals(3,requests.size);assertEquals("友好交流",result.appraisal!!.meaning)
        assertEquals(listOf("合成疑点，属于推断"),result.appraisal!!.uncertainty)
        val guidance=requests.last().messages.last().content
        assertTrue(guidance.contains("MISSING_meaning"));assertTrue(guidance.contains("STRING_ARRAY_uncertainty"))
        assertTrue(guidance.contains("appraisal.meaning"));assertTrue(guidance.contains("字符串数组"))
        assertTrue(requests.first().messages.joinToString("\n"){it.content}.contains("source.turnId"))
        assertEquals(listOf(RoleEvidence(source)),result.dependencies)
    }
}
