package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class TokenEstimateTest {
    @Test fun chineseIsNotCountedAsThreeTokensPerCharacter(){
        val text="中文".repeat(4000)
        assertEquals(12012,ConservativeTokens.tokens(text))
        assertTrue(ConservativeTokens.tokens(text)<text.toByteArray(Charsets.UTF_8).size)
        // 1.2 units/character plus 25% headroom; keep the margin explicit.
        assertTrue(ConservativeTokens.tokens(text)>text.length*1.2)
    }
    @Test fun asciiJsonAndMixedLanguageRemainPositiveWithOverhead(){
        assertEquals(12,ConservativeTokens.tokens(""))
        assertTrue(ConservativeTokens.tokens("hello world")>=12+6)
        assertTrue(ConservativeTokens.tokens("{\"事实\":\"合成测试123\"}")>12)
        assertTrue(ConservativeTokens.tokens("{}")>ConservativeTokens.tokens(""))
    }
    @Test fun emojiRareHanAndUnrecognizedScriptsKeepByteBasedHeadroom(){
        assertEquals(22,ConservativeTokens.tokens("🙂𠀀"))
        assertTrue(ConservativeTokens.tokens("العربية")>12)
        assertEquals(ConservativeTokens.tokens("🙂"),ConservativeTokens.tokens(String(Character.toChars(0x1f642))))
    }
    @Test fun largeInputsAreMonotonicAndDoNotOverflow(){
        var last=0
        for(n in listOf(0,1,100,10000,100000)){
            val count=ConservativeTokens.tokens("中".repeat(n));assertTrue(count>=last);last=count
        }
    }
    @Test fun budgetFailureNeverReducesRequestedHistoryOrRelatedCount(){
        val friend=Friend("synthetic","合成角色","测试","")
        val history=(1..40).flatMap{n->listOf(Message(n*2L,friend.id,"中".repeat(60),true,0),Message(n*2L+1,friend.id,"文".repeat(100),false,1))}
        val refs=(1..15).map{PromptReference("记忆$it","合成资料",memoryId="m$it",related=true)}
        val ready=CompanionPrompt.build(friend,RolePrompts(),history,"当前消息",refs,capacity=64000,outputLimit=4096) as PromptBuild.Ready
        assertEquals(80,ready.request.messages.count{it.sourceMessageId.isNotEmpty()})
        assertTrue(ready.request.messages.any{it.content.contains("记忆15")})
        val blocked=CompanionPrompt.build(friend,RolePrompts(),history,"当前消息",refs,capacity=4096,outputLimit=2048) as PromptBuild.Blocked
        assertEquals(ChatFailure.CONTEXT_CAPACITY,blocked.reason);assertEquals(80,history.size);assertEquals(15,refs.size)
        assertEquals(MemoryRules(40,20,15),MemoryRules())
    }
}
