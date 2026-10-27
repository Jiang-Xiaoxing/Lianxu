package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class UserPersonaTest {
    private val friend=Friend("a","合成同伴","原介绍","原自写要求")
    private val persona=UserPersona("合成用户","小星","正在学习插画，喜欢日常散步。")
    @Test fun profileRoundTripsAndLegacyNamesAreNotTruncated(){
        assertEquals(persona,UserPersona.decode(persona.encode()));assertEquals("旧昵称",UserPersona.decode("","旧昵称").name)
        assertEquals("名".repeat(80),UserPersona.decode("","名".repeat(80)).name)
        assertFalse(UserPersona.decode("").configured)
    }
    @Test fun profileDoesNotContainASecondSpeakingStyleAndRejectsUnknownFields(){
        val j=RoleArchive.parse(persona.encode().toByteArray());assertEquals(setOf("version","name","address","introduction"),j.keySet())
        j.addProperty("speakingStyle","none");assertTrue(runCatching{UserPersona.decode(j.toString())}.isFailure)
        assertFalse(persona.copy(address="跨\n行").valid());assertFalse(persona.copy(introduction="字".repeat(4001)).valid())
    }
    @Test fun userAndCharacterProfilesAreSeparateRealPromptLayers(){
        val result=CompanionPrompt.build(friend,RolePrompts(expression="角色说话方式",characterDefinition="角色独立设定"),emptyList(),"你好",persona=persona) as PromptBuild.Ready
        val fixed=result.request.messages.first().content
        assertTrue("角色独立设定" in fixed);assertTrue("合成用户" in fixed&&"小星" in fixed&&persona.introduction in fixed)
        assertTrue(fixed.indexOf("[角色定义]")<fixed.indexOf("[用户自填资料]"));assertTrue(fixed.indexOf("[用户自填资料]")<fixed.indexOf("[表达方式]"))
        assertTrue(fixed.indexOf("[用户自填资料]")<fixed.indexOf("[输出与执行边界]"));assertTrue(result.request.tools.isEmpty())
        assertFalse("原自写要求" in fixed);assertTrue(result.request.messages.last().turnContext)
    }
    @Test fun legacyCharacterDefinitionStaysIntactWhenNewFieldIsMissing(){
        val result=CompanionPrompt.build(friend,RolePrompts(),emptyList(),"你好",persona=persona) as PromptBuild.Ready
        assertTrue("原介绍" in result.request.messages.first().content);assertTrue("原自写要求" in result.request.messages.first().content)
    }
    @Test fun macrosUseTheCallingNameWithoutMutatingOrRecursivelyExpandingSource(){
        val source="{{character}}称呼{{user}}";assertEquals("同伴称呼小星",UserPersona.expand(source,"同伴",persona));assertEquals("{{character}}称呼{{user}}",source)
        assertEquals("{{user}}与小星",UserPersona.expand("{{character}}与{{user}}","{{user}}",persona))
    }
    @Test fun openingIsExplicitlyInitialAndIsNeverInventedAsUserEvidence(){
        val p=RolePrompts(opening="开场哨兵{{user}}")
        val initial=CompanionPrompt.build(friend,p,emptyList(),"你好",persona=persona,openingEligible=true) as PromptBuild.Ready
        assertTrue("开场哨兵小星" in initial.request.messages.first().content);assertTrue("不是用户原话" in initial.request.messages.first().content)
        val later=CompanionPrompt.build(friend,p,emptyList(),"你好",persona=persona,openingEligible=false) as PromptBuild.Ready
        assertFalse(later.request.messages.any{"开场哨兵" in it.content})
    }
    @Test fun configuredUserProfileCountsTowardCapacityWithoutSilentTruncation(){
        val result=CompanionPrompt.build(friend,RolePrompts(),emptyList(),"你好",capacity=4096,outputLimit=512,persona=persona.copy(introduction="字".repeat(4000)))
        assertEquals(ChatFailure.CONTEXT_CAPACITY,(result as PromptBuild.Blocked).reason)
    }
    @Test fun sharedDefinitionKeepsLegacyContentAndUsesTheWholeNewField(){
        assertEquals("原介绍\n原自写要求",RolePrompts().definition(friend))
        val full="完整角色资料".repeat(1000);assertEquals(full,RolePrompts(characterDefinition=full).definition(friend))
    }
}
