package app.lianxu.independent

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 记账参数宽松入口：只修类型和放错位置的关系输入，修完照旧严格校验；所有模型同一套规则。 */
class RecordArgumentsTest {
    private val source=SourceRef("a",1,1)
    private val context=RoleContext("a",source,mapOf(1L to source),emptyList(),emptyList(),emptyList(),emptyList())
    private val config=ModelConfig("fake","合成","custom","https://example.invalid","fake",16000,2048)
    private fun norm(raw:String):JsonObject=JsonParser.parseString(RecordArguments.normalize(raw)).asJsonObject
    /** Real arguments from the 2026-11-11 OpenCode Go comparison (English fields). */
    private val samples=JsonParser.parseReader(javaClass.getResourceAsStream("/record-lenient-samples.json")!!.reader(Charsets.UTF_8)).asJsonArray.associate{
        val o=it.asJsonObject;o["name"].asString to (o["input"].asString to o["arguments"].asString)}

    @Test fun stringifiedValuesBecomeTheirTypes(){
        val j=norm("""{"experience":{"quote":"null","memory":"1","valence":"-1","targets":"","reconsider":"null"},"learning":"","future":"null","feedback":"null","usedLearningIds":"",
            "appraisal":"{\"uncertainty\":\"\",\"evidence\":[{\"source\":\"1\",\"layer\":\"fact\"}]}","关系输入":{"消息":"1","信息类别":"关于关系","约定":"true","弥补已完成":"false"}}""")
        val e=j["experience"].asJsonObject
        assertEquals(-1,e["valence"].asInt);assertTrue(e["valence"].asJsonPrimitive.isNumber)
        assertTrue(e["targets"].asJsonArray.isEmpty);assertTrue(e["reconsider"].isJsonNull)
        assertEquals("自由文字不动","null",e["quote"].asString);assertEquals("自由文字不动","1",e["memory"].asString);assertTrue(e["memory"].asJsonPrimitive.isString)
        assertTrue(j["learning"].isJsonNull);assertTrue(j["future"].isJsonNull);assertTrue(j["feedback"].asJsonArray.isEmpty);assertTrue(j["usedLearningIds"].asJsonArray.isEmpty)
        val a=j["appraisal"].asJsonObject
        assertTrue(a["uncertainty"].asJsonArray.isEmpty);assertEquals(1,a["evidence"].asJsonArray[0].asJsonObject["source"].asInt)
        val r=j["关系输入"].asJsonObject
        assertEquals("消息保持原样","1",r["消息"].asString);assertTrue(r["约定"].asBoolean);assertFalse(r["弥补已完成"].asBoolean)
    }
    @Test fun itemWrappedArrayBecomesArray(){
        val j=norm("""{"appraisal":{"evidence":{"item":{"source":"1","layer":"fact"}}},"choices":{"item":[{"id":"a"},{"id":"b"}]}}""")
        assertEquals(1,j["appraisal"].asJsonObject["evidence"].asJsonArray[0].asJsonObject["source"].asInt)
        assertEquals(2,j["choices"].asJsonArray.size())
    }
    @Test fun wrongValuesStayWrongSoValidationStaysStrict(){
        val e=norm("""{"experience":{"valence":"开心","targets":"1","reconsider":"later","control":"none"}}""")["experience"].asJsonObject
        assertEquals("开心",e["valence"].asString);assertEquals("1",e["targets"].asString);assertEquals("later",e["reconsider"].asString)
        assertEquals("不是 JSON 对象就原样返回","not json",RecordArguments.normalize("not json"))
    }
    @Test fun relationAliasFallback(){
        val rel="{\\\"消息\\\":\\\"1\\\",\\\"信息类别\\\":\\\"关于关系\\\",\\\"P\\\":\\\"轻\\\"}"
        for(alias in RecordArguments.relationAliases){
            val j=norm("""{"$alias":"$rel"}""")
            assertEquals("$alias 字符串里的关系输入挪到 关系输入","关于关系",j["关系输入"].asJsonObject["信息类别"].asString);assertFalse(j.has(alias))
            val o=norm("""{"$alias":{"消息":"1","信息类别":"普通信息"}}""")
            assertEquals("普通信息",o["关系输入"].asJsonObject["信息类别"].asString)
        }
        val plain=norm("""{"relationship":"none"}""")
        assertFalse("枚举字符串不是关系输入",plain.has("关系输入"));assertEquals("none",plain["relationship"].asString)
        val incomplete=norm("""{"relationship":"{\"消息\":\"1\"}"}""")
        assertFalse("缺 信息类别 的字符串不挪",incomplete.has("关系输入"))
        val both=norm("""{"关系输入":{"消息":"1","信息类别":"关于她"},"relationship":{"消息":"1","信息类别":"普通信息"}}""")
        assertEquals("已有 关系输入 时不覆盖","关于她",both["关系输入"].asJsonObject["信息类别"].asString)
    }
    @Test fun deepSeekStyleRelationLandsAsRelationInput()=runBlocking {
        val (input,raw)=samples.getValue("deepseek-R1")
        assertFalse("原样里没有 关系输入",JsonParser.parseString(raw).asJsonObject.has("关系输入"))
        val reply=RolePlanner(object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("no")})
            .respond(config,"",input,ExperienceProposal(interpretationUnknown=true),context,0,TextChatRequest(listOf(WireMessage("user",input))),{true},{0},false){_,record->
                assertEquals(true,record!!(raw)["success"]);TextChatResult.Success("好呀")}
        val relation=assertNotNullR(reply.relationInput)
        assertEquals("关于关系",relation.category);assertTrue(relation.promise);assertEquals(0.3,relation.p!!,1e-9)
    }
    @Test fun miniMaxStyleStringValuesNowPassTheStrictExperienceCheck()=runBlocking {
        for(name in listOf("minimax-R1","minimax-R2")){
            val (input,raw)=samples.getValue(name)
            val before=JsonParser.parseString(raw).asJsonObject
            assertTrue("$name 原样过不了",runCatching{JointExperience.parse(before,input,0)}.isFailure)
            assertTrue("$name 修完后没有字段问题",ToolArgumentDiagnosis.diagnoseRecord(RecordArguments.normalize(raw),input,true).isEmpty())
            val reply=RolePlanner(object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("no")})
                .respond(config,"",input,ExperienceProposal(interpretationUnknown=true),context,0,TextChatRequest(listOf(WireMessage("user",input))),{true},{0},false){_,record->
                    assertEquals(name,true,record!!(raw)["success"]);TextChatResult.Success("好呀")}
            assertNotNull(name,reply.experience);assertEquals(name,1,reply.experience!!.valence)
            assertNotNull(name,reply.relationInput)
        }
    }
    @Test fun relationBlockIsRecognisedByContentUnderAnyKey(){
        val rel="{\\\"消息\\\":\\\"1\\\",\\\"信息类别\\\":\\\"关于关系\\\",\\\"P\\\":\\\"轻\\\",\\\"约定\\\":\\\"true\\\"}"
        for(key in listOf("relationshipInput","relationship_input","relationshipData","rel_ctx_9f2","zz随便起的名字")){
            val s=norm("""{"experience":{"valence":"1"},"$key":"$rel"}""")
            val r=s["关系输入"].asJsonObject
            assertEquals("$key 的 JSON 字符串认作关系输入","关于关系",r["信息类别"].asString);assertTrue(r["约定"].asBoolean);assertFalse(s.has(key))
            val o=norm("""{"$key":{"消息":"1","信息类别":"普通信息","P":"无"}}""")
            assertEquals("$key 的对象认作关系输入","普通信息",o["关系输入"].asJsonObject["信息类别"].asString);assertFalse(o.has(key))
        }
    }
    @Test fun firstQualifyingKeyWinsAndRestStay(){
        val j=norm("""{"note":{"消息":"1"},"a_rel":"{\"消息\":\"1\",\"信息类别\":\"关于她\"}","b_rel":{"消息":"1","信息类别":"普通信息"}}""")
        assertEquals("按模型给的键顺序取第一个同时有 消息 和 信息类别 的","关于她",j["关系输入"].asJsonObject["信息类别"].asString)
        assertFalse(j.has("a_rel"));assertTrue("其余候选原样留着",j.has("b_rel"));assertTrue("不合格的不动",j.has("note"))
    }
    @Test fun unrelatedKeysAreNotMistaken(){
        val j=norm("""{"experience":{"quote":"x","消息":"1"},"meta":{"消息":"1","类别":"关于她"},"msg":"{\"消息\":\"1\"}","text":"消息 信息类别","list":[{"消息":"1","信息类别":"关于她"}],"relation":"none","deep":{"inner":{"消息":"1","信息类别":"关于她"}}}""")
        assertFalse("只缺一个键、纯文本、数组、深层嵌套、枚举字符串都不认",j.has("关系输入"))
        for(k in listOf("experience","meta","msg","text","list","relation","deep"))assertTrue(k,j.has(k))
    }
    @Test fun wrongRelationInputIsReplacedButAGoodOneIsKept(){
        val bad=norm("""{"关系输入":"无","relationshipInput":{"消息":"1","信息类别":"关于关系","P":"中"}}""")
        assertEquals("关系输入 写坏了而别处有完整的，就用完整的","中",bad["关系输入"].asJsonObject["P"].asString);assertFalse(bad.has("relationshipInput"))
        val good=norm("""{"关系输入":{"消息":"1","信息类别":"关于她"},"x":{"消息":"1","信息类别":"普通信息"}}""")
        assertEquals("关于她",good["关系输入"].asJsonObject["信息类别"].asString);assertTrue(good.has("x"))
    }
    @Test fun deepSeekRetestShapeNeedsNoResend()=runBlocking {
        // 2026-11-11 复测里 DeepSeek 的写法：关系资料以 JSON 字符串放在自造的英文键下，还夹着一堆无关键。
        val (input,raw)=samples.getValue("deepseek-R1")
        val o=JsonParser.parseString(raw).asJsonObject
        val rel=listOf("relationship","relationInput","relation_input").firstNotNullOf{o.remove(it)}
        o.add("relationship_input",rel);(1..5).forEach{o.addProperty("relationship_input_ignore$it","")}
        o.addProperty("relationshipMeta","none")
        val reply=RolePlanner(object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("no")})
            .respond(config,"",input,ExperienceProposal(interpretationUnknown=true),context,0,TextChatRequest(listOf(WireMessage("user",input))),{true},{0},false){_,record->
                val first=record!!(o.toString())
                assertEquals("认到了就是普通成功，不要求补发",true,first["success"]);assertNull(first["error"]);assertNull(first["relation"])
                TextChatResult.Success("好呀")}
        val relation=assertNotNullR(reply.relationInput)
        assertEquals("关于关系",relation.category);assertTrue(relation.promise)
    }
    private fun <T> assertNotNullR(v:T?):T{assertNotNull(v);return v!!}
}