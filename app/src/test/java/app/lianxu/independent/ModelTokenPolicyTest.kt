package app.lianxu.independent

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class ModelTokenPolicyTest {
    private fun config(provider:String="mimo",base:String="https://api.xiaomimimo.com/v1",model:String="mimo-v2.6-pro")=ModelConfig("synthetic","合成配置",provider,base,model)
    @Test fun ordinaryAndReasoningHaveDifferentPurposeDefaults(){
        assertEquals(ModelTokenDefaults(128000,4096,8192),ModelTokenPolicy.defaults("openai","https://api.openai.com/v1","gpt-4o-mini"))
        assertEquals(ModelTokenDefaults(1000000,16384,32768),ModelTokenPolicy.defaults("mimo","https://api.xiaomimimo.com/v1","mimo-v2.6-pro"))
        assertEquals(ModelTokenDefaults(400000,16384,32768),ModelTokenPolicy.defaults("openai","https://api.openai.com/v1","gpt-5-mini-2025-08-07"))
    }
    @Test fun neverInferCapacityFromSimilarNameOrDifferentEndpoint(){
        for(c in listOf(config(model="mimo-v2.6-pro-unverified"),config(base="https://example.com/v1"),config(provider="custom"),config(base="https://api.xiaomimimo.com.attacker.example/v1"),config(base="https://api.xiaomimimo.com/v1/other"))){
            assertNull(ModelTokenPolicy.capability(c));assertEquals(ModelTokenDefaults(),ModelTokenPolicy.defaults(c.providerId,c.baseUrl,c.modelId))
        }
    }
    @Test fun checkedModelsRespectDocumentedCapsInsteadOfUniversal8192(){
        assertNull(ConfigRules.validate(config().copy(contextTokens=1000000,outputTokens=16384,memoryOutputTokens=32768),"synthetic"))
        assertNotNull(ConfigRules.validate(config().copy(contextTokens=1000001),"synthetic"))
        assertNotNull(ConfigRules.validate(config().copy(contextTokens=1000000,outputTokens=131073),"synthetic"))
        assertTrue(ModelTokenPolicy.acceptsRequest(config().copy(contextTokens=1000000),131072))
        assertFalse(ModelTokenPolicy.acceptsRequest(config().copy(contextTokens=1000000),131073))
        assertEquals(393216,ModelTokenPolicy.capability("deepseek","https://api.deepseek.com/v1","deepseek-flash")!!.maxOutput)
    }
    @Test fun unknownManualCapacityIsAllowedButNeverInvented(){
        val c=config(provider="custom",base="https://example.com/v1",model="manual").copy(contextTokens=128000,outputTokens=16000,memoryOutputTokens=32000)
        assertNull(ConfigRules.validate(c,"synthetic"));assertNull(ModelTokenPolicy.capability(c))
        assertNotNull(ConfigRules.validate(c.copy(outputTokens=128000),"synthetic"))
        assertNotNull(ConfigRules.validate(c.copy(memoryOutputTokens=-1),"synthetic"))
    }
    @Test fun oldJsonRetainsEverySavedValueAndMemoryInheritsItsOriginalOutput(){
        val c=Gson().fromJson("""{"id":"synthetic","name":"合成","providerId":"mimo","baseUrl":"https://api.xiaomimimo.com/v1","modelId":"mimo-v2.6-pro","contextTokens":65536,"outputTokens":3000}""",ModelConfig::class.java)
        assertEquals(65536,c.contextTokens);assertEquals(3000,c.outputTokens);assertEquals(0,c.memoryOutputTokens);assertEquals(3000,c.memoryOutputLimit())
        assertFalse(ModelBudgetDraft.existing(c).automatic)
        assertEquals(ModelBudgetDraft.existing(c),ModelBudgetDraft.existing(c).forModel("openai","https://api.openai.com/v1","gpt-4o-mini"))
    }
    @Test fun explicitPurposeLimitsSurviveSerializationWithoutChangingChat(){
        val c=config().copy(contextTokens=1000000,outputTokens=16384,memoryOutputTokens=32768)
        assertEquals(c,Gson().fromJson(Gson().toJson(c),ModelConfig::class.java));assertEquals(32768,c.memoryOutputLimit());assertEquals(16384,c.outputTokens)
        assertEquals(2048,config().memoryOutputLimit())
    }
    @Test fun freshDraftTracksModelButManualOrSavedDraftNeverDoes(){
        val fresh=ModelBudgetDraft().forModel("mimo","https://api.xiaomimimo.com/v1","mimo-v2.6-pro")
        assertEquals("1000000",fresh.context);assertEquals("32768",fresh.memory)
        assertEquals(ModelBudgetDraft(),fresh.forModel("custom","https://example.com/v1","unknown"))
        val manual=fresh.manual(chat="7777")
        assertEquals(manual,manual.forModel("openai","https://api.openai.com/v1","gpt-4o-mini"))
        val old=ModelBudgetDraft.existing(config())
        assertEquals("16384",old.context)
        assertEquals("1000000",old.apply("mimo","https://api.xiaomimimo.com/v1","mimo-v2.6-pro").context)
    }
    @Test fun outputRequestAndValidationRejectOverflows(){
        assertTrue(runCatching{TextChatRequest(listOf(WireMessage("user","test")),outputLimit=32768)}.isSuccess)
        assertTrue(runCatching{TextChatRequest(listOf(WireMessage("user","test")),outputLimit=Int.MAX_VALUE)}.isFailure)
        assertFalse(ModelTokenPolicy.acceptsRequest(config(),16384))
    }
}
