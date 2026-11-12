package app.lianxu.independent

import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StructuredModelCallTest {
    private val config=ModelConfig("synthetic","synthetic","custom","https://example.invalid/v1","synthetic")
    private val messages=listOf(WireMessage("system","Return {ok:true}"),WireMessage("user","Synthetic input"))
    private class Client(val responses:List<TextChatResult>):TextChatClient {
        val requests=mutableListOf<TextChatRequest>()
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{requests+=request;return responses.getOrElse(requests.lastIndex){responses.last()}}
    }
    private fun valid(raw:String):Boolean {val j=JsonParser.parseString(raw).asJsonObject;require(j.has("ok")&&j.get("ok").isJsonPrimitive&&j.get("ok").asJsonPrimitive.isBoolean);return j.get("ok").asBoolean}
    @Test fun invalidThenValidUsesBoundedFeedbackAndPreservesOriginalInput()=runBlocking{
        val client=Client(listOf(TextChatResult.Success("{}"),TextChatResult.Success("{\"ok\":true}")))
        assertTrue(StructuredModelCall(client).generate("test",config,"synthetic",messages,validate=::valid))
        assertEquals(2,client.requests.size);assertEquals(messages,client.requests.first().messages)
        assertEquals(messages,client.requests.last().messages.take(2));assertTrue(client.requests.last().messages.last().content.contains("错误类别"))
    }
    @Test fun recoveryKeepsAllErrorsButOnlyTheLatestUntrustedDraft()=runBlocking{
        val first="{\"candidate\":\"first\"}";val second="{\"candidate\":\"second\"}"
        val client=Client(listOf(TextChatResult.Success(first,displayReasoning="PRIVATE_FIRST_REASONING"),TextChatResult.Success(second,displayReasoning="PRIVATE_SECOND_REASONING"),TextChatResult.Success("{\"ok\":true}")))
        val hints=mapOf("MISSING_meaning" to "appraisal.meaning 必须为非空字符串","STRING_ARRAY_uncertainty" to "appraisal.uncertainty 必须为字符串数组")
        assertTrue(StructuredModelCall(client).generate("test",config,"synthetic",messages,repairHint={hints[it].orEmpty()}){raw->
            when(raw){first->throw RoleContractViolation("MISSING_meaning");second->throw RoleContractViolation("STRING_ARRAY_uncertainty");else->valid(raw)}
        })
        assertEquals(3,client.requests.size)
        val retry=client.requests.last().messages
        assertEquals(messages,retry.take(messages.size))
        val draft=retry.single{it.role=="assistant"}
        assertTrue(draft.content.endsWith(second));assertFalse(draft.content.contains(first))
        assertTrue(draft.content.contains("未发送给用户、未保存"));assertTrue(draft.content.contains("不能作为事实、真实历史"))
        assertEquals("",draft.sourceMessageId);assertTrue(draft.memoryIds.isEmpty());assertTrue(draft.toolCalls.isEmpty());assertNull(draft.continuation)
        hints.forEach{(code,hint)->assertTrue(retry.last().content.contains(code));assertTrue(retry.last().content.contains(hint))}
        assertTrue(client.requests[1].messages.single{it.role=="assistant"}.content.endsWith(first))
        assertTrue(client.requests.all{request->request.messages.none{"PRIVATE_" in it.content}})
    }
    @Test fun draftThatDoesNotFitIsOmittedWithoutTruncatingOriginalMessages()=runBlocking{
        val first="{\"candidate\":\"${"候选".repeat(2000)}\"}"
        val client=Client(listOf(TextChatResult.Success(first),TextChatResult.Success("{\"ok\":true}")))
        val small=config.copy(contextTokens=1024,outputTokens=128)
        assertTrue(StructuredModelCall(client).generate("test",small,"synthetic",messages,validate=::valid))
        val retry=client.requests.last().messages
        assertEquals(messages,retry.take(messages.size));assertEquals(messages.size+1,retry.size)
        assertTrue(retry.none{it.role=="assistant"});assertTrue(retry.last().content.contains("FIELD_OR_SOURCE_CONTRACT"))
        assertTrue(retry.sumOf{ConservativeTokens.tokens(it.content)}+small.outputTokens<=small.contextTokens)
    }
    @Test fun oversizedAndToolCallingCandidatesAreNeverReplayed()=runBlocking{
        val candidates=listOf(
            TextChatResult.Success("界".repeat(16001)),
            TextChatResult.Success("tool candidate",listOf(ChatToolCall("unused","read","{}")))
        )
        for(candidate in candidates){
            val client=Client(listOf(candidate,TextChatResult.Success("{\"ok\":true}")))
            assertTrue(StructuredModelCall(client).generate("test",config.copy(contextTokens=100000),"synthetic",messages,validate=::valid))
            val retry=client.requests.last().messages
            assertEquals(messages,retry.take(messages.size));assertTrue(retry.none{it.role=="assistant"})
        }
    }
    @Test fun repairCapacityFailureDoesNotDropOriginalInputOrSendAgain()=runBlocking{
        val initialCost=messages.sumOf{ConservativeTokens.tokens(it.content)}
        val client=Client(listOf(TextChatResult.Success("{}")))
        try{StructuredModelCall(client).generate("test",config.copy(contextTokens=initialCost+128,outputTokens=128),"synthetic",messages,validate=::valid);fail()}
        catch(e:CompanionModelFailure){assertEquals(ChatFailure.CONTEXT_CAPACITY,e.failure)}
        assertEquals(1,client.requests.size);assertEquals(messages,client.requests.single().messages)
    }
    @Test fun toolInputAllowanceIsKeptWhenDecidingWhetherTheDraftFits()=runBlocking{
        val baseline=Client(listOf(TextChatResult.Success("{}"),TextChatResult.Success("{\"ok\":true}")))
        StructuredModelCall(baseline).generate("test",config,"synthetic",messages,validate=::valid)
        val withoutDraft=baseline.requests.last().messages.filter{it.role!="assistant"}
        val reserve=config.contextTokens-config.outputTokens-withoutDraft.sumOf{ConservativeTokens.tokens(it.content)}
        val client=Client(listOf(TextChatResult.Success("{}"),TextChatResult.Success("{\"ok\":true}")))
        assertTrue(StructuredModelCall(client).generate("test",config,"synthetic",messages,inputReserve=reserve,validate=::valid))
        assertEquals(withoutDraft,client.requests.last().messages)
    }
    @Test fun exhaustedFormatRecoveryNeverReturnsAValidDefault()=runBlocking{
        val client=Client(listOf(TextChatResult.Success("{}")))
        try{StructuredModelCall(client).generate("test",config,"synthetic",messages,validate=::valid);fail("must fail")}
        catch(e:CompanionModelFailure){assertEquals(ChatFailure.STRUCTURED_FORMAT,e.failure)}
        assertEquals(3,client.requests.size)
    }
    @Test fun transportAuthenticationAndStorageAreNotFormatRetries()=runBlocking{
        for(reason in listOf(ChatFailure.UNAUTHORIZED,ChatFailure.NETWORK,ChatFailure.TIMEOUT,ChatFailure.STORAGE,ChatFailure.SOURCE_CHANGED)){
            val client=Client(listOf(TextChatResult.Failure(reason)))
            try{StructuredModelCall(client).generate("test",config,"synthetic",messages,validate=::valid);fail()}
            catch(e:CompanionModelFailure){assertEquals(reason,e.failure)}
            assertEquals(1,client.requests.size)
        }
    }
    @Test fun revokedAttemptDoesNotSendAnotherRequest()=runBlocking{
        var active=true;var calls=0
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls++;active=false;return TextChatResult.Success("{}")}}
        try{StructuredModelCall(client).generate("test",config,"synthetic",messages,active={active},validate=::valid);fail()}
        catch(e:CompanionModelFailure){assertEquals(ChatFailure.SOURCE_CHANGED,e.failure)}
        assertEquals(1,calls)
    }
    @Test fun cancellationIsNotConvertedIntoARepair()=runBlocking{
        var calls=0
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls++;throw CancellationException("synthetic cancellation")}}
        try{StructuredModelCall(client).generate("test",config,"synthetic",messages,validate=::valid);fail()}catch(_:CancellationException){}
        assertEquals(1,calls)
    }
}
