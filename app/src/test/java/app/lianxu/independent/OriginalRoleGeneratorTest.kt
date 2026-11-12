package app.lianxu.independent

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OriginalRoleGeneratorTest {
    private fun json(count:Int=3)=Gson().toJson(mapOf("roles" to (1..count).map{mapOf("name" to "原创$it","summary" to "合成简介$it","definition" to "合成设定$it","expression" to "自然表达$it","opening" to "") }))
    private val credential=ModelCredential(ModelConfig("test","合成模型","custom","https://example.invalid/v1","synthetic",16384,2048),"synthetic-key","stamp")
    @Test fun parsesCompleteBatchWithFreshLocalIds(){val a=OriginalRoleGenerator.parse(json());val b=OriginalRoleGenerator.parse(json());assertEquals(3,a.size);assertEquals("合成设定1",a[0].definition);assertTrue(a.none{x->b.any{it.friend.id==x.friend.id}})}
    @Test fun rejectsPartialMalformedDuplicateAndOversizedPayloads(){
        for(raw in listOf(json(2),"not json",json().replace("原创2","原创1"),json().replace("合成简介1","x".repeat(201)),json().replace("\"expression\"","\"unknown\"")," ".repeat(64001))){
            try{OriginalRoleGenerator.parse(raw);fail("Accepted invalid batch")}catch(_:IllegalArgumentException){}
        }
    }
    @Test fun usesConfiguredModelAndOnlyEnteredRequirements()=runBlocking {
        var calls=0
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
            calls++;assertSame(credential.config,config);assertEquals("synthetic-key",key);assertEquals(2,request.messages.size);assertEquals("{\"requirements\":\"温柔的园艺师\"}",request.messages.last().content);assertTrue(request.tools.isEmpty());assertEquals(2048,request.outputLimit);assertEquals(300_000,request.timeoutMillis);return TextChatResult.Success(json())
        }}
        val roles=OriginalRoleGenerator(client,{credential},{true}).generate("温柔的园艺师")
        assertEquals(1,calls);assertEquals(3,roles.size)
    }
    @Test fun missingConfigurationNeverCallsClient()=runBlocking {
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{error("must not call")}}
        try{OriginalRoleGenerator(client,{null},{true}).generate("要求");fail()}catch(e:RoleGenerationFailure){assertEquals(ChatFailure.MISSING_CONFIG.display,e.message)}
    }
    @Test fun configChangeRejectsReturnedBatch()=runBlocking {
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest)=TextChatResult.Success(json())}
        try{OriginalRoleGenerator(client,{credential},{false}).generate("要求");fail()}catch(e:RoleGenerationFailure){assertTrue(e.message.orEmpty().contains("配置已变化"))}
    }
    @Test fun failureAndToolCallsAreNeverCandidates()=runBlocking {
        for(result in listOf(TextChatResult.Failure(ChatFailure.TIMEOUT),TextChatResult.Failure(ChatFailure.RATE_LIMIT),TextChatResult.Success(json(),listOf(ChatToolCall("x","unknown","{}"))),TextChatResult.Success(json(1)))){
            var calls=0
            val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls++;return result}}
            try{OriginalRoleGenerator(client,{credential},{true}).generate("要求");fail()}catch(_:RoleGenerationFailure){}
            assertEquals("Failures must not trigger another paid request",1,calls)
        }
    }
}
