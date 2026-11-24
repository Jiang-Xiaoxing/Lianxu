package app.lianxu.independent
import org.junit.Assert.*
import org.junit.Test
class StructuredResponsePolicyTest {
    private val c=ModelConfig("test","test","mimo","https://api.xiaomimimo.com/v1","mimo-v2.6-flash")
    private val r=TextChatRequest(listOf(WireMessage("system","Return JSON only")),jsonObject=true)
    @Test fun continuationUsesOfficialEndpointAndKnownModelIndependentlyOfJsonMode(){
        for(base in listOf("https://api.deepseek.com","https://api.deepseek.com/v1/")){
            for(model in listOf("deepseek-chat","deepseek-reasoner","deepseek-v4-pro")){
                val config=c.copy(providerId="custom",baseUrl=base,modelId=model)
                assertTrue(StructuredResponsePolicy.supportsReasoningContinuation(config))
                assertFalse(StructuredResponsePolicy.jsonMode(config,r))
                assertFalse(StructuredResponsePolicy.supportsReasoningContinuation(config.copy(baseUrl="https://example.invalid/v1")))
                assertFalse(StructuredResponsePolicy.supportsReasoningContinuation(config.copy(modelId="unknown")))
            }
        }
        assertTrue(StructuredResponsePolicy.supportsReasoningContinuation(c))
    }
    @Test fun documentedStructuredCallsUseJsonModeButOrdinaryChatDoesNot(){assertTrue(StructuredResponsePolicy.jsonMode(c,r));assertFalse(StructuredResponsePolicy.jsonMode(c,r.copy(jsonObject=false)))}
    @Test fun unknownEndpointAndModelDoNotInheritProviderExtensions(){assertFalse(StructuredResponsePolicy.jsonMode(c.copy(baseUrl="https://example.invalid/v1"),r));assertFalse(StructuredResponsePolicy.jsonMode(c.copy(modelId="unknown"),r))}
    @Test fun offeringToolsUsesToolProtocolRatherThanAssumingCombinedJsonSupport(){assertFalse(StructuredResponsePolicy.jsonMode(c,r.copy(tools=listOf(mapOf("type" to "function")))))}
    @Test fun providerContinuationCannotSerializeOrBecomePrintableDialogue(){
        val hidden="synthetic-protocol-only"
        val state=ProviderContinuation(hidden)
        val message=WireMessage("assistant","",listOf(ChatToolCall("x","read","{}")),continuation=state)
        assertFalse(com.google.gson.Gson().toJson(message).contains(hidden));assertFalse(message.toString().contains(hidden))
        assertFalse(com.google.gson.Gson().toJson(TextChatResult.Success("",message.toolCalls,state)).contains(hidden))
        assertTrue(state.tokenCost>0)
    }
}
