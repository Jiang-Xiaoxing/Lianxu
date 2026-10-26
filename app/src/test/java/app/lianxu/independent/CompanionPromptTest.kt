package app.lianxu.independent

import org.junit.Test
import org.junit.Assert.*
import java.time.Instant
import java.time.ZoneId

class CompanionPromptTest {
    private val f=Friend("a","合成甲","仅供测试的人设","角色固定要求")
    @Test fun orderedLayersRawUserAndOneTail(){
        val p=RolePrompts(expression="自定义表达 {{character}}",extra="附加要求",internalPrompt="内部规则",internalEnabled=true,thinkingNone=false,examples=listOf(DialogueExample("示例用户","示例回复")),examplesEnabled=true)
        val history=listOf(Message(1,"a","近期原话",true,0),Message(2,"a","近期回复",false,1))
        val result=CompanionPrompt.build(f,p,history,"当前\n用户原文",listOf(PromptReference("记忆","有效事实",true)),listOf("待确认"),Instant.parse("2026-10-25T12:00:00Z"),ZoneId.of("Asia/Shanghai")) as PromptBuild.Ready
        val m=result.request.messages
        assertTrue(m[0].content.contains("角色固定要求"));assertTrue(m[0].content.contains("自定义表达 合成甲"));assertFalse(m[0].content.contains(CompanionPrompt.DEFAULT_EXPRESSION))
        assertTrue(m[1].content.contains("语气示例"));assertEquals("示例用户",m[2].content);assertEquals("示例回复",m[3].content)
        assertEquals("近期原话",m[4].content);assertEquals("近期回复",m[5].content);assertTrue(m[6].content.contains("有效事实"));assertTrue(m[7].content.contains("待确认"))
        assertEquals(WireMessage("user","当前\n用户原文"),m[8]);assertTrue(m.last().content.contains("2026-10-25T20:00+08:00"));assertTrue(m.last().content.contains("内部规则"))
        assertEquals(1,m.count{it.content.contains("[程序时间参考")});assertEquals(1,m.count{it.content=="当前\n用户原文"});assertEquals("自定义表达 {{character}}",p.expression)
    }
    @Test fun noneOrOffOmitsInternalButRetainsTime(){
        for(p in listOf(RolePrompts(internalEnabled=false,thinkingNone=false),RolePrompts(internalEnabled=true,thinkingNone=true))){
            val m=(CompanionPrompt.build(f,p,emptyList(),"hi") as PromptBuild.Ready).request.messages
            assertFalse(m.last().content.contains("内部判断提示"));assertTrue(m.last().content.contains("程序时间参考"))
        }
    }
    @Test fun noSilentHistoryOrCoreTrimming(){
        val over=(1..41).flatMap{listOf(Message(it*2L,"a","用户",true,0),Message(it*2L+1,"a","回复",false,1))}
        assertEquals(ChatFailure.CONTEXT_NEEDS_MEMORY,(CompanionPrompt.build(f,RolePrompts(),over,"hi") as PromptBuild.Blocked).reason)
        val core=listOf(PromptReference("core","x".repeat(10000),true))
        assertEquals(ChatFailure.CONTEXT_CAPACITY,(CompanionPrompt.build(f,RolePrompts(),emptyList(),"hi",core,capacity=4096,outputLimit=1024) as PromptBuild.Blocked).reason)
    }
    @Test(expected=IllegalArgumentException::class) fun crossRoleHistoryRejected(){CompanionPrompt.build(f,RolePrompts(),listOf(Message(1,"b","other",true,0)),"hi")}
    @Test fun candidatesLimitedAndStoppedExamplesStaySaved(){
        val p=RolePrompts(examples=listOf(DialogueExample("saved-example","saved-reply")),examplesEnabled=true,examplesClosed=true)
        val m=(CompanionPrompt.build(f,p,emptyList(),"hi",pending=listOf("one","two","three","four")) as PromptBuild.Ready).request.messages
        assertFalse(m.any{it.content=="saved-example"});assertFalse(m.any{it.content.contains("four")});assertEquals(1,p.examples.size)
    }
    @Test fun cancelLeaseRejectsLateCommit(){val l=ChatAttemptLease();l.cancel();var wrote=false;assertFalse(l.commit{wrote=true;true});assertFalse(wrote)}
    @Test fun successfulLeaseCommitsOnce(){val l=ChatAttemptLease();var writes=0;assertTrue(l.commit{writes++;true});assertFalse(l.commit{writes++;true});assertEquals(1,writes)}
}
