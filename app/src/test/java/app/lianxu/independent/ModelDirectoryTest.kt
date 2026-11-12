package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class ModelDirectoryTest {
 private fun config(p:String,base:String=ProviderPresets.get(p).base)=ModelConfig("c","synthetic",p,base,"synthetic-model")
 @Test fun goDirectoryUsesTheDocumentedAbsoluteAddress(){
  val go=ProviderPresets.get("opencode-go")
  assertEquals("https://opencode.ai/inference/go/v1/models",ModelDirectoryRules.url(config("opencode-go"),go,true))
  assertEquals("https://api.siliconflow.cn/v1/models",ModelDirectoryRules.url(config("silicon-cn"),ProviderPresets.get("silicon-cn"),true))
  assertEquals("https://openrouter.ai/api/v1/key",ModelDirectoryRules.url(config("openrouter","https://openrouter.ai/api/v1"),ProviderPresets.get("openrouter"),false))
  assertNull(ModelDirectoryRules.url(config("opencode-go"),go,false))
 }
 @Test fun goCatalogKeepsOnlyOpenAiChatEntriesAndDeduplicates(){
  val entries=listOf(
   ModelDirectoryEntry("deepseek-v4.1-flash",true),
   ModelDirectoryEntry("claude-haiku-5-5",false),
   ModelDirectoryEntry("grok-4.7",false),
   ModelDirectoryEntry("gemini-4-argon",false),
   ModelDirectoryEntry("glm-5.3",true),
   ModelDirectoryEntry("glm-5.3",true)
  )
  assertEquals(listOf("deepseek-v4.1-flash","glm-5.3"),ModelDirectoryParser.ids(entries,true))
  assertEquals(listOf("deepseek-v4.1-flash","claude-haiku-5-5","grok-4.7","gemini-4-argon","glm-5.3"),ModelDirectoryParser.ids(entries,false))
 }
 @Test fun unsafeBlankAndOverlongIdsAreDropped(){
  val entries=listOf(ModelDirectoryEntry("",true),ModelDirectoryEntry("bad id",true),ModelDirectoryEntry("ok-id_1",true),ModelDirectoryEntry("x".repeat(201),true))
  assertEquals(listOf("ok-id_1"),ModelDirectoryParser.ids(entries,false))
 }
}
