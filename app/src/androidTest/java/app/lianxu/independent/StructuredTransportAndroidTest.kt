package app.lianxu.independent
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StructuredTransportAndroidTest {
    private val config=ModelConfig("synthetic","synthetic","mimo","https://api.xiaomimimo.com/v1","mimo-v2.6-flash",contextTokens=1000000,outputTokens=65536,memoryOutputTokens=32768)
    private val tool=mapOf("type" to "function","function" to mapOf("name" to "read","parameters" to mapOf("type" to "object","properties" to emptyMap<String,String>())))
    @Test fun actualHttpBodyCarriesJsonModeOnlyForDocumentedStructuredRequests():Unit=runBlocking {
        val config=ModelConfig("synthetic","synthetic","mimo","https://api.xiaomimimo.com/v1","mimo-v2.6-flash",contextTokens=1000000,outputTokens=65536,memoryOutputTokens=32768)
        for(structured in listOf(true,false))TextTlsMock().use{s->
            val result=s.routedProviderClient{}.complete(config,"synthetic-key-only",TextChatRequest(listOf(WireMessage("system","Return JSON only")),jsonObject=structured))
            assertTrue(result.toString(),result is TextChatResult.Success)
            val body=JSONObject(s.body)
            assertEquals(structured,body.has("response_format"))
            if(structured)assertEquals("json_object",body.getJSONObject("response_format").getString("type"))
        }
    }
    @Test fun actualToolRequestEchoesEphemeralContinuationOnlyToDocumentedEndpoint():Unit=runBlocking {
        val raw="""{"choices":[{"finish_reason":"tool_calls","message":{"content":"","reasoning_content":"synthetic-protocol-only","tool_calls":[{"id":"x","type":"function","function":{"name":"read","arguments":"{}"}}]}}]}"""
        val parsed=HttpsTextChatClient().parse(raw,"synthetic-key-only",setOf("read"),true) as TextChatResult.Success
        assertEquals("",parsed.text);assertNotNull(parsed.continuation)
        for(documented in listOf(true,false))TextTlsMock().use{s->
            val request=TextChatRequest(listOf(WireMessage("user","合成问题"),WireMessage("assistant",parsed.text,parsed.toolCalls,continuation=parsed.continuation),WireMessage("tool","合成结果",toolCallId="x",name="read")),tools=listOf(tool),jsonObject=true)
            assertTrue(s.routedProviderClient{}.complete(if(documented)config else config.copy(baseUrl="https://example.invalid/v1"),"synthetic-key-only",request) is TextChatResult.Success)
            val body=JSONObject(s.body);val assistant=body.getJSONArray("messages").getJSONObject(1)
            assertEquals(documented,assistant.has("reasoning_content"))
            if(documented)assertEquals("synthetic-protocol-only",assistant.getString("reasoning_content"))
            assertFalse(body.has("response_format"));assertFalse(body.has("thinking"))
        }
    }
    @Test fun streamedToolContinuationNeverBecomesVisibleText():Unit=runBlocking {
        val stream="""data: {"choices":[{"index":0,"delta":{"reasoning_content":"synthetic-protocol-only"}}]}

data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"x","type":"function","function":{"name":"read","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}

data: [DONE]

"""
        TextTlsMock(response=stream,contentType="text/event-stream").use{s->
            val visible=StringBuilder();val result=s.routedProviderClient{}.stream(config,"synthetic-key-only",TextChatRequest(listOf(WireMessage("user","合成查询")),tools=listOf(tool))){visible.append(it)} as TextChatResult.Success
            assertEquals("",visible.toString());assertEquals("",result.text);assertNotNull(result.continuation)
            assertEquals("synthetic-protocol-only",result.continuation!!.wireValue())
        }
    }
}
