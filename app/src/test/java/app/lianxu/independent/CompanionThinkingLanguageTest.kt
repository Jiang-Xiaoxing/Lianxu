package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class CompanionThinkingLanguageTest {
    @Test fun defaultOrBlankUsesTheApprovedBodyAndDefaultReminder() {
        val expected = "用简体中文，按你自己的性格和口吻想。用「我」，想到对方时就用平时的称呼，不叫「用户」。先弄清对方这句话在说什么，只想跟眼下有关的事。小事不用琢磨太久，重要的事想清楚再回答。拿不准的感受或回忆，别当成事实。说出口的只留你真的想对对方说的话。"
        assertEquals(expected,CompanionPrompt.DEFAULT_THINKING_BODY)
        for (raw in listOf("", " \n\t")) {
            val sent = assemble(raw)
            assertEquals(expected,thinkingBody(sent))
            assertEquals("你好\n\n"+CompanionPrompt.THINKING_REMINDER,sent.messages.single { it.role=="user" }.content)
        }
    }

    @Test fun customIsExpandedOnlyForSendingAndKeepsEvidenceAndBoundaries() {
        val custom = "按 {{character}} 的习惯想，称呼 {{user}}。"
        val sent = assemble(custom)
        assertEquals("按 林间 的习惯想，称呼 小星。",thinkingBody(sent))
        assertEquals("你好",sent.messages.single { it.role=="user" }.content)
        assertTrue(sent.messages.first().content.contains(CompanionPrompt.NO_MEMORY_TOOLS))
        val proof = MemoryEvidence(SourceRef("test",7,1),"user","你好","你好",0,"UTC")
        assertTrue(ImmediateMemoryEvidence.visible(proof,sent.messages,true))
        assertTrue(ImmediateMemoryEvidence.visible(proof,assemble("").messages,true))
        val next = CompanionPrompt.forSend(sent.copy(messages=sent.messages+WireMessage("assistant","继续")),emptyList())
        assertEquals("你好",next.messages.single { it.role=="user" }.content)
        assertEquals(custom,RolePromptStore.decode(RolePromptStore.encode(RolePrompts(thinkingPrompt=custom))).thinkingPrompt)
    }

    private fun assemble(value:String):TextChatRequest {
        val ready = CompanionPrompt.build(Friend("test","林间","合成角色"),RolePrompts(thinkingPrompt=value),
            emptyList(),"你好",persona=UserPersona(address="小星"),currentMessageId=7) as PromptBuild.Ready
        return CompanionPrompt.forSend(ready.request,emptyList())
    }
    private fun thinkingBody(request:TextChatRequest) = request.messages.first().content
        .substringAfter("[思考语言]\n").substringBefore("\n[输出与执行边界]")

    @Test fun languageAndMemoryRangesRemainValid() {
        for(remembered in listOf(false,true)) {
            val refs=if(remembered)listOf(PromptReference("记忆","合成记忆",true,"M1"))else emptyList()
            val request=(CompanionPrompt.build(Friend("cn","林间","合成角色"),
                RolePrompts(),emptyList(),"你好",refs) as PromptBuild.Ready).request
            val header=request.messages.first().content
            val language=CompanionPrompt.thinkingLanguage("林间")
            assertTrue(header.contains(language))
            assertFalse(language.contains('【'));assertFalse(language.contains('】'))
            assertTrue(header.indexOf(language)<header.indexOf("[输出与执行边界]"))
            val memory=if(remembered)CompanionPrompt.REMEMBERING_GUIDANCE else CompanionPrompt.REMEMBERING_EMPTY
            assertTrue(memory.contains("必要时可以翻一翻之前的聊天记录。"))
            assertTrue(memory.contains("翻了就直说是翻了记录，别装作一直记得。"))
            assertTrue(memory.contains("纠正过你的事"));assertFalse(memory.contains("agent_"))
            assertTrue(header.contains(memory.replace("{{user}}","旅人")))
            val tool=mapOf<String,Any>("type" to "function","function" to mapOf("name" to "agent_memory_update"))
            val sent=CompanionPrompt.forSend(request,listOf(tool)).messages.first().content
            assertEquals(header.replace(CompanionPrompt.NO_MEMORY_TOOLS,"本次仅提供记忆写入工具，不能调用记忆回查工具；记下之前仍要核对来源和版本，并遵守保护要求。"),sent)
        }
    }
}
