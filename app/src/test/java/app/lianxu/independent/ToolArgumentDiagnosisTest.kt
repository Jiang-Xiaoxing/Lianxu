package app.lianxu.independent
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
class ToolArgumentDiagnosisTest {
    @Test fun requiredFieldsMatchRealParserFailure(){
        val raw="""{"experience":{"quote":"","memory":"","emotion":"unknown","valence":0,"relationship":"none","claimKind":"none","controlIntent":"none","requestQuote":""}}"""
        assertTrue(runCatching{JointExperience.parse(JsonParser.parseString(raw).asJsonObject,"",0)}.isFailure)
        val p=ToolArgumentDiagnosis.diagnoseRecord(raw,"",true)
        assertEquals(setOf("experience.control","experience.targets","experience.reconsider"),p.map{it.field}.toSet())
        assertTrue(p.all{it.actual=="缺失"&&"必填" in it.expected})
    }
    @Test fun preservesBaseAndFallsBack(){
        val base=mapOf<String,Any>("success" to false,"error" to "old","hint" to "unchanged")
        assertEquals(base,ToolArgumentDiagnosis.failure(base,Exception()){error("diagnosis failed")})
        val result=ToolArgumentDiagnosis.failure(base,Exception()){emptyList()}
        base.forEach{(k,v)->assertEquals(v,result[k])}
        assertTrue(result["detail"].toString().contains("Exception"))
    }
    @Test fun capsProblemsAndValues(){
        val p=ToolArgumentDiagnosis.Problem("x","为字符串","缺失")
        val result=ToolArgumentDiagnosis.failure(emptyMap(),Exception()){List(15){p}}
        assertEquals(12,(result["problems"] as List<*>).size)
        assertTrue(result["detail"].toString().contains("另有 3 处"))
        assertTrue(ToolArgumentDiagnosis.actual(JsonParser.parseString("\""+"长".repeat(100)+"\"")).length<=64)
    }
    @Test fun wrongRecordTypesAreReportedTogetherAndValidExperienceIsUnchanged(){
        val raw="""{"experience":{"quote":"","memory":"","emotion":"unknown","valence":{},"relationship":"none","control":3,"targets":{},"reconsider":null,"claimKind":"none","controlIntent":"none","requestQuote":""}}"""
        assertTrue(runCatching{JointExperience.parse(JsonParser.parseString(raw).asJsonObject,"",0)}.isFailure)
        val p=ToolArgumentDiagnosis.diagnoseRecord(raw,"",true)
        assertEquals(setOf("experience.valence","experience.control","experience.targets"),p.map{it.field}.toSet())
        assertTrue(p.any{it.field=="experience.control"&&it.actual=="数字 3"&&"字符串" in it.expected})
        val valid=raw.replace("\"valence\":{}","\"valence\":0").replace("\"control\":3","\"control\":\"none\"").replace("\"targets\":{}","\"targets\":[]")
        assertTrue(ToolArgumentDiagnosis.diagnoseRecord(valid,"",true).isEmpty())
        assertEquals("none",JointExperience.parse(JsonParser.parseString(valid).asJsonObject,"",0).control)
    }
}