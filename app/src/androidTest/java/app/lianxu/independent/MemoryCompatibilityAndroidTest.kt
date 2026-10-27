package app.lianxu.independent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Executes the real Android JSON implementation; all input is synthetic. */
class MemoryCompatibilityAndroidTest {
    @Test fun oldPolicyCanBeReadButInactiveBudgetsDoNotReappearOnSave(){
        val json=JSONObject("""{"recent":8000,"batch":2000,"recall":2000,"core":1450,"relationshipEnabled":true,"relationshipAutomatic":false,"relationshipInterval":9,"historyChoice":"BACKFILL"}""")
        val before=json.toString();val policy=ProcessingPolicy.from(json)
        assertEquals(1450,policy.coreGoal);assertTrue(policy.relationshipEnabled);assertFalse(policy.relationshipAutomatic);assertEquals(9,policy.relationshipInterval);assertEquals("BACKFILL",policy.historyChoice)
        assertTrue(policy.valid());assertEquals(before,json.toString())
        val saved=policy.json();for(key in listOf("recent","batch","recall"))assertFalse(saved.has(key))
        assertEquals(1450,saved.getInt("core"));assertEquals(MemoryRules(40,20,15),MemoryRules())
    }
    @Test fun ignoredNoOpBudgetsDoNotAffectLiveValidationButActiveFieldsStillDo(){
        val policy=ProcessingPolicy.from(JSONObject("""{"recent":-1,"batch":"unused","recall":999999999,"core":1000,"relationshipInterval":5}"""))
        assertTrue(policy.valid());assertFalse(policy.copy(coreGoal=0).valid());assertFalse(policy.copy(relationshipInterval=0).valid())
        assertEquals(ProcessingPolicy(),ProcessingPolicy.from(JSONObject()))
    }
}
