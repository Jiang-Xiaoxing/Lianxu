package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class CompanionSendPromptTest {
    private fun originalText(messages:List<WireMessage>)=messages.map{it.copy(content=it.content.removeSuffix("\n\n"+CompanionPrompt.THINKING_REMINDER))}
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
        assertTrue(sent.messages.first().content.contains("只有对方开启主动联系后，你才有机会再考虑要不要找对方，不能保证准时联系或一定送到"))
        assertFalse(sent.messages.first().content.contains("没有开放联网、主动聊天或工具执行能力"))
        assertEquals(original.messages.map{it.role},sent.messages.map{it.role})
        assertEquals(original.messages.drop(1),originalText(sent.messages.drop(1)));assertEquals(tools,sent.tools)
        assertEquals("原话",original.messages.single{it.role=="user"}.content)
        assertEquals("原话\n\n"+CompanionPrompt.THINKING_REMINDER,sent.messages.single{it.role=="user"}.content)
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
            assertEquals(original.content.removeRange(originalRange),head.content.removeRange(range))
            assertTrue(head.content.contains(profile));assertEquals(result,sent.messages.single{it.role=="tool"})
            assertEquals(call,sent.messages.single{it.toolCalls.isNotEmpty()}.toolCalls.single())
            assertEquals(input+"\n\n"+CompanionPrompt.THINKING_REMINDER,sent.messages.single{it.role=="user"}.content)
            assertEquals(tools,sent.tools)
            if(write in tools)assertTrue(head.content.substring(range).contains("核对来源和版本，并遵守保护要求"))
        }
    }
    @Test fun repeatedToolRoundsKeepOneOriginalTailAfterIntactPairs(){
        val original=request();val tail=original.messages.last();var current=original
        repeat(3){i->
            val call=ChatToolCall("call-$i","agent_history_search","{}")
            current=current.copy(messages=current.messages+WireMessage("assistant","",listOf(call))+WireMessage("tool","原始结果 $i",toolCallId=call.id,name=call.name))
            val before=current.messages.filterNot{it.turnContext}
            current=CompanionPrompt.forSend(current,listOf(read))
            assertEquals(tail,current.messages.last());assertEquals(1,current.messages.count{it.turnContext})
            assertEquals(originalText(before.drop(1)),originalText(current.messages.drop(1).dropLast(1)))
            assertTrue(current.messages.last{it.role=="user"}.content.endsWith(CompanionPrompt.THINKING_REMINDER))
            assertEquals(1,current.messages.sumOf{m->Regex.fromLiteral(CompanionPrompt.THINKING_REMINDER).findAll(m.content).count()})
            assertEquals(before.filter{it.role=="tool"},current.messages.filter{it.role=="tool"})
            assertEquals(current,CompanionPrompt.forSend(current,listOf(read)))
        }
        assertEquals(listOf("call-0","call-1","call-2"),current.messages.filter{it.role=="tool"}.map{it.toolCallId})
    }
    @Test fun lookalikeUserTextStaysUntouched(){
        val input="[程序时间参考，不是用户原话] 用户引用\n"+CompanionPrompt.NO_MEMORY_TOOLS
        val original=request(input)
        val sent=CompanionPrompt.forSend(original,listOf(write))
        assertEquals(input,sent.messages.single{it.role=="user"}.content.removeSuffix("\n\n"+CompanionPrompt.THINKING_REMINDER))
        assertFalse(sent.messages.last().content.contains("内部判断提示"))
        assertTrue(sent.messages.last().content.contains("程序时间参考"))
        assertEquals(original.messages.last(),sent.messages.last())
    }
    @Test fun droppedToolsAndFallbackDoNotRetainPermissionClaims(){
        val both=CompanionPrompt.forSend(request(),listOf(read,write))
        val writeOnly=CompanionPrompt.forSend(both,listOf(write))
        assertTrue(writeOnly.messages.first().content.contains("本次仅提供记忆写入工具"))
        val none=CompanionPrompt.forSend(writeOnly,emptyList())
        assertTrue(none.messages.first().content.contains(CompanionPrompt.NO_MEMORY_TOOLS))
        assertFalse(none.messages.first().content.contains("本次提供记忆回查与写入工具"))
        assertEquals(both.messages.drop(1),none.messages.drop(1))
    }
    @Test fun naturalRulesSurviveToolContinuationWithoutMovingTimeOrPayloads(){
        val original=request();val call=ChatToolCall("natural-call","agent_history_search","{}")
        val result=WireMessage("tool","原始来源结果",toolCallId=call.id,name=call.name)
        val continued=CompanionPrompt.forSend(original.copy(messages=original.messages+WireMessage("assistant","",listOf(call))+result),listOf(read))
        assertTrue(continued.messages.first().content.contains(CompanionPrompt.REMEMBERING_GUIDANCE.replace("{{user}}","旅人")))
        assertEquals(1,continued.messages.count{CompanionPrompt.REMEMBERING_GUIDANCE.replace("{{user}}","旅人") in it.content})
        assertEquals(original.messages.last(),continued.messages.last());assertEquals(result,continued.messages.first{it.role=="tool"})
        assertTrue(continued.messages.first().content.contains("不能调用记忆写入工具"))
    }
}
