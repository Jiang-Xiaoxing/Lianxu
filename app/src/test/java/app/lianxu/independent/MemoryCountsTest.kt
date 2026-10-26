package app.lianxu.independent
import org.junit.Test
import org.junit.Assert.*

class MemoryCountsTest{
    private val friend=Friend("a","A","Synthetic")
    private fun history(n:Int)=(1..n).flatMap{listOf(Message(it*2L,"a","u",true,0),Message(it*2L+1,"a","a",false,1))}
    @Test fun positiveCountsAndBatchBoundary(){assertTrue(MemoryRules().valid());assertTrue(MemoryRules(1,1,1).valid());for(r in listOf(MemoryRules(0,1,15),MemoryRules(40,0,15),MemoryRules(20,21,15),MemoryRules(40,20,-1)))assertFalse(r.valid())}
    @Test fun consecutiveUsersOneReplyAndUnfinishedDoNotAddRounds(){val rows=listOf(Message(1,"a","u1",true,0),Message(2,"a","u2",true,0),Message(3,"a","a",false,0),Message(4,"a","another assistant",false,0),Message(5,"a","pending",true,0),Message(6,"a"," ",false,0));assertEquals(1,MemoryRules.completeRounds(rows))}
    @Test fun fortyAllowedFortyOneRequiresMemoryCurrentInputIsSeparate(){val r=CompanionPrompt.build(friend,RolePrompts(),history(40),"current");assertTrue(r is PromptBuild.Ready);assertTrue((r as PromptBuild.Ready).boundaryReached);assertEquals(ChatFailure.CONTEXT_NEEDS_MEMORY,(CompanionPrompt.build(friend,RolePrompts(),history(41),"current") as PromptBuild.Blocked).reason)}
    @Test fun fifteenSharedDeduplicatedAndCoreIndependent(){val refs=(1..20).flatMap{listOf(PromptReference("direct$it","x".repeat(200),memoryId="m$it",related=true),PromptReference("association$it","same",memoryId="m$it",related=true))}+PromptReference("core","protected",true,"m20")
        val messages=(CompanionPrompt.build(friend,RolePrompts(),emptyList(),"current",refs,capacity=32000) as PromptBuild.Ready).request.messages;val content=messages.joinToString{it.content}
        assertTrue("direct15:" in content);assertFalse("direct16:" in content);assertFalse("association1:" in content);assertTrue("protected" in content);assertEquals(15,Regex("direct\\d+:").findAll(content).count())}
    @Test fun relatedMemoriesNotTrimmedToOldTokenBudgetAndCapacityExplicit(){val refs=(1..15).map{PromptReference("entry$it","x".repeat(400),memoryId="m$it",related=true)}
        val full=CompanionPrompt.build(friend,RolePrompts(),emptyList(),"current",refs,capacity=32000) as PromptBuild.Ready;assertTrue(full.request.messages.any{"entry15:" in it.content});assertEquals(ChatFailure.CONTEXT_CAPACITY,(CompanionPrompt.build(friend,RolePrompts(),emptyList(),"current",refs,capacity=4096,outputLimit=1024) as PromptBuild.Blocked).reason)}
}
