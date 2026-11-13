package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class CompanionThinkingLanguageTest {
    @Test fun namingUsesConfiguredAddressOrNaturalFallback() {
        for((persona,name) in listOf(UserPersona(address="小星") to "小星",UserPersona(name="星星") to "星星",UserPersona() to "对方")) {
            val language=CompanionPrompt.thinkingLanguage("林间",persona.addressee())
            assertTrue(language.contains("用「我」来想，想到${name}就叫平时的称呼，不要以「用户」开头。"))
            assertTrue(language.contains("后面说出口的才是给${name}的话。"))
            assertTrue("称呼那句单独一行",language.lines().contains(CompanionPrompt.thinkingNaming(name)))
            assertFalse("不带示例",language.contains("砂锅粥")||language.contains("Let me"))
            assertFalse(language.contains("{{"));assertFalse(language.contains("}}"));assertFalse(language.contains("{称呼}"))
        }
    }

    @Test fun languageAndMemoryRangesRemainValid() {
        for(remembered in listOf(false,true)) {
            val refs=if(remembered)listOf(PromptReference("记忆","合成记忆",true,"M1"))else emptyList()
            val request=(CompanionPrompt.build(Friend("cn","林间","合成角色"),
                RolePrompts(),emptyList(),"你好",refs) as PromptBuild.Ready).request
            val header=request.messages.first().content
            val language=CompanionPrompt.thinkingLanguage("林间")
            assertTrue(header.contains(language))
            assertFalse(language.contains('【'));assertFalse(language.contains('】'))
            assertTrue(header.indexOf(language)<header.indexOf("[输出与执行边界]"))
            val memory=if(remembered)CompanionPrompt.REMEMBERING_GUIDANCE else CompanionPrompt.REMEMBERING_EMPTY
            assertTrue(memory.contains("必要时可以翻一翻之前的聊天记录。"))
            assertTrue(memory.contains("翻了就直说是翻了记录，别装作一直记得。"))
            assertTrue(memory.contains("纠正过你的事"));assertFalse(memory.contains("agent_"))
            assertTrue(header.contains(memory.replace("{{user}}","旅人")))
            val tool=mapOf<String,Any>("type" to "function","function" to mapOf("name" to "agent_memory_update"))
            val sent=CompanionPrompt.forSend(request,listOf(tool)).messages.first().content
            assertEquals(header.replace(CompanionPrompt.NO_MEMORY_TOOLS,"本次仅提供记忆写入工具，不能调用记忆回查工具；记下之前仍要核对来源和版本，并遵守保护要求。"),sent)
        }
    }
}
