package app.lianxu.independent

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ContactExposureTest {
    private val now=1800000000000L
    private val current=SourceRef("a",57,3)
    private fun context()=RoleContext("a",current,mapOf(57L to current),emptyList(),emptyList(),emptyList(),emptyList())
    private fun parameters()="""{"改主动参数":{"消息":"57","参数":{"tone":"温柔些"},"她的话":"那我说得温柔些。"}}"""
    private fun future()="""{"future":{"kind":"follow_up","reason":"用户请求稍后回访","when":"30秒后","evidence":[{"source":57,"layer":"fact"}]}}"""
    @Test fun offDefinitionExposesOnlyTheConfirmedParameterSuggestion(){
        val encoded=Gson().toJson(ImmediateContact.definition(57,now,scheduling=false))
        assertFalse(encoded.contains("\"future\""))
        assertFalse(encoded.contains("\"when\""))
        assertFalse(encoded.contains("\"notBefore\""))
        assertFalse(encoded.contains("每轮聊天结束前"))
        assertFalse(encoded.contains("再找对方"))
        assertFalse(encoded.contains("安排"))
        assertFalse(encoded.contains("下次"))
        assertFalse(encoded.contains("再考虑"))
        assertFalse(encoded.contains("时间"))
        assertTrue(encoded.contains("改主动参数"))
        val function=JsonParser.parseString(encoded).asJsonObject.getAsJsonObject("function")
        assertEquals(setOf("改主动参数"),function.getAsJsonObject("parameters").getAsJsonObject("properties").keySet())
    }
    @Test fun offRequestCanSuggestParametersButCannotSmuggleASchedule(){
        val parsed=ImmediateContact.parse(parameters(),"改主动参数",context(),now,scheduling=false)
        assertNull(parsed.flow)
        assertEquals(mapOf("tone" to "温柔些"),parsed.parameters!!.values)
        val rejected=runCatching{ImmediateContact.parse(future(),"稍后聊",context(),now,scheduling=false)}.exceptionOrNull()
        assertTrue(rejected is RoleContractViolation)
        assertEquals("SCHEDULING_DISABLED",(rejected as RoleContractViolation).code)
    }
    @Test fun onDefinitionStillOffersTheRealScheduleEntry(){
        val encoded=Gson().toJson(ImmediateContact.definition(57,now))
        assertTrue(encoded.contains("\"future\""))
        assertTrue(encoded.contains("\"when\""))
        assertTrue(encoded.contains("\"evidence\""))
        assertTrue(encoded.contains("改主动参数"))
    }
    @Test fun staleScheduleFeedbackNeverEchoesTheRetiredSchema(){
        val result=ToolArgumentDiagnosis.writeFailure(ImmediateContact.NAME,future(),"稍后聊",emptySet(),
            mapOf("success" to false,"error" to "invalid_arguments"),RoleContractViolation("SCHEDULING_DISABLED"),contactSource=57)
        assertEquals("SCHEDULING_DISABLED",result["validation_code"])
        assertFalse(result.toString().contains("future"))
        assertFalse(result.toString().contains("notBefore"))
    }
}
