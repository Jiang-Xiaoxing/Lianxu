package app.lianxu.independent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class ToolArgumentControlTest {
    @Test fun constructedFailuresUseRealParser(){
        val cases=listOf("{}","""{"authorization":"current_direct_request","action":"forget","quote":"忘掉","targets":["1",{}]}""","""{"authorization":"wrong","action":"unknown","quote":"旧话","targets":[]}""")
        for(raw in cases){
            val args=JSONObject(raw)
            val error=runCatching{JointControl.parse(args,"忘掉",0,setOf(1L),null)}.exceptionOrNull()!!
            val p=ToolArgumentDiagnosis.diagnoseControl(args,"忘掉",setOf(1L))
            assertTrue(p.size>=2);assertTrue(p.all{it.expected.isNotBlank()&&it.actual.isNotBlank()})
            val result=ToolArgumentDiagnosis.writeFailure(JointControl.NAME,raw,"忘掉",emptySet(),mapOf("success" to false,"error" to "invalid_arguments"),error as Exception)
            assertEquals("invalid_arguments",result["error"])
            assertTrue((result["problems"] as List<*>).isNotEmpty())
            println("CONSTRUCTED BEFORE {\"success\":false,\"error\":\"invalid_arguments\"} AFTER "+com.google.gson.Gson().toJson(result))
        }
    }
    @Test fun validControlRetainsReceiptAndDoesNotDiagnose(){
        val args=JSONObject("""{"authorization":"current_direct_request","action":"forget","quote":"忘掉","targets":[1]}""")
        val p=JointControl.parse(args,"忘掉",0,setOf(1L),null)
        assertEquals(mapOf("success" to true,"action" to "forget","targets" to listOf(1L),"status" to "applied","note" to "程序已执行；按当前人设自然回应，不引用停用内容。原聊天仍保留供用户查看。"),JointControl.receipt(p))
        assertTrue(ToolArgumentDiagnosis.diagnoseControl(args,"忘掉",setOf(1L)).isEmpty())
        assertTrue(runCatching{JointControl.parse(args,"忘掉",0,emptySet(),null)}.isFailure)
        assertEquals("targets[0]",ToolArgumentDiagnosis.diagnoseControl(args,"忘掉",emptySet()).single().field)
    }
    @Test fun failurePreservesHintAndFallback(){
        val base=mapOf<String,Any>("success" to false,"error" to "invalid_arguments","hint" to "old")
        val result=ToolArgumentDiagnosis.failure(base,Exception()){ToolArgumentDiagnosis.diagnoseControl(JSONObject(),"")}
        base.forEach{(k,v)->assertEquals(v,result[k])}
        assertEquals(base,ToolArgumentDiagnosis.failure(base,Exception()){error("broken")})
    }
}
