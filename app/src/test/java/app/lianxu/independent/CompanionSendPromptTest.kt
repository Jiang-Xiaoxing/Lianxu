package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class CompanionSendPromptTest {
    private fun tool(name:String)=mapOf<String,Any>("type" to "function","function" to mapOf("name" to name))
    private val read=tool("agent_history_search")
    private val write=tool("agent_memory_update")
    private fun request(input:String="原话")=(CompanionPrompt.build(
        Friend("a","合成角色","合成人设"),RolePrompts(),
        emptyList(),input,listOf(PromptReference("核心记忆 M1@1","合成事实",true,"M1")),pending=listOf("待确认事实")) as PromptBuild.Ready).request
    private fun check(tools:List<Map<String,Any>>,expected:String) {
        val original=request();val sent=CompanionPrompt.forSend(original,tools)
        assertTrue(sent.messages.first().content.contains(expected))
        assertTrue(sent.messages.first().content.contains("目前没有可用的网页搜索或浏览工具，不能假称查过网页"))
        assertFalse(sent.messages.first().content.contains("只有对方开启主动联系后，你才有机会再考虑要不要找对方，不能保证准时联系或一定送到"))
        assertFalse(sent.messages.first().content.contains("没有开放联网、主动聊天或工具执行能力"))
        assertEquals(original.messages.map{it.role},sent.messages.map{it.role})
        val pending=sent.messages.single{it.content.startsWith("[拿不准的记忆")}
        assertTrue("待确认事实" in pending.content)
        assertEquals(write in tools,"若本次提供记忆写入工具" in pending.content)
        assertEquals(read in tools,"必要时可以翻一翻之前的聊天记录。" in sent.messages.first().content)
        assertEquals(original.messages.drop(1).filter{it.toolInstructions.isEmpty()},sent.messages.drop(1).filter{it.toolInstructions.isEmpty()})
        assertEquals(tools,sent.tools)
        assertEquals("原话",original.messages.single{it.role=="user"}.content)
        assertEquals("原话",sent.messages.single{it.role=="user"}.content)
        assertEquals(sent.messages[sent.messages.size-2],sent.messages.single{it.turnContext})
        assertTrue(sent.messages.last().thinkingContext)
        assertEquals(sent,CompanionPrompt.forSend(sent,tools))
        assertFalse(CompanionPrompt.THINKING_REMINDER.contains("【"));assertFalse(CompanionPrompt.THINKING_REMINDER.contains("】"))
        assertTrue(original.messages.first().content.contains(CompanionPrompt.NO_MEMORY_TOOLS))
    }
    @Test fun noTools(){check(emptyList(),CompanionPrompt.NO_MEMORY_TOOLS)}
    @Test fun recallOnly(){check(listOf(read),"本次仅提供记忆回查工具，不能调用记忆写入工具")}
    @Test fun writeOnly(){check(listOf(write),"本次仅提供记忆写入工具，不能调用记忆回查工具")}
    @Test fun bothTools(){check(listOf(read,write),"本次提供记忆回查与写入工具")}
    @Test fun readablePermissionsOnlyReplaceTheOwnedSpanAndPreserveProtectedPayloads(){
        val input="写入仍须通过来源、版本及保护校验。\n{\"source\":12,\"state\":\"active\"}"
        val profile="用户自写：viewpoint/interest/habit；来源12v1，不能替换这些字。"
        val base=(CompanionPrompt.build(Friend("a","角色",profile),RolePrompts(),emptyList(),input) as PromptBuild.Ready).request
        val call=ChatToolCall("call-12","agent_memory_update","{\"source\":12,\"revision\":1}")
        val result=WireMessage("tool","{\"success\":true,\"source\":12,\"version\":1}",toolCallId=call.id,name=call.name)
        val request=base.copy(messages=base.messages+WireMessage("assistant","",listOf(call))+result)
        val original=request.messages.first();val originalRange=original.capabilityRange!!
        for(tools in listOf(emptyList(),listOf(read),listOf(write),listOf(read,write))){
            val sent=CompanionPrompt.forSend(request,tools);val head=sent.messages.first();val range=head.capabilityRange!!
            val expected=original.content.removeRange(originalRange).let { text ->
                if(read in tools) text else text.removeSuffix("\n").removeSuffix(original.toolInstructions.single().text)+"\n"
            }
            assertEquals(expected,head.content.removeRange(range))
            assertTrue(head.content.contains(profile));assertEquals(result,sent.messages.single{it.role=="tool"})
            assertEquals(call,sent.messages.single{it.toolCalls.isNotEmpty()}.toolCalls.single())
            assertEquals(input,sent.messages.single{it.role=="user"}.content)
            assertEquals(tools,sent.tools)
            assertEquals(sent.messages.indexOfFirst{it.role=="assistant"}+1,sent.messages.indexOfFirst{it.role=="tool"})
            assertTrue(sent.messages.last().thinkingContext)
            if(write in tools)assertTrue(head.content.substring(range).contains("核对来源和版本，并遵守保护要求"))
        }
    }
    @Test fun repeatedToolRoundsKeepOneOriginalTailAfterIntactPairs(){
        val original=request();val context=original.messages.takeLast(2);var current=original
        repeat(3){i->
            val call=ChatToolCall("call-$i","agent_history_search","{}")
            current=current.copy(messages=current.messages+WireMessage("assistant","",listOf(call))+WireMessage("tool","原始结果 $i",toolCallId=call.id,name=call.name))
            val before=current.messages.filterNot{it.turnContext||it.thinkingContext}
            current=CompanionPrompt.forSend(current,listOf(read))
            assertEquals(context,current.messages.takeLast(2));assertEquals(2,current.messages.count{it.turnContext||it.thinkingContext})
            // The program-owned capability wording in the first message changes on the first send.
            assertEquals(before.drop(1).filter{it.toolInstructions.isEmpty()},current.messages.dropLast(2).drop(1).filter{it.toolInstructions.isEmpty()})
            assertTrue(current.messages.first().content.contains("本次仅提供记忆回查工具"))
            assertEquals("原话",current.messages.single{it.role=="user"}.content)
            assertEquals(1,current.messages.sumOf{m->Regex.fromLiteral("[思考语言]").findAll(m.content).count()})
            assertEquals(before.filter{it.role=="tool"},current.messages.filter{it.role=="tool"})
            assertEquals(current,CompanionPrompt.forSend(current,listOf(read)))
        }
        assertEquals(listOf("call-0","call-1","call-2"),current.messages.filter{it.role=="tool"}.map{it.toolCallId})
    }
    @Test fun lookalikeUserTextStaysUntouched(){
        val input="[程序时间参考，不是用户原话] 用户引用\n"+CompanionPrompt.NO_MEMORY_TOOLS
        val original=request(input)
        val sent=CompanionPrompt.forSend(original,listOf(write))
        assertEquals(input,sent.messages.single{it.role=="user"}.content)
        assertFalse(sent.messages.last().content.contains("内部判断提示"))
        assertTrue(sent.messages.single{it.turnContext}.content.contains("程序时间参考"))
        assertEquals(original.messages.last(),sent.messages.last())
    }
    @Test fun droppedToolsAndFallbackDoNotRetainPermissionClaims(){
        val base=request()
        val both=CompanionPrompt.forSend(base,listOf(read,write))
        val writeOnly=CompanionPrompt.forSend(both,listOf(write))
        assertTrue(writeOnly.messages.first().content.contains("本次仅提供记忆写入工具"))
        val none=CompanionPrompt.forSend(writeOnly,emptyList())
        assertTrue(none.messages.first().content.contains(CompanionPrompt.NO_MEMORY_TOOLS))
        assertFalse(none.messages.first().content.contains("本次提供记忆回查与写入工具"))
        assertEquals(CompanionPrompt.forSend(base,emptyList()),none)
        val candidate=none.messages.single { "待确认事实" in it.content }
        assertTrue("不得当作确定事实" in candidate.content)
        assertFalse("可按证据改正" in candidate.content)
        assertEquals(both,CompanionPrompt.forSend(none,listOf(read,write)))
    }
    @Test fun userTextAndMediaStayVerbatimWhenTheTailMoves(){
        val image=ChatImageRef("a",7,"/synthetic/image.jpg","image/jpeg","synthetic-hash",32,32)
        val base=(CompanionPrompt.build(Friend("a","角色","合成人设"),RolePrompts(),emptyList(),"带图原话",inputImage=image,currentMessageId=7) as PromptBuild.Ready).request
        val call=ChatToolCall("media-call","agent_history_search","{}")
        val tool=WireMessage("tool","合成结果",toolCallId=call.id,name=call.name)
        val sent=CompanionPrompt.forSend(base.copy(messages=base.messages+WireMessage("assistant","",listOf(call))+tool),emptyList())
        val user=sent.messages.single{it.role=="user"}
        assertEquals("带图原话",user.content);assertEquals(listOf(image),user.imageRefs);assertEquals("7",user.sourceMessageId)
        assertEquals(tool,sent.messages.single{it.role=="tool"})
        assertTrue(sent.messages.last().thinkingContext)
        assertEquals(sent.messages[sent.messages.size-2],sent.messages.single{it.turnContext})
    }
    @Test fun naturalRulesSurviveToolContinuationWithoutMovingTimeOrPayloads(){
        val original=request();val call=ChatToolCall("natural-call","agent_history_search","{}")
        val result=WireMessage("tool","原始来源结果",toolCallId=call.id,name=call.name)
        val continued=CompanionPrompt.forSend(original.copy(messages=original.messages+WireMessage("assistant","",listOf(call))+result),listOf(read))
        assertTrue(continued.messages.first().content.contains(CompanionPrompt.REMEMBERING_GUIDANCE.replace("{{user}}","旅人")))
        assertEquals(1,continued.messages.count{CompanionPrompt.REMEMBERING_GUIDANCE.replace("{{user}}","旅人") in it.content})
        assertEquals(original.messages.takeLast(2),continued.messages.takeLast(2));assertEquals(result,continued.messages.first{it.role=="tool"})
        assertTrue(continued.messages.first().content.contains("不能调用记忆写入工具"))
    }
}
