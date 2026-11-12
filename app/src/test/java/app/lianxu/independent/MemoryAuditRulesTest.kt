package app.lianxu.independent
import org.junit.Assert.*
import org.junit.Test
class MemoryAuditRulesTest {
    @Test fun importancePreservesClarityAndReinforcement(){
        val s=MemoryRecallState(0,2,84.0,100.0,"day");val now=10*86400000L
        val high=MemoryForgetting.reclassify(s,MemoryImportance.HIGH,now)
        assertEquals(s.clarity(now),high.clarity(now),0.0);assertEquals(14600.0,high.halfLife,0.0)
        assertEquals(2,high.count);assertEquals("day",high.day)
        val low=MemoryForgetting.reclassify(high,MemoryImportance.TRIVIAL,now+86400000)
        assertEquals(high.clarity(now+86400000),low.anchor,0.0);assertEquals(84.0,low.halfLife,0.0)
    }
    @Test fun missingContentAndProtectedFallback(){
        val m=StoredMemory("m","r",MemoryCategory.USER_PROFILE,"原话",createdAt=0)
        for(c in listOf(40.0,10.0)){
            assertEquals("",MemoryForgetting.text(m,c){m.text})
            assertEquals("",MemoryForgetting.text(m.copy(manual=true),c){m.text})
            assertEquals("",MemoryForgetting.text(m.copy(locked=true),c){m.text})
        }
        assertEquals("残影",MemoryForgetting.text(m.copy(trace="残影"),40.0){m.text})
        assertTrue(MemoryForgetting.selectCore(listOf(m),mapOf("m" to 60.0),emptySet(),1000){it.text}.isEmpty())
    }
    @Test fun protectedMemoriesFadeThroughAllTiers(){
        for(m in listOf(
            StoredMemory("m","r",MemoryCategory.USER_PROFILE,"晚星对芒果过敏",manual=true,core=true),
            StoredMemory("m","r",MemoryCategory.USER_PROFILE,"晚星对芒果过敏",locked=true))){
            assertEquals(3650.0,MemoryForgetting.importance(m).halfLife,0.0)
            assertEquals(m.text,MemoryForgetting.text(m,70.0){m.text})
            val filled=m.copy(gist="她有样水果不能吃",trace="芒果")
            assertEquals(filled.gist,MemoryForgetting.text(filled,40.0){m.text})
            assertEquals(filled.trace,MemoryForgetting.text(filled,10.0){m.text})
            assertEquals("",MemoryForgetting.text(m.copy(gist="大意"),10.0){m.text})
            assertTrue(MemoryForgetting.selectCore(listOf(m),mapOf("m" to 10.0),emptySet(),1000){it.text}.isEmpty())
        }
    }
    @Test fun expandedGuidanceOnceInFinalPrompt(){
        val persona=UserPersona(address="阿星")
        val result=CompanionPrompt.build(Friend("r","她",""),RolePrompts(),emptyList(),"你好",listOf(PromptReference("核心记忆 M1@1","合成事实",true,"M1")),persona=persona) as PromptBuild.Ready
        val text=result.request.messages.joinToString("\n"){it.content}
        val expected=CompanionPrompt.REMEMBERING_GUIDANCE.replace("{{user}}","阿星")
        assertEquals(1,text.split(expected).size-1);assertFalse(text.contains("{{user}}"))
        println("memory_guidance_bytes=${expected.toByteArray().size} memory_section_bytes=${("[记忆使用]\n"+expected+"\n").toByteArray().size}")
        println("expanded_memory_guidance="+expected)
    }
}
