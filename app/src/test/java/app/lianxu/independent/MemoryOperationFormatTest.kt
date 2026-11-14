package app.lianxu.independent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MemoryOperationFormatTest {
    private fun op()=JSONObject().put("id","one").put("op","ADD").put("target","N1").put("category","STATE_FACT").put("about","USER").put("text","Synthetic fact").put("evidence",JSONArray().put("S1"))
    @Test fun validAddAndLegacyUpdateDefaultsAreUnchanged(){
        assertTrue(MemoryOperationFormat.issues(op(),1).isEmpty())
        val update=JSONObject().put("id","update").put("op","UPDATE").put("target","M1").put("evidence",JSONArray().put("S1"))
        assertTrue(MemoryOperationFormat.issues(update,2).isEmpty())
        assertTrue(MemoryOperationFormat.issues(update.put("category","  "),2).isEmpty())
    }
    @Test fun missingIdsNameEveryActualPositionWithoutInventingIds(){
        val issues=(1..64).flatMap{position->val o=op();o.remove("id");MemoryOperationFormat.issues(o,position)}
        assertEquals(64,issues.size);assertEquals("operations[1]",issues.first().operation);assertEquals("operations[64]",issues.last().operation)
        assertTrue(issues.all{"缺少必填字段 id" in it.detail})
        assertEquals(64,MemoryRepairFeedback.decode(MemoryRepairFeedback.encode(issues)).size)
    }
    @Test fun fieldsAreGroupedByStableIdAndDoNotEchoPrivateText(){
        val o=op().put("text",JSONObject()).put("about","UNKNOWN");o.remove("category")
        val issue=MemoryOperationFormat.issues(o,3).single()
        assertEquals("one",issue.operation);for(field in listOf("text","about","category"))assertTrue(issue.detail,field in issue.detail)
        assertFalse(issue.detail.contains("Synthetic fact"))
    }
    @Test fun malformedEvidenceAndTagsHaveSpecificFieldPaths(){
        val o=op().put("evidence",JSONArray().put(JSONObject().put("side","user")).put(1)).put("tags","wrong")
        val detail=MemoryOperationFormat.issues(o,1).single().detail
        for(field in listOf("evidence[1]","turn","version","quote","evidence[2]","tags"))assertTrue(detail,field in detail)
    }
    @Test fun protectedOrBadSourceStillReachesTheExistingSemanticValidator(){
        assertTrue(MemoryOperationFormat.issues(op().put("locked",true),1).isEmpty())
        assertTrue(MemoryOperationFormat.issues(op().put("evidence",JSONArray().put("S999")),1).isEmpty())
    }
    @Test fun suppliedInvalidCoreIsReportedWithoutRequiringAnOmittedCore(){
        assertTrue(MemoryOperationFormat.issues(op(),1).isEmpty())
        assertTrue(MemoryOperationFormat.issues(op().put("core",false),1).isEmpty())
        assertTrue(MemoryOperationFormat.issues(op().put("core",JSONObject()),2).single().detail.contains("core"))
        assertTrue(MemoryOperationFormat.issues(op().put("date_type","week"),2).single().detail.contains("date_type"))
        assertTrue(MemoryOperationFormat.issues(op().put("date_type","habit"),2).isEmpty())
    }
    @Test fun legacyEvidenceTypeErrorsKeepFieldPathsAndNumericStringCompatibility(){
        val evidence=JSONObject().put("turn","1").put("version","1").put("side","user").put("quote","Synthetic fact")
        assertTrue(MemoryOperationFormat.issues(op().put("evidence",JSONArray().put(evidence)),1).isEmpty())
        evidence.put("turn",JSONObject()).put("version",JSONArray()).put("side","wrong").put("quote","")
        val detail=MemoryOperationFormat.issues(op().put("evidence",JSONArray().put(evidence)),2).single().detail
        for(field in listOf("turn","version","side","quote"))assertTrue(detail,"evidence[1].$field" in detail)
    }
}
