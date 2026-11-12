package app.lianxu.independent

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 关系输入没收到或读不出来时不再报“已记下”：其余照存，明说哪个字段，只给补一次。 */
class RelationReceiptTest {
    private val source=SourceRef("a",2,1)
    private val context=RoleContext("a",source,mapOf(2L to source),emptyList(),emptyList(),emptyList(),emptyList())
    private val config=ModelConfig("test","test","custom","https://example.invalid","test",64000,2048)
    private val wire="""{"appraisal":{"event":"问候","meaning":"交流","certainty":"reported","relevance":"回应","attribution":"user_report","situation":"chat","eventRelation":"new_event","uncertainty":[],"evidence":[{"source":2,"layer":"fact"}]},"feelings":[],"goal":{"description":"回应","origin":"current_request","state":"active","reason":"问候","evidence":[{"source":2,"layer":"fact"}]},"choices":[{"id":"respond","action":"acknowledge","reason":"问候","evidence":[{"source":2,"layer":"fact"}]}],"selected":"respond","judgment":{"position":"回应","kind":"inference","evidence":[{"source":2,"layer":"fact"}]},"feedback":[],"learning":null,"usedLearningIds":[],"consideredLearningIds":[],"future":null,"关系输入":{"消息":"2","信息类别":"关于关系","P":"轻","C":"无","V":"无","B":"无"}}"""
    private val plain=mapOf("success" to true,"recorded" to true,"status" to "staged_until_reply_commit","note" to "已记下；继续按人设自然回话，不要把字段读给用户。")
    private fun variant(change:(JsonObject)->Unit)=JsonParser.parseString(wire).asJsonObject.also(change).toString()
    private fun run(vararg calls:String):Pair<List<Map<String,Any>>,RolePlanner.Reply> = runBlocking {
        val results=mutableListOf<Map<String,Any>>()
        val reply=RolePlanner(object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("no")})
            .respond(config,"","问候",ExperienceProposal(),context,0,TextChatRequest(listOf(WireMessage("user","问候"))),{true},{0},false){_,record->
                calls.forEach{results+=record!!(it)};TextChatResult.Success("你好")}
        results to reply
    }

    @Test fun validRelationStillPlainSuccess(){
        val (results,reply)=run(wire)
        assertEquals(plain,results.single());assertNotNull(reply.relationInput)
    }
    @Test fun missingRelationAsksOnceAndKeepsTheRest(){
        val missing=variant{it.remove("关系输入")}
        val (results,reply)=run(missing,missing)
        val first=results[0]
        assertEquals(false,first["success"]);assertEquals("RELATION_INPUT_MISSING",first["error"])
        val detail=first["detail"] as String
        assertTrue(detail,detail.startsWith("关系输入没收到"));assertTrue(detail.contains("其余内容已经暂存"));assertTrue(detail.contains("只补这一次"))
        assertEquals("关系输入",((first["problems"] as List<*>)[0] as Map<*,*>)["field"])
        assertEquals("第二次还没有就不再追问",plain+("relation" to RelationReceipt.GIVEN_UP),results[1])
        assertNotNull("其余内容照存",reply.understanding);assertNull(reply.relationInput)
    }
    @Test fun malformedRelationNamesTheFieldAndTheResendLands(){
        val bad=variant{it.getAsJsonObject("关系输入").addProperty("P","很多")}
        val (results,reply)=run(bad,wire)
        assertEquals("RELATION_INPUT_INVALID",results[0]["error"])
        val problem=(results[0]["problems"] as List<*>)[0] as Map<*,*>
        assertEquals("关系输入.P",problem["field"]);assertEquals("为 无/轻/中/重 之一",problem["expected"])
        assertTrue((results[0]["detail"] as String).startsWith("关系输入格式不对"))
        assertEquals(plain,results[1]);assertNotNull("补上后关系记上",reply.relationInput)
    }
    @Test fun relationWrittenAsStringOrWrongCategoryIsReported(){
        val asString=run(variant{it.addProperty("关系输入","关于关系")}).first[0]
        assertEquals("RELATION_INPUT_INVALID",asString["error"]);assertTrue((asString["detail"] as String).contains("不要写成字符串"))
        val category=run(variant{it.getAsJsonObject("关系输入").addProperty("信息类别","关系")}).first[0]
        assertEquals("关系输入.信息类别",((category["problems"] as List<*>)[0] as Map<*,*>)["field"])
        val message=run(variant{it.getAsJsonObject("关系输入").addProperty("消息","99")}).first[0]
        assertTrue((message["detail"] as String).contains("原样填当前来源编号 \"2\""))
    }
    @Test fun aliasFallbackNeedsNoResend(){
        val alias=variant{val r=it.remove("关系输入");it.addProperty("relationship",r.toString())}
        val (results,reply)=run(alias)
        assertEquals(plain,results.single());assertEquals("关于关系",reply.relationInput!!.category)
    }
    @Test fun guideNoLongerNamesEnglishKeys(){
        val text=RelationInputContract.instruction(2)
        assertFalse("relationship/relationInput" in text);assertFalse("relationInput" in text)
        assertTrue(text.contains("调用 companion_record 时，参数顶层必填“关系输入”对象"))
        assertTrue(text.contains("键名就叫“关系输入”"))
    }
}