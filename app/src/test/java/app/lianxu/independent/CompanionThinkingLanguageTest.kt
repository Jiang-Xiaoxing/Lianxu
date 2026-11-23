package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class CompanionThinkingLanguageTest {
    @Test fun defaultOrBlankUsesTheApprovedBodyOnceAtTheTail() {
        val expected = "用简体中文，完全站在「我」自己的性格、脾气、节奏和口吻里想，想到对方时就用平时的称呼，绝不叫「用户」，也不像助手那样分析任务。先抓住对方这句话真正想表达的意思和分量，小事顺着接，重要的事想透再回。拿不准的感受或回忆别当成事实。开口只留真正符合你本人分寸和口吻的话，别写成客服套话或空泛大道理。"
        assertEquals(expected,CompanionPrompt.DEFAULT_THINKING_BODY)
        for (raw in listOf("", " \n\t")) {
            val sent = assemble(raw)
            assertEquals(expected,thinkingBody(sent))
            assertEquals("你好",sent.messages.single { it.role=="user" }.content)
            assertEquals(1,sent.messages.count{it.thinkingContext})
            assertEquals(1,sent.messages.sumOf{m->Regex.fromLiteral("[思考语言]").findAll(m.content).count()})
            assertFalse("开头系统不再携带思考语言",sent.messages.first().content.contains("[思考语言]"))
            assertTrue(sent.messages.last().thinkingContext)
            assertEquals(sent.messages[sent.messages.size-2],sent.messages.single{it.turnContext})
        }
    }

    @Test fun customReplacesDefaultAtTheTailAndKeepsEvidenceAndBoundaries() {
        val custom = "按 {{character}} 的习惯想，称呼 {{user}}。"
        val sent = assemble(custom)
        assertEquals("按 林间 的习惯想，称呼 小星。",thinkingBody(sent))
        assertFalse(thinkingBody(sent).contains(CompanionPrompt.DEFAULT_THINKING_BODY))
        assertEquals("你好",sent.messages.single { it.role=="user" }.content)
        assertTrue(sent.messages.first().content.contains(CompanionPrompt.NO_MEMORY_TOOLS))
        val proof = MemoryEvidence(SourceRef("test",7,1),"user","你好","你好",0,"UTC")
        assertTrue(ImmediateMemoryEvidence.visible(proof,sent.messages,true))
        assertTrue(ImmediateMemoryEvidence.visible(proof,assemble("").messages,true))
        val next = CompanionPrompt.forSend(sent.copy(messages=sent.messages+WireMessage("assistant","继续")),emptyList())
        assertEquals("你好",next.messages.single { it.role=="user" }.content)
        assertEquals(sent.messages.last(),next.messages.last())
        assertEquals(custom,RolePromptStore.decode(RolePromptStore.encode(RolePrompts(thinkingPrompt=custom))).thinkingPrompt)
    }

    @Test fun toolContinuationKeepsOnlyTheCurrentTimeAndThinkingPair() {
        val original=assemble("")
        val time=original.messages.single{it.turnContext}
        val thinking=original.messages.single{it.thinkingContext}
        val call=ChatToolCall("synthetic-call","agent_history_search","{}")
        val polluted=original.copy(messages=original.messages.dropLast(2)+listOf(
            WireMessage("system","旧时间",turnContext=true),
            WireMessage("system","旧思考",thinkingContext=true),
            time,
            thinking,
            WireMessage("assistant","",listOf(call)),
            WireMessage("tool","纯虚构结果",toolCallId=call.id,name=call.name)
        ))
        val sent=CompanionPrompt.forSend(polluted,emptyList())
        assertEquals(listOf(time,thinking),sent.messages.takeLast(2))
        assertFalse(sent.messages.any{it.content=="旧时间"||it.content=="旧思考"})
        assertEquals(2,sent.messages.count{it.turnContext||it.thinkingContext})
        assertEquals(sent.messages.indexOfFirst{it.role=="assistant"}+1,sent.messages.indexOfFirst{it.role=="tool"})
        assertEquals("纯虚构结果",sent.messages.single{it.role=="tool"}.content)
    }

    @Test fun internalRequestWithoutTurnContextIsNotInjected() {
        val request=TextChatRequest(listOf(WireMessage("system","内部任务"),WireMessage("user","内部输入")))
        val sent=CompanionPrompt.forSend(request,emptyList())
        assertEquals(request.messages,sent.messages)
        assertFalse(sent.messages.any{it.content.contains("[思考语言]")||it.content.endsWith(CompanionPrompt.THINKING_REMINDER)})
    }

    private fun assemble(value:String):TextChatRequest {
        val ready = CompanionPrompt.build(Friend("test","林间","合成角色"),RolePrompts(thinkingPrompt=value),
            emptyList(),"你好",persona=UserPersona(address="小星"),currentMessageId=7) as PromptBuild.Ready
        return CompanionPrompt.forSend(ready.request,emptyList())
    }
    private fun thinkingBody(request:TextChatRequest) = request.messages.single{it.thinkingContext}.content
        .removePrefix("[思考语言]\n")

    @Test fun languageAndMemoryRangesRemainValid() {
        for(remembered in listOf(false,true)) {
            val refs=if(remembered)listOf(PromptReference("记忆","合成记忆",true,"M1"))else emptyList()
            val request=(CompanionPrompt.build(Friend("cn","林间","合成角色"),
                RolePrompts(),emptyList(),"你好",refs) as PromptBuild.Ready).request
            val header=request.messages.first().content
            val language=CompanionPrompt.thinkingLanguage("林间")
            assertFalse(header.contains(language))
            val thinking=request.messages.single{it.thinkingContext}.content
            assertEquals(language,thinking)
            assertFalse(language.contains('【'));assertFalse(language.contains('】'))
            val memory=if(remembered)CompanionPrompt.REMEMBERING_GUIDANCE else CompanionPrompt.REMEMBERING_EMPTY
            assertTrue(memory.contains("必要时可以翻一翻之前的聊天记录。"))
            assertTrue(memory.contains("翻了就直说是翻了记录，别装作一直记得。"))
            assertTrue(memory.contains("纠正过你的事"));assertFalse(memory.contains("agent_"))
            assertTrue(header.contains(memory.replace("{{user}}","旅人")))
            val tool=mapOf<String,Any>("type" to "function","function" to mapOf("name" to "agent_memory_update"))
            val sent=CompanionPrompt.forSend(request,listOf(tool))
            val expected=header.replace(CompanionPrompt.NO_MEMORY_TOOLS,"本次仅提供记忆写入工具，不能调用记忆回查工具；记下之前仍要核对来源和版本，并遵守保护要求。")
                .removeSuffix("\n").removeSuffix(request.messages.first().toolInstructions.single().text)+"\n"
            assertEquals(expected,sent.messages.first().content)
            assertEquals(language,sent.messages.last().content)
        }
    }
}
