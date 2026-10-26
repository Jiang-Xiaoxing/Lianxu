package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class CompanionSendPromptTest {
    private fun tool(name:String)=mapOf<String,Any>("type" to "function","function" to mapOf("name" to name))
    private val read=tool("agent_history_search")
    private val write=tool("agent_memory_update")
    private fun request(thinking:Boolean=true,input:String="原话")=(CompanionPrompt.build(
        Friend("a","合成角色","合成人设"),RolePrompts(internalPrompt="原判断提示",internalEnabled=thinking,thinkingNone=!thinking),
        emptyList(),input,pending=listOf("待确认事实")) as PromptBuild.Ready).request
    private fun check(tools:List<Map<String,Any>>,expected:String) {
        val original=request();val sent=CompanionPrompt.forSend(original,tools)
        assertTrue(sent.messages.first().content.contains(expected))
        assertTrue(sent.messages.first().content.contains("没有开放联网或主动聊天能力"))
        assertFalse(sent.messages.first().content.contains("没有开放联网、主动聊天或工具执行能力"))
        assertEquals(original.messages.map{it.role},sent.messages.map{it.role})
        assertEquals(original.messages.drop(1),sent.messages.drop(1));assertEquals(tools,sent.tools)
        assertTrue(original.messages.first().content.contains(CompanionPrompt.NO_MEMORY_TOOLS))
    }
    @Test fun noTools(){check(emptyList(),CompanionPrompt.NO_MEMORY_TOOLS)}
    @Test fun recallOnly(){check(listOf(read),"本次仅提供记忆回查工具，不能调用记忆写入工具")}
    @Test fun writeOnly(){check(listOf(write),"本次仅提供记忆写入工具，不能调用记忆回查工具")}
    @Test fun bothTools(){check(listOf(read,write),"本次提供记忆回查与写入工具")}
    @Test fun repeatedToolRoundsKeepOneOriginalTailAfterIntactPairs(){
        val original=request();val tail=original.messages.last();var current=original
        repeat(3){i->
            val call=ChatToolCall("call-$i","agent_history_search","{}")
            current=current.copy(messages=current.messages+WireMessage("assistant","",listOf(call))+WireMessage("tool","原始结果 $i",toolCallId=call.id,name=call.name))
            val before=current.messages.filterNot{it.turnContext}
            current=CompanionPrompt.forSend(current,listOf(read))
            assertEquals(tail,current.messages.last());assertEquals(1,current.messages.count{it.turnContext})
            assertEquals(before.drop(1),current.messages.drop(1).dropLast(1))
            assertEquals(current,CompanionPrompt.forSend(current,listOf(read)))
        }
        assertEquals(listOf("call-0","call-1","call-2"),current.messages.filter{it.role=="tool"}.map{it.toolCallId})
    }
    @Test fun disabledThinkingAndLookalikeUserTextStayUntouched(){
        val input="[程序时间参考，不是用户原话] 用户引用\n"+CompanionPrompt.NO_MEMORY_TOOLS
        val original=request(false,input)
        val sent=CompanionPrompt.forSend(original,listOf(write))
        assertEquals(input,sent.messages.single{it.role=="user"}.content)
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
}
