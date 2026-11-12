package app.lianxu.independent

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** 总结不拦聊天：轮数超过近期轮数只触发后台总结，只有按 token 真塞不下才等总结。 */
class SummaryDoesNotBlockChatTest {
    private val friend=Friend("a","合成","合成设定")
    private fun history(n:Int,text:String="短句")=(1..n).flatMap{listOf(Message(it*2L,"a","$text$it",true,0),Message(it*2L+1,"a","回$it",false,1))}

    @Test fun overFortyRoundsWhileSummaryRunsBuildsWithEveryUnsummarizedRound()=runBlocking {
        val guard=MemorySummaryGuard();val ticket=guard.begin("a",ChatAttemptLease(),10000){}!!
        val recent=history(45)
        val built=CompanionPrompt.build(friend,RolePrompts(),recent,"现在",recentLimit=40)
        assertTrue(built is PromptBuild.Ready)
        assertFalse("must not enter the wait-for-summary loop",built.waitsForSummary())
        val sent=(built as PromptBuild.Ready).request.messages.filter{it.sourceMessageId.isNotBlank()}.map{it.content.substring(it.sourcePrefix)}
        assertEquals("rounds 41..45 go in as well, nothing is dropped",recent.map{it.text},sent)
        assertTrue(built.boundaryReached)
        assertTrue("the summary keeps running untouched",guard.current(ticket));guard.finish(ticket)
    }
    @Test fun realTokenOverflowStillWaitsForTheSummary()=runBlocking {
        val recent=history(45,"长".repeat(400))
        val built=CompanionPrompt.build(friend,RolePrompts(),recent,"现在",capacity=8192,outputLimit=1024,recentLimit=40)
        assertEquals(ChatFailure.CONTEXT_NEEDS_MEMORY,(built as PromptBuild.Blocked).reason)
        assertTrue(built.waitsForSummary())
        val guard=MemorySummaryGuard();val ticket=guard.begin("a",ChatAttemptLease(),10000){}!!;var calls=0
        val chat=async(start=CoroutineStart.UNDISPATCHED){awaitMemoryCapacity("a",guard){calls++;ProcessingRun(true)}}
        assertEquals(0,calls);assertFalse(chat.isCompleted);guard.finish(ticket);assertTrue(chat.await().success);assertEquals(1,calls)
    }
    @Test fun overflowWithinRecentRoundsIsCapacityNotAWait(){
        val built=CompanionPrompt.build(friend,RolePrompts(),history(30,"长".repeat(400)),"现在",capacity=8192,outputLimit=1024,recentLimit=40)
        assertEquals(ChatFailure.CONTEXT_CAPACITY,(built as PromptBuild.Blocked).reason)
        assertFalse(built.waitsForSummary())
    }
}
