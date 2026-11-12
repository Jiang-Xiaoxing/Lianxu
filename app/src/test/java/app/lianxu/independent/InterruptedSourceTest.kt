package app.lianxu.independent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InterruptedSourceTest {
    private fun source(reply:Message?=null)=SourceDialogue("t",SourceRef("a",1,1),Message(1,"a","我的第一句",true,100),reply,"Asia/Shanghai")
    @Test fun userOnlySourceRoundTripsWithoutInventingAssistant() {
        val s=source();val restored=SourceDialogue.from(s.json())
        assertEquals(s,restored);assertNull(restored.assistant)
        assertEquals(listOf(s.user),restored.messages)
        assertEquals(ConservativeTokens.tokens(s.user.text),restored.tokens)
    }
    @Test fun onlyRealMessagesReceiveMemoryEvidenceNumbers() {
        val refs=MemoryNumberedReferences(listOf(source()),emptyList())
        assertEquals(1,refs.sourceRows().length())
        val op=refs.normalize(JSONObject().put("evidence",JSONArray().put("S1")))
        assertEquals("我的第一句",op.getJSONArray("evidence").getJSONObject(0).getString("quote"))
        assertTrue(runCatching{refs.normalize(JSONObject().put("evidence",JSONArray().put("S2")))}.isFailure)
    }
    @Test fun actualPartialAssistantStillHasItsOwnEvidence() {
        val s=source(Message(2,"a","真的显示过",false,101))
        assertEquals(s,SourceDialogue.from(s.json()))
        val rows=MemoryNumberedReferences(listOf(s),emptyList()).sourceRows()
        assertEquals(2,rows.length());assertEquals("真的显示过",rows.getJSONObject(1).getString("content"))
    }
    @Test fun interruptedInputsCanTriggerCapacitySummaryWithoutAnAssistant() {
        val recent=(1L..4).map{Message(it,"a","原话".repeat(150),true,it)}
        val result=CompanionPrompt.build(Friend("a","甲","简介"),RolePrompts(),recent,"接着说",capacity=2500,outputLimit=100,recentLimit=1)
        assertTrue(result is PromptBuild.Blocked)
        assertEquals(ChatFailure.CONTEXT_NEEDS_MEMORY,(result as PromptBuild.Blocked).reason)
    }
}
