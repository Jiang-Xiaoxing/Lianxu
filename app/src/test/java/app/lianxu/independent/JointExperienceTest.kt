package app.lianxu.independent

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class JointExperienceTest {
    private fun json(kind:String="interaction",intent:String="none",quote:String="原话")=JsonParser.parseString("""{"experience":{"quote":"$quote","memory":"","emotion":"unknown","valence":0,"relationship":"none","control":"none","targets":[],"reconsider":null,"claimKind":"$kind","controlIntent":"$intent","requestQuote":"${if(intent=="none")""else quote}"}}""").asJsonObject
    @Test fun semanticLabelsAreProvidedByModelNotKeywords(){
        assertEquals("hypothesis",JointExperience.parse(json("hypothesis"),"原话",0).claimKind)
        assertEquals("reported_event",JointExperience.parse(json("reported_event"),"原话",0).claimKind)
        assertFalse(JointExperience.parse(json(),"原话",0).interpretationUnknown)
    }
    @Test fun controlNeedsLiteralCurrentEvidenceAndCannotSelfAuthorize(){
        val valid=json(intent="forget")
        valid.getAsJsonObject("experience").addProperty("controlAuthorized",true)
        assertFalse(JointExperience.parse(valid,"原话",0).controlAuthorized)
        assertTrue(runCatching{JointExperience.parse(json(intent="forget",quote="别的原话"),"原话",0)}.isFailure)
        valid.getAsJsonObject("experience").addProperty("control","forget")
        assertTrue(runCatching{JointExperience.parse(valid,"原话",0)}.isFailure)
    }
    @Test fun missingFactContractCannotSilentlyProduceEmptyFacts(){
        val value=json();value.getAsJsonObject("experience").remove("claimKind")
        assertTrue(runCatching{JointExperience.parse(value,"原话",0)}.isFailure)
        assertTrue(runCatching{JointExperience.parse(JsonParser.parseString("{}").asJsonObject,"原话",0)}.isFailure)
    }
}
