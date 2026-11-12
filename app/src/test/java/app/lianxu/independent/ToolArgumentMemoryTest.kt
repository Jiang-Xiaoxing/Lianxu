package app.lianxu.independent
import com.google.gson.JsonParser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class ToolArgumentMemoryTest {
    @Test fun realMissingCategoryFailsStageEnumCheck(){
        val rows=JsonParser.parseReader(javaClass.getResourceAsStream("/tool-argument-failures.json")!!.reader(Charsets.UTF_8)).asJsonArray
        val row=rows.first{it.asJsonObject["tool"].asString=="agent_memory_update"}.asJsonObject
        val args=JSONObject(row["arguments"].asString)
        // The exact MemoryTools.stage valueOf validation, before any write or digest construction.
        val error=runCatching{MemoryCategory.valueOf(args.optString("category").ifBlank{""})}.exceptionOrNull()!!
        val p=ToolArgumentDiagnosis.diagnoseMemoryUpdate(args,"我下周三要去杭州出差。",emptySet())
        assertEquals(setOf("category","about"),p.map{it.field}.toSet())
        assertTrue(p.all{it.actual=="缺失"&&it.expected.startsWith("为")})
        val result=ToolArgumentDiagnosis.writeFailure("agent_memory_update",args.toString(),"我下周三要去杭州出差。",emptySet(),mapOf("success" to false,"error" to "invalid_arguments"),error as Exception)
        println("BEFORE "+row["result"].asString+" AFTER "+com.google.gson.Gson().toJson(result))
        assertEquals("invalid_arguments",result["error"])
    }
    @Test fun reportsMultipleFailuresWithoutRejectingOptionalUpdateFields(){
        val p=ToolArgumentDiagnosis.diagnoseMemoryUpdate(JSONObject("""{"op":"add","category":{},"evidence":"S1"}"""),"",emptySet())
        assertTrue(p.any{it.field=="category"&&it.actual=="对象"})
        assertTrue(p.any{it.field=="evidence"&&"字符串" in it.actual&&"数组" in it.expected})
        assertTrue(p.size>=4)
        assertTrue(ToolArgumentDiagnosis.diagnoseMemoryUpdate(JSONObject("""{"op":"update","target":"M1","evidence":["S1"]}"""),"",setOf("M1")).isEmpty())
    }
    @Test fun stateAndReadFailuresDoNotReceiveArgumentProblems(){
        val base=mapOf<String,Any>("success" to false,"error" to "SOURCE_CHANGED")
        assertEquals(base,ToolArgumentDiagnosis.writeFailure("agent_history_read","{}","",emptySet(),base,Exception()))
        val result=ToolArgumentDiagnosis.writeFailure("agent_memory_update","{}","",emptySet(),base,Exception())
        assertFalse(result.containsKey("problems"));assertEquals(base["error"],result["error"])
        assertEquals(base,ToolArgumentDiagnosis.failure(base,Exception()){throw IllegalStateException()})
    }
}
