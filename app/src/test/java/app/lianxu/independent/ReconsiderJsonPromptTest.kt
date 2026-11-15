package app.lianxu.independent

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class ReconsiderJsonPromptTest {
    private val now=1_800_000_000_000L
    private val zone=ZoneId.of("UTC")
    private val config=ModelConfig("synthetic","synthetic","opencode-go","https://opencode.ai/inference/go/openai/v1","deepseek-v4.1-flash",1048576,65536,32768)
    private val snapshot=CompanionSnapshot("A",1,1,"stamp",listOf(SourceRef("A",2,1)),"合成原话","enabled")
    private val opportunity=CompanionOpportunity("A:2:1","A",2,1,1,1,now-1000,Long.MAX_VALUE,"合成回访","running",1)
    private val silent="""{"action":"silent","reason":"合成材料暂无需要打扰的新线索","text":"","nextWhen":null,"consideredLearningIds":[],"usedLearningIds":[],"judgmentKind":"uncertain"}"""
    private class Client(private val reply:String):TextChatClient {
        val requests=mutableListOf<TextChatRequest>()
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
            requests+=request;return TextChatResult.Success(reply)
        }
    }
    private suspend fun call(client:Client)=CompanionModel(client).reconsider(config,"synthetic-key",opportunity,snapshot,"合成角色",now,"",zone=zone)
    @Test fun completeExamplesFromProductionPromptPassStrictContractAndPreserveAllChoices()=runBlocking {
        val capture=Client(silent);assertEquals("silent",call(capture).action)
        val request=capture.requests.single()
        assertEquals(listOf("system","user"),request.messages.map{it.role})
        val program=request.messages.last()
        assertTrue(program.content.contains("不是用户聊天原话"));assertTrue(program.sourceMessageId.isEmpty())
        assertTrue(program.memoryIds.isEmpty());assertTrue(program.toolCalls.isEmpty())
        assertEquals(65536,request.outputLimit);assertEquals(120000,request.timeoutMillis)
        assertTrue(request.jsonObject);assertFalse(StructuredResponsePolicy.jsonMode(config,request))
        val examples=Regex("(?m)^\\s*(\\{[^\\r\\n]+\\})\\s*$").findAll(request.messages.first().content).map{it.groupValues[1]}.toList()
        assertEquals(4,examples.size)
        assertEquals(setOf("send","defer","cancel","silent"),examples.map{JsonParser.parseString(it).asJsonObject.get("action").asString}.toSet())
        for(raw in examples){
            val strict=CompanionContracts.decision(raw,now,Long.MAX_VALUE,emptyList(),zone)
            assertEquals(strict,CompanionContracts.decisionForReply(raw,now,Long.MAX_VALUE,emptyList(),zone))
            val client=Client(raw);assertEquals(strict,call(client));assertEquals(1,client.requests.size)
        }
    }
    @Test fun analysisBeforeJsonIsStillRejectedWithoutExtractionAndRecoveryStopsAfterThree()=runBlocking {
        val client=Client("这是分析，不是JSON。 </think>\n$silent")
        try{call(client);fail("Non-JSON prose must not be extracted or accepted")}
        catch(e:CompanionModelFailure){assertEquals(ChatFailure.REPLY_TEXT_INVALID,e.failure)}
        assertEquals(3,client.requests.size)
        val original=client.requests.first().messages
        assertTrue(client.requests.drop(1).all{it.messages.take(original.size)==original})
        assertTrue(client.requests.last().messages.last().content.contains("JSON_SYNTAX"))
    }
}
