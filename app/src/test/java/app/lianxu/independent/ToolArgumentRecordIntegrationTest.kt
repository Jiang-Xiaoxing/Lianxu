package app.lianxu.independent
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
class ToolArgumentRecordIntegrationTest {
    private val source=SourceRef("a",1,1)
    private val context=RoleContext("a",source,mapOf(1L to source),emptyList(),emptyList(),emptyList(),emptyList())
    private val config=ModelConfig("fake","合成","custom","https://example.invalid","fake",16000,2048)
    private val planner=RolePlanner(object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("No transport")})
    private fun definitionSample():com.google.gson.JsonObject {
        val function=CompanionRecord.definition(source.turnId,true)["function"] as Map<*,*>
        val raw=(function["description"] as String).lineSequence().first{it.trim().startsWith("{\"experience\"")}.trim()
        return JsonParser.parseString(raw).asJsonObject
    }
    @Test fun sampleContainsExactlyRequiredExperienceKeys() {
        val experience=definitionSample().getAsJsonObject("experience")
        assertEquals(JointExperience.requiredKeys,experience.keySet())
        assertEquals("none",experience["control"].asString)
        assertTrue(experience["targets"].asJsonArray.isEmpty)
        assertTrue(experience["reconsider"].isJsonNull)
    }
    @Test fun definitionSampleAcceptedUnmodified():Unit=runBlocking {
        planner.respond(config,"","你好",ExperienceProposal(interpretationUnknown=true),context,0,TextChatRequest(listOf(WireMessage("user","你好"))),{true},{0},false){_,record->
            assertEquals(true,record!!(definitionSample().toString())["success"])
            TextChatResult.Success("你好")
        }
    }
    @Test fun realRecordsFailInParserAndReturnDiagnostics()=runBlocking {
        val samples=JsonParser.parseReader(javaClass.getResourceAsStream("/tool-argument-failures.json")!!.reader(Charsets.UTF_8)).asJsonArray
        for(sample in samples){
            val row=sample.asJsonObject
            if(row["tool"].asString!="companion_record")continue
            val raw=row["arguments"].asString
            val root=JsonParser.parseString(raw).asJsonObject
            val input=root["experience"].asJsonObject["quote"].asString
            assertTrue(runCatching{JointExperience.parse(root,input,0)}.isFailure)
            planner.respond(config,"",input,ExperienceProposal(interpretationUnknown=true),context,0,TextChatRequest(listOf(WireMessage("user","你好"))),{true},{0},false){_,record->
                val result=record!!(raw)
                assertEquals(false,result["success"]);assertEquals("invalid_arguments",result["error"])
                assertTrue((result["problems"] as List<*>).isNotEmpty())
                val missing=setOf("control","targets","reconsider","requestQuote").filterNot(root["experience"].asJsonObject::has).map{"experience.$it"}.toSet()
                assertTrue(missing.isNotEmpty())
                assertTrue(ToolArgumentDiagnosis.diagnoseRecord(raw,input,true).map{it.field}.containsAll(missing))
                println(row["model"].asString+" BEFORE "+row["result"].asString+" AFTER "+com.google.gson.Gson().toJson(result))
                TextChatResult.Success("收到")
            }
        }
    }
    @Test fun successAndLenientIssuesRemainUnchanged():Unit=runBlocking {
        planner.respond(config,"","你好",ExperienceProposal(),context,0,TextChatRequest(listOf(WireMessage("user","你好"))),{true},{0},false){_,record->
            // Lenient parts still never fail the record, but an unreadable 关系输入 is reported once instead of a plain success.
            val raw="""{"关系输入":"错误类型","appraisal":{"uncertainty":{}}}"""
            val first=record!!(raw)
            assertEquals(false,first["success"]);assertEquals("RELATION_INPUT_INVALID",first["error"])
            assertEquals(mapOf("success" to true,"recorded" to true,"status" to "staged_until_reply_commit","note" to CompanionRecord.stagedNote,"relation" to RelationReceipt.GIVEN_UP),record(raw))
            TextChatResult.Success("你好")
        }
    }
}
