package app.lianxu.independent
import org.junit.Assert.*
import org.junit.Test

class ConfigRulesTest {
    @Test fun requiredFields() {
        assertNotNull(ConfigRules.validate("","https://example.com/v1","model","synthetic-key"))
        assertNotNull(ConfigRules.validate("name","https://example.com/v1","","synthetic-key"))
        assertNotNull(ConfigRules.validate("name","https://example.com/v1","model",""))
        assertNull(ConfigRules.validate("name","https://example.com/v1","model","synthetic-key"))
    }
    @Test fun rejectCleartextAndCredentialUrls() {
        listOf("http://example.com","file:///tmp/x","https://key@example.com/v1","https://example.com?key=secret","https://example.com/#secret","https://{WorkspaceId}.example.com","https://example.com/../x").forEach {assertNotNull(it,ConfigRules.urlError(it))}
    }
    @Test fun rejectHeaderInjection() {assertNotNull(ConfigRules.validate("name","https://example.com","m","synthetic\r\nAuthorization: injected"))}
    @Test fun directorySupportIsExplicit() {
        listOf("mimo","moonshot","minimax-cn","minimax-intl").forEach {assertEquals(it,"models",ProviderPresets.get(it).modelsPath)}
        assertNull(ProviderPresets.get("ark-cn").modelsPath)
        assertNull(ProviderPresets.get("bailian-cn").modelsPath)
        assertNull(ProviderPresets.get("zhipu-cn").modelsPath)
        assertEquals("models",ProviderPresets.get("deepseek").modelsPath)
        assertEquals("models?sub_type=chat",ProviderPresets.get("silicon-cn").modelsPath)
        assertEquals("api-key",ProviderPresets.get("mimo").authHeader)
        assertEquals("key",ProviderPresets.get("openrouter").testPath)
        assertTrue(ProviderPresets.all.all {it.base.isEmpty()||ConfigRules.urlError(it.base)==null})
    }
    @Test fun providerSearchMatchesNamesAliasesAndRegions() {
        assertTrue(providerMatches(ProviderPresets.get("silicon-cn"),"SILICONFLOW"))
        assertTrue(providerMatches(ProviderPresets.get("mimo"),"xiaomi"))
        assertTrue(providerMatches(ProviderPresets.get("deepseek"),"  DeepSeek "))
        assertFalse(providerMatches(ProviderPresets.get("deepseek"),"missing-provider"))
    }
}
