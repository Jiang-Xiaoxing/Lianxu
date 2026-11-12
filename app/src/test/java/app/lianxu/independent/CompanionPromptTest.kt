package app.lianxu.independent

import org.junit.Test
import org.junit.Assert.*
import java.time.Instant
import java.time.ZoneId

class CompanionPromptTest {
    private val f=Friend("a","合成甲","仅供测试的人设","角色固定要求")
    @Test fun sharedProfileUsesTheCharacterNameAndKeepsAuthoredWords(){
        val p=RolePrompts(characterDefinition="用户原设定：我是 AI 研究员",extra="附加要求",expression="轻声说话")
        val profile=CompanionPrompt.profile(f,p,UserPersona())
        assertTrue(profile.contains("你就是合成甲，正在和"))
        assertFalse(profile.contains("AI 伴侣角色"))
        assertTrue(profile.contains("用户原设定：我是 AI 研究员"))
        assertTrue(profile.contains("附加要求"));assertTrue(profile.contains("轻声说话"))
        val head=(CompanionPrompt.build(f,p,emptyList(),"你好") as PromptBuild.Ready).request.messages.first().content
        assertFalse(head.contains("当前程序"));assertFalse(head.contains("没法上网"));assertTrue(head.contains("目前没有可用的网页搜索或浏览工具"))
        assertTrue(head.contains("不能假称查过网页"))
        assertTrue(head.contains("只有对方开启主动联系后"));assertTrue(head.contains("不能保证准时联系或一定送到"))
    }
    @Test fun orderedLayersRawUserAndOneTail(){
        val p=RolePrompts(expression="自定义表达 {{character}}",extra="附加要求",examples=listOf(DialogueExample("示例用户","示例回复")),examplesEnabled=true)
        val history=listOf(Message(1,"a","近期原话",true,0),Message(2,"a","近期回复",false,1))
        val result=CompanionPrompt.build(f,p,history,"当前\n用户原文",listOf(PromptReference("记忆","有效事实",true)),listOf("待确认"),Instant.parse("2026-10-25T12:00:00Z"),ZoneId.of("Asia/Shanghai")) as PromptBuild.Ready
        val m=result.request.messages
        assertTrue(m[0].content.contains("角色固定要求"));assertTrue(m[0].content.contains("自定义表达 合成甲"));assertFalse(m[0].content.contains(CompanionPrompt.DEFAULT_EXPRESSION))
        assertFalse(m.any{"语气示例" in it.content||it.content in setOf("示例用户","示例回复")})
        assertEquals("近期原话",m[1].content.substring(m[1].sourcePrefix));assertEquals("近期回复",m[2].content.substring(m[2].sourcePrefix));assertTrue(m[1].content.startsWith("["));assertTrue(m[3].content.contains("有效事实"));assertTrue(m[4].content.contains("待确认"))
        assertEquals(WireMessage("user","当前\n用户原文"),m[5]);assertTrue(m.last().content.contains("2026年10月25日 星期日 20:00"));assertFalse(m.any{"内部判断提示" in it.content})
        assertEquals(1,m.count{it.content.contains("[程序时间参考")});assertEquals(1,m.count{it.content=="当前\n用户原文"});assertEquals("自定义表达 {{character}}",p.expression)
    }
    /** 「内部判断提示」已整套删掉：角色以前写过、开过的也不再拼进去，只留程序时间参考。 */
    @Test fun oldInternalPromptIsIgnoredAndTailKeepsOnlyTime(){
        val old=RolePromptStore.decode("""{"expression":"","extra":"","internalPrompt":"旧的内部规则","internalEnabled":true,"thinkingNone":false}""")
        assertFalse("internalPrompt" in RolePromptStore.encode(old));assertFalse("thinkingNone" in RolePromptStore.encode(old))
        for(p in listOf(RolePrompts(),old)){
            val m=(CompanionPrompt.build(f,p,emptyList(),"hi") as PromptBuild.Ready).request.messages
            assertFalse(m.any{"内部判断提示" in it.content||"旧的内部规则" in it.content});assertTrue(m.last().content.contains("程序时间参考"))
        }
    }
    @Test fun noSilentHistoryOrCoreTrimming(){
        val over=(1..41).flatMap{listOf(Message(it*2L,"a","用户",true,0),Message(it*2L+1,"a","回复",false,1))}
        // Over the round limit is no longer a block; every round still goes in (no silent trimming).
        assertEquals(82,(CompanionPrompt.build(f,RolePrompts(),over,"hi") as PromptBuild.Ready).request.messages.count{it.sourceMessageId.isNotBlank()})
        val core=listOf(PromptReference("core","x".repeat(10000),true))
        assertEquals(ChatFailure.CONTEXT_CAPACITY,(CompanionPrompt.build(f,RolePrompts(),emptyList(),"hi",core,capacity=4096,outputLimit=1024) as PromptBuild.Blocked).reason)
    }
    @Test(expected=IllegalArgumentException::class) fun crossRoleHistoryRejected(){CompanionPrompt.build(f,RolePrompts(),listOf(Message(1,"b","other",true,0)),"hi")}
    @Test fun retiredEnabledExamplesStaySavedButNeverEnterPrompt(){
        val p=RolePrompts(examples=listOf(DialogueExample("saved-example","saved-reply")),examplesEnabled=true,examplesClosed=false)
        val m=(CompanionPrompt.build(f,p,emptyList(),"hi",pending=listOf("one","two","three","four")) as PromptBuild.Ready).request.messages
        assertFalse(m.any{it.content=="saved-example"||it.content=="saved-reply"});assertFalse(m.any{it.content.contains("four")});assertEquals(1,p.examples.size)
    }
    @Test fun replyRoundDropsTheTemplateToneReference(){
        val tone=PromptReference(CompanionPrompt.TONE_LABEL,RelationTemplate.STEADY.prompt(),true)
        val summary=PromptReference(RelationshipPrompt.LABEL,"信任=0.3，亲近=0.2",true)
        val kept=CompanionPrompt.replyReferences(listOf(tone,summary))
        assertEquals(listOf(summary),kept)
        assertFalse(kept.any{"主动交流语气" in it.text||"不催促、不施压" in it.text})
        // The original bundle keeps the tone for control interpretation; reply/proactive filter it.
        assertTrue(listOf(tone,summary).any{it.label==CompanionPrompt.TONE_LABEL})
    }
    @Test fun duplicateGuidanceInUserTextIsNotMistakenForTheProgramSpan(){
        val copy=CompanionPrompt.REMEMBERING_GUIDANCE.replace("{{user}}","合成甲")
        val ready=CompanionPrompt.build(f,RolePrompts(extra="自填要求里引用了一段：\n$copy"),emptyList(),"哎") as PromptBuild.Ready
        val first=ready.request.messages.first().content
        assertTrue("the user's own copy stays verbatim",first.contains(copy))
        assertTrue("the program span is the one that changes",first.contains("这一轮没有想起具体的事"))
        // The capability span still points at its own sentence after the swap.
        val sent=CompanionPrompt.forSend(ready.request,listOf(mapOf<String,Any>("type" to "function","function" to mapOf<String,Any>("name" to "agent_history_search"))))
        assertFalse(sent.messages.first().content.contains(CompanionPrompt.NO_MEMORY_TOOLS))
        assertTrue(sent.messages.first().content.contains("本次仅提供记忆回查工具"))
        assertTrue("the user's copy is untouched by the capability rewrite",sent.messages.first().content.contains(copy))
    }
    @Test fun aReferenceThatCarriesExperiencesCountsAsRemembering(){
        val blob=PromptReference("可撤回的经历与状态","[手机保存的经历]来源12v1（昨天）: 她说还得加班",true,remembers=true)
        val first=(CompanionPrompt.build(f,RolePrompts(),emptyList(),"哎",listOf(blob)) as PromptBuild.Ready).request.messages.first().content
        assertTrue(first.contains("下面是你现在还记得的事"))
        assertFalse(first.contains("这一轮没有想起具体的事"))
    }
    @Test fun emptyRecallNeverPromisesMemoriesThatAreNotThere(){
        val none=(CompanionPrompt.build(f,RolePrompts(),emptyList(),"哎") as PromptBuild.Ready).request.messages.first().content
        assertFalse("an empty reference block must not sit under a remember-everything header","下面是你现在还记得的事" in none)
        assertTrue("the neutral wording still tells her how to talk",none.contains("这一轮没有想起具体的事"))
        val withMemory=(CompanionPrompt.build(f,RolePrompts(),emptyList(),"哎",listOf(PromptReference("关键词记忆 M1@1","她说今天还得加班",false,"M1")) ) as PromptBuild.Ready).request.messages.first().content
        assertTrue(withMemory.contains("下面是你现在还记得的事"))
        assertFalse(withMemory.contains("这一轮没有想起具体的事"))
    }
    @Test fun roughRecencyOnlyNamesHowLongAgoItWas(){
        val day=86_400_000L
        val now=1_700_000_000_000L
        assertEquals("今天",MemoryDates.relative(now,now))
        assertEquals("昨天",MemoryDates.relative(now-day,now))
        assertEquals("前天",MemoryDates.relative(now-2*day,now))
        assertEquals("5天前",MemoryDates.relative(now-5*day,now))
        assertEquals("10天前",MemoryDates.relative(now-10*day,now))
        assertTrue(MemoryDates.relative(now-60*day,now).let{it.endsWith("日")})
    }
    @Test fun cancelLeaseRejectsLateCommit(){val l=ChatAttemptLease();l.cancel();var wrote=false;assertFalse(l.commit{wrote=true;true});assertFalse(wrote)}
    @Test fun successfulLeaseCommitsOnce(){val l=ChatAttemptLease();var writes=0;assertTrue(l.commit{writes++;true});assertFalse(l.commit{writes++;true});assertEquals(1,writes)}
    @Test fun naturalMemoryRulesApplyWithCustomExpressionAndKeepEvidenceSeparate(){
        val raw="你还记得我之前说过什么吗？";val p=RolePrompts(expression="自定义语气",extra="保留附加要求")
        val m=(CompanionPrompt.build(f,p,emptyList(),raw,listOf(PromptReference("已确认","当前事实",true,"M1")),listOf("未确认建议")) as PromptBuild.Ready).request.messages
        val fixed=m.first().content
        assertTrue(fixed.contains(CompanionPrompt.REMEMBERING_GUIDANCE.replace("{{user}}","旅人")));assertEquals(1,fixed.split(CompanionPrompt.REMEMBERING_GUIDANCE.replace("{{user}}","旅人")).size-1);assertFalse(fixed.contains("{{user}}"))
        assertTrue(fixed.contains("别编细节"));assertTrue(fixed.contains("旅人纠正过你的事，就按旅人纠正的来。"))
        assertTrue(fixed.contains("自定义语气"));assertTrue(fixed.contains("保留附加要求"))
        assertEquals(raw,m.single{it.role=="user"}.content);assertTrue(m.last().turnContext)
        assertTrue(m.any{"拿不准的记忆，不得当作确定事实" in it.content&&"未确认建议" in it.content})
    }
}
