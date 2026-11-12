package app.lianxu.independent

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** In-memory HTTPS fixtures only; no socket, real provider, credential or billable request. */
class NativeReasoningTransportAndroidTest {
    private val key="synthetic-reasoning-key"
    private val config=ModelConfig("synthetic","Synthetic","mimo","https://api.xiaomimimo.com/v1","mimo-v2.6-flash",1000000)
    private val request=TextChatRequest(listOf(WireMessage("user","synthetic request")),128)
    private val tool=mapOf("type" to "function","function" to mapOf("name" to "read","parameters" to mapOf("type" to "object")))
    private fun reply(message:JSONObject,finish:String="stop")=JSONObject().put("choices",JSONArray().put(JSONObject().put("message",message).put("finish_reason",finish))).toString()
    private fun event(delta:JSONObject=JSONObject(),finish:String?=null)="data: "+JSONObject().put("choices",JSONArray().put(JSONObject().put("index",0).put("delta",delta).put("finish_reason",finish?:JSONObject.NULL))).toString()+"\n\n"
    private fun client(raw:String,mime:String="application/json")=HttpsTextChatClient(HttpsConnectionFactory{ProviderMockConnection(it,raw,mime)})
    @Test fun finalReplyKeepsOneNativeFieldWithoutMixingDialogueOrProtocolState()=runBlocking {
        val aliases=listOf("reasoning_content","thinking_content","reasoning","thinking")
        for(field in aliases){
            val result=client(reply(JSONObject().put("content","answer").put(field," native \n"))).complete(config,key,request) as TextChatResult.Success
            assertEquals("answer",result.text);assertEquals(" native \n",result.displayReasoning);assertNull(result.continuation)
        }
        val all=JSONObject().put("content","answer");aliases.forEachIndexed{i,f->all.put(f,"choice-$i")}
        assertEquals("choice-0",(client(reply(all)).complete(config,key,request) as TextChatResult.Success).displayReasoning)
        all.put("reasoning_content",JSONObject.NULL)
        assertEquals("choice-1",(client(reply(all)).complete(config,key,request) as TextChatResult.Success).displayReasoning)
        val bodyOnly=client(reply(JSONObject().put("content","<think>body-only thought</think>answer"))).complete(config,key,request) as TextChatResult.Success
        assertEquals("answer",bodyOnly.text);assertEquals("",bodyOnly.displayReasoning)
    }
    @Test fun typedSsePreservesWhitespaceChoosesOneAliasAndDoesNotResend()=runBlocking {
        val raw=event(JSONObject().put("reasoning_content","one").put("thinking_content","duplicate"))+
            event(JSONObject().put("reasoning_content"," ").put("thinking_content","duplicate-space"))+
            event(JSONObject().put("reasoning_content","two\n"))+event(JSONObject().put("thinking_content","late-duplicate"))+
            event(JSONObject().put("content","answer"))+event(finish="stop")+"data: [DONE]\n\n"
        var calls=0;lateinit var connection:ProviderMockConnection
        val transport=HttpsTextChatClient(HttpsConnectionFactory{calls++;ProviderMockConnection(it,raw,"text/event-stream").also{c->connection=c}})
        val events=mutableListOf<TextStreamEvent>();val result=transport.streamEvents(config,key,request){events+=it} as TextChatResult.Success
        assertEquals(1,calls);assertEquals("one two\n",result.displayReasoning);assertEquals("answer",result.text)
        assertEquals("one two\n",events.filterIsInstance<TextStreamEvent.Reasoning>().joinToString(""){it.text})
        assertEquals("answer",events.filterIsInstance<TextStreamEvent.Content>().joinToString(""){it.text})
        val body=JSONObject(connection.sent.toString("UTF-8"));assertTrue(body.getBoolean("stream"));assertFalse(body.has("thinking"));assertFalse(body.has("reasoning_effort"))
    }
    @Test fun legacyTextStreamAndNonStreamingFallbackRemainCompatible()=runBlocking {
        val raw=event(JSONObject().put("reasoning","native"))+event(JSONObject().put("content","answer"))+event(finish="stop")+"data: [DONE]\n\n"
        val chunks=StringBuilder();val result=client(raw,"text/event-stream").stream(config,key,request){chunks.append(it)} as TextChatResult.Success
        assertEquals("answer",chunks.toString());assertEquals("stream-complete",result.text);assertEquals("native",result.displayReasoning)
        val events=mutableListOf<TextStreamEvent>();val final=client(reply(JSONObject().put("content","answer").put("thinking","native"))).streamEvents(config,key,request){events+=it} as TextChatResult.Success
        assertEquals("answer",final.text);assertEquals(listOf(TextStreamEvent.Reasoning("native"),TextStreamEvent.Content("answer")),events)
    }
    @Test fun toolsKeepCanonicalContinuationSeparateFromDisplayAliases()=runBlocking {
        val call=JSONObject().put("index",0).put("id","call-a").put("type","function").put("function",JSONObject().put("name","read").put("arguments","{}"))
        val raw=event(JSONObject().put("thinking_content","display-only"))+
            event(JSONObject().put("reasoning_content","protocol-later"))+
            event(JSONObject().put("tool_calls",JSONArray().put(call)),"tool_calls")+"data: [DONE]\n\n"
        val events=mutableListOf<TextStreamEvent>();val result=client(raw,"text/event-stream").streamEvents(config,key,request.copy(tools=listOf(tool))){events+=it} as TextChatResult.Success
        assertEquals("display-only",result.displayReasoning);assertEquals("protocol-later",result.continuation!!.wireValue())
        assertEquals("",result.text);assertEquals("read",result.toolCalls.single().name);assertTrue(events.none{it is TextStreamEvent.Content})
        assertEquals("display-only",events.joinToString(""){it.text})
    }
    @Test fun typedReplyWithOfferedToolsEmitsMultipleContentChunksWithoutFinalDuplication()=runBlocking {
        val prefix="one".repeat(90);val body=JSONObject().put("reply",prefix+"two".repeat(90)).toString()
        val split=body.indexOf(prefix)+prefix.length;val first=body.substring(0,split);val second=body.substring(split)
        val raw=event(JSONObject().put("content",first))+event(JSONObject().put("content",second))+event(finish="stop")+"data: [DONE]\n\n"
        val events=mutableListOf<TextStreamEvent>()
        val result=client(raw,"text/event-stream").streamEvents(config,key,request.copy(tools=listOf(tool),jsonObject=true)){events+=it} as TextChatResult.Success
        assertEquals(listOf(TextStreamEvent.Content(first),TextStreamEvent.Content(second)),events)
        assertEquals(body,result.text);assertTrue(result.toolCalls.isEmpty());assertNull(result.continuation)
        // Existing memory/text consumers still receive one validated body for a tool-enabled request.
        val legacy=mutableListOf<String>();val old=client(raw,"text/event-stream").stream(config,key,request.copy(tools=listOf(tool))){legacy+=it} as TextChatResult.Success
        assertEquals(listOf(body),legacy);assertEquals(body,old.text)
    }
    @Test fun typedToolCallDraftStillReturnsToolCallsAndNeverStreamsTheirArguments()=runBlocking {
        val call=JSONObject().put("index",0).put("id","call-a").put("type","function").put("function",JSONObject().put("name","read").put("arguments","{\"query\":\"tool-argument-only\"}"))
        val raw=event(JSONObject().put("content","provisional content"))+event(JSONObject().put("tool_calls",JSONArray().put(call)),"tool_calls")+"data: [DONE]\n\n"
        val events=mutableListOf<TextStreamEvent>()
        val result=client(raw,"text/event-stream").streamEvents(config,key,request.copy(tools=listOf(tool))){events+=it} as TextChatResult.Success
        assertEquals(listOf(TextStreamEvent.Content("provisional content")),events)
        assertEquals("read",result.toolCalls.single().name);assertTrue(result.toolCalls.single().arguments.contains("tool-argument-only"))
        assertFalse(events.any{it.text.contains("tool-argument-only")})
        val legacy=mutableListOf<String>();val old=client(raw,"text/event-stream").stream(config,key,request.copy(tools=listOf(tool))){legacy+=it} as TextChatResult.Success
        assertTrue(legacy.isEmpty());assertEquals(result.toolCalls,old.toolCalls)
    }
    @Test fun truncatedMalformedAndReasoningOnlyStreamsNeverBecomeReplies()=runBlocking {
        val partial=event(JSONObject().put("reasoning_content","partial"))+event(JSONObject().put("content","unfinished"))
        val samples=listOf(partial to ChatFailure.TRUNCATED,(partial+event(finish="length")) to ChatFailure.TRUNCATED,
            (event(JSONObject().put("reasoning_content","only thought"))+event(finish="stop")+"data: [DONE]\n\n") to ChatFailure.FORMAT,
            "data: {bad json\n\n" to ChatFailure.STREAM_PROTOCOL)
        for((raw,expected) in samples){
            val result=client(raw,"text/event-stream").streamEvents(config,key,request){}
            assertEquals(expected,(result as TextChatResult.Failure).reason)
        }
    }
}
