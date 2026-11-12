package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class RecordRetryContextTest {
    private fun failure(id:String)=WireMessage("tool","""{"success":false,"error":"invalid_arguments","hint":"补字段"}""",toolCallId=id,name=CompanionRecord.NAME)
    @Test fun retrySuccessPreservesPairingAndOrder() {
        val before=listOf(WireMessage("user","你好"),WireMessage("assistant","",listOf(ChatToolCall("first",CompanionRecord.NAME,"{}"))),failure("first"),WireMessage("assistant","重试"))
        val request=TextChatRequest(RecordRetryContext.resolve(before,setOf(2),true))
        assertEquals(before.size,request.messages.size)
        before.indices.forEach { i -> assertEquals(before[i],if(i==2)request.messages[i].copy(content=before[i].content)else request.messages[i]) }
        assertTrue(request.messages[2].content.contains("superseded"))
        assertFalse(request.messages[2].content.contains("invalid_arguments"))
        assertFalse(request.messages[2].content.contains("\"success\":false"))
    }
    @Test fun repeatedFailuresRemainVerbatim() {
        val messages=listOf(failure("one"),failure("two"))
        assertEquals(messages,RecordRetryContext.resolve(messages,setOf(0,1),false))
    }
    @Test fun firstSuccessChangesNothing() {
        val messages=listOf(WireMessage("user","你好"),WireMessage("system",CompanionRecord.receiptNote))
        assertEquals(messages,RecordRetryContext.resolve(messages,emptySet(),true))
    }
    @Test fun resolvesAllCurrentFailuresButNotHistoryOrOtherTools() {
        val messages=listOf(failure("history"),failure("one"),failure("two"),failure("control").copy(name=JointControl.NAME))
        val result=RecordRetryContext.resolve(messages,setOf(1,2,3),true)
        assertEquals(messages[0],result[0]);assertEquals(messages[3],result[3])
        assertEquals(RecordRetryContext.resolved,result[1].content);assertEquals(RecordRetryContext.resolved,result[2].content)
    }
}
